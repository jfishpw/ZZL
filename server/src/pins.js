import { one, run, now } from './db.js';

/**
 * 离线密码备份的数据访问层。
 *
 * 与 `routes/pins.js` 分开的理由：`grants.js` 的「设备状态对账」需要把密码备份
 * 一并下发（换机恢复、远程重置都靠它），如果让 `grants.js` 去 import 路由文件
 * 就会形成循环依赖。这里只放数据读写，不碰 HTTP。
 *
 * 三条不可动摇的边界：
 *  1. **服务端从不接触明文密码。** 设备端算好 PBKDF2 结果后才上传，
 *     服务端只存 `hash + salt + iterations`，既不验证也不重算。
 *  2. **salt 与 iterations 必须与哈希一起存。** 日后调高迭代次数时，
 *     旧密码仍要能验证通过 —— 只存 hash 会让调参变成一次全量失效。
 *  3. **远程重置走设备状态对账，不走指令队列。** 密码是一份持续状态而不是
 *     一次性动作；走指令会遇到"补发的重置到达时家长早已改用新密码"的混乱。
 */

export const LEVELS = [1, 2, 3];

/** 密码哈希与盐的长度上限（hex/base64 文本） */
export const MAX_SECRET_CHARS = 512;
export const MAX_HINT_CHARS = 60;

/** 各级可执行的操作。这是控制端说明与被控端行为的唯一口径。 */
export const LEVEL_CAPABILITIES = {
  1: { name: '日常密码', actions: ['总时长加时'] },
  2: { name: '管理密码', actions: ['总时长加时', '单应用临时放行'] },
  3: {
    name: '超级密码',
    actions: ['总时长加时', '单应用临时放行', '退出管控', '解除设备管理器', '卸载本应用'],
  },
};

export function readPins(deviceId) {
  return one('SELECT * FROM local_pins WHERE device_id = ?', Number(deviceId));
}

export function hasAnyLevel(deviceId) {
  const row = readPins(deviceId);
  if (!row) return false;
  return LEVELS.some((level) => !!row[`level${level}_hash`]);
}

/** 确保存在一行，供首次上传或首次远程重置时使用 */
export function ensurePinsRow(deviceId) {
  const existing = readPins(deviceId);
  if (existing) return existing;

  // 初始版本号为 0：这样设备首次上传密码后版本是 1，符合"第 1 版"的直觉。
  // 若从 1 开始，首次上传会得到 2，调试时很容易误判成"多写了一次"。
  run(
    'INSERT INTO local_pins (device_id, level_count, updated_at, version) VALUES (?, 3, ?, 0)',
    Number(deviceId),
    now(),
  );
  return readPins(deviceId);
}

/**
 * 写入一级密码。`entry` 为 null 表示清除该级。
 *
 * 列名是拼接出来的，但 level 已由调用方限制在 [1,2,3]，
 * 不存在注入风险 —— SQLite 也不支持把标识符做成绑定参数。
 */
export function writeLevel(deviceId, level, entry) {
  if (!LEVELS.includes(level)) throw new Error(`非法的密码级别 ${level}`);

  if (entry === null) {
    run(
      `UPDATE local_pins
          SET level${level}_hash = NULL, level${level}_salt = NULL,
              level${level}_iters = NULL, level${level}_hint = NULL
        WHERE device_id = ?`,
      Number(deviceId),
    );
    return;
  }

  run(
    `UPDATE local_pins
        SET level${level}_hash = ?, level${level}_salt = ?,
            level${level}_iters = ?, level${level}_hint = ?
      WHERE device_id = ?`,
    entry.hash,
    entry.salt,
    entry.iterations,
    entry.hint ?? null,
    Number(deviceId),
  );
}

/** 推进版本号并刷新时间戳，返回新版本 */
export function bumpPinsVersion(deviceId) {
  const version = (readPins(deviceId)?.version ?? 0) + 1;
  run('UPDATE local_pins SET updated_at = ?, version = ? WHERE device_id = ?', now(), version, Number(deviceId));
  run('UPDATE devices SET pin_ready = ? WHERE id = ?', hasAnyLevel(deviceId) ? 1 : 0, Number(deviceId));
  return version;
}

/**
 * 完整视图（含哈希）。只用于下发给**被控端自己** ——
 * 换机恢复时设备需要拿到可验证的哈希与盐。
 */
export function toPinsView(row) {
  if (!row) return null;
  const levels = [];
  for (const level of LEVELS) {
    const hash = row[`level${level}_hash`];
    if (!hash) continue;
    levels.push({
      level,
      hash,
      salt: row[`level${level}_salt`] ?? null,
      iterations: row[`level${level}_iters`] ?? null,
      hint: row[`level${level}_hint`] ?? null,
    });
  }
  return {
    deviceId: row.device_id,
    levelCount: row.level_count,
    levels,
    version: row.version,
    updatedAt: row.updated_at,
  };
}

/**
 * 控制端视图：只暴露"哪几级已设置 + 提示语"。
 *
 * **绝不回传哈希** —— 控制端不需要它（远程重置是新算一个，不是复用旧的），
 * 而回传就等于把密码备份暴露给了任何拿到家长令牌的人。
 */
export function toPinsSummary(row) {
  if (!row) {
    return {
      levelCount: 0,
      levels: [],
      version: 0,
      updatedAt: null,
      capabilities: LEVEL_CAPABILITIES,
    };
  }
  return {
    levelCount: row.level_count,
    levels: LEVELS
      .filter((level) => !!row[`level${level}_hash`])
      .map((level) => ({
        level,
        hint: row[`level${level}_hint`] ?? null,
        name: LEVEL_CAPABILITIES[level].name,
        actions: LEVEL_CAPABILITIES[level].actions,
      })),
    version: row.version,
    updatedAt: row.updated_at,
    capabilities: LEVEL_CAPABILITIES,
  };
}

/** 校验设备端或控制端提交的一级条目 */
export function normalizeLevel(input) {
  if (input === null || input === undefined) return { ok: true, value: null };
  if (typeof input !== 'object') return { ok: false, reason: '密码条目格式不正确' };

  const hash = typeof input.hash === 'string' ? input.hash.trim() : '';
  const salt = typeof input.salt === 'string' ? input.salt.trim() : '';
  if (!hash || hash.length > MAX_SECRET_CHARS) {
    return { ok: false, reason: '哈希值缺失或过长' };
  }
  if (!salt || salt.length > MAX_SECRET_CHARS) {
    return { ok: false, reason: '盐值缺失或过长' };
  }

  const iterations = Number(input.iterations);
  if (!Number.isInteger(iterations) || iterations < 10_000 || iterations > 10_000_000) {
    return { ok: false, reason: '迭代次数需在 1 万 - 1000 万之间' };
  }

  const hint =
    typeof input.hint === 'string' && input.hint.trim()
      ? input.hint.trim().slice(0, MAX_HINT_CHARS)
      : null;

  return { ok: true, value: { hash, salt, iterations, hint } };
}

/**
 * 设备状态里携带的密码备份。
 *
 * 换机恢复与远程重置都靠它：设备重装后第一次对账即可拿回自己的密码，
 * 家长远程重置后设备上线也对账即可生效 —— 两条路径共用同一份数据。
 */
export function pinsForState(deviceId) {
  return toPinsView(readPins(deviceId));
}
