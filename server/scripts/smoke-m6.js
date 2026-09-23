import WebSocket from 'ws';

/**
 * M6 端到端冒烟测试：按需截屏、图标隐藏、审计日志、被控端事件补传。
 *
 * 与前面几套分开的理由：M6 的主题是「隐私数据的边界」——
 * 截屏是全系统最敏感的数据，它的越权访问、大小限制、清理策略
 * 需要一组互相印证的断言，拆开跑更容易定位是哪一层漏了。
 *
 * 用法：先启动服务，再执行 npm run smoke:m6
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

/** 上传图片：直接发二进制 body 而不是 JSON 里的 base64 */
async function uploadImage(path, { token, bytes, mime = 'image/jpeg' }) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: {
      'content-type': mime,
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: bytes,
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

/**
 * 造一张最小的合法 JPEG。
 *
 * 用真实的 JPEG 头尾（SOI + EOI）而不是随便几个字节，
 * 这样"服务端存下来的是什么"与"客户端传上去的是什么"可以逐字节比对，
 * 顺带验证二进制没有被中间层做任何转码（base64、压缩、截断）。
 */
function fakeJpeg(padding = 256) {
  const head = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46]);
  const body = Buffer.alloc(padding, 0x20);
  const tail = Buffer.from([0xff, 0xd9]);
  return Buffer.concat([head, body, tail]);
}

async function main() {
  console.log(`\n掌中灵 M6 冒烟测试  →  ${BASE}\n`);

  /* ================= 准备 ================= */

  console.log('[1] 账号与配对');
  const username = uniqueName('m6');
  const password = 'smoke-pass-1234';

  const reg = await api('POST', '/api/auth/register', { body: { username, password } });
  check('注册成功', reg.status === 201, reg.data);

  const login = await api('POST', '/api/auth/login', { body: { username, password } });
  const parentToken = login.data?.token;
  check('登录拿到家长令牌', login.status === 200 && !!parentToken, login.data);

  const codeA = await api('POST', '/api/pair/code', { token: parentToken });
  const claimA = await api('POST', '/api/pair/claim', {
    body: { code: codeA.data?.code, childUuid: uniqueName('uuid-m6'), name: '截屏测试机', model: 'M6', androidVer: 33 },
  });
  const deviceId = claimA.data?.deviceId;
  const childToken = claimA.data?.token;
  check('设备配对成功', claimA.status === 200 && !!deviceId && !!childToken, claimA.data);

  // 第二台设备：用于验证跨设备越权
  const codeB = await api('POST', '/api/pair/code', { token: parentToken });
  const claimB = await api('POST', '/api/pair/claim', {
    body: { code: codeB.data?.code, childUuid: uniqueName('uuid-m6b'), name: '第二台', model: 'M6', androidVer: 33 },
  });
  const deviceB = claimB.data?.deviceId;
  const childTokenB = claimB.data?.token;

  // 另一个家长账号：用于验证跨账号越权
  const otherName = uniqueName('m6other');
  await api('POST', '/api/auth/register', { body: { username: otherName, password } });
  const otherLogin = await api('POST', '/api/auth/login', { body: { username: otherName, password } });
  const otherToken = otherLogin.data?.token;

  const childSocket = await openSocket(childToken);

  /* ================= 截屏指令 ================= */

  console.log('\n[2] 按需截屏：指令下发');

  const shotReq = await api('POST', `/api/devices/${deviceId}/screenshot`, { token: parentToken });
  check('请求截屏成功', shotReq.status === 200 && shotReq.data?.ok === true, shotReq.data);
  check('指令实时送达在线设备', shotReq.data?.delivered === true, shotReq.data);
  const shotCommandId = shotReq.data?.commandId;
  check('返回指令 ID', typeof shotCommandId === 'string' && shotCommandId.length > 0);

  const shotEvent = await childSocket.waitFor(
    (m) => m.type === 'command' && m.command?.type === 'screenshot',
  );
  check('被控端通过 WebSocket 收到截屏指令', !!shotEvent, shotEvent);
  check('指令带 60 秒有效期', typeof shotEvent.command.expireAt === 'number', shotEvent.command);
  const ttlMs = shotEvent.command.expireAt - shotEvent.command.createdAt;
  check('TTL 落在 60 秒附近', ttlMs > 50_000 && ttlMs <= 61_000, ttlMs);

  console.log('\n[3] 截屏：图片上传');

  const jpeg = fakeJpeg(512);
  const upload = await uploadImage(
    `/api/screenshots?width=1080&height=1920&foreground=com.tencent.tmgp.sgame&mode=accessibility&commandId=${shotCommandId}`,
    { token: childToken, bytes: jpeg },
  );
  check('上传截屏成功', upload.status === 200 && upload.data?.ok === true, upload.data);
  const shotId = upload.data?.screenshot?.id;
  check('返回截屏 ID', Number.isInteger(shotId), upload.data?.screenshot);
  check('记录字节数', upload.data?.screenshot?.byteSize === jpeg.length, {
    got: upload.data?.screenshot?.byteSize,
    want: jpeg.length,
  });
  check('记录尺寸', upload.data?.screenshot?.width === 1080 && upload.data?.screenshot?.height === 1920);
  check('记录前台应用', upload.data?.screenshot?.foregroundPackage === 'com.tencent.tmgp.sgame');
  check('记录采集方式为无障碍（无录屏弹窗那条路）',
    upload.data?.screenshot?.captureMode === 'accessibility',
    upload.data?.screenshot?.captureMode);

  // 响应丢失后设备会重传同一 commandId —— 必须幂等返回原记录而不是撞唯一索引
  const reupload = await uploadImage(
    `/api/screenshots?width=1080&height=1920&commandId=${shotCommandId}`,
    { token: childToken, bytes: fakeJpeg(128) },
  );
  check('同一 commandId 重传仍返回 200（幂等）',
    reupload.status === 200 && reupload.data?.ok === true, reupload.data);
  check('重传返回的是原截屏 ID 而不是新记录',
    reupload.data?.screenshot?.id === shotId,
    { got: reupload.data?.screenshot?.id, want: shotId });

  console.log('\n[4] 家长查看图片');

  const list = await api('GET', `/api/devices/${deviceId}/screenshots`, { token: parentToken });
  check('可列出截屏', list.status === 200 && list.data?.screenshots?.length === 1, list.data);
  check('列表不含图片本体（只有元信息）',
    list.data?.screenshots?.[0]?.fileName && list.data?.screenshots?.[0]?.bytes === undefined,
    Object.keys(list.data?.screenshots?.[0] ?? {}));

  const imageRes = await fetch(`${BASE}/api/devices/${deviceId}/screenshots/${shotId}/image`, {
    headers: { authorization: `Bearer ${parentToken}` },
  });
  const imageBytes = Buffer.from(await imageRes.arrayBuffer());
  check('能取到图片', imageRes.status === 200, imageRes.status);
  check('返回的是图片 MIME',
    (imageRes.headers.get('content-type') ?? '').startsWith('image/jpeg'),
    imageRes.headers.get('content-type'));
  check('★ 图片内容逐字节一致（证明二进制没被转码或截断）',
    imageBytes.length === jpeg.length && imageBytes.equals(jpeg),
    { got: imageBytes.length, want: jpeg.length });

  const afterView = await api('GET', `/api/devices/${deviceId}/screenshots`, { token: parentToken });
  check('查看后记录 viewedAt', typeof afterView.data?.screenshots?.[0]?.viewedAt === 'number');

  /* ================= 越权 ================= */

  console.log('\n[5] 越权访问必须被拦住');

  const crossAccount = await fetch(`${BASE}/api/devices/${deviceId}/screenshots/${shotId}/image`, {
    headers: { authorization: `Bearer ${otherToken}` },
  });
  check('★ 别的家长账号取不到图片（403）', crossAccount.status === 403, crossAccount.status);

  const crossDevice = await fetch(`${BASE}/api/devices/${deviceB}/screenshots/${shotId}/image`, {
    headers: { authorization: `Bearer ${parentToken}` },
  });
  check('★ 同账号的另一台设备也取不到（截屏按设备隔离）',
    crossDevice.status === 404, crossDevice.status);

  const childList = await api('GET', `/api/devices/${deviceId}/screenshots`, { token: childToken });
  check('被控端令牌不能查截屏列表（403）', childList.status === 403, childList.status);

  const anon = await fetch(`${BASE}/api/devices/${deviceId}/screenshots/${shotId}/image`);
  check('无令牌取不到图片（401）', anon.status === 401, anon.status);

  /* ================= 上传约束 ================= */

  console.log('\n[6] 上传约束');

  const huge = fakeJpeg(2 * 1024 * 1024);
  const tooBig = await uploadImage('/api/screenshots', { token: childToken, bytes: huge });
  check('超过上限的图片被拒绝', tooBig.status === 413 || tooBig.status === 400, {
    status: tooBig.status,
    data: tooBig.data,
  });

  const empty = await uploadImage('/api/screenshots', { token: childToken, bytes: Buffer.alloc(0) });
  check('空内容被拒绝', empty.status === 400, { status: empty.status, data: empty.data });

  const parentUpload = await uploadImage('/api/screenshots', { token: parentToken, bytes: fakeJpeg() });
  check('家长令牌不能上传截屏（403）', parentUpload.status === 403, parentUpload.status);

  /* ================= 指令回执与截屏关联 ================= */

  console.log('\n[7] 截屏结果与指令关联');

  const ack = await api('POST', `/api/commands/${shotCommandId}/ack`, {
    token: childToken,
    body: { status: 'done', detail: { screenshotId: shotId, captureMode: 'accessibility' } },
  });
  check('回执成功', ack.status === 200 && ack.data?.command?.status === 'done', ack.data);
  check('回执里带上截屏 ID',
    ack.data?.command?.result?.screenshotId === shotId, ack.data?.command?.result);

  const commands = await api('GET', `/api/devices/${deviceId}/commands?limit=5`, { token: parentToken });
  const shotCommand = commands.data?.commands?.find((c) => c.commandId === shotCommandId);
  check('家长能在指令历史里看到这条截屏指令', !!shotCommand, shotCommand);

  /* ================= 图标隐藏 ================= */

  console.log('\n[8] 隐藏应用图标');

  const iconStateBefore = await api('GET', '/api/device-state', { token: childToken });
  check('初始状态图标未隐藏', iconStateBefore.data?.iconHidden === false, iconStateBefore.data);

  const hideRes = await api('POST', `/api/devices/${deviceId}/icon`, {
    token: parentToken,
    body: { hidden: true },
  });
  check('隐藏图标成功', hideRes.status === 200 && hideRes.data?.hidden === true, hideRes.data);
  check('提示文案说明如何恢复', typeof hideRes.data?.notice === 'string', hideRes.data?.notice);

  const hideEvent = await childSocket.waitFor(
    (m) => m.type === 'command' && m.command?.type === 'hide_icon',
  );
  check('被控端收到隐藏指令', !!hideEvent);

  const stateHidden = await childSocket.waitFor((m) => m.type === 'device_state_updated' && m.state?.iconHidden === true);
  check('★ 图标状态随设备状态对账下发（重启后仍生效）',
    stateHidden.state.iconHidden === true, stateHidden.state);

  const viewAfterHide = await api('GET', `/api/devices/${deviceId}`, { token: parentToken });
  check('设备详情显示图标已隐藏', viewAfterHide.data?.iconHidden === true, viewAfterHide.data);

  const showRes = await api('POST', `/api/devices/${deviceId}/icon`, {
    token: parentToken,
    body: { hidden: false },
  });
  check('恢复图标成功', showRes.status === 200 && showRes.data?.hidden === false, showRes.data);

  await childSocket.waitFor((m) => m.type === 'device_state_updated' && m.state?.iconHidden === false);
  check('恢复后状态同步', true);

  /* ================= 审计日志 ================= */

  console.log('\n[9] 审计日志');

  const audit = await api('GET', `/api/devices/${deviceId}/audit?limit=100`, { token: parentToken });
  check('可查询审计日志', audit.status === 200 && Array.isArray(audit.data?.logs), audit.data);
  const actions = (audit.data?.logs ?? []).map((l) => l.action);
  check('记录了截屏请求', actions.includes('command.screenshot'), actions);
  check('记录了截屏上传', actions.includes('screenshot.upload'), actions);
  check('★ 记录了截屏查看（隐私可追溯）', actions.includes('screenshot.view'), actions);
  check('记录了图标隐藏与恢复',
    actions.includes('device.icon_hidden') && actions.includes('device.icon_shown'), actions);
  check('日志带可读中文描述',
    (audit.data?.logs ?? []).some((l) => typeof l.actionText === 'string' && l.actionText.length > 0));

  check('摘要统计可用', typeof audit.data?.summary?.warnCount === 'number', audit.data?.summary);

  console.log('\n[10] 被控端事件补传');

  const events = [
    { clientKey: uniqueName('evt'), action: 'permission.lost', detail: { missing: ['accessibility'] }, ts: Date.now() - 5000 },
    { clientKey: uniqueName('evt'), action: 'pin.attempt.failed', detail: { level: 3, source: 'overlay' }, ts: Date.now() - 3000 },
    { clientKey: uniqueName('evt'), action: 'pin.attempt.failed', detail: { level: 3, source: 'corner' }, ts: Date.now() - 2000 },
    { clientKey: uniqueName('evt'), action: 'screenshot.failed', detail: { ok: false, reason: 'no_accessibility', message: '无障碍服务未运行' }, ts: Date.now() - 1000 },
  ];
  const evtRes = await api('POST', '/api/audit/events', { token: childToken, body: { events } });
  check('上报本地事件成功', evtRes.status === 200 && evtRes.data?.accepted === 4, evtRes.data);

  const evtAgain = await api('POST', '/api/audit/events', { token: childToken, body: { events } });
  check('★ 重复上报被幂等去重（断网重传不会写两遍）',
    evtAgain.data?.accepted === 0 && evtAgain.data?.duplicated === 4, evtAgain.data);

  const auditAfter = await api('GET', `/api/devices/${deviceId}/audit?limit=100`, { token: parentToken });
  const childEvents = (auditAfter.data?.logs ?? []).filter((l) => l.source === 'child');
  check('被控端事件出现在日志里', childEvents.length === 4, childEvents.length);
  check('★ 权限丢失被标为需关注级别',
    childEvents.find((l) => l.action === 'permission.lost')?.level === 'warn');
  check('★ 截屏失败与上传是两个动作、且标红（防止误读成"传了但没显示"）',
    childEvents.find((l) => l.action === 'screenshot.failed')?.level === 'warn' &&
      /失败/.test(childEvents.find((l) => l.action === 'screenshot.failed')?.actionText ?? ''),
    childEvents.find((l) => l.action === 'screenshot.failed'));
  check('★ 密码输入失败被标为需关注级别（家长能看到孩子在试）',
    childEvents.filter((l) => l.action === 'pin.attempt.failed').every((l) => l.level === 'warn'));

  const warnOnly = await api('GET', `/api/devices/${deviceId}/audit?level=warn`, { token: parentToken });
  check('可按级别过滤（只看需要注意的）',
    (warnOnly.data?.logs ?? []).length > 0 &&
      (warnOnly.data?.logs ?? []).every((l) => l.level === 'warn'),
    (warnOnly.data?.logs ?? []).map((l) => l.level));

  const summaryAfter = await api('GET', `/api/devices/${deviceId}/audit`, { token: parentToken });
  // 需关注的是这 4 条：1 次权限丢失 + 2 次密码尝试失败 + 1 次截屏失败。
  // 截屏查看/上传用的是 notice / info —— 它们是"记录"而非"告警"，
  // 否则家长每天会看到一堆红点，真正该注意的被淹没。
  check('摘要统计出需要注意的条数', summaryAfter.data?.summary?.warnCount === 4,
    summaryAfter.data?.summary);
  check('★ 摘要带最后一条告警（按事件发生时间取最新）',
    summaryAfter.data?.summary?.lastWarnAction === 'screenshot.failed',
    summaryAfter.data?.summary);

  /* ================= 删除与清理 ================= */

  console.log('\n[11] 删除截屏');

  const del = await api('DELETE', `/api/devices/${deviceId}/screenshots/${shotId}`, { token: parentToken });
  check('删除成功', del.status === 200, del.data);

  const afterDelete = await fetch(`${BASE}/api/devices/${deviceId}/screenshots/${shotId}/image`, {
    headers: { authorization: `Bearer ${parentToken}` },
  });
  check('删除后取不到图片', afterDelete.status === 404, afterDelete.status);

  const listAfterDelete = await api('GET', `/api/devices/${deviceId}/screenshots`, { token: parentToken });
  check('删除后列表为空', listAfterDelete.data?.screenshots?.length === 0, listAfterDelete.data);

  const crossDelete = await api('DELETE', `/api/devices/${deviceId}/screenshots/999999`, {
    token: otherToken,
  });
  check('别的账号删不掉（403/404）', crossDelete.status === 403 || crossDelete.status === 404, crossDelete.status);

  /* ================= 多张与淘汰 ================= */

  console.log('\n[12] 多张截屏与按设备隔离');

  // 逐次校验上传结果，而不是上传完再看总数。
  // 这里原本只发 3 次请求就断言列表有 3 条 —— 机器负载高时其中一次上传
  // 可能超时/失败（实测在并行跑 Gradle 构建时复现过），
  // 报告出来却是「期望 3 条、实际 2 条」这种误导性的数量断言，
  // 让人去怀疑分页、淘汰或并发写入，而真正的原因是一次请求根本没成功。
  const multiStatuses = [];
  for (let i = 0; i < 3; i += 1) {
    const up = await uploadImage(`/api/screenshots?width=100&height=200&mode=projection`, {
      token: childToken,
      bytes: fakeJpeg(64 + i),
    });
    multiStatuses.push(up.status);
    await delay(20);
  }
  check('3 次上传全部成功', multiStatuses.every((s) => s === 200), multiStatuses);

  const multi = await api('GET', `/api/devices/${deviceId}/screenshots`, { token: parentToken });
  check('可保存多张', multi.data?.screenshots?.length === 3, multi.data?.screenshots?.length);
  check('按时间倒序（最新的在最前）',
    (multi.data?.screenshots ?? []).every((s, i, arr) => i === 0 || arr[i - 1].createdAt >= s.createdAt));
  check('记录降级采集方式（低版本走录屏）',
    (multi.data?.screenshots ?? []).some((s) => s.captureMode === 'projection'));

  const deviceBList = await api('GET', `/api/devices/${deviceB}/screenshots`, { token: parentToken });
  check('★ 另一台设备的截屏列表是空的（数据按设备隔离）',
    deviceBList.data?.screenshots?.length === 0, deviceBList.data);
  void childTokenB;

  /* ================= 实时通知 ================= */

  console.log('\n[13] 截屏就绪的实时通知');

  // 家长不必轮询：设备上传完成后应立即收到推送
  const parentSocket = await openSocket(parentToken);
  await uploadImage('/api/screenshots?mode=accessibility', {
    token: childToken,
    bytes: fakeJpeg(128),
  });
  const ready = await parentSocket.waitFor((m) => m.type === 'screenshot_ready');
  check('★ 截屏一到就推送给家长（不必轮询）', !!ready, ready);
  check('推送里带上截屏元信息',
    Number.isInteger(ready.screenshot?.id) && ready.deviceId === deviceId, ready.screenshot);
  parentSocket.close();

  childSocket.close();

  console.log(`\n结果：${passed} 项通过，${failed} 项失败\n`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((error) => {
  console.error('\n冒烟测试异常终止：', error);
  process.exit(1);
});
