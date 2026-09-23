import { one, run, now } from '../db.js';
import { generatePairCode } from '../auth.js';
import { config } from '../config.js';
import { createLimiter } from '../ratelimit.js';

/**
 * 配对码只有 6 位数字（100 万种），5 分钟有效期内足以被脚本穷举，
 * 因此认领接口必须限流。同一 IP 10 分钟最多 20 次。
 */
const claimLimiter = createLimiter({ name: 'claim', max: 20, windowMs: 10 * 60 * 1000 });

export default async function pairRoutes(fastify) {
  /** 控制端生成配对码（默认 5 分钟有效） */
  fastify.post('/api/pair/code', { preHandler: fastify.requireParent }, async (request) => {
    const userId = request.claims.userId;
    const expiresAt = now() + config.pairCodeTtlMs;

    // 先作废该账号下所有未使用的旧码，保证同一时刻只有一个有效码
    run('UPDATE pair_codes SET used = 1 WHERE user_id = ? AND used = 0', userId);

    let code = generatePairCode();
    for (let i = 0; i < 10 && one('SELECT code FROM pair_codes WHERE code = ?', code); i += 1) {
      code = generatePairCode();
    }

    run(
      'INSERT INTO pair_codes (code, user_id, expires_at, used, created_at) VALUES (?, ?, ?, 0, ?)',
      code,
      userId,
      expiresAt,
      now(),
    );

    return { code, expiresAt, ttlSeconds: Math.floor(config.pairCodeTtlMs / 1000) };
  });

  /** 被控端凭配对码完成绑定，换取长期设备令牌 */
  fastify.post('/api/pair/claim', async (request, reply) => {
    const limit = claimLimiter(request.ip);
    if (!limit.allowed) {
      return reply.code(429).send({
        error: 'too_many_attempts',
        message: `配对尝试过于频繁，请 ${limit.retryAfterSeconds} 秒后再试`,
      });
    }

    const { code, childUuid, name, model, androidVer } = request.body ?? {};

    if (typeof code !== 'string' || typeof childUuid !== 'string' || childUuid.length < 8) {
      return reply.code(400).send({ error: 'invalid_body', message: '缺少配对码或设备标识' });
    }

    const record = one('SELECT * FROM pair_codes WHERE code = ?', code.trim());
    if (!record) {
      return reply.code(404).send({ error: 'code_not_found', message: '配对码不存在' });
    }
    if (record.used) {
      return reply.code(409).send({ error: 'code_used', message: '配对码已被使用' });
    }
    if (record.expires_at < now()) {
      return reply.code(410).send({ error: 'code_expired', message: '配对码已过期，请重新生成' });
    }

    const userId = record.user_id;
    const deviceName = (typeof name === 'string' && name.trim()) || '儿童平板';

    // 同一台设备重复配对时复用原记录，避免产生僵尸设备
    let device = one('SELECT * FROM devices WHERE child_uuid = ?', childUuid);
    if (device && device.user_id !== userId) {
      return reply.code(409).send({ error: 'device_bound_elsewhere', message: '该设备已绑定到其他账号' });
    }

    if (device) {
      run(
        `UPDATE devices SET name = ?, model = ?, android_ver = ?, online = 0, last_seen = ?, user_id = ?
          WHERE id = ?`,
        deviceName,
        model ?? null,
        androidVer ?? null,
        now(),
        userId,
        device.id,
      );
    } else {
      const result = run(
        `INSERT INTO devices (user_id, name, model, android_ver, child_uuid, online, last_seen, created_at)
         VALUES (?, ?, ?, ?, ?, 0, ?, ?)`,
        userId,
        deviceName,
        model ?? null,
        androidVer ?? null,
        childUuid,
        now(),
        now(),
      );
      device = { id: Number(result.lastInsertRowid) };

      // 新设备同时创建默认策略
      run(
        `INSERT INTO policies (device_id, weekday_total_min, weekend_total_min, reset_hour, list_mode, allow_time_request, updated_at, version)
         VALUES (?, 60, 120, 0, 'blacklist', 1, ?, 1)`,
        device.id,
        now(),
      );
    }

    run(
      'UPDATE pair_codes SET used = 1, device_id = ? WHERE code = ?',
      device.id,
      code.trim(),
    );

    const token = fastify.jwt.sign(
      { deviceId: Number(device.id), role: 'child' },
      { expiresIn: config.childTokenTtl },
    );

    return { deviceId: Number(device.id), token, role: 'child' };
  });
}
