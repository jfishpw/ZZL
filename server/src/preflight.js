/**
 * 启动前置检查（preflight）。
 *
 * 单独成文件而不是塞在 index.js 里，原因有两条：
 *
 * 1. **可被单元测试覆盖**。这两项检查的失败方式是「服务根本起不来」，
 *    判错的代价极高 —— 用户看到的是一个没有任何输出的死进程。
 *
 * 2. **输出必须同步**。`console.error` 在 stdout/stderr 为管道时
 *    （Docker 日志、`| grep`、CI）走的是**异步写**，而 `process.exit()`
 *    不会等缓冲区刷新 —— 结果是那段"为什么起不来"的说明被直接丢掉。
 *    这不是理论风险：本模块诞生前，时区检查的报错就是这样消失的，
 *    实测表现为「进程退出码 1、零输出」。
 */
import fs from 'node:fs';

/** 同步写 stderr。任何"退出前必须让用户看到"的输出都走这里。 */
export function writeStderr(text) {
  const line = text.endsWith('\n') ? text : `${text}\n`;
  try {
    fs.writeSync(2, line);
  } catch {
    console.error(text); // fd 不可用时的兜底（例如被重定向到已关闭的描述符）
  }
}

/** 把分钟偏移格式化成 UTC+08:00 这样的可读形式 */
export function describeOffset(minutes) {
  const sign = minutes >= 0 ? '+' : '-';
  const abs = Math.abs(minutes);
  return `UTC${sign}${String(Math.floor(abs / 60)).padStart(2, '0')}:${String(abs % 60).padStart(2, '0')}`;
}

/**
 * 某个 IANA 时区在给定时刻的偏移（分钟，东为正）。
 *
 * 走 `Intl.DateTimeFormat` 而不是 `Date` 的本地时间方法：
 * Intl 使用 Node 内置的 ICU 时区数据，**不依赖操作系统的 /usr/share/zoneinfo**。
 * 这正是它能用来"验算"系统时区的原因 —— 前者在容器里始终可用，
 * 后者缺 tzdata 时会静默退化到 UTC。
 */
export function offsetMinutesOf(timeZone, at = new Date()) {
  const parts = {};
  const dtf = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
  for (const part of dtf.formatToParts(at)) parts[part.type] = part.value;
  // 个别环境下 hour 会给出 "24"，取模归一
  const asIfUtc = Date.UTC(
    Number(parts.year), Number(parts.month) - 1, Number(parts.day),
    Number(parts.hour) % 24, Number(parts.minute), Number(parts.second),
  );
  return Math.round((asIfUtc - at.getTime()) / 60000);
}

/**
 * 检查 JWT 密钥。
 *
 * ⚠️ 不能只判断"是否以 dev-secret 开头"：
 * config.js 用的是 `process.env.JWT_SECRET ?? 'dev-secret-...'`，
 * 而 `??` **只对 null / undefined 生效**。当 .env 里写成 `JWT_SECRET=`（留空）时，
 * 拿到的是空字符串而非默认值，`startsWith('dev-secret')` 也不成立 ——
 * 于是一个「密钥为空」的服务会照常启动。
 *
 * 空密钥不等于"弱鉴权"，而是"没有鉴权"：任何人都能用空串签出合法的家长令牌。
 * 而 .env.example 里那一行正是留空的（等着被 install.sh 或人工填写）。
 */
export function inspectJwtSecret(secret, production) {
  const lines = [];

  if (secret.length === 0) {
    return {
      ok: false,
      lines: [
        '[fatal] JWT_SECRET 为空，拒绝启动 —— 留空等同于完全不做鉴权。',
        '        生成方式：openssl rand -hex 32',
      ],
    };
  }

  if (production && (secret.startsWith('dev-secret') || secret.length < 32)) {
    return {
      ok: false,
      lines: [
        '[fatal] 生产环境的 JWT_SECRET 过弱：至少 32 字符，且不得使用内置默认值。',
        '        生成方式：openssl rand -hex 32',
      ],
    };
  }

  if (secret.length < 32) {
    lines.push('[warn] JWT_SECRET 短于 32 字符，仅建议用于本机开发。');
  }

  return { ok: true, lines };
}

/**
 * 检查进程实际生效的时区是否符合声明。
 *
 * 【为什么必须检查】
 * 额度日的归日点完全由**进程本地时区**决定：
 *   - 服务端 grants.js#dayKeyAt / usage.js#dayKeyOf 用 getFullYear / getHours
 *   - 被控端 DayKeys 用 Calendar（设备本地时区）
 * 容器默认 UTC，与被控端（北京时间）差 8 小时。后果不是"差一点"而是**整天错位**：
 * 平板把 9/19 早 8 点的使用记录标成 dayKey=2026-09-19，
 * 而服务器此刻的 todayKey() 还是 2026-09-18 ——
 * 家长点开使用报告看到「今日用量 0 分钟」，加时授权也会落在错误的日子上。
 *
 * 【为什么用"声明值 vs 实际值"来判】
 * glibc 读 TZ 依赖 /usr/share/zoneinfo，镜像里缺 tzdata 时 TZ 会被**静默忽略**，
 * 于是 `TZ=Asia/Shanghai` 看起来设了、实际仍按 UTC 跑，没有任何报错。
 * 把"按 ICU 算出的应有偏移"与"进程实际生效的偏移"对一下，就能抓到这种情况。
 */
export function inspectTimezone({ tz, actualOffsetMinutes, production }) {
  const problems = [];

  if (tz) {
    let expected = null;
    try {
      expected = offsetMinutesOf(tz);
    } catch {
      problems.push(`TZ="${tz}" 不是有效的时区名（示例：Asia/Shanghai）`);
    }
    if (expected !== null && expected !== actualOffsetMinutes) {
      problems.push(
        `TZ="${tz}" 期望 ${describeOffset(expected)}，实际却是 ${describeOffset(actualOffsetMinutes)}；` +
          '通常是镜像缺少 tzdata，导致 TZ 被静默忽略',
      );
    }
  } else if (actualOffsetMinutes === 0) {
    problems.push(
      '未设置 TZ，进程正按 UTC 运行；容器默认时区就是 UTC，' +
        '这会让「额度日」与设备端相差 8 小时',
    );
  }

  if (problems.length === 0) return { ok: true, lines: [], problems };

  const lines = problems.map((p) => `[${production ? 'fatal' : 'warn'}] 时区异常：${p}`);
  lines.push('        影响：使用报告的"今日用量"会与设备实际不符，加时授权可能落在错误的日子。');
  if (production) {
    lines.push('        修复：在 deploy/.env 中设置 TZ=Asia/Shanghai，然后 docker compose up -d --force-recreate');
  }

  return { ok: !production, lines, problems };
}
