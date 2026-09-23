import { listAudit, auditSummary, ingestChildEvents } from '../audit.js';
import { ownedDevice, deviceFromToken } from './helpers.js';

/**
 * 审计日志：控制端查询 + 被控端上报本地事件。
 *
 * 两个方向的接口放在同一文件，是因为它们是同一份数据的两端：
 * 被控端断网时把事件落本地库，联网后从这里补传；
 * 家长看到的列表里两类来源（server / child）混排，按时间倒序。
 */
export default async function auditRoutes(fastify) {
  /** 控制端：查询审计日志（可按设备过滤） */
  fastify.get('/api/audit', { preHandler: fastify.requireParent }, async (request) => {
    const userId = request.claims.userId;
    const deviceId = request.query?.deviceId ? Number(request.query.deviceId) : null;

    return {
      logs: listAudit(userId, {
        deviceId,
        limit: request.query?.limit,
        level: request.query?.level ?? null,
      }),
      summary: auditSummary(userId, deviceId),
    };
  });

  /** 控制端：单台设备的审计日志（路径更直观，供设备详情页用） */
  fastify.get(
    '/api/devices/:id/audit',
    { preHandler: fastify.requireParent },
    async (request, reply) => {
      const device = ownedDevice(request, reply);
      if (!device) return;

      return {
        logs: listAudit(request.claims.userId, {
          deviceId: device.id,
          limit: request.query?.limit,
          level: request.query?.level ?? null,
        }),
        summary: auditSummary(request.claims.userId, device.id),
      };
    },
  );

  /**
   * 被控端：补传本地事件。
   *
   * 幂等靠 `clientKey`。断网期间的事件会在每次重连时重传，
   * 重复上报不能让家长的日志里出现两条一样的记录
   * —— 那样家长会误以为孩子"试了两次密码"。
   */
  fastify.post(
    '/api/audit/events',
    { preHandler: fastify.requireChild },
    async (request, reply) => {
      const device = deviceFromToken(request, reply);
      if (!device) return;

      const events = request.body?.events;
      if (!Array.isArray(events)) {
        return reply.code(400).send({ error: 'invalid_body', message: '缺少 events 数组' });
      }
      if (events.length > 200) {
        return reply.code(400).send({ error: 'too_many', message: '单次最多上报 200 条' });
      }

      const result = ingestChildEvents(device.id, device.user_id, events);
      return { ok: true, accepted: result.accepted, duplicated: result.duplicated };
    },
  );
}
