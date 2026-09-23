/**
 * 极简内存限流器。
 *
 * 服务以公网 IP 直连，`/api/auth/login` 与 `/api/pair/claim` 是仅有的
 * 两个"猜对了就能进门"的入口，必须防爆破：
 *   - 登录密码：暴力破解成本太低
 *   - 配对码只有 6 位数字（100 万种），5 分钟有效期内足够被穷举
 *
 * 单进程内存计数即可 —— 本服务本来就是单容器单进程。
 * 若日后要多实例部署，这里需要换成 Redis 之类的共享存储。
 */
const buckets = new Map();

/**
 * 环回地址不限流。
 * 服务部署在公网 IP 后，request.ip 就是真实客户端地址；
 * 而本机开发与自动化测试会反复触发登录/配对，被自己的限流挡住反而误事。
 * 能访问环回地址的人本来就已经拿到了本机权限，这里不构成额外风险。
 */
function isLoopback(key) {
  return key === '127.0.0.1' || key === '::1' || key === '::ffff:127.0.0.1' || key === 'localhost';
}

export function createLimiter({ name, max, windowMs }) {
  return function check(key) {
    if (isLoopback(key)) return { allowed: true, remaining: max };

    const now = Date.now();
    const bucketKey = `${name}:${key}`;
    const bucket = buckets.get(bucketKey);

    if (!bucket || now > bucket.resetAt) {
      buckets.set(bucketKey, { count: 1, resetAt: now + windowMs });
      return { allowed: true, remaining: max - 1 };
    }

    bucket.count += 1;
    if (bucket.count > max) {
      return {
        allowed: false,
        retryAfterMs: bucket.resetAt - now,
        retryAfterSeconds: Math.ceil((bucket.resetAt - now) / 1000),
      };
    }

    return { allowed: true, remaining: max - bucket.count };
  };
}

/** 定期清理过期计数桶，避免长期运行后内存无限增长 */
const sweeper = setInterval(() => {
  const now = Date.now();
  for (const [key, bucket] of buckets) {
    if (now > bucket.resetAt) buckets.delete(key);
  }
}, 60_000);

// 不阻止进程退出
if (typeof sweeper.unref === 'function') sweeper.unref();

/** 供测试或运维查看当前桶数量 */
export function bucketCount() {
  return buckets.size;
}
