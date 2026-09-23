import { one, all, run, now } from '../db.js';
import { sendToUser } from '../ws.js';
import { ownedDevice, ownDeviceFromToken } from './helpers.js';

const DAY_KEY_RE = /^\d{4}-\d{2}-\d{2}$/;
const MAX_BATCH = 500;
const MAX_BLOCK_BATCH = 300;

/** 拦截原因的展示文案，与控制端/被控端两侧保持一致 */
const BLOCK_REASON_TEXT = {
  total_exhausted: '今日总时长已用完',
  app_exhausted: '该应用今日时长已用完',
  out_of_window: '不在允许使用的时间段内',
  blacklist: '在禁止名单中',
  not_in_whitelist: '不在允许名单中',
  lock: '家长已锁定设备',
};

function pad(n) {
  return String(n).padStart(2, '0');
}

function dayKeyOf(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function todayKey() {
  return dayKeyOf(new Date());
}

/** 生成从 `days` 天前到今天（含）的额度日序列，用于把趋势里的空档补成 0 */
function recentDayKeys(days) {
  const keys = [];
  const cursor = new Date();
  for (let i = days - 1; i >= 0; i -= 1) {
    const d = new Date(cursor.getTime());
    d.setDate(d.getDate() - i);
    keys.push(dayKeyOf(d));
  }
  return keys;
}

/**
 * 该日期适用的每日总时长。
 *
 * 工作日/周末由日期本身决定，而不是「今天」—— 否则查看上周六的报告时
 * 会用工作日的额度去衡量，剩余时间算出来是错的。
 */
function dailyLimitMsFor(deviceId, dateKey) {
  const policy = one(
    'SELECT weekday_total_min, weekend_total_min, reset_hour, enabled FROM policies WHERE device_id = ?',
    Number(deviceId),
  );
  if (!policy) return 0;

  const [y, m, d] = dateKey.split('-').map(Number);
  const date = new Date(y, m - 1, d, 12, 0, 0);
  const dayOfWeek = date.getDay();
  const isWeekend = dayOfWeek === 0 || dayOfWeek === 6;
  const minutes = isWeekend ? policy.weekend_total_min : policy.weekday_total_min;
  return Math.max(0, minutes) * 60_000;
}

/** 当日仍生效的加时授权累计（毫秒）—— 与家长看到的"剩余"保持一致 */
function activeExtraMsFor(deviceId, dateKey) {
  const row = one(
    `SELECT COALESCE(SUM(extra_minutes), 0) AS total FROM grants
      WHERE device_id = ? AND scope = 'total_add' AND revoked = 0
        AND day_key = ? AND (expire_at IS NULL OR expire_at > ?)`,
    Number(deviceId), dateKey, now(),
  );
  return (row?.total ?? 0) * 60_000;
}

/** packageName -> appLabel，用已安装应用清单补全，避免报告里全是包名 */
function labelMapFor(deviceId) {
  const rows = all(
    'SELECT package_name, app_label FROM installed_apps WHERE device_id = ?',
    Number(deviceId),
  );
  return new Map(rows.map((r) => [r.package_name, r.app_label]));
}

/** 逐应用规则（含豁免标记），一次取出供多个报表接口复用 */
function appRulesFor(deviceId) {
  return all(
    `SELECT package_name, daily_limit_min, weekdays_mask, enabled, exempt_total
       FROM app_rules WHERE device_id = ?`,
    Number(deviceId),
  );
}

/** 日期的生效星期位：bit0 = 周一 … bit6 = 周日（与被控端 DayKeys.weekdayBit 一致） */
function weekdayBitOf(dateKey) {
  const [y, m, d] = dateKey.split('-').map(Number);
  const dayOfWeek = new Date(y, m - 1, d, 12, 0, 0).getDay(); // 0 = 周日
  return (dayOfWeek + 6) % 7;
}

/**
 * 某个「额度日」里不计入当日总时长的应用集合。
 *
 * 判定口径必须与被控端 RuleJudge 第 5 条逐字对齐：规则已启用、
 * 当天落在生效星期内、开关打开，三者缺一不可。
 * 少判一条，家长在报告里看到的「今日已用」就会和设备实际执行的额度对不上 ——
 * 这是最难排查的一类偏差，因为两端看起来都在正常工作。
 */
function exemptPackagesFor(rules, dateKey) {
  const bit = weekdayBitOf(dateKey);
  const set = new Set();
  for (const rule of rules) {
    if (!rule.exempt_total || !rule.enabled) continue;
    if (((rule.weekdays_mask >> bit) & 1) !== 1) continue;
    set.add(rule.package_name);
  }
  return set;
}

export default async function usageRoutes(fastify) {
  /**
   * 被控端：批量上报使用会话。
   * 必须幂等 —— 被控端在响应丢失时会重传同一批数据，靠 clientKey 去重。
   */
  fastify.post('/api/devices/:id/usage/report', { preHandler: fastify.requireChild }, async (request, reply) => {
    const device = ownDeviceFromToken(request, reply);
    if (!device) return;

    const sessions = request.body?.sessions;
    if (!Array.isArray(sessions)) {
      return reply.code(400).send({ error: 'invalid_body', message: '缺少 sessions 数组' });
    }

    let accepted = 0;
    let duplicated = 0;
    let rejected = 0;

    for (const item of sessions.slice(0, MAX_BATCH)) {
      const packageName =
        typeof item?.packageName === 'string' && item.packageName.trim() ? item.packageName.trim() : null;
      const dayKey = typeof item?.dayKey === 'string' && DAY_KEY_RE.test(item.dayKey) ? item.dayKey : null;
      const startTs = Number(item?.startTs);
      const durationMs = Math.round(Number(item?.durationMs));
      const endTsRaw = item?.endTs === null || item?.endTs === undefined ? null : Number(item.endTs);
      const clientKey =
        typeof item?.clientKey === 'string' && item.clientKey.trim() ? item.clientKey.trim() : null;

      const valid =
        packageName &&
        dayKey &&
        Number.isFinite(startTs) &&
        Number.isFinite(durationMs) &&
        durationMs >= 0 &&
        durationMs <= 24 * 60 * 60 * 1000 &&
        (endTsRaw === null || Number.isFinite(endTsRaw));

      if (!valid) {
        rejected += 1;
        continue;
      }

      const inserted = run(
        `INSERT OR IGNORE INTO usage_sessions
           (client_key, device_id, package_name, start_ts, end_ts, duration_ms, day_key)
         VALUES (?, ?, ?, ?, ?, ?, ?)`,
        clientKey, device.id, packageName, startTs, endTsRaw, durationMs, dayKey,
      );

      if (inserted.changes === 1) {
        accepted += 1;
        // 会话明细与日汇总在同一事务里更严谨；此处单条 upsert 已足够，SQLite 单写者模型下不会并发冲突
        run(
          `INSERT INTO usage_daily (device_id, day_key, package_name, total_ms, open_count)
           VALUES (?, ?, ?, ?, 1)
           ON CONFLICT(device_id, day_key, package_name)
           DO UPDATE SET total_ms = total_ms + excluded.total_ms,
                         open_count = open_count + 1`,
          device.id, dayKey, packageName, durationMs,
        );
      } else {
        duplicated += 1;
      }
    }

    if (accepted > 0) {
      sendToUser(device.user_id, {
        type: 'usage_updated',
        deviceId: device.id,
        accepted,
        at: now(),
      });
    }

    return { ok: true, accepted, duplicated, rejected };
  });

  /**
   * 被控端：批量上报拦截记录。
   * 与使用记录同一套幂等思路（clientKey 去重），因为诱发重传的场景完全相同。
   */
  fastify.post('/api/devices/:id/blocks', { preHandler: fastify.requireChild }, async (request, reply) => {
    const device = ownDeviceFromToken(request, reply);
    if (!device) return;

    const logs = request.body?.logs;
    if (!Array.isArray(logs)) {
      return reply.code(400).send({ error: 'invalid_body', message: '缺少 logs 数组' });
    }

    let accepted = 0;
    let duplicated = 0;
    let rejected = 0;

    for (const item of logs.slice(0, MAX_BLOCK_BATCH)) {
      const packageName =
        typeof item?.packageName === 'string' && item.packageName.trim() ? item.packageName.trim() : null;
      const reason = typeof item?.reason === 'string' && item.reason.trim() ? item.reason.trim() : null;
      const ts = Number(item?.ts);
      const clientKey =
        typeof item?.clientKey === 'string' && item.clientKey.trim() ? item.clientKey.trim() : null;

      if (!packageName || !reason || !Number.isFinite(ts)) {
        rejected += 1;
        continue;
      }

      const inserted = run(
        `INSERT OR IGNORE INTO block_logs (client_key, device_id, package_name, reason, ts)
         VALUES (?, ?, ?, ?, ?)`,
        clientKey, device.id, packageName, reason, ts,
      );

      if (inserted.changes === 1) accepted += 1;
      else duplicated += 1;
    }

    return { ok: true, accepted, duplicated, rejected };
  });

  /** 控制端：某日汇总（按应用降序） */
  fastify.get('/api/devices/:id/usage/daily', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const date = typeof request.query?.date === 'string' && DAY_KEY_RE.test(request.query.date)
      ? request.query.date
      : todayKey();

    const rows = all(
      `SELECT package_name, total_ms, open_count
         FROM usage_daily
        WHERE device_id = ? AND day_key = ?
        ORDER BY total_ms DESC`,
      device.id, date,
    );

    const exempt = exemptPackagesFor(appRulesFor(device.id), date);

    const apps = rows.map((r) => ({
      packageName: r.package_name,
      totalMs: r.total_ms,
      openCount: r.open_count,
      /** 该应用当天是否不计入总时长 */
      exemptTotal: exempt.has(r.package_name),
    }));

    // 「已用总时长」只算计入额度的部分 —— 这个数必须与设备端执行的额度一致。
    // 被豁免的应用照旧留在 apps 里（家长要知道它其实用了多久），只是不进这个总数。
    const counted = apps.filter((a) => !a.exemptTotal);
    const exempted = apps.filter((a) => a.exemptTotal);

    return {
      date,
      totalMs: counted.reduce((sum, a) => sum + a.totalMs, 0),
      exemptMs: exempted.reduce((sum, a) => sum + a.totalMs, 0),
      apps,
    };
  });

  /**
   * 控制端：使用报告总览。
   * 把「额度、加时、剩余」一起算好返回 —— 否则控制端要自己拼三份数据，
   * 迟早会和被控端的判定逻辑算出不同的数字。
   */
  fastify.get('/api/devices/:id/usage/overview', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const date = typeof request.query?.date === 'string' && DAY_KEY_RE.test(request.query.date)
      ? request.query.date
      : todayKey();

    const rows = all(
      `SELECT package_name, total_ms, open_count
         FROM usage_daily
        WHERE device_id = ? AND day_key = ?
        ORDER BY total_ms DESC`,
      device.id, date,
    );

    const labels = labelMapFor(device.id);
    const rules = appRulesFor(device.id);
    const limitByPackage = new Map(rules.map((r) => [r.package_name, r.daily_limit_min]));
    const exempt = exemptPackagesFor(rules, date);

    const apps = rows.map((r) => ({
      packageName: r.package_name,
      appLabel: labels.get(r.package_name) ?? null,
      totalMs: r.total_ms,
      openCount: r.open_count,
      limitMin: limitByPackage.get(r.package_name) ?? 0,
      /** 该应用当天是否不计入总时长 */
      exemptTotal: exempt.has(r.package_name),
    }));

    const baseLimitMs = dailyLimitMsFor(device.id, date);
    const extraMs = activeExtraMsFor(device.id, date);
    const limitMs = baseLimitMs + extraMs;
    // 剩余额度必须与设备端一致：被豁免的应用不参与计算，否则家长会看到
    // 「还剩 0 分钟」而孩子其实还能继续用那个应用
    const totalMs = apps
      .filter((a) => !a.exemptTotal)
      .reduce((sum, a) => sum + a.totalMs, 0);
    const exemptMs = apps
      .filter((a) => a.exemptTotal)
      .reduce((sum, a) => sum + a.totalMs, 0);

    const policy = one(
      'SELECT enabled, list_mode, weekday_total_min, weekend_total_min FROM policies WHERE device_id = ?',
      device.id,
    );

    return {
      date,
      totalMs,
      exemptMs,
      openCount: apps.reduce((sum, a) => sum + a.openCount, 0),
      appCount: apps.length,
      baseLimitMs,
      extraMs,
      limitMs,
      remainingMs: Math.max(0, limitMs - totalMs),
      enforcementEnabled: policy ? !!policy.enabled : true,
      listMode: policy?.list_mode ?? 'blacklist',
      apps,
    };
  });

  /**
   * 控制端：近 N 日趋势（缺失的日期补 0，否则折线会跳过空白日）。
   *
   * 每日柱状值与每日额度上限是同一套口径，所以这里**剔除**不计入总时长的应用 ——
   * 否则折线上会出现"超出上限"的柱子，而设备实际上并没有触发拦截。
   */
  fastify.get('/api/devices/:id/usage/trend', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const daysRaw = Number(request.query?.days);
    const days = Number.isInteger(daysRaw) && daysRaw > 0 && daysRaw <= 60 ? daysRaw : 7;

    const keys = recentDayKeys(days);
    const rules = appRulesFor(device.id);

    // 取回逐日逐应用的明细，在 JS 里聚合，而不是让 SQL 一口气 SUM。
    // 原因：豁免集合是按「那一天」的生效星期算的，用今天的状态去回算七天前的用量
    // 会让历史数字随配置变动而漂移。
    const rows = all(
      `SELECT day_key, package_name,
              COALESCE(SUM(total_ms), 0)   AS total_ms,
              COALESCE(SUM(open_count), 0) AS open_count
         FROM usage_daily
        WHERE device_id = ? AND day_key >= ?
        GROUP BY day_key, package_name`,
      device.id, keys[0],
    );

    const exemptCache = new Map();
    const exemptForDay = (dayKey) => {
      let cached = exemptCache.get(dayKey);
      if (!cached) {
        cached = exemptPackagesFor(rules, dayKey);
        exemptCache.set(dayKey, cached);
      }
      return cached;
    };

    const byDay = new Map();
    for (const row of rows) {
      if (exemptForDay(row.day_key).has(row.package_name)) continue;
      const acc = byDay.get(row.day_key) ?? { total_ms: 0, open_count: 0 };
      acc.total_ms += row.total_ms;
      acc.open_count += row.open_count;
      byDay.set(row.day_key, acc);
    }

    const points = keys.map((dayKey) => {
      const row = byDay.get(dayKey);
      return {
        dayKey,
        totalMs: row?.total_ms ?? 0,
        openCount: row?.open_count ?? 0,
        limitMs: dailyLimitMsFor(device.id, dayKey),
      };
    });

    return {
      days,
      points,
      totalMs: points.reduce((sum, p) => sum + p.totalMs, 0),
      averageMs: Math.round(points.reduce((sum, p) => sum + p.totalMs, 0) / points.length),
      peak: points.reduce((max, p) => (p.totalMs > max.totalMs ? p : max), points[0]),
    };
  });

  /**
   * 控制端：近 N 日应用使用排行。
   *
   * 与趋势图不同，这里**不剔除**被豁免的应用：排行回答的是"哪个应用用得最多"，
   * 是实际使用时长的排名，与额度判定无关。剔掉反而会让家长看不到真实用量。
   */
  fastify.get('/api/devices/:id/usage/ranking', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const daysRaw = Number(request.query?.days);
    const days = Number.isInteger(daysRaw) && daysRaw > 0 && daysRaw <= 60 ? daysRaw : 7;
    const limitRaw = Number(request.query?.limit);
    const limit = Number.isInteger(limitRaw) && limitRaw > 0 && limitRaw <= 100 ? limitRaw : 20;

    const keys = recentDayKeys(days);
    const rows = all(
      `SELECT package_name,
              COALESCE(SUM(total_ms), 0)   AS total_ms,
              COALESCE(SUM(open_count), 0) AS open_count,
              COUNT(DISTINCT day_key)      AS active_days
         FROM usage_daily
        WHERE device_id = ? AND day_key >= ?
        GROUP BY package_name
        ORDER BY total_ms DESC
        LIMIT ?`,
      device.id, keys[0], limit,
    );

    const labels = labelMapFor(device.id);
    return {
      days,
      apps: rows.map((r) => ({
        packageName: r.package_name,
        appLabel: labels.get(r.package_name) ?? null,
        totalMs: r.total_ms,
        openCount: r.open_count,
        activeDays: r.active_days,
        dailyAverageMs: Math.round(r.total_ms / Math.max(1, r.active_days)),
      })),
    };
  });

  /** 控制端：拦截记录（孩子何时因何原因被拦下） */
  fastify.get('/api/devices/:id/blocks', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const date = typeof request.query?.date === 'string' && DAY_KEY_RE.test(request.query.date)
      ? request.query.date
      : null;
    const limitRaw = Number(request.query?.limit);
    const limit = Number.isInteger(limitRaw) && limitRaw > 0 && limitRaw <= 500 ? limitRaw : 100;

    const conditions = ['device_id = ?'];
    const params = [device.id];

    if (date) {
      // 用本地日界过滤：拦截时间戳是毫秒，跨零点归日与使用记录的口径不同，
      // 这里按自然日统计，家长看的是"哪天"，不需要额度日那套归日逻辑
      const [y, m, d] = date.split('-').map(Number);
      const start = new Date(y, m - 1, d, 0, 0, 0, 0).getTime();
      conditions.push('ts >= ? AND ts < ?');
      params.push(start, start + 24 * 60 * 60 * 1000);
    }

    const rows = all(
      `SELECT id, package_name, reason, ts FROM block_logs
        WHERE ${conditions.join(' AND ')}
        ORDER BY ts DESC LIMIT ?`,
      ...params, limit,
    );

    const labels = labelMapFor(device.id);
    return {
      date,
      blocks: rows.map((r) => ({
        id: r.id,
        packageName: r.package_name,
        appLabel: labels.get(r.package_name) ?? null,
        reason: r.reason,
        reasonText: BLOCK_REASON_TEXT[r.reason] ?? '已被家长限制',
        ts: r.ts,
      })),
    };
  });

  /** 控制端：会话明细（时间线） */
  fastify.get('/api/devices/:id/usage/sessions', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const { date, packageName } = request.query ?? {};
    const limitRaw = Number(request.query?.limit);
    const limit = Number.isInteger(limitRaw) && limitRaw > 0 && limitRaw <= 1000 ? limitRaw : 200;

    const conditions = ['device_id = ?'];
    const params = [device.id];

    if (typeof date === 'string' && DAY_KEY_RE.test(date)) {
      conditions.push('day_key = ?');
      params.push(date);
    }
    if (typeof packageName === 'string' && packageName.trim()) {
      conditions.push('package_name = ?');
      params.push(packageName.trim());
    }

    const rows = all(
      `SELECT id, package_name, start_ts, end_ts, duration_ms, day_key
         FROM usage_sessions
        WHERE ${conditions.join(' AND ')}
        ORDER BY start_ts DESC
        LIMIT ?`,
      ...params, limit,
    );

    const labels = labelMapFor(device.id);
    return {
      sessions: rows.map((r) => ({
        id: r.id,
        packageName: r.package_name,
        appLabel: labels.get(r.package_name) ?? null,
        startTs: r.start_ts,
        endTs: r.end_ts,
        durationMs: r.duration_ms,
        dayKey: r.day_key,
      })),
    };
  });
}
