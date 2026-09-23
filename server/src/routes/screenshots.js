import fs from 'node:fs';
import { one } from '../db.js';
import { ownedDevice } from './helpers.js';
import { enqueueCommand } from '../commands.js';
import { writeAudit } from '../audit.js';
import {
  saveScreenshot,
  listScreenshots,
  resolveScreenshotFile,
  markViewed,
  removeOne,
} from '../screenshots.js';
import { config } from '../config.js';

/**
 * 截屏：按需单张。
 *
 * 为什么不做成"定时自动截屏"或"实时镜像"：
 *   - 定时截屏的流量与存储消耗大，且会产生大量家长根本不看的图片
 *   - 实时镜像的权限提示（系统持续显示"正在录制屏幕"）与合规风险都最高
 * 按需单张的体验是"家长点一下 → 几秒后看到一张"，够用且代价最小。
 *
 * 合规提示：截屏涉及未成年人隐私。控制端每查看一次都会写审计日志，
 * 且图片有张数与天数双重上限，不长期留存。
 */
export default async function screenshotRoutes(fastify) {
  /** 控制端：请求设备立即截一张 */
  fastify.post(
    '/api/devices/:id/screenshot',
    { preHandler: fastify.requireParent },
    async (request, reply) => {
      const device = ownedDevice(request, reply);
      if (!device) return;

      // 走指令队列而不是直接推送：设备可能离线，
      // 指令落库后会在它上线时补发（TTL 60 秒，过期即作废）
      const command = enqueueCommand(device.id, 'screenshot', {}, {
        userId: request.claims.userId,
        auditAction: 'command.screenshot',
      });

      return {
        ok: true,
        commandId: command.commandId,
        delivered: command.delivered,
        expireAt: command.expireAt,
        notice: command.delivered
          ? '已下发，通常几秒内返回'
          : '设备当前离线，指令将在其上线后 60 秒内有效',
      };
    },
  );

  /**
   * 被控端：上传截屏图片。
   *
   * 用原始二进制 body（`Content-Type: image/jpeg`）而不是 JSON 里的 base64，
   * 因为 base64 会让体积膨胀 33%，而截屏是本系统里最大的单体上传。
   */
  fastify.post(
    '/api/screenshots',
    {
      preHandler: fastify.requireChild,
      bodyLimit: config.screenshot.maxBytes + 64 * 1024,
    },
    async (request, reply) => {
      const deviceId = Number(request.claims.deviceId);
      if (!Number.isInteger(deviceId)) {
        return reply.code(401).send({ error: 'invalid_token', message: '设备令牌无效' });
      }
      const device = one('SELECT * FROM devices WHERE id = ?', deviceId);
      if (!device) {
        return reply.code(404).send({ error: 'device_not_found', message: '设备不存在，请重新配对' });
      }

      const body = request.body;
      if (!Buffer.isBuffer(body)) {
        return reply.code(400).send({
          error: 'invalid_body',
          message: '请求体必须是图片二进制',
        });
      }

      // 元信息走查询参数 —— body 已被图片占用
      const query = request.query ?? {};
      const result = saveScreenshot(deviceId, {
        bytes: body,
        mime: String(request.headers['content-type'] ?? 'image/jpeg').split(';')[0].trim(),
        width: Number(query.width),
        height: Number(query.height),
        foregroundPackage: query.foreground ?? null,
        captureMode: query.mode ?? null,
        commandId: query.commandId ?? null,
        userId: device.user_id,
      });

      if (!result.ok) {
        return reply.code(400).send({ error: result.reason, message: result.message });
      }

      return { ok: true, screenshot: result.view };
    },
  );

  /** 控制端：某设备的截屏列表（不含图片本体） */
  fastify.get(
    '/api/devices/:id/screenshots',
    { preHandler: fastify.requireParent },
    async (request, reply) => {
      const device = ownedDevice(request, reply);
      if (!device) return;

      const limit = Number(request.query?.limit ?? 30);
      return { screenshots: listScreenshots(device.id, limit) };
    },
  );

  /**
   * 控制端：取图片本体。
   *
   * 路由里带 `:id`（设备）而不是只按截图 id 查 ——
   * 这是越权访问的最后一道闸：只按截图 id 查的话，
   * 家长 A 猜一个数字就能拿到家长 B 孩子的屏幕内容。
   */
  fastify.get(
    '/api/devices/:id/screenshots/:shotId/image',
    { preHandler: fastify.requireParent },
    async (request, reply) => {
      const device = ownedDevice(request, reply);
      if (!device) return;

      const shotId = Number(request.params.shotId);
      if (!Number.isInteger(shotId)) {
        return reply.code(400).send({ error: 'invalid_id', message: '截屏 ID 不合法' });
      }

      const found = resolveScreenshotFile(shotId, device.id);
      if (!found) {
        return reply.code(404).send({ error: 'not_found', message: '截屏不存在或已被清理' });
      }

      // 查看行为记审计：截屏涉及未成年人隐私，谁看过必须可追溯
      markViewed(shotId, request.claims.userId);

      /**
       * 必须传**文件流**，不能传路径字符串。
       *
       * `reply.send('/path/to/x.jpg')` 会把路径当普通字符串发出去 ——
       * 家长拿到一段坏掉的"图片"，同时把服务器的文件系统路径暴露出去了。
       * 而且这个错误完全静默：状态码 200、MIME 也正确，只有图片打不开。
       *
       * 用流而不是 readFileSync：截屏最大 1 MB，读进内存没必要，
       * 流还能让客户端在下载过程中就开始解码。
       */
      reply.type(found.row.mime);
      // no-store：截屏是隐私数据，不该留在任何中间缓存或浏览器磁盘缓存里
      reply.header('cache-control', 'private, no-store');
      return reply.send(fs.createReadStream(found.absPath));
    },
  );

  /** 控制端：删除某张截屏 */
  fastify.delete(
    '/api/devices/:id/screenshots/:shotId',
    { preHandler: fastify.requireParent },
    async (request, reply) => {
      const device = ownedDevice(request, reply);
      if (!device) return;

      const shotId = Number(request.params.shotId);
      const found = resolveScreenshotFile(shotId, device.id);
      if (!found) {
        return reply.code(404).send({ error: 'not_found', message: '截屏不存在' });
      }

      removeOne(shotId);
      writeAudit(request.claims.userId, device.id, 'screenshot.delete', { screenshotId: shotId });
      return { ok: true };
    },
  );
}
