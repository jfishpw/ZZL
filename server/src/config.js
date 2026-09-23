import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const serverRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

function num(value, fallback) {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

export const config = {
  host: process.env.APP_HOST ?? '0.0.0.0',
  port: num(process.env.APP_PORT, 8080),

  // 生产环境必须通过环境变量覆盖，切勿使用默认值
  jwtSecret: process.env.JWT_SECRET ?? 'dev-secret-please-change-in-production',

  dbPath: path.resolve(serverRoot, process.env.DB_PATH ?? 'data/app.db'),
  uploadDir: path.resolve(serverRoot, process.env.UPLOAD_DIR ?? 'data/uploads'),

  /** 截屏图片的落盘目录。与 uploadDir 分开：两者的保留策略不同，混在一起不好清理 */
  screenshotDir: path.resolve(serverRoot, process.env.SCREENSHOT_DIR ?? 'data/screenshots'),

  /** 截屏约束 */
  screenshot: {
    /** 单张图片的字节上限。超过直接拒绝，避免被单次上传撑爆磁盘 */
    maxBytes: num(process.env.SCREENSHOT_MAX_BYTES, 1024 * 1024),
    /** 保留张数上限（每台设备）。超出后淘汰最旧的 */
    maxPerDevice: num(process.env.SCREENSHOT_MAX_PER_DEVICE, 60),
    /** 保留天数 */
    retentionDays: num(process.env.SCREENSHOT_RETENTION_DAYS, 30),
  },

  // 令牌有效期
  parentTokenTtl: process.env.PARENT_TOKEN_TTL ?? '30d',
  childTokenTtl: process.env.CHILD_TOKEN_TTL ?? '3650d',

  // 配对码有效期（分钟）
  pairCodeTtlMs: num(process.env.PAIR_CODE_TTL_MINUTES, 5) * 60 * 1000,

  // 心跳超时：超过该时长未收到心跳则标记离线
  heartbeatTimeoutMs: num(process.env.HEARTBEAT_TIMEOUT_MS, 90_000),

  /**
   * 指令与授权的有效期。
   *
   * 单位统一为毫秒（时间常量例外地用分钟，因为它们出现在界面上）。
   * 集中放在这里的理由：这些数值直接决定「补发的指令还算不算数」，
   * 是需要按实际使用习惯调的运营参数，不该散落在各个模块的常量里。
   */
  ttl: {
    /** 立即锁定：永久有效，直到家长显式解除 */
    immediateLock: null,
    /** 解除锁定：防止「意外解锁」在很久之后才被补执行 */
    clearLock: num(process.env.COMMAND_TTL_CLEAR_LOCK_MINUTES, 360) * 60 * 1000,
    /** 请求设备重新上报已安装应用 */
    requestApps: num(process.env.COMMAND_TTL_REQUEST_APPS_MINUTES, 360) * 60 * 1000,
    /** 按需截屏：过期即无意义 */
    screenshot: num(process.env.COMMAND_TTL_SCREENSHOT_SECONDS, 60) * 1000,
  },

  /** 临时授权（grants） */
  grant: {
    /** 各范围的默认有效期（分钟） */
    defaultTtlMinutes: {
      total_add: num(process.env.GRANT_TTL_TOTAL_ADD_MINUTES, 720),
      app_allow: num(process.env.GRANT_TTL_APP_ALLOW_MINUTES, 120),
      unlock: num(process.env.GRANT_TTL_UNLOCK_MINUTES, 60),
    },
    /** 有效期上限与单次加时上限（分钟） */
    maxTtlMinutes: 24 * 60,
    maxExtraMinutes: 24 * 60,
  },

  /** 加时申请 */
  timeRequest: {
    /** 审批时限：超过则自动作废，避免家长误批几小时前的请求（分钟） */
    ttlMinutes: num(process.env.TIME_REQUEST_TTL_MINUTES, 30),
    /** 两次申请之间的最小间隔（分钟） */
    minIntervalMinutes: num(process.env.TIME_REQUEST_MIN_INTERVAL_MINUTES, 10),
    /** 每小时最多申请次数 */
    maxPerHour: num(process.env.TIME_REQUEST_MAX_PER_HOUR, 2),
    /** 单次申请与批准的时长上限（分钟） */
    maxRequestMinutes: num(process.env.TIME_REQUEST_MAX_MINUTES, 180),
  },

  // 离线密码约束
  pin: {
    maxAttempts: num(process.env.PIN_MAX_ATTEMPTS, 5),
    lockMinutes: num(process.env.PIN_LOCK_MINUTES, 10),
  },
};

export function ensureRuntimeDirs() {
  fs.mkdirSync(path.dirname(config.dbPath), { recursive: true });
  fs.mkdirSync(config.uploadDir, { recursive: true });
  fs.mkdirSync(config.screenshotDir, { recursive: true });
}

export const isProduction = process.env.NODE_ENV === 'production';
