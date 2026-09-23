/**
 * 数据库自检：验证 schema 可创建、增删查改与级联删除正常。
 * 使用独立测试库，不污染正式数据。
 */
process.env.DB_PATH = 'data/_selftest.db';
process.env.JWT_SECRET = 'selftest';

const fs = await import('node:fs');

const dbFile = new URL('../data/_selftest.db', import.meta.url);
for (const suffix of ['', '-wal', '-shm']) {
  try {
    fs.unlinkSync(new URL('../data/_selftest.db' + suffix, import.meta.url));
  } catch {
    /* 文件不存在，忽略 */
  }
}

const { db, initDb, one, all, run } = await import('../src/db.js');
const { hashPassword, verifyPassword, hashPin, verifyPin, generatePairCode } = await import('../src/auth.js');

let pass = 0;
let fail = 0;
function check(label, ok, extra) {
  if (ok) {
    pass += 1;
    console.log(`  \u2713 ${label}`);
  } else {
    fail += 1;
    console.log(`  \u2717 ${label}${extra !== undefined ? `  → ${JSON.stringify(extra)}` : ''}`);
  }
}

console.log('\n数据库与密码学自检\n');

console.log('[1] schema 初始化');
initDb();
const tables = all("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
  .map((r) => r.name);
const expected = [
  'app_rules', 'audit_logs', 'block_logs', 'commands', 'devices', 'grants',
  'installed_apps', 'local_pins', 'pair_codes', 'pin_attempts', 'policies',
  'policy_list_items', 'screenshots', 'time_requests', 'usage_daily',
  'usage_sessions', 'users',
];
check(`创建了 ${expected.length} 张表`, expected.every((t) => tables.includes(t)), tables);
check('WAL 模式生效', one('PRAGMA journal_mode')?.journal_mode === 'wal');

console.log('\n[2] 密码哈希');
const pwHash = hashPassword('test123456');
check('scrypt 哈希格式正确', pwHash.startsWith('scrypt$'), pwHash.slice(0, 20));
check('正确密码通过校验', verifyPassword('test123456', pwHash));
check('错误密码被拒绝', !verifyPassword('wrong', pwHash));
check('相同密码两次哈希不同（有盐）', hashPassword('test123456') !== hashPassword('test123456'));

const pinHash = hashPin('889900');
check('PBKDF2 哈希格式正确', pinHash.startsWith('pbkdf2$100000$'), pinHash.slice(0, 24));
check('正确 PIN 通过校验', verifyPin('889900', pinHash));
check('错误 PIN 被拒绝', !verifyPin('000000', pinHash));

console.log('\n[3] 配对码');
const codes = new Set();
for (let i = 0; i < 200; i += 1) codes.add(generatePairCode());
check('配对码恒为 6 位数字', [...codes].every((c) => /^\d{6}$/.test(c)));
check('200 次生成无明显碰撞', codes.size > 190, codes.size);

console.log('\n[4] 数据读写与关联');
const userId = Number(run(
  'INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)',
  'selftest_user', hashPassword('x'), 1,
).lastInsertRowid);
check('插入用户返回自增主键', userId > 0, userId);

const deviceId = Number(run(
  `INSERT INTO devices (user_id, name, model, android_ver, child_uuid, online, last_seen, created_at)
   VALUES (?, ?, ?, ?, ?, 0, ?, ?)`,
  userId, '自检平板', 'TestPad', 33, 'selftest-uuid', 1, 1,
).lastInsertRowid);
check('插入设备成功', deviceId > 0, deviceId);

run(
  `INSERT INTO policies (device_id, weekday_total_min, weekend_total_min, reset_hour, list_mode, allow_time_request, updated_at, version)
   VALUES (?, 60, 120, 0, 'blacklist', 1, ?, 1)`,
  deviceId, 1,
);
check('默认策略已写入', one('SELECT * FROM policies WHERE device_id = ?', deviceId)?.weekday_total_min === 60);

run(
  `INSERT INTO app_rules (device_id, package_name, app_label, daily_limit_min, time_windows, weekdays_mask, enabled, exempt_total)
   VALUES (?, ?, ?, ?, ?, ?, 1, ?)`,
  deviceId, 'com.tencent.mm', '微信', 40, JSON.stringify([{ start: '19:00', end: '20:00' }]), 127, 1,
);
const rule = one('SELECT * FROM app_rules WHERE device_id = ?', deviceId);
check('逐应用规则可写入并还原 JSON', JSON.parse(rule.time_windows)[0].start === '19:00');
check('「不计入当日总时长」开关可写入', rule.exempt_total === 1, rule.exempt_total);

// 不显式给值时必须是 0：老库升级、老客户端不发该字段，走的都是这条默认值。
// 万一默认成 1，等于把所有已有应用一次性变成"不占额度"，是最危险的静默失效。
run(
  `INSERT INTO app_rules (device_id, package_name, app_label, daily_limit_min, weekdays_mask, enabled)
   VALUES (?, ?, ?, 0, 127, 1)`,
  deviceId, 'com.android.chrome', '浏览器',
);
check(
  '★ 未设置 exempt_total 时默认为 0（不会把已有应用悄悄变成豁免）',
  one(
    'SELECT exempt_total FROM app_rules WHERE device_id = ? AND package_name = ?',
    deviceId, 'com.android.chrome',
  )?.exempt_total === 0,
);

run(
  `INSERT INTO time_requests (device_id, scope, request_min, reason, status, created_at, expire_at)
   VALUES (?, 'total', 60, '想看完这集', 'pending', ?, ?)`,
  deviceId, Date.now(), Date.now() + 1800000,
);
check('加时申请可写入', one('SELECT * FROM time_requests WHERE device_id = ?', deviceId)?.request_min === 60);

const cmd = run(
  `INSERT INTO commands (command_id, device_id, type, payload, status, expire_at, created_at)
   VALUES (?, ?, 'screenshot', NULL, 'pending', ?, ?)`,
  'cmd-selftest-1', deviceId, Date.now() + 60000, Date.now(),
);
check('指令入队成功', cmd.changes === 1);
check('指令可查询待执行队列',
  all('SELECT * FROM commands WHERE device_id = ? AND status = ?', deviceId, 'pending').length === 1);

console.log('\n[5] 约束与级联');
let dupRejected = false;
try {
  run('INSERT INTO devices (user_id, name, child_uuid, created_at) VALUES (?, ?, ?, ?)', userId, 'dup', 'selftest-uuid', 1);
} catch {
  dupRejected = true;
}
check('child_uuid 唯一约束生效', dupRejected);

run('DELETE FROM devices WHERE id = ?', deviceId);
const leftovers = [
  one('SELECT * FROM policies WHERE device_id = ?', deviceId),
  one('SELECT * FROM app_rules WHERE device_id = ?', deviceId),
  one('SELECT * FROM time_requests WHERE device_id = ?', deviceId),
  one('SELECT * FROM commands WHERE device_id = ?', deviceId),
].filter(Boolean).length;
check('删除设备时级联清理关联数据', leftovers === 0, leftovers);

const orphan = one('SELECT * FROM devices WHERE user_id = ?', userId);
check('用户仍保留（未被误删）', orphan === null);

db.close();
for (const suffix of ['', '-wal', '-shm']) {
  try {
    fs.unlinkSync(new URL('../data/_selftest.db' + suffix, import.meta.url));
  } catch {
    /* ignore */
  }
}

console.log('\n[6] 限流器');

const { createLimiter } = await import('../src/ratelimit.js');

const limiter = createLimiter({ name: 'selftest', max: 3, windowMs: 60_000 });
check('前 3 次放行', [1, 2, 3].every(() => limiter('203.0.113.7').allowed));
const blocked = limiter('203.0.113.7');
check('第 4 次被拦下', blocked.allowed === false, blocked);
check('给出剩余等待秒数', blocked.retryAfterSeconds > 0, blocked.retryAfterSeconds);
check('不同 IP 互不影响', limiter('203.0.113.8').allowed === true);

const loopback = createLimiter({ name: 'selftest-loop', max: 1, windowMs: 60_000 });
check('环回地址不限流（本机开发与自动化测试用）',
  [1, 2, 3, 4, 5].every(() => loopback('127.0.0.1').allowed));

console.log(`\n结果：${pass} 项通过，${fail} 项失败\n`);
process.exit(fail === 0 ? 0 : 1);