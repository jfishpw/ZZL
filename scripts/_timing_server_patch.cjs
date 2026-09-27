// 临时脚本：计时模式功能服务端补丁（用完即删）
const fs = require('fs');

// 1) db.js：policies 加 timing_mode
let t = fs.readFileSync('server/src/db.js', 'utf8');
let a = "  addColumnIfMissing('devices', 'private_dns_host', \"TEXT NOT NULL DEFAULT ''\");";
if (!t.includes(a)) throw new Error('db anchor');
t = t.replace(a, a + `
  // 计时方式：standard=现状单前台计时；recommended=可见窗口并算+系统对账；system=纯系统口径（对账驱动）
  addColumnIfMissing('policies', 'timing_mode', "TEXT NOT NULL DEFAULT 'standard'");`);
fs.writeFileSync('server/src/db.js', t);

// 2) policy.js：视图 / 更新
t = fs.readFileSync('server/src/routes/policy.js', 'utf8');
a = `    listMode: policy.list_mode,
    allowTimeRequest: !!policy.allow_time_request,
    enabled: !!policy.enabled,
    version: policy.version,`;
if (!t.includes(a)) throw new Error('view anchor');
t = t.replace(a, `    listMode: policy.list_mode,
    allowTimeRequest: !!policy.allow_time_request,
    enabled: !!policy.enabled,
    timingMode: policy.timing_mode || 'standard',
    version: policy.version,`);

a = `    const allowTimeRequest =
      body.allowTimeRequest === undefined ? current.allow_time_request : body.allowTimeRequest ? 1 : 0;
    const enabled = body.enabled === undefined ? current.enabled : body.enabled ? 1 : 0;`;
if (!t.includes(a)) throw new Error('update vars anchor');
t = t.replace(a, a + `
    const TIMING_MODES = new Set(['standard', 'recommended', 'system']);
    const timingModeRaw = body.timingMode === undefined ? current.timing_mode : String(body.timingMode);
    if (!TIMING_MODES.has(timingModeRaw)) {
      return reply.code(400).send({ error: 'invalid_timing_mode', message: '计时方式只能是 standard / recommended / system' });
    }`);

a = `      run(
      \`UPDATE policies
          SET weekday_total_min = ?, weekend_total_min = ?, reset_hour = ?,
              list_mode = ?, allow_time_request = ?, enabled = ?
        WHERE device_id = ?\`,
      weekdayTotalMin, weekendTotalMin, resetHour,
      listMode, allowTimeRequest, enabled,
      device.id,
    );`;
if (!t.includes(a)) throw new Error('update sql anchor');
t = t.replace(a, `      run(
      \`UPDATE policies
          SET weekday_total_min = ?, weekend_total_min = ?, reset_hour = ?,
              list_mode = ?, allow_time_request = ?, enabled = ?, timing_mode = ?
        WHERE device_id = ?\`,
      weekdayTotalMin, weekendTotalMin, resetHour,
      listMode, allowTimeRequest, enabled, timingModeRaw,
      device.id,
    );`);

// commitPolicyChange 的审计详情带上 timingMode
a = "    const { bundle, delivered } = commitPolicyChange(device.id, request.claims.userId, 'policy.update', {\n      weekdayTotalMin, weekendTotalMin, resetHour, listMode, allowTimeRequest, enabled: !!enabled,\n    });";
if (!t.includes(a)) throw new Error('audit anchor');
t = t.replace(a, "    const { bundle, delivered } = commitPolicyChange(device.id, request.claims.userId, 'policy.update', {\n      weekdayTotalMin, weekendTotalMin, resetHour, listMode, allowTimeRequest, enabled: !!enabled, timingMode: timingModeRaw,\n    });");
fs.writeFileSync('server/src/routes/policy.js', t);

// 3) 版本 0.2.4
const pkg = JSON.parse(fs.readFileSync('server/package.json', 'utf8'));
pkg.version = '0.2.4';
fs.writeFileSync('server/package.json', JSON.stringify(pkg, null, 2) + '\n');
console.log('server patched, version', pkg.version);
