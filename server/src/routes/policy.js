import { one, all, run, now, transaction } from '../db.js';
import { writeAudit } from '../audit.js';
import { sendToDevice } from '../ws.js';
import { ownedDevice } from './helpers.js';

/** 每日总时长的合法范围（分钟）。0 表示当天完全不允许使用。 */
const MAX_TOTAL_MIN = 24 * 60;
/** 单应用单日上限的合法范围（分钟）。0 表示不单独限制。 */
const MAX_APP_LIMIT_MIN = 24 * 60;
/** 名单条数上限 */
const MAX_LIST_ITEMS = 500;

const LIST_MODES = new Set(['blacklist', 'whitelist']);
/** 生效星期位掩码：bit0 = 周一 … bit6 = 周日 */
const WEEKDAY_MASK_MIN = 0;
const WEEKDAY_MASK_MAX = 0b1111111;

const TIME_RE = /^([01]\d|2[0-3]):([0-5]\d)$/;

export function toPolicyView(policy) {
  return {
    deviceId: policy.device_id,
    weekdayTotalMin: policy.weekday_total_min,
    weekendTotalMin: policy.weekend_total_min,
    resetHour: policy.reset_hour,
    listMode: policy.list_mode,
    allowTimeRequest: !!policy.allow_time_request,
    enabled: !!policy.enabled,
    version: policy.version,
    updatedAt: policy.updated_at,
  };
}

/** 老库或异常情况下兜底补一条默认策略，保证接口始终有值可返回 */
function ensurePolicy(deviceId) {
  let policy = one('SELECT * FROM policies WHERE device_id = ?', deviceId);
  if (policy) return policy;

  run(
    `INSERT INTO policies
       (device_id, weekday_total_min, weekend_total_min, reset_hour, list_mode, allow_time_request, enabled, updated_at, version)
     VALUES (?, 60, 120, 0, 'blacklist', 1, 1, ?, 1)`,
    deviceId,
    now(),
  );
  return one('SELECT * FROM policies WHERE device_id = ?', deviceId);
}

function toListItemView(row) {
  return { packageName: row.package_name, appLabel: row.app_label };
}

function toAppRuleView(row) {
  return {
    packageName: row.package_name,
    appLabel: row.app_label,
    dailyLimitMin: row.daily_limit_min,
    timeWindows: row.time_windows ? safeParse(row.time_windows) : [],
    weekdaysMask: row.weekdays_mask,
    enabled: !!row.enabled,
    /** 用时不计入当日总时长（总时长耗尽后该应用仍可打开） */
    exemptTotal: !!row.exempt_total,
  };
}

function safeParse(json) {
  try {
    const parsed = JSON.parse(json);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

/**
 * 策略包：基础策略 + 黑白名单 + 逐应用规则。
 *
 * 为什么打包下发而不是分三个接口：被控端必须能在**一次请求**里拿到完整的本地规则副本。
 * 分三次拉取会引入"只同步了一半"的中间态，导致管控行为短暂不一致。
 */
export function loadPolicyBundle(deviceId) {
  const policy = ensurePolicy(deviceId);
  const listItems = all(
    'SELECT package_name, app_label FROM policy_list_items WHERE device_id = ? ORDER BY app_label',
    deviceId,
  );
  const appRules = all(
    'SELECT * FROM app_rules WHERE device_id = ? ORDER BY app_label',
    deviceId,
  );

  return {
    ...toPolicyView(policy),
    listItems: listItems.map(toListItemView),
    appRules: appRules.map(toAppRuleView),
  };
}

/** 任何改动规则的接口都要：版本自增 → 记审计 → 实时推送完整策略包 */
function commitPolicyChange(deviceId, userId, action, detail) {
  const current = ensurePolicy(deviceId);
  const version = current.version + 1;
  const updatedAt = now();

  run(
    'UPDATE policies SET version = ?, updated_at = ? WHERE device_id = ?',
    version, updatedAt, deviceId,
  );

  writeAudit(userId, deviceId, action, { ...detail, version });

  const bundle = loadPolicyBundle(deviceId);
  const delivered = sendToDevice(deviceId, { type: 'policy_updated', bundle, at: updatedAt });
  return { bundle, delivered };
}

/* ---------------- 校验 ---------------- */

function intInRange(value, min, max) {
  const n = Number(value);
  if (!Number.isInteger(n) || n < min || n > max) return null;
  return n;
}

/** 时段格式 HH:MM；end <= start 表示跨零点（如 22:00 - 07:00） */
function normalizeTimeWindows(input) {
  if (input === undefined) return { ok: true, value: null };
  if (!Array.isArray(input)) return { ok: false, reason: 'timeWindows 必须是数组' };
  if (input.length > 12) return { ok: false, reason: '单个应用最多配置 12 个时段' };

  const cleaned = [];
  for (const item of input) {
    const start = typeof item?.start === 'string' ? item.start.trim() : '';
    const end = typeof item?.end === 'string' ? item.end.trim() : '';
    if (!TIME_RE.test(start) || !TIME_RE.test(end)) {
      return { ok: false, reason: `时段格式必须是 HH:MM，收到 ${start} - ${end}` };
    }
    if (start === end) {
      return { ok: false, reason: `时段的开始与结束不能相同（${start}）` };
    }
    cleaned.push({ start, end });
  }
  return { ok: true, value: cleaned };
}

function normalizeAppRules(input, deviceId) {
  if (!Array.isArray(input)) return { ok: false, reason: 'rules 必须是数组' };
  if (input.length > 500) return { ok: false, reason: '单个设备最多配置 500 条应用规则' };

  const seen = new Set();
  const cleaned = [];

  for (const item of input) {
    const packageName = typeof item?.packageName === 'string' ? item.packageName.trim() : '';
    if (!packageName) return { ok: false, reason: '存在缺少 packageName 的规则' };
    if (seen.has(packageName)) return { ok: false, reason: `应用 ${packageName} 存在重复规则` };
    seen.add(packageName);

    const dailyLimitMin =
      item.dailyLimitMin === undefined ? 0 : intInRange(item.dailyLimitMin, 0, MAX_APP_LIMIT_MIN);
    if (dailyLimitMin === null) {
      return { ok: false, reason: `${packageName} 的单日上限需在 0 - ${MAX_APP_LIMIT_MIN} 分钟之间` };
    }

    const weekdaysMask =
      item.weekdaysMask === undefined
        ? WEEKDAY_MASK_MAX
        : intInRange(item.weekdaysMask, WEEKDAY_MASK_MIN, WEEKDAY_MASK_MAX);
    if (weekdaysMask === null) {
      return { ok: false, reason: `${packageName} 的生效星期掩码需在 0 - 127 之间` };
    }

    const windows = normalizeTimeWindows(item.timeWindows);
    if (!windows.ok) return { ok: false, reason: `${packageName}: ${windows.reason}` };

    cleaned.push({
      packageName,
      appLabel: typeof item.appLabel === 'string' ? item.appLabel.slice(0, 80) : null,
      dailyLimitMin,
      timeWindows: windows.value,
      weekdaysMask,
      enabled: item.enabled === undefined ? true : !!item.enabled,
      // 布尔开关，缺省即 false：老客户端不发这个字段时行为与从前完全一致
      exemptTotal: item.exemptTotal === undefined ? false : !!item.exemptTotal,
    });
  }

  void deviceId;
  return { ok: true, value: cleaned };
}

/* ---------------- 路由 ---------------- */

export default async function policyRoutes(fastify) {
  /** 控制端：读取基础策略（管控规则弹窗用） */
  fastify.get('/api/devices/:id/policy', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;
    return toPolicyView(ensurePolicy(device.id));
  });

  /** 控制端：读取完整策略包（应用管控页用） */
  fastify.get('/api/devices/:id/policy/bundle', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;
    return loadPolicyBundle(device.id);
  });

  /** 控制端：更新基础策略 */
  fastify.put('/api/devices/:id/policy', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const body = request.body ?? {};
    const current = ensurePolicy(device.id);

    const weekdayTotalMin =
      body.weekdayTotalMin === undefined
        ? current.weekday_total_min
        : intInRange(body.weekdayTotalMin, 0, MAX_TOTAL_MIN);
    const weekendTotalMin =
      body.weekendTotalMin === undefined
        ? current.weekend_total_min
        : intInRange(body.weekendTotalMin, 0, MAX_TOTAL_MIN);
    const resetHour =
      body.resetHour === undefined ? current.reset_hour : intInRange(body.resetHour, 0, 23);
    const listMode = body.listMode === undefined ? current.list_mode : String(body.listMode);
    const allowTimeRequest =
      body.allowTimeRequest === undefined ? current.allow_time_request : body.allowTimeRequest ? 1 : 0;
    const enabled = body.enabled === undefined ? current.enabled : body.enabled ? 1 : 0;

    if (weekdayTotalMin === null || weekendTotalMin === null || resetHour === null) {
      return reply.code(400).send({
        error: 'invalid_total_minutes',
        message: `总时长需在 0 - ${MAX_TOTAL_MIN} 分钟之间，重置时间需在 0 - 23 之间`,
      });
    }
    if (!LIST_MODES.has(listMode)) {
      return reply.code(400).send({ error: 'invalid_list_mode', message: '名单模式只能是 blacklist 或 whitelist' });
    }

    run(
      `UPDATE policies
          SET weekday_total_min = ?, weekend_total_min = ?, reset_hour = ?,
              list_mode = ?, allow_time_request = ?, enabled = ?
        WHERE device_id = ?`,
      weekdayTotalMin, weekendTotalMin, resetHour,
      listMode, allowTimeRequest, enabled,
      device.id,
    );

    const { bundle, delivered } = commitPolicyChange(device.id, request.claims.userId, 'policy.update', {
      weekdayTotalMin, weekendTotalMin, resetHour, listMode, allowTimeRequest, enabled: !!enabled,
    });

    return { ...bundle, delivered };
  });

  /** 控制端：整份替换黑白名单 */
  fastify.put('/api/devices/:id/policy/lists', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const { listMode, items } = request.body ?? {};
    if (listMode !== undefined && !LIST_MODES.has(String(listMode))) {
      return reply.code(400).send({ error: 'invalid_list_mode', message: '名单模式只能是 blacklist 或 whitelist' });
    }
    if (items !== undefined && !Array.isArray(items)) {
      return reply.code(400).send({ error: 'invalid_items', message: 'items 必须是数组' });
    }
    if (Array.isArray(items) && items.length > MAX_LIST_ITEMS) {
      return reply.code(400).send({ error: 'too_many_items', message: `名单最多 ${MAX_LIST_ITEMS} 项` });
    }

    // 名单的替换也是「先删后插」，同样放进事务，避免中途失败后名单变空
    transaction(() => {
      if (listMode !== undefined) {
        run('UPDATE policies SET list_mode = ? WHERE device_id = ?', String(listMode), device.id);
      }

      if (Array.isArray(items)) {
        run('DELETE FROM policy_list_items WHERE device_id = ?', device.id);

        for (const item of items) {
          const packageName = typeof item?.packageName === 'string' ? item.packageName.trim() : '';
          if (!packageName) continue;
          run(
            `INSERT OR IGNORE INTO policy_list_items (device_id, package_name, app_label)
             VALUES (?, ?, ?)`,
            device.id,
            packageName,
            typeof item.appLabel === 'string' ? item.appLabel.slice(0, 80) : null,
          );
        }
      }
    });

    const { bundle, delivered } = commitPolicyChange(device.id, request.claims.userId, 'policy.lists', {
      listMode, itemCount: Array.isArray(items) ? items.length : undefined,
    });

    return { ...bundle, delivered };
  });

  /** 控制端：整份替换逐应用规则 */
  fastify.put('/api/devices/:id/policy/app-rules', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const normalized = normalizeAppRules(request.body?.rules, device.id);
    if (!normalized.ok) {
      return reply.code(400).send({ error: 'invalid_rules', message: normalized.reason });
    }

    // 先删后插放进事务：中途失败时不能把规则清空
    transaction(() => {
      run('DELETE FROM app_rules WHERE device_id = ?', device.id);

      for (const rule of normalized.value) {
        run(
          `INSERT INTO app_rules
             (device_id, package_name, app_label, daily_limit_min, time_windows, weekdays_mask, enabled, exempt_total)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
          device.id,
          rule.packageName,
          rule.appLabel,
          rule.dailyLimitMin,
          rule.timeWindows && rule.timeWindows.length > 0 ? JSON.stringify(rule.timeWindows) : null,
          rule.weekdaysMask,
          rule.enabled ? 1 : 0,
          rule.exemptTotal ? 1 : 0,
        );
      }
    });

    const { bundle, delivered } = commitPolicyChange(device.id, request.claims.userId, 'policy.appRules', {
      ruleCount: normalized.value.length,
    });

    return { ...bundle, delivered };
  });

  /**
   * 控制端：一次性替换名单与逐应用规则（原子）。
   *
   * 应用管控界面的"保存"必须走这个接口，而不是分两次调 lists 与 app-rules：
   * 分两次会出现"名单换了但规则没换"的中间态，被控端会按这个半套配置执行管控。
   */
  fastify.put('/api/devices/:id/policy/bundle', { preHandler: fastify.requireParent }, async (request, reply) => {
    const device = ownedDevice(request, reply);
    if (!device) return;

    const { listMode, items, rules } = request.body ?? {};

    if (listMode !== undefined && !LIST_MODES.has(String(listMode))) {
      return reply.code(400).send({ error: 'invalid_list_mode', message: '名单模式只能是 blacklist 或 whitelist' });
    }
    if (items !== undefined && !Array.isArray(items)) {
      return reply.code(400).send({ error: 'invalid_items', message: 'items 必须是数组' });
    }
    if (rules !== undefined && !Array.isArray(rules)) {
      return reply.code(400).send({ error: 'invalid_rules', message: 'rules 必须是数组' });
    }
    if (Array.isArray(items) && items.length > MAX_LIST_ITEMS) {
      return reply.code(400).send({ error: 'too_many_items', message: `名单最多 ${MAX_LIST_ITEMS} 项` });
    }

    const normalizedRules = rules === undefined ? null : normalizeAppRules(rules, device.id);
    if (normalizedRules && !normalizedRules.ok) {
      return reply.code(400).send({ error: 'invalid_rules', message: normalizedRules.reason });
    }

    // 校验全部通过后才动数据库，且整组写操作在一个事务里
    transaction(() => {
      if (listMode !== undefined) {
        run('UPDATE policies SET list_mode = ? WHERE device_id = ?', String(listMode), device.id);
      }

      if (Array.isArray(items)) {
        run('DELETE FROM policy_list_items WHERE device_id = ?', device.id);
        for (const item of items) {
          const packageName = typeof item?.packageName === 'string' ? item.packageName.trim() : '';
          if (!packageName) continue;
          run(
            `INSERT OR IGNORE INTO policy_list_items (device_id, package_name, app_label)
             VALUES (?, ?, ?)`,
            device.id,
            packageName,
            typeof item.appLabel === 'string' ? item.appLabel.slice(0, 80) : null,
          );
        }
      }

      if (normalizedRules) {
        run('DELETE FROM app_rules WHERE device_id = ?', device.id);
        for (const rule of normalizedRules.value) {
          run(
            `INSERT INTO app_rules
               (device_id, package_name, app_label, daily_limit_min, time_windows, weekdays_mask, enabled, exempt_total)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
            device.id,
            rule.packageName,
            rule.appLabel,
            rule.dailyLimitMin,
            rule.timeWindows && rule.timeWindows.length > 0 ? JSON.stringify(rule.timeWindows) : null,
            rule.weekdaysMask,
            rule.enabled ? 1 : 0,
            rule.exemptTotal ? 1 : 0,
          );
        }
      }
    });

    const { bundle, delivered } = commitPolicyChange(device.id, request.claims.userId, 'policy.bundle', {
      listMode,
      itemCount: Array.isArray(items) ? items.length : undefined,
      ruleCount: normalizedRules ? normalizedRules.value.length : undefined,
    });

    return { ...bundle, delivered };
  });

  /** 被控端：一次性拉取完整策略包 */
  fastify.get('/api/policy', { preHandler: fastify.requireChild }, async (request, reply) => {
    const deviceId = Number(request.claims.deviceId);
    const device = one('SELECT id FROM devices WHERE id = ?', deviceId);
    if (!device) {
      return reply.code(404).send({ error: 'device_not_found', message: '设备不存在，请重新配对' });
    }
    return loadPolicyBundle(deviceId);
  });
}
