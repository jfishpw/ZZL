import Fastify from 'fastify';
import cors from '@fastify/cors';
import jwt from '@fastify/jwt';

import { config, ensureRuntimeDirs, isProduction } from './config.js';
import { initDb, db, run, all, now } from './db.js';
import { setupWebSocket } from './ws.js';

import healthRoutes from './routes/health.js';
import authRoutes from './routes/auth.js';
import pairRoutes from './routes/pair.js';
import deviceRoutes from './routes/devices.js';
import policyRoutes from './routes/policy.js';
import usageRoutes from './routes/usage.js';
import appRoutes from './routes/apps.js';
import commandRoutes from './routes/commands.js';
import grantRoutes from './routes/grants.js';
import timeRequestRoutes from './routes/timeRequests.js';
import pinRoutes from './routes/pins.js';
import screenshotRoutes from './routes/screenshots.js';
import auditRoutes from './routes/audit.js';
import { expireStaleCommands } from './commands.js';
import { expireStaleRequests } from './routes/timeRequests.js';
import { pruneOrphanFiles } from './screenshots.js';
import { pruneAudit } from './audit.js';
import { sendToUser } from './ws.js';
import { inspectJwtSecret, inspectTimezone, writeStderr } from './preflight.js';

/* ---------------- 初始化 ---------------- */

ensureRuntimeDirs();
initDb();

/* ---------------- 启动前置检查 ---------------- */

/**
 * 两项检查都在监听端口之前完成，任一不过就拒绝启动。
 * 判定细节在 src/preflight.js（抽出去是为了能被单元测试覆盖 ——
 * 这类检查判错的代价是「服务起不来」，而它偏偏最容易写错）。
 *
 * 输出用 writeStderr 而不是 console.error：stdout/stderr 为管道时
 * （Docker 日志、`| grep`、CI）console 走的是**异步写**，
 * 紧随其后的 process.exit() 不会等缓冲区刷新 ——
 * 用户看到的就是「进程退出、零输出、不知道为什么」。
 */
{
  const checks = [
    ['JWT 密钥', inspectJwtSecret(config.jwtSecret, isProduction)],
    [
      '时区',
      inspectTimezone({
        tz: process.env.TZ,
        actualOffsetMinutes: -new Date().getTimezoneOffset(),
        production: isProduction,
      }),
    ],
  ];

  const failed = [];
  for (const [label, result] of checks) {
    for (const line of result.lines) writeStderr(line);
    if (!result.ok) failed.push(label);
  }

  if (failed.length > 0) {
    writeStderr(`[fatal] 启动中止：${failed.join('、')} 检查未通过`);
    process.exit(1);
  }
}

const fastify = Fastify({
  logger: { level: 'info', transport: undefined },
  bodyLimit: 8 * 1024 * 1024, // 截屏上传预留
});

await fastify.register(cors, { origin: true });
await fastify.register(jwt, { secret: config.jwtSecret });

/**
 * 原始二进制 body 解析器。
 *
 * Fastify 默认只解析 application/json 与 text/plain，其它类型直接不填 request.body。
 * 截屏上传走的是原始图片流（`Content-Type: image/jpeg`），
 * 用 base64 塞进 JSON 会让体积膨胀 33%，而截屏是本系统最大的单体上传。
 *
 * `parseAs: 'buffer'` 是关键：不加的话拿到的是字符串，
 * 二进制数据会被 UTF-8 解码破坏 —— 存下来的图片打不开，且不会报任何错。
 */
fastify.addContentTypeParser(
  ['image/jpeg', 'image/jpg', 'image/png', 'image/webp', 'application/octet-stream'],
  { parseAs: 'buffer' },
  (_request, body, done) => done(null, body),
);

/* ---------------- 鉴权前置钩子 ---------------- */

fastify.decorate('requireParent', async function requireParent(request, reply) {
  try {
    await request.jwtVerify();
  } catch {
    return reply.code(401).send({ error: 'unauthorized', message: '登录已失效，请重新登录' });
  }
  if (request.user?.role !== 'parent') {
    return reply.code(403).send({ error: 'forbidden', message: '仅控制端可执行该操作' });
  }
  request.claims = request.user;
});

fastify.decorate('requireChild', async function requireChild(request, reply) {
  try {
    await request.jwtVerify();
  } catch {
    return reply.code(401).send({ error: 'unauthorized', message: '设备令牌无效，请重新配对' });
  }
  if (request.user?.role !== 'child') {
    return reply.code(403).send({ error: 'forbidden', message: '仅被控端可执行该操作' });
  }
  request.claims = request.user;
});

/* ---------------- 路由 ---------------- */

await fastify.register(healthRoutes);
await fastify.register(authRoutes);
await fastify.register(pairRoutes);
await fastify.register(deviceRoutes);
await fastify.register(policyRoutes);
await fastify.register(usageRoutes);
await fastify.register(appRoutes);
await fastify.register(commandRoutes);
await fastify.register(grantRoutes);
await fastify.register(timeRequestRoutes);
await fastify.register(pinRoutes);
await fastify.register(screenshotRoutes);
await fastify.register(auditRoutes);

/* ---------------- WebSocket ---------------- */

const wss = setupWebSocket(fastify);

/* ---------------- 离线巡检 ---------------- */

// 长连接异常断开时可能来不及更新状态，定时兜底
const reaper = setInterval(() => {
  const threshold = now() - config.heartbeatTimeoutMs;
  const stale = all('SELECT id, user_id FROM devices WHERE online = 1 AND (last_seen IS NULL OR last_seen < ?)', threshold);
  for (const device of stale) {
    run('UPDATE devices SET online = 0 WHERE id = ?', device.id);
  }

  // 指令与加时申请都有 TTL，过期必须主动作废：
  // 一条「2 小时前的截屏请求」现在才补发到达是毫无意义的
  try {
    expireStaleCommands();
  } catch (error) {
    fastify.log.warn({ err: error }, '清理过期指令失败');
  }

  try {
    // 先取出即将被标记过期的申请，改状态后逐一通知家长 ——
    // 否则家长会一直看到一个"可以批准"的按钮，点了才报错
    const expiring = all(
      `SELECT r.id, r.device_id, d.user_id
         FROM time_requests r JOIN devices d ON d.id = r.device_id
        WHERE r.status = 'pending' AND r.expire_at <= ?`,
      now(),
    );
    const changed = expireStaleRequests();
    if (changed > 0) {
      for (const row of expiring) {
        sendToUser(row.user_id, {
          type: 'time_request_expired',
          requestId: row.id,
          deviceId: row.device_id,
          at: now(),
        });
      }
    }
  } catch (error) {
    fastify.log.warn({ err: error }, '清理过期加时申请失败');
  }
}, 30_000);

/**
 * 低频维护：孤儿图片与陈旧审计。
 *
 * 单开一个慢周期定时器而不是塞进上面的 30 秒巡检：
 * 扫目录与删日志的代价远高于更新几个状态字段，
 * 放在高频路径里会让巡检的时序变得不可预期。
 *
 * 孤儿文件的存在是必然的：设备解绑时数据库行被级联删除，
 * 但磁盘上的图片不会 —— 而那些正是最不该长期留存的隐私数据。
 */
const maintainer = setInterval(() => {
  try {
    const removed = pruneOrphanFiles();
    if (removed > 0) fastify.log.info(`清理孤儿截屏文件 ${removed} 个`);
  } catch (error) {
    fastify.log.warn({ err: error }, '清理孤儿截屏失败');
  }

  try {
    const pruned = pruneAudit();
    if (pruned > 0) fastify.log.info(`清理过期审计日志 ${pruned} 条`);
  } catch (error) {
    fastify.log.warn({ err: error }, '清理审计日志失败');
  }
}, 6 * 60 * 60 * 1000);

// 启动时先跑一次，避免重启后长时间留着上一次运行期间的孤儿文件
setTimeout(() => {
  try {
    pruneOrphanFiles();
  } catch {
    /* 启动清理失败不影响服务可用性，下一次周期任务会重试 */
  }
}, 10_000);

/* ---------------- 启动 ---------------- */

try {
  await fastify.listen({ host: config.host, port: config.port });
  fastify.log.info(`掌中灵中继服务已启动 — http://${config.host}:${config.port}`);
  fastify.log.info(`WebSocket 端点 — ws://${config.host}:${config.port}/ws`);
  fastify.log.info(`数据库文件 — ${config.dbPath}`);
} catch (err) {
  fastify.log.error(err);
  process.exit(1);
}

/* ---------------- 优雅退出 ---------------- */

async function shutdown(signal) {
  fastify.log.info(`收到 ${signal}，正在关闭…`);
  clearInterval(reaper);
  clearInterval(maintainer);
  for (const client of wss.clients) client.terminate();
  wss.close();
  await fastify.close();
  db.close();
  process.exit(0);
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
