import { one, run, now } from '../db.js';
import { hashPassword, verifyPassword, dummyHash } from '../auth.js';
import { config } from '../config.js';
import { createLimiter } from '../ratelimit.js';

/** 同一 IP 5 分钟内最多 10 次登录尝试 */
const loginLimiter = createLimiter({ name: 'login', max: 10, windowMs: 5 * 60 * 1000 });
/** 注册同样限流，避免被刷出大量垃圾账号 */
const registerLimiter = createLimiter({ name: 'register', max: 5, windowMs: 60 * 60 * 1000 });

export default async function authRoutes(fastify) {
  /** 注册家长账号 */
  fastify.post('/api/auth/register', async (request, reply) => {
    const limit = registerLimiter(request.ip);
    if (!limit.allowed) {
      return reply
        .code(429)
        .send({ error: 'too_many_requests', message: `注册过于频繁，请 ${limit.retryAfterSeconds} 秒后再试` });
    }

    const { username, password } = request.body ?? {};

    if (typeof username !== 'string' || username.trim().length < 3) {
      return reply.code(400).send({ error: 'invalid_username', message: '用户名至少 3 个字符' });
    }
    if (typeof password !== 'string' || password.length < 6) {
      return reply.code(400).send({ error: 'invalid_password', message: '密码至少 6 位' });
    }

    const name = username.trim();
    if (one('SELECT id FROM users WHERE username = ?', name)) {
      return reply.code(409).send({ error: 'username_taken', message: '该用户名已被占用' });
    }

    const result = run(
      'INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)',
      name,
      hashPassword(password),
      now(),
    );

    return reply.code(201).send({ userId: Number(result.lastInsertRowid), username: name });
  });

  /** 家长登录 */
  fastify.post('/api/auth/login', async (request, reply) => {
    // 限流键用 IP：账号维度反而会泄露"这个用户名存在"
    const limit = loginLimiter(request.ip);
    if (!limit.allowed) {
      return reply.code(429).send({
        error: 'too_many_attempts',
        message: `登录尝试过于频繁，请 ${limit.retryAfterSeconds} 秒后再试`,
      });
    }

    const { username, password } = request.body ?? {};
    if (typeof username !== 'string' || typeof password !== 'string') {
      return reply.code(400).send({ error: 'invalid_body', message: '缺少用户名或密码' });
    }

    const user = one('SELECT * FROM users WHERE username = ?', username.trim());
    // 用户不存在时也走一遍等价开销的校验，避免通过响应时间枚举账号
    const ok = verifyPassword(password, user ? user.password_hash : dummyHash());

    if (!user || !ok) {
      return reply.code(401).send({ error: 'invalid_credentials', message: '用户名或密码错误' });
    }

    const token = fastify.jwt.sign(
      { userId: user.id, role: 'parent', username: user.username },
      { expiresIn: config.parentTokenTtl },
    );

    return { token, userId: user.id, username: user.username, role: 'parent' };
  });

  /** 校验当前令牌是否有效（App 启动时探活 / 切换服务器后校验） */
  fastify.get('/api/auth/me', { preHandler: fastify.requireParent }, async (request) => {
    return { userId: request.claims.userId, username: request.claims.username, role: 'parent' };
  });
}
