/**
 * 老库升级自检。
 *
 * 为什么单独一个脚本：这类问题**只在真跑时才暴露**，看代码看不出来。
 * 典型的"升级即坏"是「新代码不再写某一列，而那一列在老库里是 NOT NULL」——
 * 编译通过、新库上测试全绿，一上线到已有数据的库上就全线失败。
 *
 * 做法：手工造一个老结构的库 → 跑 initDb() → 验证新代码能正常读写。
 *
 * 用法：node --disable-warning=ExperimentalWarning scripts/migrationtest.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DatabaseSync } from 'node:sqlite';

// 必须用 fileURLToPath：直接读 url.pathname 拿到的是百分号编码的路径
// （本项目路径含中文，会变成 %E7%9B%91… 而打不开文件），
// 且 Windows 上还会多一个前导斜杠。
const dbFile = fileURLToPath(new URL('../data/_migrationtest.db', import.meta.url));

for (const suffix of ['', '-wal', '-shm']) {
  try {
    fs.unlinkSync(dbFile + suffix);
  } catch {
    /* 文件不存在，忽略 */
  }
}

// 确保目录存在，否则 sqlite 打不开文件
fs.mkdirSync(path.dirname(dbFile), { recursive: true });

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

console.log('\n老库升级自检\n');

/* ---------------- 造一个 M5 时代的库 ---------------- */

console.log('[1] 构造 M5 时代的库结构');

const legacy = new DatabaseSync(dbFile);
legacy.exec(`
  CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL
  );

  CREATE TABLE devices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    child_uuid TEXT NOT NULL UNIQUE,
    admin_mode TEXT,
    online INTEGER NOT NULL DEFAULT 0,
    last_seen INTEGER,
    foreground_pkg TEXT,
    health_json TEXT,
    created_at INTEGER NOT NULL,
    locked INTEGER NOT NULL DEFAULT 0,
    locked_at INTEGER,
    state_version INTEGER NOT NULL DEFAULT 1,
    device_owner INTEGER NOT NULL DEFAULT 0,
    uninstall_blocked INTEGER NOT NULL DEFAULT 0,
    keepalive_json TEXT,
    pin_ready INTEGER NOT NULL DEFAULT 0
  );

  -- ★ 关键：老结构的 screenshots 有 file_path NOT NULL
  CREATE TABLE screenshots (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id  INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    file_path  TEXT NOT NULL,
    width      INTEGER,
    height     INTEGER,
    created_at INTEGER NOT NULL
  );

  -- 老结构的 audit_logs 没有 client_key / source / level
  CREATE TABLE audit_logs (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER REFERENCES users(id) ON DELETE SET NULL,
    device_id  INTEGER REFERENCES devices(id) ON DELETE SET NULL,
    action     TEXT NOT NULL,
    detail     TEXT,
    created_at INTEGER NOT NULL
  );

  -- 老结构的 app_rules 没有 exempt_total（逐应用"不计入总时长"是后来才加的）
  CREATE TABLE app_rules (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id       INTEGER NOT NULL,
    package_name    TEXT NOT NULL,
    app_label       TEXT,
    daily_limit_min INTEGER NOT NULL DEFAULT 0,
    time_windows    TEXT,
    weekdays_mask   INTEGER NOT NULL DEFAULT 127,
    enabled         INTEGER NOT NULL DEFAULT 1,
    UNIQUE(device_id, package_name)
  );
`);

legacy
  .prepare('INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)')
  .run('legacy_parent', 'scrypt$16384$8$1$aaa$bbb', Date.now());
legacy
  .prepare('INSERT INTO devices (user_id, name, child_uuid, created_at) VALUES (?, ?, ?, ?)')
  .run(1, '老平板', 'legacy-uuid-1', Date.now());
legacy
  .prepare('INSERT INTO screenshots (device_id, file_path, width, height, created_at) VALUES (?, ?, ?, ?, ?)')
  .run(1, 'old-shots/abc.jpg', 1080, 1920, Date.now());
legacy
  .prepare('INSERT INTO audit_logs (user_id, device_id, action, created_at) VALUES (?, ?, ?, ?)')
  .run(1, 1, 'policy.update', Date.now());
legacy
  .prepare(
    `INSERT INTO app_rules (device_id, package_name, app_label, daily_limit_min, weekdays_mask, enabled)
     VALUES (?, ?, ?, ?, ?, ?)`,
  )
  .run(1, 'com.tencent.mm', '微信', 40, 127, 1);
legacy.close();

check('老库已就绪（含 file_path NOT NULL 的 screenshots）', fs.existsSync(dbFile));

/* ---------------- 用当前代码打开它 ---------------- */

console.log('\n[2] 用当前代码执行迁移');

process.env.DB_PATH = 'data/_migrationtest.db';
process.env.JWT_SECRET = 'migrationtest';

const { db, initDb, one, all, run } = await import('../src/db.js');

let migrated = true;
let migrationError = null;
try {
  initDb();
} catch (error) {
  migrated = false;
  migrationError = error.message;
}
check('迁移过程不抛异常', migrated, migrationError);

if (!migrated) {
  console.log(`\n结果：${pass} 项通过，${fail + 1} 项失败\n`);
  process.exit(1);
}

/* ---------------- 验证新结构可用 ---------------- */

console.log('\n[3] 迁移后的结构');

const columns = all('PRAGMA table_info(screenshots)').map((c) => c.name);
check('screenshots 已重建（不再有 file_path）', !columns.includes('file_path'), columns);
check('screenshots 具备 file_name', columns.includes('file_name'));
check('screenshots 具备 command_id', columns.includes('command_id'));
check('screenshots 具备 capture_mode', columns.includes('capture_mode'));

const auditColumns = all('PRAGMA table_info(audit_logs)').map((c) => c.name);
check('audit_logs 具备 client_key', auditColumns.includes('client_key'));
check('audit_logs 具备 source', auditColumns.includes('source'));
check('audit_logs 具备 level', auditColumns.includes('level'));

const deviceColumns = all('PRAGMA table_info(devices)').map((c) => c.name);
check('devices 具备 icon_hidden', deviceColumns.includes('icon_hidden'));

const ruleColumns = all('PRAGMA table_info(app_rules)').map((c) => c.name);
check('app_rules 已补齐 exempt_total', ruleColumns.includes('exempt_total'), ruleColumns);

console.log('\n[4] 老数据是否保住');

const legacyShot = one('SELECT * FROM screenshots WHERE id = 1');
check('老截屏行仍在（内容已转换）', legacyShot !== null, legacyShot);
check('老截屏的 file_name 已从 file_path 推导',
  typeof legacyShot?.file_name === 'string' && legacyShot.file_name.length > 0,
  legacyShot?.file_name);
check('老截屏的尺寸字段保留', legacyShot?.width === 1080 && legacyShot?.height === 1920);

const legacyAudit = one("SELECT * FROM audit_logs WHERE action = 'policy.update'");
check('老审计日志仍在', legacyAudit !== null);
check('老审计日志的 source 回填为 server', legacyAudit?.source === 'server', legacyAudit?.source);

const legacyRule = one('SELECT * FROM app_rules WHERE package_name = ?', 'com.tencent.mm');
check('老库的逐应用规则仍在', legacyRule !== null, legacyRule);
check('老规则的其它字段未被改动',
  legacyRule?.daily_limit_min === 40 && legacyRule?.weekdays_mask === 127,
  legacyRule);
check('★ 老规则的 exempt_total 回填为 0（升级不会把已有应用悄悄变成豁免）',
  legacyRule?.exempt_total === 0, legacyRule?.exempt_total);

/* ---------------- 关键：新代码能否正常写入 ---------------- */

console.log('\n[5] 新代码在新结构上的读写');

let insertOk = true;
let insertError = null;
try {
  // 这一步在老库不迁移的情况下必然失败：新代码不写 file_path，而它是 NOT NULL
  run(
    `INSERT INTO screenshots
       (device_id, command_id, file_name, byte_size, mime, width, height, foreground_pkg, capture_mode, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    1, 'cmd-1', 'new-shot.jpg', 12345, 'image/jpeg', 720, 1280, 'com.demo.app', 'accessibility', Date.now(),
  );
} catch (error) {
  insertOk = false;
  insertError = error.message;
}
check('★ 能插入不带 file_path 的截屏（老库不迁移就会在这里失败）', insertOk, insertError);

if (insertOk) {
  const newShot = one("SELECT * FROM screenshots WHERE file_name = 'new-shot.jpg'");
  check('新截屏可读取', newShot !== null);
  check('前台应用与采集方式已保存',
    newShot?.foreground_pkg === 'com.demo.app' && newShot?.capture_mode === 'accessibility');
}

// 索引是否真的建起来了（重建表时最容易悄悄丢掉）
const indexes = all(
  "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='screenshots' AND name NOT LIKE 'sqlite_%'",
).map((r) => r.name);
check('★ screenshots 的索引没有在重建中丢失',
  indexes.includes('idx_screenshots_device') && indexes.includes('idx_screenshots_command'),
  indexes);

// 唯一索引仍能阻止同一指令重复入库两张
let dupBlocked = false;
try {
  run(
    `INSERT INTO screenshots (device_id, command_id, file_name, byte_size, created_at)
     VALUES (?, ?, ?, ?, ?)`,
    1, 'cmd-1', 'dup.jpg', 1, Date.now(),
  );
} catch {
  dupBlocked = true;
}
check('同一 command_id 不能重复入库', dupBlocked);

// 被控端事件上报（M6 新能力）
let childEventOk = true;
try {
  run(
    `INSERT INTO audit_logs (user_id, device_id, action, detail, client_key, source, level, created_at)
     VALUES (?, ?, ?, ?, ?, 'child', ?, ?)`,
    1, 1, 'permission.lost', '{"missing":["accessibility"]}', 'evt-1', 'warn', Date.now(),
  );
} catch (error) {
  childEventOk = false;
  console.error(error.message);
}
check('★ 能写入被控端上报的本地事件', childEventOk);

const childEvent = one("SELECT * FROM audit_logs WHERE client_key = 'evt-1'");
check('事件来源标记为 child', childEvent?.source === 'child');
check('事件级别为 warn', childEvent?.level === 'warn');

// 幂等：同一 client_key 再插一次应被唯一索引拒绝
let dupEvent = false;
try {
  run(
    `INSERT INTO audit_logs (user_id, device_id, action, client_key, source, level, created_at)
     VALUES (?, ?, ?, ?, 'child', 'warn', ?)`,
    1, 1, 'permission.lost', 'evt-1', Date.now(),
  );
} catch {
  dupEvent = true;
}
check('同一 client_key 重复上报被拒绝（幂等）', dupEvent);

/* ---------------- 再来一次：幂等性 ---------------- */

console.log('\n[6] 重复执行迁移应无副作用');

// 必须在 db.close() 之前跑：initDb 用的是同一个已打开的连接。
// 重跑一遍等价于"服务再启动一次"，是幂等性最贴近真实的检验方式。
let secondRunOk = true;
let secondError = null;
try {
  initDb();
} catch (error) {
  secondRunOk = false;
  secondError = error.message;
}
check('重复执行迁移不报错（幂等）', secondRunOk, secondError);

// 重跑之后数据不能被动过
check('重跑后老截屏行仍在', one('SELECT * FROM screenshots WHERE id = 1') !== null);
check('重跑后新截屏行仍在', one("SELECT * FROM screenshots WHERE file_name = 'new-shot.jpg'") !== null);
check('重跑后被控端事件仍在', one("SELECT * FROM audit_logs WHERE client_key = 'evt-1'") !== null);

db.close();

for (const suffix of ['', '-wal', '-shm']) {
  try {
    fs.unlinkSync(dbFile + suffix);
  } catch {
    /* ignore */
  }
}

console.log(`\n结果：${pass} 项通过，${fail} 项失败\n`);
process.exit(fail === 0 ? 0 : 1);
