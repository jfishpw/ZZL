import { ownedDevice, deviceFromToken } from './helpers.js';
import { setLocked, setIconHidden } from '../grants.js';
import {
  enqueueCommand,
  pendingCommands,
  ackCommand,
  listCommands,
} from '../commands.js';

/**
 * 下行动作指令。
 *
 * 所有指令都先落库再推送，因此「设备离线时下发」不会丢：
 * 被控端每次上线（含断线重连）都会拉取待执行列表，按 id 升序依次执行并回执。
 */
export default async function commandRoutes(fastify) {

  /* ---------------- 控制端 ---------------- */

  /** 立即锁定设备（孩子端立刻弹出全屏遮罩，直到家长解除） */
  fastify.post('/api/devices/:id/lock-now', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    // 两步都要做：命令保证"即时动作"，设备字段保证"状态可对账恢复"
    const { delivered } = setLocked(device.id, true, request.claims.userId);
    const command = enqueueCommand(device.id, 'immediate_lock', { reason: request.body?.reason ?? null });

    return { ok: true, delivered: delivered || command.delivered, commandId: command.commandId };
  });

  /** 解除锁定 */
  fastify.post('/api/devices/:id/unlock', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const { delivered } = setLocked(device.id, false, request.claims.userId);
    const command = enqueueCommand(device.id, 'clear_lock', {});

    return { ok: true, delivered: delivered || command.delivered, commandId: command.commandId };
  });

  /**
   * 隐藏 / 恢复被控端的桌面图标。
   *
   * 与锁定同一套思路：状态字段负责"重启后仍生效"，指令负责"立刻动作"。
   * 只做指令的话，孩子重启一次平板图标就回来了。
   */
  fastify.post('/api/devices/:id/icon', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const hidden = request.body?.hidden === true;
    const { delivered } = setIconHidden(device.id, hidden, request.claims.userId);
    const command = enqueueCommand(device.id, hidden ? 'hide_icon' : 'show_icon', {});

    return {
      ok: true,
      hidden,
      delivered: delivered || command.delivered,
      commandId: command.commandId,
      notice: hidden
        ? '图标已隐藏（重启/离线不失效）。可在控制端「限制工具 → 桌面图标」随时恢复'
        : '图标已恢复',
    };
  });

  /** 指令历史与状态（含已失效） */
  fastify.get('/api/devices/:id/commands', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const limitRaw = Number(request.query?.limit);
    const limit = Number.isInteger(limitRaw) && limitRaw > 0 && limitRaw <= 200 ? limitRaw : 50;
    return { commands: listCommands(device.id, limit) };
  });

  /* ---------------- 被控端 ---------------- */

  /**
   * 拉取待执行指令。
   * 按 id 升序 —— 顺序即语义，「先解除锁定、再立即锁定」必须按这个次序执行。
   */
  fastify.get('/api/commands/pending', { preHandler: fastify.requireChild }, async (request, reply) => {
    const device = deviceFromToken(request, reply);
    if (!device) return;

    return {
      commands: pendingCommands(device.id),
      serverTime: Date.now(),
      locked: !!device.locked,
      iconHidden: !!device.icon_hidden,
    };
  });

  /**
   * 指令回执。
   * 已终态的指令不会被覆盖 —— 补发重试的回执不能改写首次执行结果。
   */
  fastify.post('/api/commands/:commandId/ack', { preHandler: fastify.requireChild }, async (request, reply) => {
    const commandId = String(request.params.commandId ?? '');
    if (!commandId) {
      return reply.code(400).send({ error: 'invalid_command_id', message: '缺少指令 ID' });
    }

    const { status, detail } = request.body ?? {};
    const result = ackCommand(commandId, Number(request.claims.deviceId), status, detail ?? null);

    if (!result.ok && result.reason === 'not_found') {
      // 指令不存在（可能已被清理）：不当作错误，避免设备无限重试
      return { ok: true, ignored: true };
    }
    if (!result.ok) {
      return reply.code(403).send({ error: result.reason, message: '指令不属于该设备' });
    }

    return { ok: true, command: result.view };
  });
}
