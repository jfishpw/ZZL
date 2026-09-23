import { one, all, run, now } from '../db.js';
import { writeAudit } from '../audit.js';
import { sendToUser } from '../ws.js';
import { bumpStateVersion, pushDeviceState } from '../grants.js';
import {
  LEVELS,
  MAX_HINT_CHARS,
  ensurePinsRow,
  readPins,
  writeLevel,
  bumpPinsVersion,
  toPinsView,
  toPinsSummary,
  normalizeLevel,
  hasAnyLevel,
} from '../pins.js';
import { ownedDevice, deviceFromToken } from './helpers.js';

/**
 * 离线密码的 HTTP 接口。
 *
 * 数据访问与视图逻辑都在 `src/pins.js`，这里只负责鉴权、校验与推送。
 * 拆开的原因：设备状态对账（`grants.js`）也要下发这份密码备份，
 * 让 `grants.js` 去 import 路由文件会形成循环依赖。
 */

const MAX_ATTEMPTS_BATCH = 200;

export function toAttemptView(row) {
  return {
    id: row.id,
    deviceId: row.device_id,
    level: row.level,
    success: !!row.success,
    source: row.source ?? null,
    ts: row.ts,
  };
}

export default async function pinRoutes(fastify) {

  /* ---------------- 被控端 ---------------- */

  /**
   * 上传密码备份。
   *
   * 设备端只在**本地改过密码**时才调用，因此不会与服务端下发的重置来回覆盖。
   * 返回新版本号，设备端记下来 —— 之后对账时就能判断服务端那套是否比自己新。
   */
  fastify.put('/api/pins', { preHandler: fastify.requireChild }, async (request, reply) => {
    const device = deviceFromToken(request, reply);
    if (!device) return;

    const body = request.body ?? {};
    const normalized = {};
    for (const level of LEVELS) {
      const key = `level${level}`;
      if (!(key in body)) continue;
      const result = normalizeLevel(body[key]);
      if (!result.ok) {
        return reply.code(400).send({ error: 'invalid_pin', message: `第 ${level} 级：${result.reason}` });
      }
      normalized[level] = result.value;
    }

    const levelCountRaw = Number(body.levelCount);
    const levelCount =
      Number.isInteger(levelCountRaw) && levelCountRaw >= 1 && levelCountRaw <= 3 ? levelCountRaw : 3;

    ensurePinsRow(device.id);
    run('UPDATE local_pins SET level_count = ? WHERE device_id = ?', levelCount, device.id);

    for (const level of LEVELS) {
      if (level in normalized) writeLevel(device.id, level, normalized[level]);
    }

    const version = bumpPinsVersion(device.id);

    // 密码变了也要让状态版本前进：设备状态里带着这份备份，
    // 版本不动的话换机恢复时另一台设备就拉不到新密码
    bumpStateVersion(device.id);
    pushDeviceState(device.id);

    writeAudit(null, device.id, 'pin.upload', {
      levelCount,
      version,
      levels: Object.keys(normalized).map(Number),
    });

    return { ok: true, version, pins: toPinsView(readPins(device.id)) };
  });

  /**
   * 上报密码尝试记录（含失败）。
   * 家长正是靠它知道"孩子试过破解" —— 缺了这条，防破解就是盲的。
   */
  fastify.post('/api/pin-attempts', { preHandler: fastify.requireChild }, async (request, reply) => {
    const device = deviceFromToken(request, reply);
    if (!device) return;

    const attempts = request.body?.attempts;
    if (!Array.isArray(attempts)) {
      return reply.code(400).send({ error: 'invalid_body', message: '缺少 attempts 数组' });
    }

    let accepted = 0;
    let duplicated = 0;
    let rejected = 0;

    for (const item of attempts.slice(0, MAX_ATTEMPTS_BATCH)) {
      const level = Number(item?.level);
      const ts = Number(item?.ts);
      const clientKey =
        typeof item?.clientKey === 'string' && item.clientKey.trim() ? item.clientKey.trim() : null;
      const source =
        typeof item?.source === 'string' && item.source.trim() ? item.source.trim().slice(0, 24) : null;

      if (!Number.isInteger(level) || !LEVELS.includes(level) || !Number.isFinite(ts)) {
        rejected += 1;
        continue;
      }

      const inserted = run(
        `INSERT OR IGNORE INTO pin_attempts (client_key, device_id, level, success, source, ts)
         VALUES (?, ?, ?, ?, ?, ?)`,
        clientKey, device.id, level, item.success ? 1 : 0, source, ts,
      );

      if (inserted.changes === 1) {
        accepted += 1;
        // 失败尝试要立刻让家长知道 —— 这是"有人在破解"的信号，
        // 等家长下次打开 App 才看到就太晚了
        if (!item.success) {
          sendToUser(device.user_id, {
            type: 'pin_attempt_failed',
            deviceId: device.id,
            deviceName: device.name,
            level,
            source,
            at: now(),
          });
        }
      } else {
        duplicated += 1;
      }
    }

    return { ok: true, accepted, duplicated, rejected };
  });

  /** 被控端读取自己的备份（重装后恢复同一套密码时用） */
  fastify.get('/api/pins', { preHandler: fastify.requireChild }, async (request, reply) => {
    const device = deviceFromToken(request, reply);
    if (!device) return;
    return { pins: toPinsView(readPins(device.id)) };
  });

  /* ---------------- 控制端 ---------------- */

  /** 密码概览：只暴露"哪几级已设置 + 提示语"，绝不回传哈希 */
  fastify.get('/api/devices/:id/pins', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;
    return toPinsSummary(readPins(device.id));
  });

  /**
   * 远程重置/清除某一级密码。
   *
   * body: { level: 1|2|3, clear?: boolean, entry?: {hash, salt, iterations, hint} }
   *
   * - `clear: true` → 清除该级（家长忘记密码时用，也用于"孩子知道密码了"的补救）
   * - 带 `entry`    → 设定新的哈希（控制端算好再传，服务端同样不接触明文）
   *
   * 变更后立刻推送设备状态：离线设备上线时对账即生效。
   */
  fastify.post('/api/devices/:id/pins/reset', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const level = Number(request.body?.level);
    if (!Number.isInteger(level) || !LEVELS.includes(level)) {
      return reply.code(400).send({ error: 'invalid_level', message: '密码级别只能是 1 / 2 / 3' });
    }

    const clear = request.body?.clear === true;
    const normalized = clear ? { ok: true, value: null } : normalizeLevel(request.body?.entry);
    if (!normalized.ok) {
      return reply.code(400).send({ error: 'invalid_pin', message: normalized.reason });
    }
    if (!clear && normalized.value === null) {
      return reply.code(400).send({
        error: 'missing_entry',
        message: '请提供新密码的哈希，或显式传 clear=true 清除该级',
      });
    }

    ensurePinsRow(device.id);
    writeLevel(device.id, level, normalized.value);
    const version = bumpPinsVersion(device.id);

    bumpStateVersion(device.id);
    const delivered = pushDeviceState(device.id);

    writeAudit(request.claims.userId, device.id, clear ? 'pin.clear' : 'pin.reset', {
      level,
      version,
      delivered,
    });

    return {
      ok: true,
      pins: toPinsSummary(readPins(device.id)),
      delivered,
      notice: delivered
        ? null
        : '设备当前离线，重置将在其上线后生效（期间旧密码仍然可用）',
    };
  });

  /** 孩子尝试输入密码的记录。家长据此判断是否有人在破解。 */
  fastify.get('/api/devices/:id/pin-attempts', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const limitRaw = Number(request.query?.limit);
    const limit = Number.isInteger(limitRaw) && limitRaw > 0 && limitRaw <= 200 ? limitRaw : 50;

    const rows = all(
      'SELECT * FROM pin_attempts WHERE device_id = ? ORDER BY ts DESC LIMIT ?',
      device.id, limit,
    );

    const failedRow = one(
      'SELECT COUNT(*) AS c FROM pin_attempts WHERE device_id = ? AND success = 0',
      device.id,
    );
    const failedTotal = failedRow?.c ?? 0;

    return {
      attempts: rows.map(toAttemptView),
      failedTotal,
      /** 有过失败尝试说明可能有人在尝试破解，界面据此给出提醒 */
      suspicious: failedTotal > 0,
    };
  });
}

// 供其他模块复用，避免各处重复判断"是否已设置密码"
export { hasAnyLevel, MAX_HINT_CHARS };
