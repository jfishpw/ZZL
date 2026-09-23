import { one, all, run, now } from './db.js';
import { sendToDevice } from './ws.js';
import { writeAudit } from './audit.js';
import { config } from './config.js';
import { pinsForState } from './pins.js';

/**
 * 临时授权（grants）。
 *
 * 与「指令队列」刻意分开：授权是**持续状态**而非一次性动作，因此用
 * 「全量对账 + expire_at 自然淘汰」来表达，而不是逐条下发命令。
 *
 * 这样做的两个好处：
 *   1. 设备离线期间创建的授权，上线对账时自动补上；已过期的自动消失。
 *   2. 撤销授权只需在服务端标记 revoked，设备下一次对账即同步 ——
 *      不会出现「撤销命令丢了，设备继续放行」的窗口。
 *
 * 三种授权范围：
 *   - total_add  加时：只增加当日总时长上限（按额度日绑定，跨归日点自动失效）
 *   - app_allow  单应用放行：该应用不受黑白名单与逐应用规则限制，但仍受当日总时长约束
 *   - unlock     临时总解封：有效期内完全关闭管控
 */

export const SCOPES = new Set(['total_add', 'app_allow', 'unlock']);

/** 有效期与时长上限都来自配置层，便于按实际使用习惯调整而不改代码 */
const DEFAULT_TTL_MIN = config.grant.defaultTtlMinutes;
const MAX_TTL_MIN = config.grant.maxTtlMinutes;
export const MAX_EXTRA_MIN = config.grant.maxExtraMinutes;

export function toGrantView(row) {
  return {
    id: row.id,
    deviceId: row.device_id,
    scope: row.scope,
    packageName: row.package_name,
    appLabel: row.app_label,
    extraMinutes: row.extra_minutes,
    dayKey: row.day_key,
    expireAt: row.expire_at,
    source: row.source,
    createdAt: row.created_at,
    revoked: !!row.revoked,
  };
}

/** 按策略的 reset_hour 计算「额度日」，与服务端 / 被控端两侧保持一致 */
export function dayKeyAt(deviceId, at = now()) {
  const policy = one('SELECT reset_hour FROM policies WHERE device_id = ?', Number(deviceId));
  const resetHour = policy?.reset_hour ?? 0;
  const date = new Date(at);
  date.setHours(date.getHours() - Math.min(Math.max(resetHour, 0), 23));
  const pad = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** 当前仍然生效的授权（未撤销、未过期） */
export function activeGrants(deviceId, at = now()) {
  return all(
    `SELECT * FROM grants
      WHERE device_id = ? AND revoked = 0
        AND (expire_at IS NULL OR expire_at > ?)
      ORDER BY id ASC`,
    Number(deviceId), at,
  );
}

/** 某台设备当前应当呈现给被控端的动态状态 */
export function deviceState(deviceId) {
  // ⚠️ 这里用的是**显式列清单**而不是 SELECT *：
  // 新增状态字段（如 icon_hidden）时必须同步加进来，
  // 否则它会永远读不到 —— `!!undefined` = false，表现为"设置成功了但设备不生效"，
  // 而且不报任何错。历史上已经踩过一次。
  const device = one(
    `SELECT id, locked, locked_at, state_version, admin_mode, device_owner,
            uninstall_blocked, icon_hidden
       FROM devices WHERE id = ?`,
    Number(deviceId),
  );
  if (!device) return null;

  return {
    deviceId: device.id,
    locked: !!device.locked,
    lockedAt: device.locked_at,
    grants: activeGrants(deviceId).map(toGrantView),
    /**
     * 离线密码备份（含哈希与盐）。
     *
     * 放进设备状态而不是单独接口，是为了让「换机恢复」与「家长远程重置」
     * 共用同一条对账通道 —— 设备重装后第一次对账就拿回自己的密码，
     * 家长重置后设备上线对账即生效，两条路径都不需要额外逻辑。
     */
    pins: pinsForState(deviceId),
    /**
     * 家长侧期望的加固配置。
     *
     * 只下发"期望值"，不代替设备判断能不能做到 ——
     * Device Owner 能否生效取决于首次装机时是否做过 ADB 激活，
     * 这是设备本地的既成事实，服务端无法代劳。
     */
    hardening: {
      adminMode: device.admin_mode ?? null,
      deviceOwner: !!device.device_owner,
      uninstallBlocked: !!device.uninstall_blocked,
    },
    /**
     * 桌面图标是否隐藏。
     *
     * 放进状态而不是做成一次性指令：它是**持续状态**，设备重启后仍要生效。
     * 做成指令的话，孩子重启一次平板图标就回来了 —— 而重装 App 之前
     * 家长根本没机会再下发一次。
     */
    iconHidden: !!device.icon_hidden,
    stateVersion: device.state_version ?? 1,
    serverTime: now(),
  };
}

/**
 * 状态版本自增。
 *
 * 被控端对账时用它判断"这份状态是否比本地新"，避免乱序到达的旧状态
 * 覆盖掉刚生效的新状态（例如「加时」与「撤销加时」两个推送顺序颠倒）。
 */
export function bumpStateVersion(deviceId) {
  const current = one('SELECT state_version FROM devices WHERE id = ?', Number(deviceId));
  const next = (current?.state_version ?? 1) + 1;
  run('UPDATE devices SET state_version = ? WHERE id = ?', next, Number(deviceId));
  return next;
}

/** 状态变更后立即推送给在线设备 */
export function pushDeviceState(deviceId) {
  const state = deviceState(deviceId);
  if (!state) return false;
  return sendToDevice(deviceId, { type: 'device_state_updated', state, at: state.serverTime });
}

/**
 * 创建一条临时授权，并立即推送状态。
 *
 * @returns {{ok: true, grant: object, delivered: boolean}}
 */
export function createGrant(deviceId, input, options = {}) {
  const scope = String(input?.scope ?? '');
  if (!SCOPES.has(scope)) {
    return { ok: false, reason: 'invalid_scope', message: '授权范围只能是 total_add / app_allow / unlock' };
  }

  const packageName =
    typeof input?.packageName === 'string' && input.packageName.trim()
      ? input.packageName.trim()
      : null;
  if (scope === 'app_allow' && !packageName) {
    return { ok: false, reason: 'missing_package', message: '单应用放行必须指定 packageName' };
  }

  let extraMinutes = null;
  if (scope === 'total_add') {
    extraMinutes = Number(input?.extraMinutes);
    if (!Number.isInteger(extraMinutes) || extraMinutes <= 0 || extraMinutes > MAX_EXTRA_MIN) {
      return { ok: false, reason: 'invalid_extra_minutes', message: `加时时长需在 1 - ${MAX_EXTRA_MIN} 分钟之间` };
    }
  }

  const ttlRaw = input?.ttlMinutes === undefined ? DEFAULT_TTL_MIN[scope] : Number(input.ttlMinutes);
  if (!Number.isInteger(ttlRaw) || ttlRaw <= 0 || ttlRaw > MAX_TTL_MIN) {
    return { ok: false, reason: 'invalid_ttl', message: `有效期需在 1 - ${MAX_TTL_MIN} 分钟之间` };
  }

  const createdAt = now();
  const expireAt = createdAt + ttlRaw * 60_000;

  const result = run(
    `INSERT INTO grants
       (device_id, scope, package_name, app_label, extra_minutes, day_key, expire_at, source, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    Number(deviceId),
    scope,
    packageName,
    typeof input?.appLabel === 'string' ? input.appLabel.slice(0, 80) : null,
    extraMinutes,
    scope === 'total_add' ? dayKeyAt(deviceId, createdAt) : null,
    expireAt,
    options.source ?? 'manual',
    createdAt,
  );

  const row = one('SELECT * FROM grants WHERE id = ?', result.lastInsertRowid);
  bumpStateVersion(deviceId);
  const delivered = pushDeviceState(deviceId);

  if (options.userId !== undefined) {
    writeAudit(options.userId, Number(deviceId), options.auditAction ?? 'grant.create', {
      grantId: row.id, scope, packageName, extraMinutes, ttlMinutes: ttlRaw, delivered,
    });
  }

  return { ok: true, grant: toGrantView(row), delivered };
}

/** 撤销授权（设备下一次对账即失效；同时立即推送） */
export function revokeGrant(grantId, userId) {
  const row = one('SELECT * FROM grants WHERE id = ?', Number(grantId));
  if (!row) return { ok: false, reason: 'not_found', message: '授权不存在' };
  if (row.revoked) return { ok: true, grant: toGrantView(row), delivered: false };

  run('UPDATE grants SET revoked = 1, revoked_at = ? WHERE id = ?', now(), Number(grantId));
  bumpStateVersion(row.device_id);
  const delivered = pushDeviceState(row.device_id);

  writeAudit(userId, row.device_id, 'grant.revoke', { grantId: row.id, scope: row.scope, delivered });

  return { ok: true, grant: toGrantView(one('SELECT * FROM grants WHERE id = ?', Number(grantId))), delivered };
}

/** 设置/解除「立即锁定」。锁定状态是设备字段，因此同样能被对账恢复。 */
export function setLocked(deviceId, locked, userId) {
  const at = now();
  run(
    'UPDATE devices SET locked = ?, locked_at = ? WHERE id = ?',
    locked ? 1 : 0, locked ? at : null, Number(deviceId),
  );
  bumpStateVersion(deviceId);
  const delivered = pushDeviceState(deviceId);

  writeAudit(userId, Number(deviceId), locked ? 'device.lock' : 'device.unlock', { delivered });
  return { delivered };
}

/**
 * 设置/解除桌面图标隐藏。
 *
 * 与锁定同理：这是设备级**状态**，靠对账保证最终一致，
 * 因此设备重启、进程被杀、离线几天都不会让它失效。
 */
export function setIconHidden(deviceId, hidden, userId) {
  run('UPDATE devices SET icon_hidden = ? WHERE id = ?', hidden ? 1 : 0, Number(deviceId));
  bumpStateVersion(deviceId);
  const delivered = pushDeviceState(deviceId);

  writeAudit(
    userId,
    Number(deviceId),
    hidden ? 'device.icon_hidden' : 'device.icon_shown',
    { delivered },
  );
  return { delivered };
}

/** 把「已过期的授权」清出列表：不是删除数据，只是让历史与生效区分开 */
export function grantHistory(deviceId, limit = 50) {
  const rows = all(
    'SELECT * FROM grants WHERE device_id = ? ORDER BY id DESC LIMIT ?',
    Number(deviceId), limit,
  );
  const at = now();
  return rows.map((row) => ({
    ...toGrantView(row),
    active: !row.revoked && (row.expire_at === null || row.expire_at > at),
  }));
}
