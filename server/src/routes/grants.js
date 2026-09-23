import { one } from '../db.js';
import { ownedDevice, deviceFromToken } from './helpers.js';
import {
  createGrant,
  revokeGrant,
  grantHistory,
  deviceState,
  activeGrants,
  toGrantView,
} from '../grants.js';

/**
 * 临时授权接口。
 *
 * 控制端创建/撤销，被控端通过 `/api/device-state` 做**全量对账**。
 * 之所以不做成逐条下发指令：授权是有有效期的持续状态，
 * 逐条下发会出现「补发的授权到达时早已过期」，而对账天然不会。
 */
export default async function grantRoutes(fastify) {

  /* ---------------- 控制端 ---------------- */

  /**
   * 创建临时授权。
   * body: { scope: 'total_add' | 'app_allow' | 'unlock',
   *         packageName?, extraMinutes?, ttlMinutes? }
   */
  fastify.post('/api/devices/:id/grants', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const result = createGrant(device.id, request.body ?? {}, {
      source: 'manual',
      userId: request.claims.userId,
      auditAction: 'grant.create',
    });

    if (!result.ok) {
      return reply.code(400).send({ error: result.reason, message: result.message });
    }

    return {
      ok: true,
      grant: result.grant,
      delivered: result.delivered,
      notice: result.delivered
        ? null
        : '设备当前离线，授权将在其上线后自动生效（若届时仍在有效期内）',
    };
  });

  /** 授权列表：active 标记区分「正在生效」与「已过期/已撤销」 */
  fastify.get('/api/devices/:id/grants', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const limitRaw = Number(request.query?.limit);
    const limit = Number.isInteger(limitRaw) && limitRaw > 0 && limitRaw <= 200 ? limitRaw : 50;

    return {
      grants: grantHistory(device.id, limit),
      active: activeGrants(device.id).map(toGrantView),
    };
  });

  /** 撤销授权 */
  fastify.delete('/api/grants/:grantId', { preHandler: fastify.requireParent }, async (request, reply) => {
    const grantId = Number(request.params.grantId);
    if (!Number.isInteger(grantId)) {
      return reply.code(400).send({ error: 'invalid_grant_id', message: '授权 ID 不合法' });
    }

    // 先确认这条授权属于当前家长名下的设备，避免越权撤销
    const owned = one(
      `SELECT g.id FROM grants g
         JOIN devices d ON d.id = g.device_id
        WHERE g.id = ? AND d.user_id = ?`,
      grantId, request.claims.userId,
    );
    if (!owned) {
      return reply.code(404).send({ error: 'grant_not_found', message: '授权不存在' });
    }

    const result = revokeGrant(grantId, request.claims.userId);
    if (!result.ok) {
      return reply.code(404).send({ error: result.reason, message: result.message });
    }
    return { ok: true, grant: result.grant };
  });

  /* ---------------- 被控端 ---------------- */

  /**
   * 设备状态对账。
   *
   * 这是被控端恢复「最终一致」的兜底通道：锁定状态、全部生效授权一次性拿全。
   * 断网期间漏掉的状态推送、丢失回执的指令，都会在下一次对账时被纠正。
   */
  fastify.get('/api/device-state', { preHandler: fastify.requireChild }, async (request, reply) => {
    const device = deviceFromToken(request, reply);
    if (!device) return;

    return deviceState(device.id);
  });
}
