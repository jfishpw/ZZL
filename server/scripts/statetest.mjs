import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 指令队列与临时授权的模块级自检。
 *
 * 为什么单独存在：这些行为的核心是**时间语义**（TTL 过期、补发顺序、
 * 「先解除锁定再立即锁定」的执行次序），用 HTTP 端到端测要么需要等真实的
 * 时间流逝（60 秒起步），要么根本测不到。这里直接操作模块，把"时间"参数化。
 *
 * 独立数据库：不碰 data/app.db，因此可以和正在运行的服务并存。
 */

const serverRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const testDb = path.resolve(serverRoot, 'data/test-state.db');

for (const suffix of ['', '-wal', '-shm']) {
  const file = `${testDb}${suffix}`;
  if (fs.existsSync(file)) fs.rmSync(file);
}
process.env.DB_PATH = path.relative(serverRoot, testDb);

const { initDb, run, one, now } = await import('../src/db.js');
const { enqueueCommand, pendingCommands, ackCommand, listCommands, expireStaleCommands, TTL_MS } =
  await import('../src/commands.js');
const {
  createGrant, revokeGrant, activeGrants, deviceState, setLocked, grantHistory, dayKeyAt, bumpStateVersion,
} = await import('../src/grants.js');

initDb();

let pass = 0;
let fail = 0;

function check(label, condition, extra) {
  if (condition) {
    pass += 1;
    console.log(`  ✓ ${label}`);
  } else {
    fail += 1;
    console.log(`  ✗ ${label}`);
    if (extra !== undefined) console.log(`      实际：${JSON.stringify(extra)}`);
  }
}

/* ---------------- 准备一台设备 ---------------- */

run(
  'INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)',
  'state_tester', 'x', now(),
);
const userId = one('SELECT id FROM users WHERE username = ?', 'state_tester').id;

run(
  `INSERT INTO devices (user_id, name, child_uuid, online, created_at)
   VALUES (?, ?, ?, 0, ?)`,
  userId, '状态测试机', 'uuid-state-1', now(),
);
const deviceId = one('SELECT id FROM devices WHERE child_uuid = ?', 'uuid-state-1').id;

run(
  `INSERT INTO devices (user_id, name, child_uuid, online, created_at)
   VALUES (?, ?, ?, 0, ?)`,
  userId, '另一台设备', 'uuid-state-2', now(),
);
const otherDeviceId = one('SELECT id FROM devices WHERE child_uuid = ?', 'uuid-state-2').id;

// 策略里的 reset_hour 决定「额度日」的归日点
run(
  `INSERT INTO policies (device_id, weekday_total_min, weekend_total_min, reset_hour, list_mode, updated_at, version)
   VALUES (?, 60, 120, 4, 'blacklist', ?, 1)`,
  deviceId, now(),
);

console.log('\n掌中灵 指令队列与授权 模块自检\n');

/* ================ 指令 TTL ================ */

console.log('[1] 指令有效期（TTL）');

const screenshot = enqueueCommand(deviceId, 'screenshot', { quality: 60 });
check('截屏指令带上 60 秒有效期', screenshot.expireAt - now() <= 60_000 && screenshot.expireAt > now(), screenshot);

const lockCommand = enqueueCommand(deviceId, 'immediate_lock', {});
check('立即锁定指令永久有效（不过期）', lockCommand.expireAt === null, lockCommand);

const clearCommand = enqueueCommand(deviceId, 'clear_lock', {});
check('解除锁定指令有时限（防止很久后才被补执行）',
  clearCommand.expireAt !== null && clearCommand.expireAt > now(), clearCommand);

check('TTL 表覆盖全部指令类型',
  ['immediate_lock', 'clear_lock', 'request_installed_apps', 'screenshot'].every((t) => t in TTL_MS),
  Object.keys(TTL_MS));

// 把截屏指令的过期时间改到过去，模拟"时间流逝"
run('UPDATE commands SET expire_at = ? WHERE command_id = ?', now() - 1000, screenshot.commandId);
const expiredCount = expireStaleCommands(deviceId);
check('过期指令被标记为 expired', expiredCount >= 1, { expiredCount });

const staleRow = one('SELECT status FROM commands WHERE command_id = ?', screenshot.commandId);
check('过期指令状态正确', staleRow.status === 'expired', staleRow);

const pending = pendingCommands(deviceId);
check('过期指令不再出现在待执行列表',
  !pending.some((c) => c.commandId === screenshot.commandId), pending.map((c) => c.type));

/* ================ 执行顺序 ================ */

console.log('\n[2] 补发顺序与幂等');

check('待执行列表按 id 升序返回（顺序即语义）',
  pending.length >= 2 && pending[0].type === 'immediate_lock' && pending[1].type === 'clear_lock',
  pending.map((c) => `${c.id}:${c.type}`));

const firstAck = ackCommand(lockCommand.commandId, deviceId, 'done', { applied: true });
check('回执成功并记录执行时间', firstAck.ok && firstAck.view.status === 'done' && firstAck.view.executedAt > 0,
  firstAck.view);

const overwrite = ackCommand(lockCommand.commandId, deviceId, 'failed', { reason: '迟到的重试' });
check('已终态指令不被重复回执覆盖', overwrite.view.status === 'done', overwrite.view);

const wrongDevice = ackCommand(clearCommand.commandId, otherDeviceId, 'done');
check('其他设备无法回执本设备的指令', wrongDevice.ok === false && wrongDevice.reason === 'forbidden', wrongDevice);

const unknown = ackCommand('not-a-real-command', deviceId, 'done');
check('未知指令回执返回 not_found', unknown.ok === false && unknown.reason === 'not_found', unknown);

const listed = listCommands(deviceId, 50);
check('指令历史包含已失效与已执行的指令',
  listed.some((c) => c.status === 'expired') && listed.some((c) => c.status === 'done'),
  listed.map((c) => `${c.type}:${c.status}`));

/* ================ 额度日计算 ================ */

console.log('\n[3] 额度日归日点');

// reset_hour = 4：凌晨 2 点应归到"昨天"
const base = new Date(2026, 8, 18, 2, 0, 0); // 2026-09-18 02:00 本地时间
const earlyMorning = dayKeyAt(deviceId, base.getTime());
const sameDayNoon = dayKeyAt(deviceId, new Date(2026, 8, 18, 12, 0, 0).getTime());
const beforeReset = dayKeyAt(deviceId, new Date(2026, 8, 18, 3, 59, 0).getTime());
const afterReset = dayKeyAt(deviceId, new Date(2026, 8, 18, 4, 0, 0).getTime());

check('凌晨 2 点归属前一天（归日点为 4 点）', earlyMorning === '2026-09-17', earlyMorning);
check('同一天中午归当天', sameDayNoon === '2026-09-18', sameDayNoon);
check('归日点前后分属两天', beforeReset === '2026-09-17' && afterReset === '2026-09-18', { beforeReset, afterReset });

/* ================ 授权校验 ================ */

console.log('\n[4] 授权创建与校验');

const badScope = createGrant(deviceId, { scope: 'give_everything' });
check('非法范围被拒绝', badScope.ok === false && badScope.reason === 'invalid_scope', badScope);

const noPackage = createGrant(deviceId, { scope: 'app_allow' });
check('单应用放行缺少包名被拒绝', noPackage.ok === false && noPackage.reason === 'missing_package', noPackage);

const zeroExtra = createGrant(deviceId, { scope: 'total_add', extraMinutes: 0 });
check('加时 0 分钟被拒绝', zeroExtra.ok === false && zeroExtra.reason === 'invalid_extra_minutes', zeroExtra);

const hugeExtra = createGrant(deviceId, { scope: 'total_add', extraMinutes: 99_999 });
check('加时超上限被拒绝', hugeExtra.ok === false && hugeExtra.reason === 'invalid_extra_minutes', hugeExtra);

const badTtl = createGrant(deviceId, { scope: 'unlock', ttlMinutes: 0 });
check('有效期 0 分钟被拒绝', badTtl.ok === false && badTtl.reason === 'invalid_ttl', badTtl);

const addGrant = createGrant(deviceId, { scope: 'total_add', extraMinutes: 30, ttlMinutes: 180 });
check('加时授权创建成功', addGrant.ok && addGrant.grant.extraMinutes === 30, addGrant.grant);
check('加时授权绑定额度日（不泄漏到次日）',
  addGrant.grant.dayKey === dayKeyAt(deviceId, now()), addGrant.grant.dayKey);
check('设备离线时授权标记为待补发', addGrant.delivered === false, addGrant);

/* ================ 授权生效与过期的判定 ================ */

console.log('\n[5] 授权生效、过期与撤销');

const appGrant = createGrant(deviceId, {
  scope: 'app_allow', packageName: 'com.tencent.mm', ttlMinutes: 60,
});
check('单应用放行授权创建成功', appGrant.ok && appGrant.grant.packageName === 'com.tencent.mm', appGrant.grant);

const unlockGrant = createGrant(deviceId, { scope: 'unlock', ttlMinutes: 30 });
check('临时总解封授权创建成功', unlockGrant.ok, unlockGrant.grant);

check('三条授权同时生效', activeGrants(deviceId).length === 3, activeGrants(deviceId).map((g) => g.scope));

// 把其中一条改到过去，模拟过期
run('UPDATE grants SET expire_at = ? WHERE id = ?', now() - 1000, unlockGrant.grant.id);
check('过期授权不再生效', activeGrants(deviceId).length === 2, activeGrants(deviceId).map((g) => g.scope));

revokeGrant(appGrant.grant.id, userId);
check('撤销后的授权不再生效', activeGrants(deviceId).length === 1, activeGrants(deviceId).map((g) => g.scope));

const history = grantHistory(deviceId, 20);
check('历史列表仍能查到已撤销与已过期的授权',
  history.length === 3 && history.filter((g) => g.active).length === 1,
  history.map((g) => `${g.scope}:${g.active}`));
check('历史项带 active 标记区分生效与失效',
  history.find((g) => g.id === appGrant.grant.id)?.revoked === true,
  history.find((g) => g.id === appGrant.grant.id));

/* ================ 设备状态对账 ================ */

console.log('\n[6] 设备状态与版本号');

const v0 = deviceState(deviceId).stateVersion;
check('初始状态未锁定', deviceState(deviceId).locked === false, deviceState(deviceId).locked);

setLocked(deviceId, true, userId);
const locked = deviceState(deviceId);
check('锁定状态写入后可对账读回', locked.locked === true && locked.lockedAt > 0, locked);

const v1 = locked.stateVersion;
bumpStateVersion(deviceId);
const v2 = deviceState(deviceId).stateVersion;
check('状态版本随变更单调递增', v0 < v1 && v1 < v2, { v0, v1, v2 });

setLocked(deviceId, false, userId);
check('解除锁定同样反映在对账结果里', deviceState(deviceId).locked === false, deviceState(deviceId).locked);

const other = deviceState(otherDeviceId);
check('另一台设备的授权互不影响', other.grants.length === 0 && other.locked === false, other);

console.log(`\n结果：${pass} 项通过，${fail} 项失败\n`);
process.exit(fail === 0 ? 0 : 1);
