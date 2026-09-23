import { randomUUID } from 'node:crypto';
import { one, all, run, now } from './db.js';
import { sendToDevice, sendToUser } from './ws.js';
import { writeAudit } from './audit.js';
import { config } from './config.js';

/**
 * 下行动作指令的队列。
 *
 * 核心约定：**先落库、再推送**。
 * 设备离线时指令不丢，上线后主动拉取并补执行；每条指令带 TTL，
 * 过期后置为 expired 并在控制端显示「已失效」，而不是静默丢弃 ——
 * 「2 小时前的截屏请求现在才到」是没有意义的，必须让它自然作废。
 *
 * 这与「临时授权（grants）」是两套机制，边界刻意划清：
 *   - 指令 = 一次性动作（锁定、解锁、截屏、请求上报），需要回执与有效期
 *   - 授权 = 持续状态（加时、放行、解封），全量对账 + expire_at 自然淘汰
 * 把授权也做成指令会出现「补发的命令到达时授权早已过期」这类语义错误。
 */

/** 各指令的有效期（毫秒），取自配置层。null 表示永久有效，直到被显式处理。 */
export const TTL_MS = {
  /** 立即锁定：必须一直生效到家长解除，不能自己过期 */
  immediate_lock: config.ttl.immediateLock,
  /** 解除锁定：6 小时内有效，防止「意外解锁」在很久之后才被补执行 */
  clear_lock: config.ttl.clearLock,
  /** 请求设备重新上报已安装应用 */
  request_installed_apps: config.ttl.requestApps,
  /** 按需截屏：60 秒，过期即无意义 */
  screenshot: config.ttl.screenshot,
};

const MAX_PAYLOAD_CHARS = 8 * 1024;

export function toCommandView(row) {
  return {
    id: row.id,
    commandId: row.command_id,
    deviceId: row.device_id,
    type: row.type,
    payload: row.payload ? safeParse(row.payload) : null,
    status: row.status,
    expireAt: row.expire_at,
    createdAt: row.created_at,
    pushedAt: row.pushed_at,
    executedAt: row.executed_at,
    result: row.result ? safeParse(row.result) : null,
  };
}

function safeParse(json) {
  try {
    return JSON.parse(json);
  } catch {
    return null;
  }
}

/**
 * 把已过期的 pending/sent 指令标记为 expired。
 *
 * 调用点有三处：拉取待执行列表之前、控制端查询之前、以及后台巡检。
 * 之所以在读取前也调用一次，是为了保证「任何时刻看到的列表都是自洽的」，
 * 不必依赖巡检的时序。
 */
export function expireStaleCommands(deviceId) {
  const at = now();
  const result = deviceId
    ? run(
        `UPDATE commands SET status = 'expired'
          WHERE device_id = ? AND status IN ('pending','sent')
            AND expire_at IS NOT NULL AND expire_at <= ?`,
        Number(deviceId), at,
      )
    : run(
        `UPDATE commands SET status = 'expired'
          WHERE status IN ('pending','sent')
            AND expire_at IS NOT NULL AND expire_at <= ?`,
        at,
      );
  return result.changes;
}

/**
 * 落库一条指令并尝试实时推送。
 *
 * @returns {{commandId: string, delivered: boolean, expireAt: number|null}}
 *          delivered=false 表示设备当前离线，指令会在其上线后由拉取接口补发。
 */
export function enqueueCommand(deviceId, type, payload = null, options = {}) {
  const ttlMs = options.ttlMs === undefined ? TTL_MS[type] ?? null : options.ttlMs;
  const commandId = options.commandId ?? randomUUID();
  const createdAt = now();
  const expireAt = ttlMs === null ? null : createdAt + ttlMs;

  const payloadJson = payload ? JSON.stringify(payload) : null;
  if (payloadJson && payloadJson.length > MAX_PAYLOAD_CHARS) {
    throw new Error(`指令载荷过大（${payloadJson.length} 字符）`);
  }

  run(
    `INSERT INTO commands (command_id, device_id, type, payload, status, expire_at, created_at)
     VALUES (?, ?, ?, ?, 'pending', ?, ?)`,
    commandId, Number(deviceId), type, payloadJson, expireAt, createdAt,
  );

  const row = one('SELECT * FROM commands WHERE command_id = ?', commandId);
  const delivered = pushCommand(row);

  if (delivered) {
    run('UPDATE commands SET status = ? , pushed_at = ? WHERE command_id = ?', 'sent', now(), commandId);
    row.status = 'sent';
  }

  if (options.userId !== undefined && options.auditAction) {
    writeAudit(options.userId, deviceId, options.auditAction, { commandId, type, delivered });
  }

  return { commandId, delivered, expireAt };
}

/** 推送单条指令；设备离线返回 false（调用方据 decision 落库等待补发） */
export function pushCommand(row) {
  if (!row) return false;
  return sendToDevice(row.device_id, { type: 'command', command: toCommandView(row), at: now() });
}

/**
 * 设备上线后拉取待执行指令。
 * 按 id 升序返回 —— 顺序即语义：「先解除锁定、再立即锁定」必须按这个次序执行。
 */
export function pendingCommands(deviceId) {
  expireStaleCommands(deviceId);
  const rows = all(
    `SELECT * FROM commands
      WHERE device_id = ? AND status IN ('pending','sent')
      ORDER BY id ASC
      LIMIT 100`,
    Number(deviceId),
  );

  const at = now();
  for (const row of rows) {
    // 标记为已投递：即使设备拉取后进程被杀，也不会无限重复投递同一批
    if (row.status === 'pending') {
      run('UPDATE commands SET status = ?, pushed_at = ? WHERE id = ?', 'sent', at, row.id);
    }
  }

  return rows.map(toCommandView);
}

/** 被控端回执：done | failed，附带执行结果说明 */
export function ackCommand(commandId, deviceId, status, result) {
  const row = one('SELECT * FROM commands WHERE command_id = ?', commandId);
  if (!row) return { ok: false, reason: 'not_found' };
  if (Number(row.device_id) !== Number(deviceId)) return { ok: false, reason: 'forbidden' };

  // 已终态就不再改写：补发的重试回执不能覆盖首次结果
  if (row.status === 'done' || row.status === 'failed' || row.status === 'expired') {
    return { ok: true, reason: 'already_final', view: toCommandView(row) };
  }

  const finalStatus = status === 'done' ? 'done' : 'failed';
  run(
    'UPDATE commands SET status = ?, executed_at = ?, result = ? WHERE command_id = ?',
    finalStatus, now(), result ? JSON.stringify(result) : null, commandId,
  );

  const updated = one('SELECT * FROM commands WHERE command_id = ?', commandId);
  // 让控制端立刻看到回执，不必等下一次轮询
  sendToUser(
    one('SELECT user_id FROM devices WHERE id = ?', row.device_id)?.user_id,
    { type: 'command_result', command: toCommandView(updated), at: now() },
  );

  return { ok: true, reason: finalStatus, view: toCommandView(updated) };
}

/** 控制端查看某设备的指令历史（含已失效） */
export function listCommands(deviceId, limit = 50) {
  expireStaleCommands(deviceId);
  const rows = all(
    'SELECT * FROM commands WHERE device_id = ? ORDER BY id DESC LIMIT ?',
    Number(deviceId), limit,
  );
  return rows.map(toCommandView);
}

/** 设备上线时提示「有 N 条待执行」——真正的指令内容由拉取接口返回，避免长连接承载大载荷 */
export function notifyPending(deviceId) {
  expireStaleCommands(deviceId);
  const row = one(
    `SELECT COUNT(*) AS c FROM commands
      WHERE device_id = ? AND status IN ('pending','sent')`,
    Number(deviceId),
  );
  const count = row?.c ?? 0;
  if (count > 0) {
    sendToDevice(deviceId, { type: 'commands_pending', count, at: now() });
  }
  return count;
}
