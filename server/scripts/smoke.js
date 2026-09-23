/**
 * 端到端冒烟测试
 *   M1：健康检查 → 注册 → 登录 → 配对码 → 认领 → 设备列表 → 心跳 → WebSocket 双向
 *   M2：策略下发与拉取 → 使用记录批量上报（幂等）→ 日汇总与会话明细
 *
 * 用法：先启动服务（npm start），再执行 npm run smoke
 */
import WebSocket from 'ws';

const BASE = process.env.SMOKE_BASE ?? 'http://127.0.0.1:8080';
const WS_BASE = BASE.replace(/^http/, 'ws');

let passed = 0;
let failed = 0;

function check(label, condition, extra) {
  if (condition) {
    passed += 1;
    console.log(`  \u2713 ${label}`);
  } else {
    failed += 1;
    console.log(`  \u2717 ${label}${extra ? `  →  ${JSON.stringify(extra)}` : ''}`);
  }
}

async function api(method, path, { token, body } = {}) {
  const headers = {}
  if (token) headers.authorization = `Bearer ${token}`
  // 只有真的带请求体时才声明 content-type，
  // 否则 Fastify 会以 FST_ERR_CTP_EMPTY_JSON_BODY 拒绝
  if (body !== undefined) headers['content-type'] = 'application/json'

  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let data
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = text
  }
  return { status: res.status, data }
}

function waitForMessage(socket, predicate, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      socket.off('message', onMessage);
      reject(new Error('等待消息超时'));
    }, timeoutMs);

    function onMessage(raw) {
      let msg;
      try {
        msg = JSON.parse(raw.toString());
      } catch {
        return;
      }
      if (predicate(msg)) {
        clearTimeout(timer);
        socket.off('message', onMessage);
        resolve(msg);
      }
    }

    socket.on('message', onMessage);
  });
}

function openSocket(token) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${WS_BASE}/ws?token=${encodeURIComponent(token)}`);
    const timer = setTimeout(() => reject(new Error('WebSocket 连接超时')), 5000);
    socket.on('open', () => {
      clearTimeout(timer);
      resolve(socket);
    });
    socket.on('error', (err) => {
      clearTimeout(timer);
      reject(err);
    });
  });
}

async function main() {
  const suffix = Date.now().toString(36);
  const username = `smoke_${suffix}`;
  const password = 'test123456';

  console.log(`\n掌中灵中继服务 · 端到端冒烟测试  →  ${BASE}\n`);

  // 1. 健康检查
  console.log('[1/13] 健康检查');
  const health = await api('GET', '/api/health');
  check('服务存活', health.status === 200 && health.data?.ok === true, health.data);

  // 2. 注册
  console.log('[2/13] 注册家长账号');
  const reg = await api('POST', '/api/auth/register', { body: { username, password } });
  check('注册成功返回 201', reg.status === 201, reg.data);

  // 3. 登录
  console.log('[3/13] 登录');
  const login = await api('POST', '/api/auth/login', { body: { username, password } });
  const parentToken = login.data?.token;
  check('登录返回令牌', login.status === 200 && typeof parentToken === 'string', login.data);

  const badLogin = await api('POST', '/api/auth/login', { body: { username, password: 'wrong-pass' } });
  check('错误密码被拒绝 (401)', badLogin.status === 401, badLogin.data);

  // 4. 未授权访问
  console.log('[4/13] 鉴权保护');
  const noAuth = await api('GET', '/api/devices');
  check('无令牌访问设备列表被拒绝 (401)', noAuth.status === 401, noAuth.data);

  // 5. 生成配对码
  console.log('[5/13] 生成配对码');
  const codeRes = await api('POST', '/api/pair/code', { token: parentToken });
  const pairCode = codeRes.data?.code;
  check('配对码为 6 位数字', /^\d{6}$/.test(pairCode ?? ''), codeRes.data);

  // 6. 被控端认领
  console.log('[6/13] 被控端认领配对码');
  const childUuid = `smoke-device-${suffix}`;
  const claim = await api('POST', '/api/pair/claim', {
    body: {
      code: pairCode,
      childUuid,
      name: '测试平板',
      model: 'SmokePad',
      androidVer: 33,
    },
  });
  const childToken = claim.data?.token;
  const deviceId = claim.data?.deviceId;
  check('认领成功并返回设备令牌', claim.status === 200 && !!childToken && !!deviceId, claim.data);

  const reuse = await api('POST', '/api/pair/claim', { body: { code: pairCode, childUuid } });
  check('已使用的配对码不可重复认领 (409)', reuse.status === 409, reuse.data);

  // 7. 设备列表
  console.log('[7/13] 设备列表');
  const list = await api('GET', '/api/devices', { token: parentToken });
  check('列表包含刚绑定的设备', list.status === 200 && list.data?.devices?.length === 1, list.data);
  check('设备名称正确', list.data?.devices?.[0]?.name === '测试平板', list.data?.devices?.[0]);

  // 8. 心跳上报
  console.log('[8/13] 心跳上报与状态更新');
  const hb = await api('POST', `/api/devices/${deviceId}/heartbeat`, {
    token: childToken,
    body: {
      foregroundPackage: 'com.tencent.mm',
      remainingMs: 1_800_000,
      health: { accessibility: true, usageAccess: true, overlay: true, deviceAdmin: true },
    },
  });
  check('心跳成功', hb.status === 200, hb.data);

  const detail = await api('GET', `/api/devices/${deviceId}`, { token: parentToken });
  check('前台应用已更新', detail.data?.foregroundPackage === 'com.tencent.mm', detail.data);
  check('权限健康度已上报', detail.data?.health?.accessibility === true, detail.data?.health);

  const crossAccess = await api('GET', `/api/devices/${deviceId}`, { token: 'invalid.token.value' });
  check('伪造令牌被拒绝 (401)', crossAccess.status === 401, crossAccess.data);

  // 9. 策略下发与同步
  console.log('[9/13] 策略配置与同步');
  const policyUpdate = await api('PUT', `/api/devices/${deviceId}/policy`, {
    token: parentToken,
    body: { weekdayTotalMin: 1, weekendTotalMin: 5, resetHour: 6, enabled: true },
  });
  check('家长可更新策略', policyUpdate.status === 200 && policyUpdate.data?.weekdayTotalMin === 1, policyUpdate.data);
  check('策略版本自增到 2', policyUpdate.data?.version === 2, policyUpdate.data?.version);

  const policyForChild = await api('GET', '/api/policy', { token: childToken });
  check('被控端可拉取到同一份策略',
    policyForChild.data?.weekdayTotalMin === 1 && policyForChild.data?.resetHour === 6,
    policyForChild.data);

  const badPolicy = await api('PUT', `/api/devices/${deviceId}/policy`, {
    token: parentToken,
    body: { weekdayTotalMin: 99999 },
  });
  check('非法总时长被拒绝 (400)', badPolicy.status === 400, badPolicy.data);

  const crossPolicy = await api('GET', '/api/policy', { token: parentToken });
  check('家长令牌不能冒充被控端拉策略 (403)', crossPolicy.status === 403, crossPolicy.data);

  // 10. 使用记录上报与查询
  console.log('[10/13] 使用记录上报与查询');
  const dayKey = new Date().toISOString().slice(0, 10);
  const seedSessions = [
    { clientKey: `s-${suffix}-1`, packageName: 'com.tencent.mm', durationMs: 60000, dayKey },
    { clientKey: `s-${suffix}-2`, packageName: 'com.tencent.mm', durationMs: 30000, dayKey },
    { clientKey: `s-${suffix}-3`, packageName: 'com.android.chrome', durationMs: 60000, dayKey },
  ].map((s, i) => ({
    ...s,
    startTs: Date.now() - (300 - i * 60) * 1000,
    endTs: Date.now() - (300 - i * 60) * 1000 + s.durationMs,
  }));

  const report = await api('POST', `/api/devices/${deviceId}/usage/report`, {
    token: childToken,
    body: { sessions: seedSessions },
  });
  check('批量上报 3 条全部接收', report.status === 200 && report.data?.accepted === 3, report.data);

  const repost = await api('POST', `/api/devices/${deviceId}/usage/report`, {
    token: childToken,
    body: { sessions: seedSessions },
  });
  check('重复上报被幂等去重', repost.data?.accepted === 0 && repost.data?.duplicated === 3, repost.data);

  const invalidReport = await api('POST', `/api/devices/${deviceId}/usage/report`, {
    token: childToken,
    body: { sessions: [{ packageName: '', durationMs: -1, dayKey: 'bad' }] },
  });
  check('非法会话被拒绝计数', invalidReport.data?.rejected === 1, invalidReport.data);

  const daily = await api('GET', `/api/devices/${deviceId}/usage/daily?date=${dayKey}`, { token: parentToken });
  check('日汇总共 150 秒', daily.data?.totalMs === 150000, daily.data);
  check('按应用聚合为 2 项', daily.data?.apps?.length === 2, daily.data?.apps);
  check('微信累计 90 秒',
    daily.data?.apps?.find((a) => a.packageName === 'com.tencent.mm')?.totalMs === 90000,
    daily.data?.apps);

  const timeline = await api(
    'GET',
    `/api/devices/${deviceId}/usage/sessions?date=${dayKey}&packageName=com.tencent.mm`,
    { token: parentToken },
  );
  check('会话明细可按应用过滤', timeline.data?.sessions?.length === 2, timeline.data?.sessions?.length);

  const fakeReport = await api('POST', `/api/devices/${deviceId}/usage/report`, {
    token: parentToken,
    body: { sessions: seedSessions },
  });
  check('家长令牌不能冒充被控端上报 (403)', fakeReport.status === 403, fakeReport.data);

  // 11. 黑白名单与逐应用规则
  console.log('[11/13] 黑白名单与逐应用规则');

  const lists = await api('PUT', `/api/devices/${deviceId}/policy/lists`, {
    token: parentToken,
    body: {
      listMode: 'whitelist',
      items: [
        { packageName: 'com.tencent.mm', appLabel: '微信' },
        { packageName: 'com.tencent.mobileqq', appLabel: 'QQ' },
      ],
    },
  });
  check('名单可整份替换', lists.status === 200 && lists.data?.listItems?.length === 2, lists.data?.listItems);
  check('名单模式已切为白名单', lists.data?.listMode === 'whitelist', lists.data?.listMode);
  check('策略版本自增到 3', lists.data?.version === 3, lists.data?.version);

  const rules = await api('PUT', `/api/devices/${deviceId}/policy/app-rules`, {
    token: parentToken,
    body: {
      rules: [
        {
          packageName: 'com.tencent.mm',
          appLabel: '微信',
          dailyLimitMin: 40,
          timeWindows: [{ start: '19:00', end: '20:00' }, { start: '22:00', end: '07:00' }],
          weekdaysMask: 127,
          enabled: true,
          exemptTotal: true,
        },
        {
          packageName: 'com.android.chrome',
          appLabel: '浏览器',
          dailyLimitMin: 0,
          timeWindows: [],
          weekdaysMask: 31,
          enabled: false,
        },
      ],
    },
  });
  check('逐应用规则可整份替换', rules.data?.appRules?.length === 2, rules.data?.appRules?.length);
  check(
    '时段（含跨零点 22:00-07:00）正确回读',
    rules.data?.appRules?.find((r) => r.packageName === 'com.tencent.mm')?.timeWindows?.length === 2,
    rules.data?.appRules?.find((r) => r.packageName === 'com.tencent.mm')?.timeWindows,
  );
  check(
    '生效星期掩码与启用状态保留',
    rules.data?.appRules?.find((r) => r.packageName === 'com.android.chrome')?.weekdaysMask === 31 &&
      rules.data?.appRules?.find((r) => r.packageName === 'com.android.chrome')?.enabled === false,
    rules.data?.appRules?.find((r) => r.packageName === 'com.android.chrome'),
  );
  check(
    '「用时不计入总时长」开关正确往返',
    rules.data?.appRules?.find((r) => r.packageName === 'com.tencent.mm')?.exemptTotal === true,
    rules.data?.appRules?.find((r) => r.packageName === 'com.tencent.mm'),
  );
  check(
    '没发这个字段的规则默认按"不计入关闭"处理',
    rules.data?.appRules?.find((r) => r.packageName === 'com.android.chrome')?.exemptTotal === false,
    rules.data?.appRules?.find((r) => r.packageName === 'com.android.chrome')?.exemptTotal,
  );

  const badHour = await api('PUT', `/api/devices/${deviceId}/policy/app-rules`, {
    token: parentToken,
    body: { rules: [{ packageName: 'x', timeWindows: [{ start: '25:00', end: '26:00' }] }] },
  });
  check('非法小时数被拒绝 (400)', badHour.status === 400, badHour.data);

  const sameEdge = await api('PUT', `/api/devices/${deviceId}/policy/app-rules`, {
    token: parentToken,
    body: { rules: [{ packageName: 'x', timeWindows: [{ start: '10:00', end: '10:00' }] }] },
  });
  check('起止相同的时段被拒绝 (400)', sameEdge.status === 400, sameEdge.data);

  const dupRule = await api('PUT', `/api/devices/${deviceId}/policy/app-rules`, {
    token: parentToken,
    body: { rules: [{ packageName: 'a' }, { packageName: 'a' }] },
  });
  check('同一应用重复规则被拒绝 (400)', dupRule.status === 400, dupRule.data);

  const bundleForChild = await api('GET', '/api/policy', { token: childToken });
  check(
    '被控端一次拿到完整策略包（策略 + 名单 + 规则）',
    bundleForChild.data?.listItems?.length === 2 &&
      bundleForChild.data?.appRules?.length === 2 &&
      bundleForChild.data?.listMode === 'whitelist',
    { lists: bundleForChild.data?.listItems?.length, rules: bundleForChild.data?.appRules?.length },
  );

  // 原子替换：应用管控界面「保存」走的就是这个接口
  const atomic = await api('PUT', `/api/devices/${deviceId}/policy/bundle`, {
    token: parentToken,
    body: {
      listMode: 'blacklist',
      items: [
        { packageName: 'com.tencent.mm', appLabel: '微信' },
        { packageName: 'com.tencent.mobileqq', appLabel: 'QQ' },
      ],
      rules: [
        {
          packageName: 'com.tencent.mm',
          appLabel: '微信',
          dailyLimitMin: 30,
          timeWindows: [{ start: '08:00', end: '22:00' }],
          weekdaysMask: 127,
          enabled: true,
        },
        { packageName: 'com.android.chrome', appLabel: '浏览器', dailyLimitMin: 20 },
      ],
    },
  });
  check(
    '原子接口一次替换名单与规则',
    atomic.status === 200 &&
      atomic.data?.listItems?.length === 2 &&
      atomic.data?.appRules?.length === 2,
    { lists: atomic.data?.listItems?.length, rules: atomic.data?.appRules?.length },
  );
  check('原子接口同时切换了名单模式', atomic.data?.listMode === 'blacklist', atomic.data?.listMode);

  // 关键：有一条规则不合法时，整组都必须不生效（不能只写进去一半）
  const atomicInvalid = await api('PUT', `/api/devices/${deviceId}/policy/bundle`, {
    token: parentToken,
    body: {
      listMode: 'whitelist',
      items: [{ packageName: 'com.some.other', appLabel: '不该被写入' }],
      rules: [{ packageName: 'x', timeWindows: [{ start: '99:00', end: '10:00' }] }],
    },
  });
  check('原子接口的非法规则被整体拒绝 (400)', atomicInvalid.status === 400, atomicInvalid.data);

  const afterRejected = await api('GET', `/api/devices/${deviceId}/policy/bundle`, { token: parentToken });
  check(
    '整组校验失败时名单、规则、模式三者都不变',
    afterRejected.data?.listItems?.length === 2 &&
      afterRejected.data?.appRules?.length === 2 &&
      afterRejected.data?.listMode === 'blacklist' &&
      !afterRejected.data?.listItems?.some((i) => i.packageName === 'com.some.other'),
    {
      lists: afterRejected.data?.listItems?.length,
      rules: afterRejected.data?.appRules?.length,
      mode: afterRejected.data?.listMode,
    },
  );

  // 12. 已安装应用清单
  console.log('[12/13] 已安装应用清单');

  const appReport = await api('POST', `/api/devices/${deviceId}/installed-apps`, {
    token: childToken,
    body: {
      apps: [
        { packageName: 'com.tencent.mm', appLabel: '微信', isSystem: false },
        { packageName: 'com.android.chrome', appLabel: 'Chrome', isSystem: false },
        { packageName: 'com.android.settings', appLabel: '设置', isSystem: true },
      ],
    },
  });
  check('被控端可上报应用清单', appReport.status === 200 && appReport.data?.accepted === 3, appReport.data);

  const appList = await api('GET', `/api/devices/${deviceId}/installed-apps`, { token: parentToken });
  check('控制端可读取应用清单', appList.data?.apps?.length === 3, appList.data?.apps?.length);
  check(
    '系统应用标记保留',
    appList.data?.apps?.find((a) => a.packageName === 'com.android.settings')?.isSystem === true,
    appList.data?.apps,
  );

  const appResend = await api('POST', `/api/devices/${deviceId}/installed-apps`, {
    token: childToken,
    body: { apps: [{ packageName: 'com.tencent.mm', appLabel: '微信' }] },
  });
  check('重复上报为整份替换而非累加', appResend.data?.accepted === 1, appResend.data);

  const appListAfter = await api('GET', `/api/devices/${deviceId}/installed-apps`, { token: parentToken });
  check('替换后只剩 1 条', appListAfter.data?.apps?.length === 1, appListAfter.data?.apps?.length);

  const fakeAppReport = await api('POST', `/api/devices/${deviceId}/installed-apps`, {
    token: parentToken,
    body: { apps: [] },
  });
  check('家长令牌不能冒充被控端上报 (403)', fakeAppReport.status === 403, fakeAppReport.data);

  // 13. WebSocket
  console.log('[13/13] WebSocket 双向通道');
  const parentSocket = await openSocket(parentToken);
  const parentReady = await waitForMessage(parentSocket, (m) => m.type === 'ready');
  check('控制端收到 ready', parentReady.role === 'parent', parentReady);

  const statusPromise = waitForMessage(parentSocket, (m) => m.type === 'device_status' && m.online === true);

  const childSocket = await openSocket(childToken);
  const childReady = await waitForMessage(childSocket, (m) => m.type === 'ready');
  check('被控端收到 ready', childReady.role === 'child', childReady);

  const status = await statusPromise;
  check('控制端实时收到设备上线事件', status.deviceId === deviceId, status);

  // 被控端上报状态 → 控制端应实时收到
  const livePromise = waitForMessage(parentSocket, (m) => m.type === 'device_status' && m.foregroundPackage === 'com.tencent.mm');
  childSocket.send(JSON.stringify({ type: 'status', foregroundPackage: 'com.tencent.mm', remainingMs: 900_000 }));
  const live = await livePromise;
  check('前台应用变更实时推送至控制端', live.foregroundPackage === 'com.tencent.mm', live);

  // 权限丢失告警
  const lostPromise = waitForMessage(parentSocket, (m) => m.type === 'permission_lost');
  childSocket.send(JSON.stringify({ type: 'permission_lost', missing: ['accessibility'] }));
  const lost = await lostPromise;
  check('权限被取消时控制端收到告警', lost.missing?.includes('accessibility'), lost);

  // 策略变更应实时推送到被控端，且携带完整策略包
  const policyPushPromise = waitForMessage(childSocket, (m) => m.type === 'policy_updated');
  await api('PUT', `/api/devices/${deviceId}/policy`, { token: parentToken, body: { weekdayTotalMin: 2 } });
  const pushed = await policyPushPromise;
  check('策略变更实时推送到被控端', pushed.bundle?.weekdayTotalMin === 2, pushed.bundle?.weekdayTotalMin);
  check(
    '推送的策略包包含名单与逐应用规则',
    pushed.bundle?.listItems?.length === 2 && pushed.bundle?.appRules?.length === 2,
    { lists: pushed.bundle?.listItems?.length, rules: pushed.bundle?.appRules?.length },
  );

  parentSocket.close();
  childSocket.close();

  // 删除设备与重新配对：被控端解绑后，控制端要能删除离线条目；
  // 同一台设备（同 childUuid）重新认领 —— 记录已删就新增，还在就复用更新
  console.log('[14] 删除设备与重新配对');
  const del = await api('DELETE', `/api/devices/${deviceId}`, { token: parentToken });
  check('删除设备成功', del.status === 200, del.data);

  const delList = await api('GET', '/api/devices', { token: parentToken });
  check(
    '删除后列表不再包含该设备',
    delList.status === 200 && !delList.data?.devices?.some((d) => d.id === deviceId),
    delList.data,
  );

  const codeAfterDelete = await api('POST', '/api/pair/code', { token: parentToken });
  const reclaim = await api('POST', '/api/pair/claim', {
    body: {
      code: codeAfterDelete.data?.code,
      childUuid,
      name: '测试平板',
      model: 'SmokePad',
      androidVer: 33,
    },
  });
  const newDeviceId = reclaim.data?.deviceId;
  check(
    '删除后同一设备重新认领 → 新增记录',
    reclaim.status === 200 && !!newDeviceId && newDeviceId !== deviceId,
    reclaim.data,
  );

  const codeAgain = await api('POST', '/api/pair/code', { token: parentToken });
  const reclaimAgain = await api('POST', '/api/pair/claim', {
    body: { code: codeAgain.data?.code, childUuid, name: '测试平板改名' },
  });
  check(
    '记录还在时重新认领 → 复用原条目而非新增',
    reclaimAgain.status === 200 && reclaimAgain.data?.deviceId === newDeviceId,
    reclaimAgain.data,
  );

  const renamedList = await api('GET', '/api/devices', { token: parentToken });
  check(
    '复用记录的名称已被更新',
    renamedList.data?.devices?.[0]?.name === '测试平板改名',
    renamedList.data?.devices?.[0],
  );

  console.log(`\n结果：${passed} 项通过，${failed} 项失败\n`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error('\n冒烟测试异常终止：', err.message);
  process.exit(1);
});
