import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { one, all, run, now } from './db.js';
import { config } from './config.js';
import { writeAudit } from './audit.js';
import { sendToUser } from './ws.js';

/**
 * 截屏的存储与读取。
 *
 * 三个刻意的设计决定：
 *
 * 1. **图片存磁盘，数据库只存元信息。**
 *    把 JPEG 塞进 SQLite 会让每次列表查询都拖着几 MB 的 BLOB，
 *    备份与清理也变得笨重。文件系统天然适合大二进制。
 *
 * 2. **文件名由服务端生成，绝不采用客户端传来的值。**
 *    客户端可控的文件名是路径穿越漏洞的经典入口
 *    （`../../etc/passwd`、`..\\..\\app.db`）。这里只用随机 UUID + 白名单扩展名。
 *
 * 3. **删除数据库行不会自动删除文件。**
 *    SQLite 的外键级联只管行。因此必须有一条兜底的孤儿文件清理，
 *    否则设备解绑后图片会永远留在磁盘上，尤其是隐私敏感的截屏。
 */

/** 只接受这几种图片格式。扩展名由 MIME 反查，不由客户端指定。 */
const MIME_EXT = {
  'image/jpeg': 'jpg',
  'image/jpg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
};

export const ALLOWED_MIME = Object.keys(MIME_EXT);

/**
 * 保存一张截屏。
 *
 * @returns {{ok: true, view: object} | {ok: false, reason: string, message: string}}
 */
export function saveScreenshot(deviceId, payload) {
  const { bytes, mime, width, height, foregroundPackage, captureMode, commandId, userId } = payload;

  if (!Buffer.isBuffer(bytes) || bytes.length === 0) {
    return { ok: false, reason: 'empty', message: '图片内容为空' };
  }
  if (bytes.length > config.screenshot.maxBytes) {
    return {
      ok: false,
      reason: 'too_large',
      message: `图片超过 ${Math.round(config.screenshot.maxBytes / 1024)} KB 上限`,
    };
  }

  const safeMime = MIME_EXT[mime] ? mime : 'image/jpeg';
  const ext = MIME_EXT[safeMime];

  // 幂等去重：指令可能在 WS 推送与离线补发两条路径上重复到达，
  // 设备对同一 commandId 重传时直接返回已有记录，而不是撞
  // idx_screenshots_command 唯一索引抛异常（那会让这次上传变成 500、
  // 截屏丢失，设备侧还会认为失败）。SQLite 唯一索引视 NULL 互异，
  // 因此手动截屏（无 commandId）不受影响。
  if (commandId) {
    const existing = one('SELECT * FROM screenshots WHERE command_id = ?', commandId);
    if (existing) {
      return { ok: true, view: toScreenshotView(existing), duplicate: true };
    }
  }

  // 文件名完全由服务端决定：UUID + 白名单扩展名，客户端无法影响路径
  const fileName = `${randomUUID()}.${ext}`;
  const absPath = path.join(config.screenshotDir, fileName);

  // 双保险：即便 UUID 理论上被污染，也确保最终路径没跑出目录
  if (!absPath.startsWith(config.screenshotDir + path.sep)) {
    return { ok: false, reason: 'bad_path', message: '文件路径非法' };
  }

  fs.mkdirSync(config.screenshotDir, { recursive: true });
  fs.writeFileSync(absPath, bytes);

  run(
    `INSERT INTO screenshots
       (device_id, command_id, file_name, byte_size, mime, width, height,
        foreground_pkg, capture_mode, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    Number(deviceId),
    commandId ?? null,
    fileName,
    bytes.length,
    safeMime,
    Number.isFinite(width) ? Number(width) : null,
    Number.isFinite(height) ? Number(height) : null,
    foregroundPackage ? String(foregroundPackage).slice(0, 200) : null,
    captureMode ? String(captureMode).slice(0, 40) : null,
    now(),
  );

  const row = one('SELECT * FROM screenshots WHERE file_name = ?', fileName);

  writeAudit(userId ?? null, deviceId, 'screenshot.upload', {
    screenshotId: row.id,
    byteSize: bytes.length,
    captureMode: captureMode ?? null,
  });

  // 让家长不必轮询：截屏一到就推给他
  const device = one('SELECT user_id FROM devices WHERE id = ?', Number(deviceId));
  if (device?.user_id) {
    sendToUser(device.user_id, {
      type: 'screenshot_ready',
      deviceId: Number(deviceId),
      screenshot: toScreenshotView(row),
      at: now(),
    });
  }

  pruneScreenshots(Number(deviceId));

  return { ok: true, view: toScreenshotView(row) };
}

export function toScreenshotView(row) {
  if (!row) return null;
  return {
    id: row.id,
    deviceId: row.device_id,
    commandId: row.command_id,
    byteSize: row.byte_size,
    mime: row.mime,
    width: row.width,
    height: row.height,
    foregroundPackage: row.foreground_pkg,
    captureMode: row.capture_mode,
    createdAt: row.created_at,
    viewedAt: row.viewed_at,
    /** 前端拼图片 URL 用。服务端保证这是自己生成的随机名，不含用户输入 */
    fileName: row.file_name,
  };
}

export function listScreenshots(deviceId, limit = 30) {
  const rows = all(
    'SELECT * FROM screenshots WHERE device_id = ? ORDER BY created_at DESC LIMIT ?',
    Number(deviceId),
    Math.min(Math.max(Number(limit) || 30, 1), 100),
  );
  return rows.map(toScreenshotView);
}

/**
 * 取某张截屏的绝对路径。
 *
 * `deviceId` 必须一起传：这是**越权访问的最后一道闸**。
 * 只按 id 查会让家长 A 通过猜 id 拿到家长 B 孩子的截屏
 * —— 截屏是全系统最敏感的数据，这一层不能省。
 */
export function resolveScreenshotFile(screenshotId, deviceId) {
  const row = one(
    'SELECT * FROM screenshots WHERE id = ? AND device_id = ?',
    Number(screenshotId),
    Number(deviceId),
  );
  if (!row) return null;

  // fileName 是服务端生成的，这里再校验一次防止历史脏数据
  if (!row.file_name || row.file_name.includes('/') || row.file_name.includes('\\')) {
    return null;
  }

  const absPath = path.join(config.screenshotDir, row.file_name);
  if (!absPath.startsWith(config.screenshotDir + path.sep)) return null;
  if (!fs.existsSync(absPath)) return null;

  return { row, absPath };
}

/** 家长查看了某张截屏 —— 记下来，截图涉及未成年人隐私，查看行为必须可追溯 */
export function markViewed(screenshotId, userId) {
  const row = one('SELECT * FROM screenshots WHERE id = ?', Number(screenshotId));
  if (!row) return null;

  if (!row.viewed_at) {
    run('UPDATE screenshots SET viewed_at = ? WHERE id = ?', now(), Number(screenshotId));
  }
  writeAudit(userId, row.device_id, 'screenshot.view', { screenshotId: Number(screenshotId) });

  return toScreenshotView(one('SELECT * FROM screenshots WHERE id = ?', Number(screenshotId)));
}

/** 删除单张截屏（家长手动清理） */
export function removeOne(screenshotId) {
  const row = one('SELECT file_name FROM screenshots WHERE id = ?', Number(screenshotId));
  if (!row) return false;
  removeFile(row.file_name);
  run('DELETE FROM screenshots WHERE id = ?', Number(screenshotId));
  return true;
}

/**
 * 淘汰旧截屏。
 *
 * 两条独立规则同时生效，缺一不可：
 *   - **张数上限** —— 防止孩子连续申请截屏把磁盘撑满
 *   - **天数上限** —— 隐私数据不该无限期留存
 *
 * 删除时先删文件再删行：反过来会留下无法定位的孤儿文件。
 */
export function pruneScreenshots(deviceId) {
  const victims = [];
  const key = Number(deviceId);

  // 规则一：超出张数上限的（按时间倒序保留最新的 maxPerDevice 张）
  const overflow = all(
    `SELECT id, file_name FROM screenshots
      WHERE device_id = ?
      ORDER BY created_at DESC, id DESC
      LIMIT -1 OFFSET ?`,
    key,
    config.screenshot.maxPerDevice,
  );
  victims.push(...overflow);

  // 规则二：超过保留期的
  const before = now() - config.screenshot.retentionDays * 24 * 3600 * 1000;
  const aged = all(
    'SELECT id, file_name FROM screenshots WHERE device_id = ? AND created_at < ?',
    key,
    before,
  );
  victims.push(...aged);

  const seen = new Set();
  let removed = 0;
  for (const victim of victims) {
    if (seen.has(victim.id)) continue;
    seen.add(victim.id);

    removeFile(victim.file_name);
    run('DELETE FROM screenshots WHERE id = ?', victim.id);
    removed += 1;
  }

  return removed;
}

/**
 * 清理孤儿文件。
 *
 * 存在的理由：数据库行被级联删除（设备解绑）时，
 * 磁盘上的文件不会被自动删掉 —— 而这些正是最不该长期留存的隐私数据。
 * 由后台巡检定期调用。
 */
export function pruneOrphanFiles() {
  if (!fs.existsSync(config.screenshotDir)) return 0;

  const known = new Set(
    all('SELECT file_name FROM screenshots').map((r) => r.file_name),
  );

  let removed = 0;
  for (const entry of fs.readdirSync(config.screenshotDir)) {
    if (known.has(entry)) continue;
    if (removeFile(entry)) removed += 1;
  }
  return removed;
}

function removeFile(fileName) {
  if (!fileName || fileName.includes('/') || fileName.includes('\\')) return false;
  const absPath = path.join(config.screenshotDir, fileName);
  if (!absPath.startsWith(config.screenshotDir + path.sep)) return false;
  try {
    fs.rmSync(absPath, { force: true });
    return true;
  } catch {
    return false;
  }
}

/** 设备被解绑：连同图片一起清掉，不留痕迹 */
export function purgeDeviceScreenshots(deviceId) {
  const rows = all(
    'SELECT id, file_name FROM screenshots WHERE device_id = ?',
    Number(deviceId),
  );
  for (const row of rows) removeFile(row.file_name);
  run('DELETE FROM screenshots WHERE device_id = ?', Number(deviceId));
  return rows.length;
}
