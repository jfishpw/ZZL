import WebSocket from 'ws';

/**
 * M4 端到端冒烟测试：离线指令补发、临时授权对账、加时申请与折扣审批、使用报告。
 *
 * 与 smoke.js 分开的理由：M1–M3 的用例是回归基线，需要保持稳定；
 * M4 引入了一批「有时间语义」的接口（TTL、有效期、审批时限），
 * 它们的用例更长且需要多设备协作，混在一起会让基线变得脆弱。
 *
 * 用法：先启动服务，再执行 npm run smoke:m4
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
  // 只有真的带请求体时才发 content-type：空体 + JSON 头会被 Fastify 正确拒绝
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

/** 建立长连接，并把收到的消息缓存下来供后续断言 */
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

async function main() {
  console.log(`\n掌中灵 M4 冒烟测试  →  ${BASE}\n`);

  /* ================= 准备：一个家长 + 两台设备 ================= */

  console.log('[1] 账号与配对');
  const username = uniqueName('m4');
  const password = 'smoke-pass-1234';

  const reg = await api('POST', '/api/auth/register', { body: { username, password } });
  check('注册成功', reg.status === 201, reg.data);

  const login = await api('POST', '/api/auth/login', { body: { username, password } });
  const parentToken = login.data?.token;
  check('登录拿到家长令牌', login.status === 200 && !!parentToken, login.data);

  const codeA = await api('POST', '/api/pair/code', { token: parentToken });
  const claimA = await api('POST', '/api/pair/claim', {
    body: { code: codeA.data?.code, childUuid: uniqueName('uuid-a'), name: '平板A', model: 'M4A', androidVer: 33 },
  });
  const deviceA = claimA.data?.deviceId;
  const childTokenA = claimA.data?.token;
  check('设备 A 配对成功', claimA.status === 200 && !!deviceA && !!childTokenA, claimA.data);

  const codeB = await api('POST', '/api/pair/code', { token: parentToken });
  const claimB = await api('POST', '/api/pair/claim', {
    body: { code: codeB.data?.code, childUuid: uniqueName('uuid-b'), name: '平板B', model: 'M4B', androidVer: 33 },
  });
  const deviceB = claimB.data?.deviceId;
  const childTokenB = claimB.data?.token;
  check('设备 B 配对成功（用于多设备用例）', claimB.status === 200 && !!deviceB && !!childTokenB, claimB.data);

  /* ================= 指令队列：在线即时下发与回执 ================= */

  console.log('\n[2] 在线指令下发与回执');
  const parentSocket = await openSocket(parentToken);
  const childSocketA = await openSocket(childTokenA);
  await delay(300);

  const lockRes = await api('POST', `/api/devices/${deviceA}/lock-now`, { token: parentToken });
  check('立即锁定成功且实时送达', lockRes.status === 200 && lockRes.data?.delivered === true, lockRes.data);

  const lockCommand = await childSocketA.waitFor((m) => m.type === 'command' && m.command?.type === 'immediate_lock');
  check('被控端实时收到 immediate_lock 指令', !!lockCommand?.command?.commandId, lockCommand);

  const stateAfterLock = await childSocketA.waitFor(
    (m) => m.type === 'device_state_updated' && m.state?.locked === true,
  );
  check('被控端同时收到锁定状态（可对账恢复）', stateAfterLock.state.locked === true, stateAfterLock.state);

  const deviceDetail = await api('GET', `/api/devices/${deviceA}`, { token: parentToken });
  check('设备详情标记为已锁定', deviceDetail.data?.locked === true, deviceDetail.data);

  // 用「长连接回执」通道：服务端应在控制端侧产生 command_result
  childSocketA.send(JSON.stringify({
    type: 'command_result',
    commandId: lockCommand.command.commandId,
    status: 'done',
    detail: { applied: true },
  }));

  const commandResult = await parentSocket.waitFor((m) => m.type === 'command_result');
  check('控制端收到指令回执', commandResult.command?.status === 'done', commandResult.command);
  check('回执被持久化（结果可查）', commandResult.command?.executedAt > 0, commandResult.command);

  const commandsList = await api('GET', `/api/devices/${deviceA}/commands`, { token: parentToken });
  check('指令历史包含刚执行的指令', 
    commandsList.data?.commands?.some((c) => c.commandId === lockCommand.command.commandId && c.status === 'done'),
    commandsList.data?.commands?.slice(0, 3));

  // 重复回执不能改写首次结果
  const reAck = await api('POST', `/api/commands/${lockCommand.command.commandId}/ack`, {
    token: childTokenA,
    body: { status: 'failed', detail: { reason: '重试回执' } },
  });
  check('已终态指令不被重复回执覆盖', reAck.data?.command?.status === 'done', reAck.data?.command);

  const unlockRes = await api('POST', `/api/devices/${deviceA}/unlock`, { token: parentToken });
  check('解除锁定成功', unlockRes.status === 200, unlockRes.data);
  await childSocketA.waitFor((m) => m.type === 'command' && m.command?.type === 'clear_lock');

  const deviceAfterUnlock = await api('GET', `/api/devices/${deviceA}`, { token: parentToken });
  check('设备详情恢复为未锁定', deviceAfterUnlock.data?.locked === false, deviceAfterUnlock.data);

  /* ================= 离线指令补发 ================= */

  console.log('\n[3] 离线下发 → 上线补发');
  childSocketA.close();
  await delay(600);

  const offlineLock = await api('POST', `/api/devices/${deviceA}/lock-now`, { token: parentToken });
  check('设备离线时指令未丢失，标记为待补发', offlineLock.status === 200 && offlineLock.data?.delivered === false, offlineLock.data);

  const pendingBefore = await api('GET', '/api/commands/pending', { token: childTokenA });
  check('离线设备仍可拉取到待执行指令（HTTP 通道）',
    pendingBefore.data?.commands?.some((c) => c.type === 'immediate_lock'), pendingBefore.data?.commands);
  const resent = pendingBefore.data.commands.find((c) => c.type === 'immediate_lock');
  check('补发指令带下发时间与有效期字段',
    resent.createdAt > 0 && resent.expireAt === null, resent);

  const ackResent = await api('POST', `/api/commands/${resent.commandId}/ack`, {
    token: childTokenA,
    body: { status: 'done', detail: { by: 'reconnect' } },
  });
  check('补发指令可正常回执', ackResent.data?.command?.status === 'done', ackResent.data?.command);

  // 重新上线后服务端应主动提示「有 N 条待执行」与当前状态
  await api('POST', `/api/devices/${deviceA}/unlock`, { token: parentToken });
  const childSocketA2 = await openSocket(childTokenA);
  const nudge = await childSocketA2.waitFor((m) => m.type === 'commands_pending' || m.type === 'device_state_updated');
  check('上线后服务端主动推送待执行提示或设备状态',
    nudge.type === 'commands_pending' || nudge.type === 'device_state_updated', nudge.type);

  const offlineUnlock = await api('POST', `/api/devices/${deviceA}/lock-now`, { token: parentToken });
  check('在线设备指令立即送达（对照组）', offlineUnlock.data?.delivered === true, offlineUnlock.data);

  const requestApps = await api('POST', `/api/devices/${deviceA}/installed-apps/refresh`, { token: parentToken });
  check('请求重报应用清单走指令队列', requestApps.data?.delivered === true && !!requestApps.data?.commandId, requestApps.data);
  const appsCommand = await childSocketA2.waitFor(
    (m) => m.type === 'command' && m.command?.type === 'request_installed_apps',
  );
  check('被控端收到 request_installed_apps', !!appsCommand.command.commandId, appsCommand);

  await api('POST', `/api/devices/${deviceA}/unlock`, { token: parentToken });
  await api('GET', `/api/commands/pending`, { token: childTokenA });

  /* ================= 临时授权（对账语义） ================= */

  console.log('\n[4] 临时授权与设备状态对账');

  const badScope = await api('POST', `/api/devices/${deviceA}/grants`, {
    token: parentToken,
    body: { scope: 'nonsense' },
  });
  check('非法授权范围被拒绝 (400)', badScope.status === 400, badScope.data);

  const badApp = await api('POST', `/api/devices/${deviceA}/grants`, {
    token: parentToken,
    body: { scope: 'app_allow' },
  });
  check('单应用放行缺少包名被拒绝 (400)', badApp.status === 400, badApp.data);

  const badExtra = await api('POST', `/api/devices/${deviceA}/grants`, {
    token: parentToken,
    body: { scope: 'total_add', extraMinutes: 0 },
  });
  check('加时时长为 0 被拒绝 (400)', badExtra.status === 400, badExtra.data);

  const grantAdd = await api('POST', `/api/devices/${deviceA}/grants`, {
    token: parentToken,
    body: { scope: 'total_add', extraMinutes: 30, ttlMinutes: 180 },
  });
  check('创建加时授权成功', grantAdd.status === 200 && grantAdd.data?.grant?.extraMinutes === 30, grantAdd.data);
  check('加时授权绑定到当前额度日（跨归日点自动失效）',
    typeof grantAdd.data?.grant?.dayKey === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(grantAdd.data.grant.dayKey),
    grantAdd.data?.grant);

  const grantPush = await childSocketA2.waitFor(
    (m) => m.type === 'device_state_updated' && m.state?.grants?.length === 1,
  );
  check('被控端实时收到设备状态推送', grantPush.state.grants[0].scope === 'total_add', grantPush.state.grants);

  const grantApp = await api('POST', `/api/devices/${deviceA}/grants`, {
    token: parentToken,
    body: { scope: 'app_allow', packageName: 'com.tencent.mm', appLabel: '微信', ttlMinutes: 60 },
  });
  check('创建单应用放行授权成功', grantApp.status === 200 && grantApp.data?.grant?.packageName === 'com.tencent.mm', grantApp.data);

  // 放行的时长必须被尊重：家长说多久就是多久，不能被默认值覆盖
  const appTtlMinutes = Math.round(
    (grantApp.data.grant.expireAt - grantApp.data.grant.createdAt) / 60_000,
  );
  check('单应用放行按指定的时长生效', appTtlMinutes === 60, { appTtlMinutes });

  const grantUnlock = await api('POST', `/api/devices/${deviceA}/grants`, {
    token: parentToken,
    body: { scope: 'unlock', ttlMinutes: 30 },
  });
  check('创建临时总解封授权成功', grantUnlock.status === 200 && grantUnlock.data?.grant?.scope === 'unlock', grantUnlock.data);
  check('未指定时长时使用范围默认值', 
    Math.round((grantUnlock.data.grant.expireAt - grantUnlock.data.grant.createdAt) / 60_000) > 0,
    grantUnlock.data?.grant);

  const stateRes = await api('GET', '/api/device-state', { token: childTokenA });
  check('被控端对账拿到全部生效授权', stateRes.data?.grants?.length === 3, stateRes.data?.grants);
  check('对账响应包含锁定状态与状态版本',
    stateRes.data?.locked === false && stateRes.data?.stateVersion > 1, stateRes.data);

  const grantsList = await api('GET', `/api/devices/${deviceA}/grants`, { token: parentToken });
  check('授权列表区分生效项', grantsList.data?.active?.length === 3, grantsList.data?.active);

  const revoke = await api('DELETE', `/api/grants/${grantAdd.data.grant.id}`, { token: parentToken });
  check('撤销授权成功', revoke.status === 200, revoke.data);

  const afterRevoke = await childSocketA2.waitFor(
    (m) => m.type === 'device_state_updated' && m.state?.grants?.length === 2,
  );
  check('撤销后推送的状态只剩 2 条', afterRevoke.state.grants.length === 2, afterRevoke.state.grants);

  const stateAfterRevoke = await api('GET', '/api/device-state', { token: childTokenA });
  check('对账结果与撤销一致（不会因丢失推送而不一致）',
    stateAfterRevoke.data?.grants?.length === 2 &&
      stateAfterRevoke.data.grants.every((g) => g.scope !== 'total_add'),
    stateAfterRevoke.data?.grants);

  /* ================= 加时申请与折扣审批 ================= */

  console.log('\n[5] 加时申请与审批（支持打折批准）');

  const badScopeRequest = await api('POST', '/api/time-requests', {
    token: childTokenA,
    body: { scope: 'unlock', requestMin: 30 },
  });
  check('孩子不能申请「解除全部管控」(400)', badScopeRequest.status === 400, badScopeRequest.data);

  const badMin = await api('POST', '/api/time-requests', {
    token: childTokenA,
    body: { scope: 'total_add', requestMin: 0 },
  });
  check('申请时长为 0 被拒绝 (400)', badMin.status === 400, badMin.data);

  const request = await api('POST', '/api/time-requests', {
    token: childTokenA,
    body: { scope: 'total_add', requestMin: 60, reason: '作业查资料' },
  });
  check('提交加时申请成功', request.status === 200 && request.data?.request?.status === 'pending', request.data);
  check('申请带审批时限（30 分钟自动过期）',
    request.data?.request?.expireAt > request.data?.request?.createdAt, request.data?.request);

  const parentGotRequest = await parentSocket.waitFor((m) => m.type === 'time_request');
  check('控制端实时收到加时申请', parentGotRequest.request?.requestMin === 60, parentGotRequest.request);
  check('申请携带设备名（多设备时家长能分辨）', parentGotRequest.request?.deviceName === '平板A', parentGotRequest.request);

  const duplicateRequest = await api('POST', '/api/time-requests', {
    token: childTokenA,
    body: { scope: 'total_add', requestMin: 60 },
  });
  check('同一范围重复申请被复用而非堆积', duplicateRequest.data?.duplicated === true, duplicateRequest.data);

  const pendingList = await api('GET', '/api/time-requests?status=pending', { token: parentToken });
  check('家长可列出待审批申请', pendingList.data?.requests?.length === 1, pendingList.data);

  const badDecide = await api('POST', `/api/time-requests/${request.data.request.id}/decide`, {
    token: parentToken,
    body: { approve: true, decidedMin: 90 },
  });
  check('批准时长超过申请值被拒绝 (400)', badDecide.status === 400, badDecide.data);

  const decide = await api('POST', `/api/time-requests/${request.data.request.id}/decide`, {
    token: parentToken,
    body: { approve: true, decidedMin: 20 },
  });
  check('折扣批准成功（申请 60 → 批准 20）', 
    decide.status === 200 && decide.data?.request?.status === 'approved' && decide.data?.request?.decidedMin === 20,
    decide.data);
  check('批准后自动生成对应授权', decide.data?.grant?.extraMinutes === 20, decide.data?.grant);

  const childGotDecision = await childSocketA2.waitFor((m) => m.type === 'time_request_decided');
  check('被控端收到审批结果', childGotDecision.request?.status === 'approved', childGotDecision.request);

  const decideAgain = await api('POST', `/api/time-requests/${request.data.request.id}/decide`, {
    token: parentToken,
    body: { approve: false },
  });
  check('重复审批被拒绝 (409)', decideAgain.status === 409, decideAgain.data);

  const mine = await api('GET', '/api/time-requests/mine', { token: childTokenA });
  check('被控端可轮询自己的申请结果（推送丢失时的补偿通道）',
    mine.data?.requests?.[0]?.status === 'approved', mine.data?.requests);

  // 第二台设备用于「拒绝」与「关闭申请」两条分支，绕开 10 分钟间隔限制
  const rejectRequest = await api('POST', '/api/time-requests', {
    token: childTokenB,
    body: { scope: 'app_allow', packageName: 'com.tencent.tmgp.sgame', appLabel: '王者荣耀', requestMin: 30, reason: '想玩一会' },
  });
  check('设备 B 提交应用放行申请', rejectRequest.status === 200, rejectRequest.data);

  const rejectDecision = await api('POST', `/api/time-requests/${rejectRequest.data.request.id}/decide`, {
    token: parentToken,
    body: { approve: false },
  });
  check('拒绝申请成功', rejectDecision.data?.request?.status === 'rejected', rejectDecision.data);

  const disableRequest = await api('PUT', `/api/devices/${deviceB}/policy`, {
    token: parentToken,
    body: { allowTimeRequest: false },
  });
  check('家长可关闭申请加时功能', disableRequest.status === 200 && disableRequest.data?.allowTimeRequest === false, disableRequest.data);

  const blockedRequest = await api('POST', '/api/time-requests', {
    token: childTokenB,
    body: { scope: 'total_add', requestMin: 10 },
  });
  check('家长关闭申请功能后孩子无法提交 (403)', blockedRequest.status === 403, blockedRequest.data);

  const forbiddenDecide = await api('POST', `/api/time-requests/${rejectRequest.data.request.id}/decide`, {
    token: childTokenA,
    body: { approve: true },
  });
  check('被控端令牌无法审批 (403)', forbiddenDecide.status === 403, forbiddenDecide.data);

  /* ================= 使用报告 ================= */

  console.log('\n[6] 使用记录、报告与拦截记录');

  const today = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const dayKey = `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`;

  await api('POST', `/api/devices/${deviceA}/installed-apps`, {
    token: childTokenA,
    body: {
      apps: [
        { packageName: 'com.tencent.mm', appLabel: '微信', isSystem: false },
        { packageName: 'com.tencent.tmgp.sgame', appLabel: '王者荣耀', isSystem: false },
      ],
    },
  });

  const sessions = [
    { clientKey: uniqueName('s'), packageName: 'com.tencent.mm', startTs: Date.now() - 600_000, endTs: Date.now() - 300_000, durationMs: 300_000, dayKey },
    { clientKey: uniqueName('s'), packageName: 'com.tencent.tmgp.sgame', startTs: Date.now() - 240_000, endTs: Date.now() - 60_000, durationMs: 180_000, dayKey },
  ];
  const usageReport = await api('POST', `/api/devices/${deviceA}/usage/report`, {
    token: childTokenA,
    body: { sessions },
  });
  check('批量上报使用会话', usageReport.data?.accepted === 2, usageReport.data);

  const overview = await api('GET', `/api/devices/${deviceA}/usage/overview?date=${dayKey}`, { token: parentToken });
  check('报告总览返回当日总时长', overview.data?.totalMs === 480_000, overview.data);
  check('报告总览补全应用名（不是一堆包名）',
    overview.data?.apps?.some((a) => a.packageName === 'com.tencent.mm' && a.appLabel === '微信'),
    overview.data?.apps);
  check('报告总览返回额度与剩余（家长一眼看到还能用多久）',
    overview.data?.limitMs >= 480_000 && overview.data?.remainingMs === overview.data.limitMs - overview.data.totalMs,
    { limitMs: overview.data?.limitMs, remainingMs: overview.data?.remainingMs });
  check('报告总览包含生效中的加时额度',
    overview.data?.extraMs === 20 * 60_000, { extraMs: overview.data?.extraMs, baseLimitMs: overview.data?.baseLimitMs });

  const trend = await api('GET', `/api/devices/${deviceA}/usage/trend?days=7`, { token: parentToken });
  check('趋势返回 7 个数据点（缺失日期补 0）', trend.data?.points?.length === 7, trend.data?.points);
  check('趋势中今天的数值正确',
    trend.data?.points?.[6]?.dayKey === dayKey && trend.data?.points?.[6]?.totalMs === 480_000,
    trend.data?.points?.[6]);
  check('趋势给出日均与峰值', trend.data?.averageMs >= 0 && trend.data?.peak?.totalMs === 480_000, trend.data?.peak);

  const ranking = await api('GET', `/api/devices/${deviceA}/usage/ranking?days=7`, { token: parentToken });
  check('排行按总时长降序', 
    ranking.data?.apps?.[0]?.packageName === 'com.tencent.mm' && ranking.data?.apps?.length === 2,
    ranking.data?.apps);
  check('排行包含活跃天数与日均', ranking.data?.apps?.[0]?.activeDays === 1, ranking.data?.apps?.[0]);

  const blocks = [
    { clientKey: uniqueName('b'), packageName: 'com.tencent.tmgp.sgame', reason: 'total_exhausted', ts: Date.now() - 60_000 },
    { clientKey: uniqueName('b'), packageName: 'com.tencent.mm', reason: 'blacklist', ts: Date.now() - 30_000 },
  ];
  const blockReport = await api('POST', `/api/devices/${deviceA}/blocks`, { token: childTokenA, body: { logs: blocks } });
  check('上报拦截记录', blockReport.data?.accepted === 2, blockReport.data);

  const blockReportAgain = await api('POST', `/api/devices/${deviceA}/blocks`, { token: childTokenA, body: { logs: blocks } });
  check('拦截记录上报幂等去重', blockReportAgain.data?.accepted === 0 && blockReportAgain.data?.duplicated === 2, blockReportAgain.data);

  // 家长主动锁定也会产生一条拦截记录，原因文案必须是"已锁定"而不是"已被家长限制"
  const lockBlock = await api('POST', `/api/devices/${deviceA}/blocks`, {
    token: childTokenA,
    body: { logs: [{ clientKey: uniqueName('b'), packageName: 'com.tencent.mm', reason: 'lock', ts: Date.now() - 5_000 }] },
  });
  check('家长锁定也能作为拦截记录上报', lockBlock.data?.accepted === 1, lockBlock.data);

  const blockList = await api('GET', `/api/devices/${deviceA}/blocks?limit=10`, { token: parentToken });
  check('家长可查看拦截记录', blockList.data?.blocks?.length === 3, blockList.data?.blocks);
  check('拦截记录带可读原因与应用名',
    blockList.data?.blocks?.some((b) => b.reasonText === '今日总时长已用完' && b.appLabel === '王者荣耀'),
    blockList.data?.blocks);
  check('锁定原因有专属文案', 
    blockList.data?.blocks?.some((b) => b.reason === 'lock' && b.reasonText === '家长已锁定设备'),
    blockList.data?.blocks?.find((b) => b.reason === 'lock'));

  const sessionsList = await api('GET', `/api/devices/${deviceA}/usage/sessions?date=${dayKey}`, { token: parentToken });
  check('会话时间线包含应用名', sessionsList.data?.sessions?.length === 2 && !!sessionsList.data?.sessions?.[0]?.appLabel,
    sessionsList.data?.sessions);

  /* ================= 逐应用「用时不计入当日总时长」 ================= */

  console.log('\n[6b] 不计入当日总时长');
  const EXEMPT_PKG = 'com.tencent.tmgp.sgame'; // 当天 180 秒用量

  const beforeExempt = await api('GET', `/api/devices/${deviceA}/policy/bundle`, { token: parentToken });
  const baseRules = beforeExempt.data?.appRules ?? [];

  const enableExempt = await api('PUT', `/api/devices/${deviceA}/policy/app-rules`, {
    token: parentToken,
    body: {
      rules: [
        ...baseRules,
        { packageName: EXEMPT_PKG, appLabel: '王者荣耀', exemptTotal: true },
        // 故意不发 exemptTotal：老客户端、以及升级后的老库走的都是这条默认值路径
        { packageName: 'com.tencent.mm', appLabel: '微信', dailyLimitMin: 20 },
      ],
    },
  });
  check('「不计入总时长」开关随策略下发',
    enableExempt.data?.appRules?.find((r) => r.packageName === EXEMPT_PKG)?.exemptTotal === true,
    enableExempt.data?.appRules);
  check('未显式设置的应用默认为关闭（老客户端不发该字段也不会变）',
    enableExempt.data?.appRules?.find((r) => r.packageName === 'com.tencent.mm')?.exemptTotal === false,
    enableExempt.data?.appRules);

  const exemptedOverview = await api('GET', `/api/devices/${deviceA}/usage/overview?date=${dayKey}`, { token: parentToken });
  check('★ 被豁免的应用不再占用当日额度（480s → 300s）',
    exemptedOverview.data?.totalMs === 300_000 && exemptedOverview.data?.exemptMs === 180_000,
    { totalMs: exemptedOverview.data?.totalMs, exemptMs: exemptedOverview.data?.exemptMs });
  check('剩余额度随之回涨，与设备端执行的额度同口径',
    exemptedOverview.data?.remainingMs === exemptedOverview.data.limitMs - 300_000,
    { limitMs: exemptedOverview.data?.limitMs, remainingMs: exemptedOverview.data?.remainingMs });
  check('被豁免的应用仍在明细里（家长要知道它实际用了多久）',
    exemptedOverview.data?.apps?.some((a) => a.packageName === EXEMPT_PKG && a.exemptTotal === true),
    exemptedOverview.data?.apps);

  const exemptedTrend = await api('GET', `/api/devices/${deviceA}/usage/trend?days=7`, { token: parentToken });
  check('趋势图与总览口径一致（不会画出"超出上限"的柱子）',
    exemptedTrend.data?.points?.[6]?.totalMs === 300_000,
    exemptedTrend.data?.points?.[6]);

  const exemptedRanking = await api('GET', `/api/devices/${deviceA}/usage/ranking?days=7`, { token: parentToken });
  check('应用排行仍按真实用量统计（豁免不改变排行）',
    exemptedRanking.data?.apps?.find((a) => a.packageName === EXEMPT_PKG)?.totalMs === 180_000,
    exemptedRanking.data?.apps);

  // 复原，避免影响后续断言
  await api('PUT', `/api/devices/${deviceA}/policy/app-rules`, {
    token: parentToken,
    body: { rules: baseRules },
  });
  const restored = await api('GET', `/api/devices/${deviceA}/usage/overview?date=${dayKey}`, { token: parentToken });
  check('关掉开关后额度立即恢复计数（开关可逆）',
    restored.data?.totalMs === 480_000 && restored.data?.exemptMs === 0,
    { totalMs: restored.data?.totalMs, exemptMs: restored.data?.exemptMs });

  /* ================= 权限边界 ================= */

  console.log('\n[7] 权限边界');
  const childOnCommands = await api('GET', `/api/devices/${deviceA}/commands`, { token: childTokenA });
  check('被控端令牌不能查看指令历史 (403)', childOnCommands.status === 403, childOnCommands.data);

  const parentOnPending = await api('GET', '/api/commands/pending', { token: parentToken });
  check('家长令牌不能拉取被控端待执行指令 (403)', parentOnPending.status === 403, parentOnPending.data);

  const parentOnState = await api('GET', '/api/device-state', { token: parentToken });
  check('家长令牌不能冒充被控端对账 (403)', parentOnState.status === 403, parentOnState.data);

  const crossDevice = await api('GET', '/api/commands/pending', { token: childTokenA });
  check('被控端只能操作自己的指令', crossDevice.status === 200, crossDevice.data);

  parentSocket.close();
  childSocketA2.close();

  console.log(`\n结果：${passed} 项通过，${failed} 项失败\n`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((error) => {
  console.error('\n冒烟测试异常终止：', error);
  process.exit(1);
});
