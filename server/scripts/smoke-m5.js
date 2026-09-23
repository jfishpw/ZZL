import WebSocket from 'ws';

/**
 * M5 端到端冒烟测试：离线密码备份、远程重置、密码尝试上报、设备加固状态。
 *
 * 与 smoke.js / smoke-m4.js 分开的理由：M5 的用例围绕"防绕过"这一主题，
 * 它们的断言彼此相关（例如"密码随状态对账下发"必须先有密码备份），
 * 拆开跑能更清楚地定位是哪一层出了问题。
 *
 * 用法：先启动服务，再执行 npm run smoke:m5
 */

const BASE = process.env.SMOKE_BASE ?? 'http://127.0.0.1:8080';
const WS_BASE = BASE.replace(/^http/, 'ws');

let passed = 0;
let failed = 0;

function check(label, condition, extra) {
  if (condition) {
    passed += 1;
    console.log(`  ✓ ${label}`);
  } else {
    failed += 1;
    console.log(`  ✗ ${label}`);
    if (extra !== undefined) console.log(`      实际：${JSON.stringify(extra)}`);
  }
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function api(method, path, { token, body } = {}) {
  const headers = {};
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (token) headers.authorization = `Bearer ${token}`;

  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let data;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = text;
  }
  return { status: res.status, data };
}

function openSocket(token) {
  return new Promise((resolve, reject) => {
    const messages = [];
    const waiters = [];
    const socket = new WebSocket(`${WS_BASE}/ws?token=${encodeURIComponent(token)}`);

    socket.on('message', (raw) => {
      let msg;
      try {
        msg = JSON.parse(raw.toString());
      } catch {
        return;
      }
      messages.push(msg);
      for (const waiter of [...waiters]) {
        if (waiter.predicate(msg)) {
          waiters.splice(waiters.indexOf(waiter), 1);
          clearTimeout(waiter.timer);
          waiter.resolve(msg);
        }
      }
    });

    socket.on('open', () => {
      socket.messages = messages;
      socket.waitFor = (predicate, timeoutMs = 6000) =>
        new Promise((res, rej) => {
          const found = messages.find(predicate);
          if (found) {
            res(found);
            return;
          }
          const waiter = { predicate, resolve: res };
          waiter.timer = setTimeout(() => {
            const index = waiters.indexOf(waiter);
            if (index >= 0) waiters.splice(index, 1);
            rej(new Error('等待事件超时'));
          }, timeoutMs);
          waiters.push(waiter);
        });
      resolve(socket);
    });

    socket.on('error', reject);
  });
}

function uniqueName(prefix) {
  return `${prefix}_${Math.random().toString(36).slice(2, 10)}`;
}

/** 造一份看起来合法的 PBKDF2 结果。服务端不验证它，只存不算。 */
function fakePin(level, hint) {
  return {
    level,
    hash: Buffer.from(`hash-${level}-${Math.random()}`).toString('base64'),
    salt: Buffer.from(`salt-${level}-${Math.random()}`).toString('base64'),
    iterations: 120_000,
    hint: hint ?? null,
  };
}

async function main() {
  console.log(`\n掌中灵 M5 冒烟测试  →  ${BASE}\n`);

  /* ================= 准备 ================= */

  console.log('[1] 账号与配对');
  const username = uniqueName('m5');
  const password = 'smoke-pass-1234';

  const reg = await api('POST', '/api/auth/register', { body: { username, password } });
  check('注册成功', reg.status === 201, reg.data);

  const login = await api('POST', '/api/auth/login', { body: { username, password } });
  const parentToken = login.data?.token;
  check('登录拿到家长令牌', login.status === 200 && !!parentToken, login.data);

  const codeA = await api('POST', '/api/pair/code', { token: parentToken });
  const claimA = await api('POST', '/api/pair/claim', {
    body: { code: codeA.data?.code, childUuid: uniqueName('uuid-m5'), name: '加固测试机', model: 'M5', androidVer: 33 },
  });
  const deviceId = claimA.data?.deviceId;
  const childToken = claimA.data?.token;
  check('设备配对成功', claimA.status === 200 && !!deviceId && !!childToken, claimA.data);

  /* ================= 初始状态：未加固 ================= */

  console.log('\n[2] 初始加固状态（应当是最弱的一档）');

  const initialHardening = await api('GET', `/api/devices/${deviceId}/hardening`, { token: parentToken });
  check('可读取加固状态', initialHardening.status === 200, initialHardening.data);
  check('未激活任何模式时归一化为 none',
    initialHardening.data?.effectiveAdminMode === 'none', initialHardening.data?.effectiveAdminMode);
  check('返回当前模式的能力矩阵',
    Array.isArray(initialHardening.data?.capabilities?.can) &&
      initialHardening.data.capabilities.can.length > 0,
    initialHardening.data?.capabilities);
  check('能力矩阵明确写出「可被直接卸载」这一风险',
    initialHardening.data?.capabilities?.limits?.some((item) => item.includes('卸载')),
    initialHardening.data?.capabilities?.limits);
  check('返回全部三档模式供对比',
    ['device_owner', 'device_admin', 'none'].every((mode) => mode in (initialHardening.data?.allModes ?? {})),
    Object.keys(initialHardening.data?.allModes ?? {}));
  check('Device Owner 激活命令已就绪',
    initialHardening.data?.activationHint?.deviceOwner?.[0]?.includes('set-device-owner'),
    initialHardening.data?.activationHint);
  check('未设置密码时 pinReady 为 false',
    initialHardening.data?.pinReady === false, initialHardening.data?.pinReady);

  const initialPins = await api('GET', `/api/devices/${deviceId}/pins`, { token: parentToken });
  check('密码概览可读且为空', initialPins.status === 200 && initialPins.data?.levels?.length === 0, initialPins.data);
  check('密码概览包含三级能力说明',
    initialPins.data?.capabilities?.['3']?.actions?.includes('退出管控'),
    initialPins.data?.capabilities);

  /* ================= 设备上报加固状态 ================= */

  console.log('\n[3] 设备上报加固状态');

  const heartbeat = await api('POST', `/api/devices/${deviceId}/heartbeat`, {
    token: childToken,
    body: {
      foregroundPackage: 'com.tencent.mm',
      remainingMs: 1_200_000,
      hardening: { adminMode: 'device_admin', deviceOwner: false, uninstallBlocked: true },
      keepalive: {
        batteryOptimized: true,
        exactAlarmAllowed: false,
        notificationEnabled: true,
        foregroundService: true,
        vendorWhitelistConfirmed: false,
        vendor: '小米 / 红米',
        vendorHint: '设置 → 应用设置 → 应用管理 → 掌中灵 → 省电策略选「无限制」',
      },
    },
  });
  check('心跳携带加固状态被接受', heartbeat.status === 200, heartbeat.data);

  const afterReport = await api('GET', `/api/devices/${deviceId}/hardening`, { token: parentToken });
  check('加固模式已更新为设备管理器模式',
    afterReport.data?.effectiveAdminMode === 'device_admin', afterReport.data?.effectiveAdminMode);
  check('阻止卸载状态已上报', afterReport.data?.uninstallBlocked === true, afterReport.data);
  check('能力矩阵切换为设备管理器档',
    afterReport.data?.capabilities?.strength === '防普通儿童', afterReport.data?.capabilities);
  check('明确写出「可被取消激活绕过」这一限制',
    afterReport.data?.capabilities?.limits?.some((item) => item.includes('取消激活')),
    afterReport.data?.capabilities?.limits);

  const deviceList = await api('GET', '/api/devices', { token: parentToken });
  const listed = deviceList.data?.devices?.find((d) => d.id === deviceId);
  check('设备列表带出保活健康度',
    listed?.keepalive?.vendor === '小米 / 红米' && listed?.keepalive?.exactAlarmAllowed === false,
    listed?.keepalive);

  // 上报 Device Owner 后应切到最强档
  await api('POST', `/api/devices/${deviceId}/heartbeat`, {
    token: childToken,
    body: {
      hardening: { adminMode: 'device_owner', deviceOwner: true, uninstallBlocked: true },
    },
  });
  const ownerState = await api('GET', `/api/devices/${deviceId}/hardening`, { token: parentToken });
  check('上报 Device Owner 后切到最强档',
    ownerState.data?.effectiveAdminMode === 'device_owner' && ownerState.data?.deviceOwner === true,
    ownerState.data);
  check('最强档不再声称「可被卸载」',
    !ownerState.data?.capabilities?.limits?.some((item) => item.includes('取消激活')),
    ownerState.data?.capabilities?.limits);

  // 退回设备管理器，后续用例基于这一档
  await api('POST', `/api/devices/${deviceId}/heartbeat`, {
    token: childToken,
    body: { hardening: { adminMode: 'device_admin', deviceOwner: false, uninstallBlocked: true } },
  });

  /* ================= 密码上传与下发 ================= */

  console.log('\n[4] 离线密码上传与随状态下发');

  const badHash = await api('PUT', '/api/pins', {
    token: childToken,
    body: { levelCount: 3, level1: { hash: '', salt: 'x', iterations: 120_000 } },
  });
  check('缺少哈希被拒绝 (400)', badHash.status === 400, badHash.data);

  const badSalt = await api('PUT', '/api/pins', {
    token: childToken,
    body: { levelCount: 3, level1: { hash: 'x', salt: '', iterations: 120_000 } },
  });
  check('缺少盐值被拒绝 (400)', badSalt.status === 400, badSalt.data);

  const weakIterations = await api('PUT', '/api/pins', {
    token: childToken,
    body: { levelCount: 3, level1: { hash: 'x', salt: 'y', iterations: 100 } },
  });
  check('迭代次数过低被拒绝 (400)', weakIterations.status === 400, weakIterations.data);

  const upload = await api('PUT', '/api/pins', {
    token: childToken,
    body: {
      levelCount: 3,
      level1: fakePin(1, '常用那个'),
      level2: fakePin(2, null),
      level3: fakePin(3, '问妈妈'),
    },
  });
  check('三级密码上传成功', upload.status === 200 && upload.data?.pins?.levels?.length === 3, upload.data);
  check('返回版本号供设备记录', upload.data?.version === 1, upload.data?.version);
  check('盐与迭代次数一并保存（调参后旧密码仍可验证）',
    upload.data.pins.levels.every((l) => !!l.salt && l.iterations === 120_000),
    upload.data.pins.levels);
  check('提示语被保留', upload.data.pins.levels[0].hint === '常用那个', upload.data.pins.levels[0]);

  const pinsAfterUpload = await api('GET', `/api/devices/${deviceId}/pins`, { token: parentToken });
  check('控制端能看到三级都已设置', pinsAfterUpload.data?.levels?.length === 3, pinsAfterUpload.data);  check('控制端概览不含哈希（不回传备份内容）',
    pinsAfterUpload.data.levels.every((l) => l.hash === undefined),
    pinsAfterUpload.data.levels[0]);
  check('控制端概览含提示语与能力说明',
    pinsAfterUpload.data.levels[0].hint === '常用那个' && pinsAfterUpload.data.levels[0].actions.length > 0,
    pinsAfterUpload.data.levels[0]);

  const stateWithPins = await api('GET', '/api/device-state', { token: childToken });
  check('密码备份随设备状态对账下发（换机恢复靠它）',
    stateWithPins.data?.pins?.levels?.length === 3, stateWithPins.data?.pins);
  check('对账下发的备份含哈希与盐（设备端需要它来验证）',
    !!stateWithPins.data.pins.levels[0].hash && !!stateWithPins.data.pins.levels[0].salt,
    stateWithPins.data.pins.levels[0]);
  check('对账同时带出加固快照',
    stateWithPins.data?.hardening?.adminMode === 'device_admin', stateWithPins.data?.hardening);

  /* ================= 远程重置 ================= */

  console.log('\n[5] 家长远程重置密码');

  const childSocket = await openSocket(childToken);
  await delay(300);

  const badLevel = await api('POST', `/api/devices/${deviceId}/pins/reset`, {
    token: parentToken,
    body: { level: 5, clear: true },
  });
  check('非法级别被拒绝 (400)', badLevel.status === 400, badLevel.data);

  const missingEntry = await api('POST', `/api/devices/${deviceId}/pins/reset`, {
    token: parentToken,
    body: { level: 1 },
  });
  check('既没给新密码也没说清除，被拒绝 (400)', missingEntry.status === 400, missingEntry.data);

  const reset = await api('POST', `/api/devices/${deviceId}/pins/reset`, {
    token: parentToken,
    body: { level: 2, entry: fakePin(2, '新的提示') },
  });
  check('远程设定新密码成功', reset.status === 200, reset.data);
  check('版本号自增（设备端据此判断云端是否更新）', reset.data?.pins?.version === 2, reset.data?.pins?.version);
  check('提示语已更新',
    reset.data.pins.levels.find((l) => l.level === 2)?.hint === '新的提示',
    reset.data.pins.levels);

  const pushed = await childSocket.waitFor((m) => m.type === 'device_state_updated' && m.state?.pins?.version === 2);
  check('重置结果实时推送到设备', pushed.state.pins.version === 2, pushed.state.pins.version);
  check('推送里不含明文密码（服务端从不接触明文）',
    !JSON.stringify(pushed.state.pins).includes('newPassword'),
    'ok');

  const clear = await api('POST', `/api/devices/${deviceId}/pins/reset`, {
    token: parentToken,
    body: { level: 3, clear: true },
  });
  check('远程清除某一级成功', clear.status === 200, clear.data);
  check('清除后该级不再出现在概览中',
    clear.data.pins.levels.length === 2 && clear.data.pins.levels.every((l) => l.level !== 3),
    clear.data.pins.levels);

  const stateAfterClear = await api('GET', '/api/device-state', { token: childToken });
  check('对账结果与清除一致（不会因丢失推送而不一致）',
    stateAfterClear.data?.pins?.levels?.length === 2, stateAfterClear.data?.pins?.levels);

  // 断开长连接再重置，才能验证"设备离线时"的分支
  childSocket.close();
  await delay(600);

  const offlineReset = await api('POST', `/api/devices/${deviceId}/pins/reset`, {
    token: parentToken,
    body: { level: 1, entry: fakePin(1, null) },
  });
  check('设备离线时重置仍成功并提示将延后生效',
    offlineReset.status === 200 && offlineReset.data?.delivered === false,
    offlineReset.data);
  check('离线重置的提示文案说明了「期间旧密码仍可用」',
    offlineReset.data?.notice?.includes('旧密码') === true, offlineReset.data?.notice);

  const offlineState = await api('GET', '/api/device-state', { token: childToken });
  check('离线期间重置的结果仍会随下次对账下发（不会丢）',
    offlineState.data?.pins?.levels?.length === 2, offlineState.data?.pins?.levels);

  /* ================= 密码尝试记录 ================= */

  console.log('\n[6] 密码尝试记录（含失败）');

  const now = Date.now();
  const attempts = [
    { clientKey: uniqueName('a'), level: 1, success: false, source: 'overlay', ts: now - 30_000 },
    { clientKey: uniqueName('a'), level: 1, success: false, source: 'overlay', ts: now - 25_000 },
    { clientKey: uniqueName('a'), level: 3, success: false, source: 'corner', ts: now - 20_000 },
    { clientKey: uniqueName('a'), level: 3, success: true, source: 'corner', ts: now - 10_000 },
  ];
  const report = await api('POST', '/api/pin-attempts', { token: childToken, body: { attempts } });
  check('批量上报 4 条尝试记录', report.status === 200 && report.data?.accepted === 4, report.data);

  const repost = await api('POST', '/api/pin-attempts', { token: childToken, body: { attempts } });
  check('重复上报被幂等去重',
    repost.data?.accepted === 0 && repost.data?.duplicated === 4, repost.data);

  const invalidAttempt = await api('POST', '/api/pin-attempts', {
    token: childToken,
    body: { attempts: [{ level: 9, ts: now, success: false }] },
  });
  check('非法级别被拒绝计数', invalidAttempt.data?.rejected === 1, invalidAttempt.data);

  const attemptList = await api('GET', `/api/devices/${deviceId}/pin-attempts`, { token: parentToken });
  check('家长可查看尝试记录', attemptList.data?.attempts?.length === 4, attemptList.data?.attempts?.length);
  check('失败次数单独统计', attemptList.data?.failedTotal === 3, attemptList.data?.failedTotal);
  check('有失败尝试时置为可疑（界面据此提醒）', attemptList.data?.suspicious === true, attemptList.data);
  check('成功尝试也被记录（孩子试对了同样要可见）',
    attemptList.data.attempts.some((a) => a.success === true), attemptList.data?.attempts);
  check('记录带入口来源（能区分从哪里试的）',
    attemptList.data.attempts.some((a) => a.source === 'corner'),
    attemptList.data?.attempts);

  const parentNotified = await openSocket(parentToken);
  await api('POST', '/api/pin-attempts', {
    token: childToken,
    body: {
      attempts: [{ clientKey: uniqueName('a'), level: 2, success: false, source: 'dialer', ts: Date.now() }],
    },
  });
  const alert = await parentNotified.waitFor((m) => m.type === 'pin_attempt_failed');
  check('失败尝试实时推送给家长（不必等他下次打开 App）',
    alert.level === 2 && alert.source === 'dialer', alert);
  check('告警带设备名（多设备时家长能分辨）', alert.deviceName === '加固测试机', alert.deviceName);

  /* ================= 权限边界 ================= */

  console.log('\n[7] 权限边界');

  const childOnPins = await api('GET', `/api/devices/${deviceId}/pins`, { token: childToken });
  check('被控端令牌不能查看控制端的密码概览 (403)', childOnPins.status === 403, childOnPins.data);

  const parentOnOwnPins = await api('GET', '/api/pins', { token: parentToken });
  check('家长令牌不能冒充被控端读取密码备份 (403)', parentOnOwnPins.status === 403, parentOnOwnPins.data);

  const parentOnUpload = await api('PUT', '/api/pins', {
    token: parentToken,
    body: { levelCount: 3 },
  });
  check('家长令牌不能冒充被控端上传密码 (403)', parentOnUpload.status === 403, parentOnUpload.data);

  const parentOnAttempts = await api('POST', '/api/pin-attempts', {
    token: parentToken,
    body: { attempts: [] },
  });
  check('家长令牌不能冒充被控端上报尝试 (403)', parentOnAttempts.status === 403, parentOnAttempts.data);

  const childOnReset = await api('POST', `/api/devices/${deviceId}/pins/reset`, {
    token: childToken,
    body: { level: 1, clear: true },
  });
  check('被控端令牌不能自行重置密码 (403)', childOnReset.status === 403, childOnReset.data);

  const childOnHardening = await api('GET', `/api/devices/${deviceId}/hardening`, { token: childToken });
  check('被控端令牌不能查看加固面板 (403)', childOnHardening.status === 403, childOnHardening.data);

  /* ================= 跨设备隔离 ================= */

  console.log('\n[8] 跨设备隔离');

  const codeB = await api('POST', '/api/pair/code', { token: parentToken });
  const claimB = await api('POST', '/api/pair/claim', {
    body: { code: codeB.data?.code, childUuid: uniqueName('uuid-m5b'), name: '另一台', model: 'M5B', androidVer: 33 },
  });
  const otherDeviceId = claimB.data?.deviceId;
  const otherChildToken = claimB.data?.token;

  const otherPins = await api('GET', `/api/devices/${otherDeviceId}/pins`, { token: parentToken });
  check('另一台设备的密码列表为空（互不影响）',
    otherPins.data?.levels?.length === 0, otherPins.data);

  const crossState = await api('GET', '/api/device-state', { token: otherChildToken });
  check('另一台设备对账拿不到别家的密码',
    crossState.data?.pins === null || crossState.data?.pins?.levels?.length === 0,
    crossState.data?.pins);

  const crossUpload = await api('PUT', '/api/pins', {
    token: otherChildToken,
    body: { levelCount: 3, level1: fakePin(1, null) },
  });
  check('另一台设备可独立上传自己的密码', crossUpload.status === 200, crossUpload.data);

  const originalStillIntact = await api('GET', `/api/devices/${deviceId}/pins`, { token: parentToken });
  check('原设备的密码未被影响', originalStillIntact.data?.levels?.length === 2, originalStillIntact.data?.levels);

  const crossAttempt = await api('POST', '/api/pin-attempts', {
    token: otherChildToken,
    body: {
      attempts: [{ clientKey: uniqueName('a'), level: 1, success: false, source: 'overlay', ts: Date.now() }],
    },
  });
  check('另一台设备的尝试记录独立计数', crossAttempt.data?.accepted === 1, crossAttempt.data);

  const originalAttempts = await api('GET', `/api/devices/${deviceId}/pin-attempts`, { token: parentToken });
  check('原设备的尝试记录不受影响', originalAttempts.data?.failedTotal === 4, originalAttempts.data?.failedTotal);

  parentNotified.close();

  console.log(`\n结果：${passed} 项通过，${failed} 项失败\n`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((error) => {
  console.error('\n冒烟测试异常终止：', error);
  process.exit(1);
});
