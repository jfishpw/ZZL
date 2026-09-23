import { DatabaseSync } from 'node:sqlite';
import { config, ensureRuntimeDirs } from './config.js';

// 数据库模块自带目录准备，避免因 data/ 不存在而启动失败
ensureRuntimeDirs();

/**
 * 使用 Node 内置的 node:sqlite（Node ≥ 22.13 起无需 flag）。
 *
 * 选型理由：
 *   - 零原生依赖，服务器上不需要编译工具链，Docker 镜像更小、构建更快
 *   - 符合「尽量轻服务」的部署要求
 * 本文件是唯一的数据库适配层：若日后需要换成 better-sqlite3 或 PostgreSQL，
 * 只需替换这里的实现，上层 routes / ws 代码不受影响。
 */
export const db = new DatabaseSync(config.dbPath);

// WAL 模式：读写并发更好，更适合单容器部署
db.exec('PRAGMA journal_mode = WAL;');
db.exec('PRAGMA foreign_keys = ON;');
db.exec('PRAGMA busy_timeout = 5000;');

const SCHEMA = `
-- ============ 账号 ============
CREATE TABLE IF NOT EXISTS users (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  username      TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  created_at    INTEGER NOT NULL
);

-- ============ 设备 ============
CREATE TABLE IF NOT EXISTS devices (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id        INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name           TEXT NOT NULL,
  model          TEXT,
  android_ver    INTEGER,
  child_uuid     TEXT NOT NULL UNIQUE,
  admin_mode     TEXT,
  online         INTEGER NOT NULL DEFAULT 0,
  last_seen      INTEGER,
  foreground_pkg TEXT,
  health_json    TEXT,
  created_at     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);

-- ============ 配对码 ============
CREATE TABLE IF NOT EXISTS pair_codes (
  code       TEXT PRIMARY KEY,
  user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id  INTEGER REFERENCES devices(id) ON DELETE SET NULL,
  expires_at INTEGER NOT NULL,
  used       INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);

-- ============ 策略 ============
CREATE TABLE IF NOT EXISTS policies (
  device_id          INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
  weekday_total_min  INTEGER NOT NULL DEFAULT 60,
  weekend_total_min  INTEGER NOT NULL DEFAULT 120,
  reset_hour         INTEGER NOT NULL DEFAULT 0,
  list_mode          TEXT    NOT NULL DEFAULT 'blacklist',
  allow_time_request INTEGER NOT NULL DEFAULT 1,
  enabled            INTEGER NOT NULL DEFAULT 1,
  updated_at         INTEGER NOT NULL,
  version            INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS policy_list_items (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  package_name TEXT NOT NULL,
  app_label    TEXT,
  UNIQUE(device_id, package_name)
);

CREATE TABLE IF NOT EXISTS app_rules (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id       INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  package_name    TEXT NOT NULL,
  app_label       TEXT,
  daily_limit_min INTEGER NOT NULL DEFAULT 0,
  time_windows    TEXT,
  weekdays_mask   INTEGER NOT NULL DEFAULT 127,
  enabled         INTEGER NOT NULL DEFAULT 1,
  -- 该应用的用时不计入当日总时长；总时长耗尽后它仍可打开。
  -- 注意它只豁免「总时长」这一条，单日上限 / 允许时段 / 黑白名单照旧生效。
  exempt_total    INTEGER NOT NULL DEFAULT 0,
  UNIQUE(device_id, package_name)
);

-- ============ 临时授权 ============
CREATE TABLE IF NOT EXISTS grants (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id     INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  scope         TEXT NOT NULL,
  package_name  TEXT,
  app_label     TEXT,
  extra_minutes INTEGER,
  -- 加时类授权绑定到某个「额度日」（按策略的 reset_hour 计算）：
  -- 跨过归日点后该授权不再叠加，避免昨天的加时泄漏到今天。
  day_key       TEXT,
  expire_at     INTEGER,
  revoked       INTEGER NOT NULL DEFAULT 0,
  revoked_at    INTEGER,
  source        TEXT NOT NULL DEFAULT 'manual',
  created_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_grants_device ON grants(device_id);

-- ============ 加时申请 ============
CREATE TABLE IF NOT EXISTS time_requests (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  scope        TEXT NOT NULL,
  package_name TEXT,
  app_label    TEXT,
  request_min  INTEGER NOT NULL,
  decided_min  INTEGER,
  reason       TEXT,
  status       TEXT NOT NULL DEFAULT 'pending',
  created_at   INTEGER NOT NULL,
  expire_at    INTEGER NOT NULL,
  decided_at   INTEGER
);
CREATE INDEX IF NOT EXISTS idx_timereq_device ON time_requests(device_id, status);

-- ============ 使用记录 ============
CREATE TABLE IF NOT EXISTS usage_sessions (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  client_key   TEXT,
  device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  package_name TEXT NOT NULL,
  start_ts     INTEGER NOT NULL,
  end_ts       INTEGER,
  duration_ms  INTEGER NOT NULL DEFAULT 0,
  day_key      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_dev_day ON usage_sessions(device_id, day_key);

CREATE TABLE IF NOT EXISTS usage_daily (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  day_key      TEXT NOT NULL,
  package_name TEXT NOT NULL,
  total_ms     INTEGER NOT NULL DEFAULT 0,
  open_count   INTEGER NOT NULL DEFAULT 0,
  UNIQUE(device_id, day_key, package_name)
);

-- ============ 指令队列（支持离线补发）============
CREATE TABLE IF NOT EXISTS commands (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  command_id  TEXT NOT NULL UNIQUE,
  device_id   INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  type        TEXT NOT NULL,
  payload     TEXT,
  status      TEXT NOT NULL DEFAULT 'pending',
  expire_at   INTEGER,
  created_at  INTEGER NOT NULL,
  pushed_at   INTEGER,
  executed_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_commands_pending ON commands(device_id, status);

-- ============ 截屏 ============
-- 图片本体存磁盘（data/screenshots/），数据库只存元信息。
-- 把 JPEG 塞进 SQLite 会让每次查询都拖着几 MB 的 BLOB，
-- 备份与清理也变得笨重；而文件系统天然适合大二进制。
CREATE TABLE IF NOT EXISTS screenshots (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id     INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  command_id    TEXT,
  file_name     TEXT NOT NULL,
  byte_size     INTEGER NOT NULL DEFAULT 0,
  mime          TEXT NOT NULL DEFAULT 'image/jpeg',
  width         INTEGER,
  height        INTEGER,
  foreground_pkg TEXT,
  /** 采集方式：accessibility（无障碍，无弹窗）| projection（录屏降级） */
  capture_mode  TEXT,
  created_at    INTEGER NOT NULL,
  /** 家长第一次查看的时间。用它区分"孩子可能知道被截屏了"与"家长看过" */
  viewed_at     INTEGER
);

-- ============ 拦截记录 ============
-- client_key 由被控端生成，用于上报去重（与 usage_sessions 同一套幂等思路）
CREATE TABLE IF NOT EXISTS block_logs (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  client_key   TEXT,
  device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  package_name TEXT NOT NULL,
  reason       TEXT NOT NULL,
  ts           INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_blocks_device ON block_logs(device_id, ts);

-- ============ 已安装应用清单 ============
CREATE TABLE IF NOT EXISTS installed_apps (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  package_name TEXT NOT NULL,
  app_label    TEXT,
  is_system    INTEGER NOT NULL DEFAULT 0,
  updated_at   INTEGER NOT NULL,
  UNIQUE(device_id, package_name)
);

-- ============ 审计日志 ============
-- 两类来源共用一张表：
--   source='server' — 控制端发起的操作（改策略、下发指令、重置密码…）
--   source='child'  — 被控端上报的本地事件（权限被关、密码被试、管控被绕过）
-- 后者在断网期间先落本地库，联网后批量补传，因此有 client_key 做幂等去重。
CREATE TABLE IF NOT EXISTS audit_logs (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER REFERENCES users(id) ON DELETE SET NULL,
  device_id  INTEGER REFERENCES devices(id) ON DELETE SET NULL,
  action     TEXT NOT NULL,
  detail     TEXT,
  client_key TEXT,
  source     TEXT NOT NULL DEFAULT 'server',
  level      TEXT NOT NULL DEFAULT 'info',
  created_at INTEGER NOT NULL
);

-- ============ 被控端本地密码（云端备份，用于换机恢复与远程重置）============
-- 注意：这里存的是**已经算好的 PBKDF2 结果**，服务端从不接触明文密码，
-- 也无法反推。它的作用是「设备重装后恢复同一套密码」与「家长远程重置」。
-- salt 与 iterations 必须一起存：日后调高迭代次数时，旧密码仍要能验证通过。
CREATE TABLE IF NOT EXISTS local_pins (
  device_id    INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
  level_count  INTEGER NOT NULL DEFAULT 3,
  level1_hash  TEXT,
  level1_salt  TEXT,
  level1_iters INTEGER,
  level1_hint  TEXT,
  level2_hash  TEXT,
  level2_salt  TEXT,
  level2_iters INTEGER,
  level2_hint  TEXT,
  level3_hash  TEXT,
  level3_salt  TEXT,
  level3_iters INTEGER,
  level3_hint  TEXT,
  updated_at   INTEGER NOT NULL,
  version      INTEGER NOT NULL DEFAULT 0
);

-- 密码尝试记录（含失败）。家长正是靠它知道"孩子试过破解"。
CREATE TABLE IF NOT EXISTS pin_attempts (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  client_key TEXT,
  device_id  INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  level      INTEGER NOT NULL,
  success    INTEGER NOT NULL,
  -- 从哪个隐藏入口发起的：overlay（连点标题）| corner（长按角落）| dialer（拨号暗码）
  source     TEXT,
  ts         INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pin_attempts_device ON pin_attempts(device_id, ts);
`;

export function initDb() {
  db.exec(SCHEMA);
  migrate();
}

/* ---------------- 迁移 ---------------- */

function addColumnIfMissing(table, column, definition) {
  const columns = db.prepare(`PRAGMA table_info(${table})`).all();
  if (!columns.some((c) => c.name === column)) {
    db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${definition}`);
  }
}

/**
 * 幂等的列级迁移，供老库升级时补齐新增字段。
 * 注意：SQLite 的 ALTER TABLE ADD COLUMN 不允许携带 UNIQUE 约束，
 * 因此唯一性一律用独立索引实现，且必须在补列之后创建。
 */
function migrate() {
  addColumnIfMissing('policies', 'enabled', 'INTEGER NOT NULL DEFAULT 1');
  addColumnIfMissing('usage_sessions', 'client_key', 'TEXT');

  // M4：设备锁定状态、状态版本、授权归日、指令回执、拦截记录去重键
  addColumnIfMissing('devices', 'locked', 'INTEGER NOT NULL DEFAULT 0');
  addColumnIfMissing('devices', 'locked_at', 'INTEGER');
  addColumnIfMissing('devices', 'state_version', 'INTEGER NOT NULL DEFAULT 1');
  addColumnIfMissing('grants', 'app_label', 'TEXT');
  addColumnIfMissing('grants', 'day_key', 'TEXT');
  addColumnIfMissing('grants', 'revoked_at', 'INTEGER');
  addColumnIfMissing('commands', 'result', 'TEXT');
  addColumnIfMissing('block_logs', 'client_key', 'TEXT');
  addColumnIfMissing('time_requests', 'app_label', 'TEXT');

  // M5：离线密码的盐与迭代次数（必须与哈希一起存，否则调高迭代次数后旧密码会失效）、
  // 设备加固状态、密码尝试来源
  for (const level of [1, 2, 3]) {
    addColumnIfMissing('local_pins', `level${level}_salt`, 'TEXT');
    addColumnIfMissing('local_pins', `level${level}_iters`, 'INTEGER');
    // 早期 schema 用的是 hint1/hint2/hint3，改名成与 hash/salt/iters 一致的 levelN_hint。
    // 改名而不是新增：留着旧列会让「哪一列才是当前提示语」变得不确定。
    addColumnIfMissing('local_pins', `level${level}_hint`, 'TEXT');
  }
  addColumnIfMissing('local_pins', 'version', 'INTEGER NOT NULL DEFAULT 1');
  addColumnIfMissing('pin_attempts', 'client_key', 'TEXT');
  addColumnIfMissing('pin_attempts', 'source', 'TEXT');

  // 老库的 hint1/hint2/hint3 迁移到 levelN_hint
  migrateLegacyHintColumns();

  // 老库的 screenshots 表有 `file_path NOT NULL`，而新代码不再写这一列
  // （改为 file_name + 服务端生成路径），INSERT 会直接违反约束。
  // 必须重建表 —— SQLite 无法用 ALTER TABLE 去掉 NOT NULL。
  rebuildLegacyScreenshots();

  // 设备加固状态：由被控端上报，控制端据此展示能力矩阵
  addColumnIfMissing('devices', 'device_owner', 'INTEGER NOT NULL DEFAULT 0');
  addColumnIfMissing('devices', 'uninstall_blocked', 'INTEGER NOT NULL DEFAULT 0');
  addColumnIfMissing('devices', 'keepalive_json', 'TEXT');
  addColumnIfMissing('devices', 'pin_ready', 'INTEGER NOT NULL DEFAULT 0');

  // M6：截屏元信息（早期只有 file_path/width/height）
  addColumnIfMissing('screenshots', 'command_id', 'TEXT');
  addColumnIfMissing('screenshots', 'file_name', 'TEXT');

  // M8：逐应用规则的「不计入当日总时长」开关。
  // 老库补列后默认 0，语义与旧行为完全一致（所有应用都计入总时长）。
  addColumnIfMissing('app_rules', 'exempt_total', 'INTEGER NOT NULL DEFAULT 0');
  addColumnIfMissing('screenshots', 'byte_size', 'INTEGER NOT NULL DEFAULT 0');
  addColumnIfMissing('screenshots', 'mime', "TEXT NOT NULL DEFAULT 'image/jpeg'");
  addColumnIfMissing('screenshots', 'foreground_pkg', 'TEXT');
  addColumnIfMissing('screenshots', 'capture_mode', 'TEXT');
  addColumnIfMissing('screenshots', 'viewed_at', 'INTEGER');

  // M6：被控端上报的本地审计事件（权限丢失、破解尝试、绕过尝试）
  addColumnIfMissing('audit_logs', 'client_key', 'TEXT');
  addColumnIfMissing('audit_logs', 'source', "TEXT NOT NULL DEFAULT 'server'");
  addColumnIfMissing('audit_logs', 'level', "TEXT NOT NULL DEFAULT 'info'");

  // 图标隐藏状态（由控制端切换，被控端据本地副本执行）
  addColumnIfMissing('devices', 'icon_hidden', 'INTEGER NOT NULL DEFAULT 0');

  // client_key 由被控端生成，用于上报去重；SQLite 唯一索引视 NULL 为彼此不同，故历史行不受影响
  db.exec('CREATE UNIQUE INDEX IF NOT EXISTS idx_sessions_client_key ON usage_sessions(client_key)');
  db.exec('CREATE UNIQUE INDEX IF NOT EXISTS idx_blocks_client_key ON block_logs(client_key)');
  db.exec('CREATE UNIQUE INDEX IF NOT EXISTS idx_pin_attempts_client_key ON pin_attempts(client_key)');
  db.exec('CREATE UNIQUE INDEX IF NOT EXISTS idx_audit_client_key ON audit_logs(client_key)');
  db.exec('CREATE INDEX IF NOT EXISTS idx_grants_active ON grants(device_id, revoked, expire_at)');
  db.exec('CREATE INDEX IF NOT EXISTS idx_commands_expire ON commands(status, expire_at)');
  db.exec('CREATE INDEX IF NOT EXISTS idx_screenshots_device ON screenshots(device_id, created_at)');
  db.exec('CREATE UNIQUE INDEX IF NOT EXISTS idx_screenshots_command ON screenshots(command_id)');
  db.exec('CREATE INDEX IF NOT EXISTS idx_audit_device ON audit_logs(device_id, created_at)');
}

/**
 * 把早期 schema 的 hint1/hint2/hint3 迁移到 levelN_hint。
 *
 * 只搬一次：迁移后把旧列置空，避免下次启动又搬一遍
 * （如果家长在旧库上改过提示语，重复搬移会用陈旧值覆盖新值）。
 */
function migrateLegacyHintColumns() {
  const columns = db.prepare('PRAGMA table_info(local_pins)').all().map((c) => c.name);
  for (const level of [1, 2, 3]) {
    const legacy = `hint${level}`;
    if (!columns.includes(legacy)) continue;

    db.exec(
      `UPDATE local_pins
          SET level${level}_hint = ${legacy}
        WHERE level${level}_hint IS NULL AND ${legacy} IS NOT NULL`,
    );
    db.exec(`UPDATE local_pins SET ${legacy} = NULL`);
  }
}

/**
 * 把早期 schema 的 screenshots 表重建为新结构。
 *
 * 触发条件是存在 `file_path` 列 —— 只有老结构才有它。
 * 不重建的话，老库升级后**每一次截屏上传都会失败**：
 * 新代码不写 file_path，而那一列是 NOT NULL。
 * 这类"升级即坏"的问题靠看代码是发现不了的，只有真跑一次才知道。
 *
 * 迁移时把旧行一起搬过来，file_name 取 file_path 的basename。
 */
function rebuildLegacyScreenshots() {
  const columns = db.prepare('PRAGMA table_info(screenshots)').all().map((c) => c.name);
  if (!columns.includes('file_path')) return;

  db.exec('BEGIN');
  try {
    // 先删掉旧索引。
    //
    // 坑：SQLite 在 `ALTER TABLE RENAME` 时会把索引一并带到新表名下
    // **但保留索引名**。于是 migrate 末尾的 `CREATE INDEX IF NOT EXISTS`
    // 会因为"名字已存在"而跳过，最后 DROP 掉 legacy 表时索引一起消失 ——
    // 结果是新表上一根索引都没有，而且完全静默。
    for (const index of ['idx_screenshots_device', 'idx_screenshots_command']) {
      db.exec(`DROP INDEX IF EXISTS ${index}`);
    }

    db.exec('ALTER TABLE screenshots RENAME TO screenshots_legacy');

    db.exec(`
      CREATE TABLE screenshots (
        id            INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id     INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
        command_id    TEXT,
        file_name     TEXT NOT NULL,
        byte_size     INTEGER NOT NULL DEFAULT 0,
        mime          TEXT NOT NULL DEFAULT 'image/jpeg',
        width         INTEGER,
        height        INTEGER,
        foreground_pkg TEXT,
        capture_mode  TEXT,
        created_at    INTEGER NOT NULL,
        viewed_at     INTEGER
      )
    `);

    // 只搬能对上的字段。旧行的图片文件仍在 uploadDir 下，
    // 会被"孤儿文件清理"识别并删除 —— 这是可以接受的：
    // 老版本从未真正使用过截屏功能。
    db.exec(`
      INSERT INTO screenshots (id, device_id, file_name, width, height, created_at)
      SELECT id, device_id,
             COALESCE(NULLIF(file_path, ''), 'legacy-' || id || '.jpg'),
             width, height, created_at
        FROM screenshots_legacy
    `);

    db.exec('DROP TABLE screenshots_legacy');
    db.exec('COMMIT');
  } catch (error) {
    try {
      db.exec('ROLLBACK');
    } catch {
      /* 回滚失败时保留原始异常 */
    }
    throw error;
  }
}

/** 便捷查询封装：统一使用位置参数（?），避免命名参数绑定的歧义 */export function one(sql, ...params) {
  return db.prepare(sql).get(...params) ?? null;
}

export function all(sql, ...params) {
  return db.prepare(sql).all(...params);
}

export function run(sql, ...params) {
  return db.prepare(sql).run(...params);
}

/**
 * 在同一事务里执行一组写操作。
 *
 * 用于「要么全生效、要么全不生效」的场景，例如同时替换名单与逐应用规则 ——
 * 分两次写会留下"名单换了但规则没换"的半套配置，被控端会按这个半套配置执行管控。
 */
export function transaction(fn) {
  db.exec('BEGIN');
  try {
    const result = fn();
    db.exec('COMMIT');
    return result;
  } catch (error) {
    try {
      db.exec('ROLLBACK');
    } catch {
      /* 回滚失败时保留原始异常，它更有诊断价值 */
    }
    throw error;
  }
}

export function now() {
  return Date.now();
}
