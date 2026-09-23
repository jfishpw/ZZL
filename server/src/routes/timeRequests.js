import { one, all, run, now } from '../db.js';
import { sendToDevice, sendToUser } from '../ws.js';
import { writeAudit } from '../audit.js';
import { createGrant, dayKeyAt } from '../grants.js';
import { deviceFromToken } from './helpers.js';
import { config } from '../config.js';

/**
 * 「申请加时」的完整闭环。
 *
 * 孩子端受拦时可以主动申请，家长端可以**打折批准** ——
 * 申请 60 分钟、只批 20 分钟。没有折扣能力的话，家长面对"要么全给要么全不给"
 * 只能选拒绝，这个功能就废掉了。
 *
 * 频率限制刻意严格（间隔 ≥10 分钟、每小时 ≤2 次），防的是孩子反复轰炸家长。
 * 申请 30 分钟未处理自动过期，避免家长翻到几小时前的请求时误批。
 */

/** 审批时限（分钟） */
const REQUEST_TTL_MIN = config.timeRequest.ttlMinutes;
/** 两次申请之间的最小间隔（分钟） */
const MIN_INTERVAL_MIN = config.timeRequest.minIntervalMinutes;
/** 每小时最多申请次数 */
const MAX_PER_HOUR = config.timeRequest.maxPerHour;
/** 单次申请与批准的时长上限（分钟） */
export const MAX_REQUEST_MIN = config.timeRequest.maxRequestMinutes;

const CHILD_SCOPES = new Set(['total_add', 'app_allow']);

export function toRequestView(row) {
  const at = now();
  return {
    id: row.id,
    deviceId: row.device_id,
    deviceName: row.device_name ?? undefined,
    scope: row.scope,
    packageName: row.package_name,
    appLabel: row.app_label,
    requestMin: row.request_min,
    decidedMin: row.decided_min,
    reason: row.reason,
    status: row.status,
    createdAt: row.created_at,
    expireAt: row.expire_at,
    decidedAt: row.decided_at,
    /** 是否仍可审批：pending 且未过期 */
    decidable: row.status === 'pending' && row.expire_at > at,
  };
}

/** 把过期的申请标为 expired。读取前调用，保证列表自洽。 */
export function expireStaleRequests(deviceId) {
  const at = now();
  const result = deviceId
    ? run(
        `UPDATE time_requests SET status = 'expired'
          WHERE device_id = ? AND status = 'pending' AND expire_at <= ?`,
        Number(deviceId), at,
      )
    : run(
        `UPDATE time_requests SET status = 'expired'
          WHERE status = 'pending' AND expire_at <= ?`,
        at,
      );
  return result.changes;
}

export default async function timeRequestRoutes(fastify) {

  /* ---------------- 被控端 ---------------- */

  /** 提交加时/放行申请 */
  fastify.post('/api/time-requests', { preHandler: fastify.requireChild }, async (request, reply) => {
    const device = deviceFromToken(request, reply);
    if (!device) return;

    const policy = one('SELECT allow_time_request FROM policies WHERE device_id = ?', device.id);
    if (policy && !policy.allow_time_request) {
      return reply.code(403).send({
        error: 'request_disabled',
        message: '家长已关闭申请功能，请让家长在设备上输入密码',
      });
    }

    const scope = String(request.body?.scope ?? 'total_add');
    if (!CHILD_SCOPES.has(scope)) {
      return reply.code(400).send({ error: 'invalid_scope', message: '只能申请加时或放行某个应用' });
    }

    const packageName =
      typeof request.body?.packageName === 'string' && request.body.packageName.trim()
        ? request.body.packageName.trim()
        : null;
    if (scope === 'app_allow' && !packageName) {
      return reply.code(400).send({ error: 'missing_package', message: '请指定要申请放行的应用' });
    }

    const requestMin = Number(request.body?.requestMin);
    if (!Number.isInteger(requestMin) || requestMin <= 0 || requestMin > MAX_REQUEST_MIN) {
      return reply.code(400).send({
        error: 'invalid_request_min',
        message: `申请时长需在 1 - ${MAX_REQUEST_MIN} 分钟之间`,
      });
    }

    const at = now();

    // 同一应用/同一范围的待审批申请直接复用。
    // 这一步必须排在频率限制**之前**：重复提交同一个申请并不是"新的一次尝试"，
    // 它既不新增记录也不会再通知家长，因此不该消耗孩子的申请额度。
    const duplicate = one(
      `SELECT * FROM time_requests
        WHERE device_id = ? AND status = 'pending' AND scope = ?
          AND COALESCE(package_name, '') = COALESCE(?, '')
        ORDER BY id DESC LIMIT 1`,
      device.id, scope, packageName,
    );
    if (duplicate) {
      return { ok: true, request: toRequestView({ ...duplicate, device_name: device.name }), duplicated: true };
    }

    // 频率限制用「持久化的申请记录」判断，而不是内存计数器 ——
    // 重启服务不该让限制失效，孩子重连一次就能绕过是不能接受的
    const last = one(
      'SELECT created_at FROM time_requests WHERE device_id = ? ORDER BY id DESC LIMIT 1',
      device.id,
    );
    if (last && at - last.created_at < MIN_INTERVAL_MIN * 60_000) {
      const waitMin = Math.ceil((MIN_INTERVAL_MIN * 60_000 - (at - last.created_at)) / 60_000);
      return reply.code(429).send({
        error: 'too_frequent',
        message: `申请太频繁，请 ${waitMin} 分钟后再试`,
      });
    }

    const hourCount = one(
      `SELECT COUNT(*) AS c FROM time_requests
        WHERE device_id = ? AND created_at > ?`,
      device.id, at - 60 * 60_000,
    );
    if ((hourCount?.c ?? 0) >= MAX_PER_HOUR) {
      return reply.code(429).send({
        error: 'hourly_limit',
        message: `每小时最多申请 ${MAX_PER_HOUR} 次，请稍后再试`,
      });
    }

    const reason =
      typeof request.body?.reason === 'string' ? request.body.reason.trim().slice(0, 120) : null;
    const appLabel =
      typeof request.body?.appLabel === 'string' ? request.body.appLabel.slice(0, 80) : null;

    const result = run(
      `INSERT INTO time_requests
         (device_id, scope, package_name, app_label, request_min, reason, status, created_at, expire_at)
       VALUES (?, ?, ?, ?, ?, ?, 'pending', ?, ?)`,
      device.id, scope, packageName, appLabel, requestMin, reason, at, at + REQUEST_TTL_MIN * 60_000,
    );

    const row = one('SELECT * FROM time_requests WHERE id = ?', result.lastInsertRowid);
    sendToUser(device.user_id, {
      type: 'time_request',
      request: toRequestView({ ...row, device_name: device.name }),
      at,
    });

    return { ok: true, request: toRequestView({ ...row, device_name: device.name }) };
  });

  /** 查看自己提交的申请（离线时轮询补偿：推送可能因断线丢失） */
  fastify.get('/api/time-requests/mine', { preHandler: fastify.requireChild }, async (request, reply) => {
    const device = deviceFromToken(request, reply);
    if (!device) return;

    expireStaleRequests(device.id);
    const rows = all(
      'SELECT * FROM time_requests WHERE device_id = ? ORDER BY id DESC LIMIT 20',
      device.id,
    );
    return { requests: rows.map((row) => toRequestView({ ...row, device_name: device.name })) };
  });

  /* ---------------- 控制端 ---------------- */

  /** 待审批/历史申请列表（可跨设备） */
  fastify.get('/api/time-requests', { preHandler: fastify.requireParent }, async (request) => {
    expireStaleRequests();

    const status = typeof request.query?.status === 'string' ? request.query.status : null;
    const deviceId = Number(request.query?.deviceId);
    const limitRaw = Number(request.query?.limit);
    const limit = Number.isInteger(limitRaw) && limitRaw > 0 && limitRaw <= 200 ? limitRaw : 50;

    const conditions = ['d.user_id = ?'];
    const params = [request.claims.userId];

    if (status && ['pending', 'approved', 'rejected', 'expired'].includes(status)) {
      conditions.push('r.status = ?');
      params.push(status);
    }
    if (Number.isInteger(deviceId)) {
      conditions.push('r.device_id = ?');
      params.push(deviceId);
    }

    const rows = all(
      `SELECT r.*, d.name AS device_name
         FROM time_requests r
         JOIN devices d ON d.id = r.device_id
        WHERE ${conditions.join(' AND ')}
        ORDER BY r.id DESC
        LIMIT ?`,
      ...params, limit,
    );

    return {
      requests: rows.map(toRequestView),
      pendingCount: rows.filter((row) => row.status === 'pending' && row.expire_at > now()).length,
    };
  });

  /**
   * 审批。
   * body: { approve: boolean, decidedMin?: number }  —— decidedMin 支持「打折批准」
   */
  fastify.post('/api/time-requests/:id/decide', { preHandler: fastify.requireParent }, async (request, reply) => {
    const id = Number(request.params.id);
    if (!Number.isInteger(id)) {
      return reply.code(400).send({ error: 'invalid_id', message: '申请 ID 不合法' });
    }

    const row = one(
      `SELECT r.*, d.name AS device_name
         FROM time_requests r
         JOIN devices d ON d.id = r.device_id
        WHERE r.id = ? AND d.user_id = ?`,
      id, request.claims.userId,
    );
    if (!row) {
      return reply.code(404).send({ error: 'request_not_found', message: '申请不存在' });
    }
    if (row.status !== 'pending') {
      return reply.code(409).send({ error: 'already_decided', message: '该申请已被处理' });
    }
    if (row.expire_at <= now()) {
      expireStaleRequests(row.device_id);
      return reply.code(409).send({ error: 'request_expired', message: '该申请已超过审批时限' });
    }

    const approve = request.body?.approve === true;
    const at = now();

    if (!approve) {
      run(
        `UPDATE time_requests SET status = 'rejected', decided_at = ?, decided_min = 0 WHERE id = ?`,
        at, id,
      );
      const decided = one('SELECT * FROM time_requests WHERE id = ?', id);
      writeAudit(request.claims.userId, row.device_id, 'timeRequest.reject', { requestId: id });

      sendToDevice(row.device_id, {
        type: 'time_request_decided',
        request: toRequestView({ ...decided, device_name: row.device_name }),
        at,
      });

      return { ok: true, request: toRequestView({ ...decided, device_name: row.device_name }) };
    }

    // 折扣批准：允许少批，但不允许超过申请值 —— 否则等于家长给自己开后门
    const decidedMin =
      request.body?.decidedMin === undefined ? row.request_min : Number(request.body.decidedMin);
    if (!Number.isInteger(decidedMin) || decidedMin <= 0 || decidedMin > row.request_min) {
      return reply.code(400).send({
        error: 'invalid_decided_min',
        message: `批准时长需在 1 - ${row.request_min} 分钟之间`,
      });
    }

    // 审批通过直接复用授权链路：额度叠加、有效期、离线补发行为完全一致，
    // 不另外实现一套生效逻辑
    const grantResult = createGrant(
      row.device_id,
      {
        scope: row.scope,
        packageName: row.package_name,
        appLabel: row.app_label,
        extraMinutes: row.scope === 'total_add' ? decidedMin : undefined,
        // 加时：绑定额度日即可，有效期给足一天让 day_key 去限制；
        // 放行：批准的分钟数**就是**放行时长，否则"放行 30 分钟"会变成 2 小时
        ttlMinutes: row.scope === 'total_add' ? 24 * 60 : decidedMin,
      },
      { source: 'request', userId: request.claims.userId, auditAction: 'timeRequest.approve' },
    );

    run(
      `UPDATE time_requests SET status = 'approved', decided_at = ?, decided_min = ? WHERE id = ?`,
      at, decidedMin, id,
    );
    const decided = one('SELECT * FROM time_requests WHERE id = ?', id);

    writeAudit(request.claims.userId, row.device_id, 'timeRequest.approve', {
      requestId: id,
      requestMin: row.request_min,
      decidedMin,
      scope: row.scope,
      dayKey: dayKeyAt(row.device_id, at),
      grantOk: grantResult.ok,
    });

    sendToDevice(row.device_id, {
      type: 'time_request_decided',
      request: toRequestView({ ...decided, device_name: row.device_name }),
      at,
    });

    return {
      ok: true,
      request: toRequestView({ ...decided, device_name: row.device_name }),
      grant: grantResult.ok ? grantResult.grant : null,
      delivered: grantResult.delivered ?? false,
    };
  });
}
