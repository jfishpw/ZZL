import { all, run, now } from '../db.js';
import { config } from '../config.js';
import { sendToDevice, sendToUser, isDeviceOnline } from '../ws.js';
import { writeAudit } from '../audit.js';
import { purgeDeviceScreenshots } from '../screenshots.js';
import { ownedDevice, ownDeviceFromToken } from './helpers.js';
import { loadPolicyBundle } from './policy.js';

/** 结合心跳超时判定真实在线状态（长连接异常断开时可能来不及更新） */
function isEffectivelyOnline(device) {
  if (!device.online) return false;
  if (isDeviceOnline(device.id)) return true;
  if (!device.last_seen) return false;
  return now() - device.last_seen < config.heartbeatTimeoutMs;
}

/**
 * 管控模式的能力矩阵。
 *
 * 这不是"配置项"而是"客观能力" —— Device Owner 能否生效取决于首次装机时
 * 是否做过 ADB 激活，是设备本地的既成事实。因此把矩阵放在服务端统一输出，
 * 保证控制端展示的说明与被控端的实际行为不会各说一套。
 */
export const ADMIN_MODE_CAPABILITIES = {
  device_owner: {
    label: '设备所有者模式',
    strength: '厂商级',
    can: [
      '真正无法卸载（系统级阻止，无任何入口可绕过）',
      '无法在系统设置里取消激活',
      '可隐藏「设置」「应用商店」等系统入口',
      '可禁用安全模式与 USB 调试',
    ],
    limits: ['首次装机需要一次电脑 ADB 激活'],
    risks: ['家长忘记离线密码且完全离线时，只能恢复出厂设置'],
  },
  device_admin: {
    label: '设备管理器模式',
    strength: '防普通儿童',
    can: [
      '阻止卸载本应用',
      '隐藏桌面图标',
      '后台保活与开机自启',
      '卸载/取消激活时立刻上报家长',
    ],
    limits: [
      '孩子可在「设置 → 安全 → 设备管理应用」里取消激活后卸载',
      '无法隐藏系统设置入口',
    ],
    risks: ['对技术型孩子只能起到"拖延 + 暴露"的作用，不能真正阻止'],
  },
  none: {
    label: '未激活任何模式',
    strength: '无防护',
    can: ['时长限制与拦截（依赖无障碍与悬浮窗）'],
    limits: ['应用可被直接卸载', '孩子卸载后管控立即失效'],
    risks: ['这是最弱的一档，建议至少激活设备管理器'],
  },
};

/** 结合心跳上报的加固状态，给出当前实际生效的模式 */
function resolveAdminMode(device) {
  if (device.device_owner) return 'device_owner';
  if (device.admin_mode === 'device_admin') return 'device_admin';
  return 'none';
}

function toDeviceView(device) {
  const mode = resolveAdminMode(device);
  return {
    id: device.id,
    name: device.name,
    model: device.model,
    androidVersion: device.android_ver,
    adminMode: device.admin_mode,
    /** 归一化后的实际生效模式：device_owner | device_admin | none */
    effectiveAdminMode: mode,
    capabilities: ADMIN_MODE_CAPABILITIES[mode],
    /** 是否已阻止卸载（Device Owner 下由系统强制，Device Admin 下可被取消激活绕过） */
    uninstallBlocked: !!device.uninstall_blocked,
    /** 离线密码是否已设置 —— 家长忘了密码时这里会显示"未设置"，需要提前提醒 */
    pinReady: !!device.pin_ready,
    online: isEffectivelyOnline(device),
    lastSeen: device.last_seen,
    foregroundPackage: device.foreground_pkg,
    health: device.health_json ? JSON.parse(device.health_json) : null,
    /** 保活健康度：厂商白名单、电池优化、精确闹钟是否到位 */
    keepalive: device.keepalive_json ? JSON.parse(device.keepalive_json) : null,
    /** 「立即锁定」的持久状态：列表上要能一眼看出设备是否被锁 */
    locked: !!device.locked,
    /** 桌面图标是否被隐藏。隐藏后家长只能靠暗码或这里恢复 */
    iconHidden: !!device.icon_hidden,
    createdAt: device.created_at,
  };
}

export default async function deviceRoutes(fastify) {
  /** 控制端：设备列表 */
  fastify.get('/api/devices', { preHandler: fastify.requireParent }, async (request) => {
    const rows = all(
      'SELECT * FROM devices WHERE user_id = ? ORDER BY created_at ASC',
      request.claims.userId,
    );
    return { devices: rows.map(toDeviceView) };
  });

  /** 控制端：单台设备详情 */
  fastify.get('/api/devices/:id', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;
    return toDeviceView(device);
  });

  /** 控制端：重命名 */
  fastify.patch('/api/devices/:id', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const { name } = request.body ?? {};
    if (typeof name !== 'string' || !name.trim()) {
      return reply.code(400).send({ error: 'invalid_name', message: '设备名称不能为空' });
    }

    run('UPDATE devices SET name = ? WHERE id = ?', name.trim(), device.id);
    writeAudit(request.claims.userId, device.id, 'device.rename', { name: name.trim() });
    return { ok: true };
  });

  /** 控制端：解绑设备 */
  fastify.delete('/api/devices/:id', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    // 删除前先通知被控端：让它清掉本地会话回到配对页，
    // 否则设备继续拿死 token 心跳/上报，只会不停失败。
    // 在线才能收到；离线设备反正下次上线时令牌已失效，会走同样的清理路径。
    const notified = sendToDevice(device.id, { type: 'device_removed', at: now() });

    // 先删图片再删设备行：数据库行会被外键级联清掉，
    // 但磁盘上的截屏不会 —— 而那正是最不该继续留存的隐私数据。
    // 顺序反了就拿不到 device_id，只能等孤儿清理兜底（慢，且容易漏）。
    const removedShots = purgeDeviceScreenshots(device.id);

    run('DELETE FROM devices WHERE id = ?', device.id);
    writeAudit(request.claims.userId, null, 'device.unbind', {
      deviceId: device.id,
      name: device.name,
      removedScreenshots: removedShots,
      notifiedChild: notified,
    });
    return { ok: true, removedScreenshots: removedShots };
  });

  /** 控制端：强制同步策略到被控端（离线时返回 delivered=false，被控端上线后会自行拉取） */
  fastify.post('/api/devices/:id/sync', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    // 推送完整策略包：被控端一次即可拿到全部本地规则
    const bundle = loadPolicyBundle(device.id);
    const delivered = sendToDevice(device.id, { type: 'policy_updated', bundle, at: now() });

    writeAudit(request.claims.userId, device.id, 'device.sync', { delivered });
    return { ok: true, delivered };
  });

  /** 被控端：心跳 */
  fastify.post('/api/devices/:id/heartbeat', { preHandler: fastify.requireChild }, async (request, reply) => {
    const device = ownDeviceFromToken(request, reply);
    if (!device) return;

    const { foregroundPackage, remainingMs, health, hardening, keepalive } = request.body ?? {};

    run(
      `UPDATE devices
          SET online = 1,
              last_seen = ?,
              foreground_pkg = COALESCE(?, foreground_pkg),
              health_json = COALESCE(?, health_json),
              keepalive_json = COALESCE(?, keepalive_json),
              -- 加固状态由设备上报，因为只有设备自己能知道"当前是不是 Device Owner"
              admin_mode = COALESCE(?, admin_mode),
              device_owner = COALESCE(?, device_owner),
              uninstall_blocked = COALESCE(?, uninstall_blocked)
        WHERE id = ?`,
      now(),
      foregroundPackage ?? null,
      health ? JSON.stringify(health) : null,
      keepalive ? JSON.stringify(keepalive) : null,
      hardening?.adminMode ?? null,
      hardening?.deviceOwner === undefined ? null : hardening.deviceOwner ? 1 : 0,
      hardening?.uninstallBlocked === undefined ? null : hardening.uninstallBlocked ? 1 : 0,
      device.id,
    );

    sendToUser(device.user_id, {
      type: 'device_status',
      deviceId: device.id,
      online: true,
      foregroundPackage: foregroundPackage ?? null,
      remainingMs: remainingMs ?? null,
      at: now(),
    });

    return { ok: true, serverTime: now() };
  });

  /** 控制端：某台设备的加固状态与能力矩阵 */
  fastify.get('/api/devices/:id/hardening', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const mode = resolveAdminMode(device);
    return {
      deviceId: device.id,
      adminMode: device.admin_mode,
      effectiveAdminMode: mode,
      capabilities: ADMIN_MODE_CAPABILITIES[mode],
      allModes: ADMIN_MODE_CAPABILITIES,
      deviceOwner: !!device.device_owner,
      uninstallBlocked: !!device.uninstall_blocked,
      pinReady: !!device.pin_ready,
      keepalive: device.keepalive_json ? JSON.parse(device.keepalive_json) : null,
      /** 供控制端直接展示的一键激活命令，家长复制到电脑执行即可 */
      activationHint: {
        deviceOwner: [
          'adb shell dpm set-device-owner com.zzl.guardian.child/.child.service.GuardDeviceAdminReceiver',
        ],
        notes: [
          '执行前请确认设备已安装被控端、且未添加过任何账号（系统限制）',
          '如提示 already set，说明设备上已有其他 Device Owner，需先恢复出厂',
        ],
      },
    };
  });
}
