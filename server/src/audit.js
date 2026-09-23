import { one, all, run, now } from './db.js';

/**
 * 操作审计。
 *
 * 两类来源共用一张表：
 *   source='server' — 控制端发起的操作（改策略、下发指令、重置密码、加固…）
 *   source='child'  — 被控端上报的本地事件（权限被关、密码被试、管控被绕过）
 *
 * 为什么要记录"查看截屏"这种只读操作：截屏涉及未成年人隐私，
 * 谁在什么时候看过必须可追溯。这是合规要求，不是可选功能。
 */

/** 依据动作推断告警级别，供控制端做视觉区分（红色 = 需要立刻关注） */
function levelOf(action) {
  if (
    action.startsWith('alert.') ||
    action === 'pin.attempt.failed' ||
    action === 'permission.lost' ||
    action === 'device.admin_disabled' ||
    action === 'device.uninstall_attempt' ||
    // 截屏采集失败 = 家长点了「立即截屏」却拿不到图，
    // 原因通常是无障碍被关或系统版本过低 —— 不标红就没人去修
    action === 'screenshot.failed'
  ) {
    return 'warn';
  }
  if (action === 'pin.attempt.success' || action === 'screenshot.view') return 'notice';
  return 'info';
}

/** 写一条审计日志。任何会改变管控状态的调用都应记录。 */
export function writeAudit(userId, deviceId, action, detail) {
  run(
    `INSERT INTO audit_logs (user_id, device_id, action, detail, client_key, source, level, created_at)
     VALUES (?, ?, ?, ?, NULL, 'server', ?, ?)`,
    userId ?? null,
    deviceId ?? null,
    action,
    detail === undefined ? null : JSON.stringify(detail),
    levelOf(action),
    now(),
  );
}

/**
 * 接收被控端上报的本地事件。
 *
 * 幂等靠 `clientKey`（被控端生成 UUID + 唯一索引）——
 * 断网期间的事件会重传，重复上报不能让家长的日志里出现两条一样的记录。
 *
 * 单条事件写失败（例如 clientKey 冲突）不该让整批失败，
 * 因此逐条 try/catch：其余事件照常入库。
 *
 * @returns {{accepted: number, duplicated: number}}
 */
export function ingestChildEvents(deviceId, userId, events) {
  let accepted = 0;
  let duplicated = 0;

  for (const event of events) {
    const action = typeof event?.action === 'string' ? event.action.trim().slice(0, 80) : '';
    if (!action) continue;

    const clientKey = typeof event?.clientKey === 'string' ? event.clientKey.slice(0, 80) : null;
    const ts = Number.isFinite(event?.ts) ? Number(event.ts) : now();

    try {
      const result = run(
        `INSERT INTO audit_logs
           (user_id, device_id, action, detail, client_key, source, level, created_at)
         VALUES (?, ?, ?, ?, ?, 'child', ?, ?)`,
        userId ?? null,
        Number(deviceId),
        action,
        event?.detail === undefined ? null : JSON.stringify(event.detail),
        clientKey,
        typeof event?.level === 'string' ? event.level.slice(0, 16) : levelOf(action),
        ts,
      );
      if (result.changes > 0) accepted += 1;
    } catch (error) {
      // 唯一索引冲突 = 这条之前已经收到过。它是正常路径（重传），不是故障
      if (String(error?.message ?? '').includes('UNIQUE')) {
        duplicated += 1;
      }
    }
  }

  return { accepted, duplicated };
}

function toAuditView(row) {
  const detail = row.detail ? safeParse(row.detail) : null;
  let actionText = describeAction(row.action);
  // 截屏失败的原因藏在 detail 里，家长端不显示 detail ——
  // 不把它并进文案，家长就只看到"失败"两个字，还是不知道该去修什么
  if (row.action === 'screenshot.failed' && detail && typeof detail.reason === 'string') {
    const reason = SCREENSHOT_FAIL_REASONS[detail.reason] ?? detail.reason;
    actionText += `：${reason}`;
  }
  return {
    id: row.id,
    deviceId: row.device_id,
    deviceName: row.device_name ?? null,
    action: row.action,
    actionText,
    detail,
    source: row.source,
    level: row.level,
    createdAt: row.created_at,
  };
}

/** 被控端截屏失败的原因码（ScreenshotCapturer）→ 家长能看懂的话 */
const SCREENSHOT_FAIL_REASONS = {
  unsupported_os: '系统版本低于 Android 11，无法静默截屏',
  no_accessibility: '无障碍服务未运行（请到平板的设置里重新开启）',
  call_failed: '系统调用失败（可重试一次）',
  timeout: '采集超时（可重试一次）',
  capture_failed: '系统返回截屏失败（可重试一次）',
  compress_failed: '图片压缩失败',
};

function safeParse(json) {
  try {
    return JSON.parse(json);
  } catch {
    return null;
  }
}

/**
 * 动作码到中文的映射。
 *
 * 放在服务端而不是客户端：文案改动不该要求两端同时发版，
 * 而且控制端历史上可能存着旧动作码，集中映射能保证显示一致。
 */
const ACTION_TEXT = {
  'auth.register': '注册账号',
  'auth.login': '登录',
  'pair.code.create': '生成配对码',
  'pair.claim': '设备完成配对',
  'device.rename': '重命名设备',
  'device.unbind': '解绑设备',
  'device.sync': '手动同步策略',
  'device.heartbeat': '设备心跳（仅记录异常）',

  'policy.update': '修改时长规则',
  'policy.lists': '修改黑白名单',
  'policy.appRules': '修改逐应用规则',
  'policy.bundle': '原子更新管控策略',

  'grant.create': '创建临时授权',
  'grant.revoke': '撤销临时授权',
  'timeRequest.approve': '批准加时申请',
  'timeRequest.reject': '拒绝加时申请',
  'timeRequest.create': '孩子提交加时申请',

  'command.immediateLock': '立即锁定设备',
  'command.clearLock': '解除锁定',
  'command.screenshot': '请求截屏',
  'command.requestApps': '请求重报应用清单',

  'pin.reset': '远程重置离线密码',
  'pin.upload': '设备上传密码备份',
  'pin.attempt.failed': '★ 密码输入错误（可能是孩子在尝试）',
  'pin.attempt.success': '密码验证通过',
  'pin.locked': '密码尝试过多已被锁定',

  'permission.lost': '★ 管控权限被关闭',
  'device.admin_disabled': '★ 设备管理器被取消激活',
  'device.uninstall_attempt': '★ 检测到卸载尝试',
  'device.guard_exited': '已退出管控（离线密码）',
  'device.icon_hidden': '已隐藏应用图标',
  'device.icon_shown': '已恢复应用图标',

  'screenshot.upload': '设备上传截屏',
  'screenshot.failed': '设备截屏失败（未生成图片）',
  'screenshot.view': '家长查看截屏',
  'user.pinQuery': '查询密码状态',
};

export function describeAction(action) {
  return ACTION_TEXT[action] ?? action;
}

/**
 * 控制端查询审计日志。
 *
 * @param deviceId 限定设备；null 表示该家长账号下的全部设备
 */
export function listAudit(userId, { deviceId = null, limit = 100, level = null } = {}) {
  const conditions = ['a.user_id = ?'];
  const params = [Number(userId)];

  if (deviceId) {
    conditions.push('a.device_id = ?');
    params.push(Number(deviceId));
  }
  if (level) {
    conditions.push('a.level = ?');
    params.push(String(level));
  }
  params.push(Math.min(Math.max(Number(limit) || 100, 1), 300));

  const rows = all(
    `SELECT a.*, d.name AS device_name
       FROM audit_logs a
       LEFT JOIN devices d ON d.id = a.device_id
      WHERE ${conditions.join(' AND ')}
      ORDER BY a.created_at DESC, a.id DESC
      LIMIT ?`,
    ...params,
  );

  return rows.map(toAuditView);
}

/** 汇总统计，供控制端在审计页顶部展示"最近有多少需要注意的事" */
export function auditSummary(userId, deviceId = null) {
  const since = now() - 7 * 24 * 3600 * 1000;
  const conditions = ['user_id = ?', 'created_at >= ?'];
  const params = [Number(userId), since];
  if (deviceId) {
    conditions.push('device_id = ?');
    params.push(Number(deviceId));
  }
  const where = conditions.join(' AND ');

  const warn = one(
    `SELECT COUNT(*) AS c FROM audit_logs WHERE ${where} AND level = 'warn'`,
    ...params,
  );
  const total = one(
    `SELECT COUNT(*) AS c FROM audit_logs WHERE ${where}`,
    ...params,
  );
  const lastWarn = one(
    `SELECT action, created_at FROM audit_logs
      WHERE ${where} AND level = 'warn'
      ORDER BY created_at DESC LIMIT 1`,
    ...params,
  );

  return {
    windowDays: 7,
    total: total?.c ?? 0,
    warnCount: warn?.c ?? 0,
    lastWarnAction: lastWarn?.action ?? null,
    lastWarnActionText: lastWarn ? describeAction(lastWarn.action) : null,
    lastWarnAt: lastWarn?.created_at ?? null,
  };
}

/** 清理超过保留期的日志。被控端上报的事件量大，不清理会无限增长。 */
export function pruneAudit(retentionDays = 180) {
  const before = now() - retentionDays * 24 * 3600 * 1000;
  return run('DELETE FROM audit_logs WHERE created_at < ?', before).changes;
}
