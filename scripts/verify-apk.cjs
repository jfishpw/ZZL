/**
 * 校验 release APK 的签名与包信息。
 *
 * 用法：node scripts/verify-apk.cjs
 *
 * 【为什么必须单独验一遍】
 * 签名不对的 APK，构建过程**不会有任何报错**，但装到手机上会失败，
 * 或者更糟 —— 装上了却无法覆盖升级。而本项目只有一次真机机会，
 * 不能把"能不能装"留到那时才发现。
 *
 * 这里用 Android SDK 自带的两个权威工具逐项核对：
 *   apksigner  签名是否有效、用的哪个证书、v1/v2/v3 方案是否齐全
 *   aapt2      applicationId / versionName / 权限等清单信息
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..');
const SDK = process.env.ANDROID_SDK_ROOT
  || path.join(require('node:os').homedir(), 'AppData', 'Local', 'Android', 'Sdk');
const BUILD_TOOLS = process.env.BUILD_TOOLS || '34.0.0';

const APKSIGNER = path.join(SDK, 'build-tools', BUILD_TOOLS, 'apksigner.bat');
const AAPT2 = path.join(SDK, 'build-tools', BUILD_TOOLS, 'aapt2.exe');
const APK_BASE = path.join(ROOT, 'android', 'app', 'build', 'outputs', 'apk');

/** release 包必须存在；debug 包只作对照 */
const TARGETS = [
  { role: 'child', label: '被控端（儿童平板）', file: 'app-child-release.apk', expectId: 'com.zzl.guardian.child' },
  { role: 'parent', label: '控制端（家长手机）', file: 'app-parent-release.apk', expectId: 'com.zzl.guardian.parent' },
];

let pass = 0;
let fail = 0;
function check(label, ok, extra) {
  if (ok) {
    pass += 1;
    console.log(`  \u2713 ${label}`);
  } else {
    fail += 1;
    console.log(`  \u2717 ${label}${extra !== undefined ? `  → ${extra}` : ''}`);
  }
}

/** 只作说明、不计入通过率的观察项 */
function note(label, detail) {
  console.log(`  \u2139 ${label}${detail ? `：${detail}` : ''}`);
}

/** 运行外部命令，返回 { ok, out } */
function run(cmd, args) {
  const r = spawnSync('cmd.exe', ['/c', cmd, ...args], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
  return { ok: r.status === 0, out: `${r.stdout || ''}${r.stderr || ''}`.trim(), status: r.status };
}

console.log('\nRelease APK 签名与清单校验\n');

if (!fs.existsSync(APKSIGNER)) {
  console.error('找不到 apksigner：' + APKSIGNER);
  console.error('请确认已安装 build-tools ' + BUILD_TOOLS + '，或用 BUILD_TOOLS 环境变量指定版本。');
  process.exit(1);
}

let allGood = true;

for (const target of TARGETS) {
  const apk = path.join(APK_BASE, target.role, 'release', target.file);

  console.log(`[${target.role}] ${target.label}`);
  console.log(`     ${apk}`);

  if (!fs.existsSync(apk)) {
    check('产物存在', false, '文件不存在 —— 请先运行 node scripts/build-release.cjs');
    allGood = false;
    console.log('');
    continue;
  }

  const sizeMb = (fs.statSync(apk).size / 1048576).toFixed(2);
  check(`产物存在（${sizeMb} MB）`, true);

  /* ---------------- 签名 ---------------- */

  const verify = run(APKSIGNER, ['verify', '--verbose', apk]);

  check('apksigner verify 通过', verify.ok, verify.out.split('\n').slice(0, 3).join(' / '));

  if (verify.ok) {
    // ---- 签名方案与 minSdk 的对应关系 ----
    //
    //   v1（JAR 签名）        API ≤ 23（Android 6 及以下）才需要
    //   v2（APK Sig Scheme）  API ≥ 24（Android 7.0 起）—— 本项目 minSdk 26，**必需**
    //   v3（加 v2 之上）      API ≥ 28，作用是支持**签名密钥轮换**，可选
    //
    // 本项目 minSdk = 26，因此 AGP 只启用 v2 是**正确行为**，不是缺陷。
    // 一开始这里把三种方案都写成"必须启用"，结果误报了 4 项失败 ——
    // 校验脚本的断言同样要对齐真实约束，否则会把正确产物判成坏的。
    const v2 = /Verified using v2 scheme.*true/i.test(verify.out);
    const v1 = /Verified using v1 scheme.*true/i.test(verify.out);
    const v3 = /Verified using v3 scheme.*true/i.test(verify.out);

    check('v2 签名方案已启用（minSdk 26 的必需项）', v2);

    // v1 只对 Android 6 及以下有意义；本项目 minSdk 26，不需要它
    note('v1（JAR）签名', v1 ? '已启用' : '未启用 — minSdk 26 不需要，属正常');

    // v3 的价值是"日后可以轮换签名密钥"；不启用不影响安装
    note(
      'v3 签名',
      v3
        ? '已启用'
        : '未启用 — 不影响安装；如需日后轮换密钥，可在 signingConfig 中开启 enableV3Signing',
    );

    // 用 debug 证书签出来的 release 包能装但不可信，必须区分开
    const signer = run(APKSIGNER, ['verify', '--print-certs', apk]);
    const dn = /Signer #1 certificate DN:\s*(.+)/i.exec(signer.out)?.[1]?.trim() ?? '';
    check('不是 Android 默认调试证书', !/Android Debug/i.test(dn), dn || '未取到 DN');
    check('证书主体与本项目一致', /ZhangZhongling/i.test(dn), dn);

    const digests = [...signer.out.matchAll(/^\s*(SHA-1|SHA-256) digest:\s*([0-9a-f]+)/gim)];
    for (const [, algo, value] of digests) {
      console.log(`      ${algo}  ${value.slice(0, 32).toUpperCase()}…`);
    }
  }

  /* ---------------- 清单 ---------------- */

  const badging = run(AAPT2, ['dump', 'badging', apk]);
  if (badging.ok) {
    const pkg = /^package:\s*name='([^']+)'.*versionCode='([^']+)'.*versionName='([^']+)'/m.exec(badging.out);
    check(`applicationId 正确`, pkg?.[1] === target.expectId, pkg?.[1]);
    check(`versionCode 为 ${pkg?.[2]}`, /^\d+$/.test(pkg?.[2] ?? ''), pkg?.[2]);
    check(`versionName 为 ${pkg?.[3]}`, /^\d+\.\d+\.\d+/.test(pkg?.[3] ?? ''), pkg?.[3]);

    const hasDebuggable = /application-debuggable/.test(badging.out);
    check('未标记为可调试（release 包不应带 debuggable）', !hasDebuggable);

    // 被控端的关键权限一个都不能少，否则装机时才发现就晚了
    if (target.role === 'child') {
      const required = [
        'android.permission.PACKAGE_USAGE_STATS',
        'android.permission.SYSTEM_ALERT_WINDOW',
        'android.permission.FOREGROUND_SERVICE',
        'android.permission.RECEIVE_BOOT_COMPLETED',
        'android.permission.SCHEDULE_EXACT_ALARM',
        'android.permission.QUERY_ALL_PACKAGES',
      ];
      const missing = required.filter((p) => !badging.out.includes(p));
      check(`被控端 ${required.length} 项关键权限齐全`, missing.length === 0, missing.join(', '));
    }

    const launcher = /launchable-activity: name='([^']+)'/.exec(badging.out)?.[1];
    check('有启动入口（桌面图标可点开）', Boolean(launcher), launcher);
  } else {
    check('aapt2 dump badging 成功', false, badging.out.split('\n')[0]);
  }

  if (!verify.ok) allGood = false;
  console.log('');
}

/* ---------------- dist/ 与本次构建产物是否同一份 ---------------- */
/*
 * dist/ 才是真正拿去装机的那份。它若停在上一轮构建，**光看时间戳发现不了**：
 * fs.copyFileSync 走的是 Windows CopyFileEx，会保留源文件的时间戳，
 * 于是新包和旧包看起来都"像"是构建时间。所以这里直接比 sha256。
 */
console.log('【交付目录】dist/ 是否为本次构建的产物');
const DIST = path.join(ROOT, 'dist');
if (!fs.existsSync(DIST)) {
  check('dist/ 存在', false, '先跑 node scripts/build-release.cjs');
} else {
  const sha = (p) => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
  for (const t of TARGETS) {
    const src = path.join(APK_BASE, t.role, 'release', t.file);
    if (!fs.existsSync(src)) {
      note(`${t.role} 构建产物缺失`, '本次未构建该变体，跳过');
      continue;
    }
    const candidates = fs
      .readdirSync(DIST)
      .filter((f) => f.startsWith(`zzl-${t.role}-v`) && f.endsWith('-release.apk'));
    if (candidates.length === 0) {
      check(`dist/ 里有 ${t.role} 的 release 包`, false, '先跑 node scripts/build-release.cjs');
      continue;
    }
    check(
      `dist/ 中 ${t.role} 只有一份 release 包`,
      candidates.length === 1,
      candidates.length > 1 ? `发现 ${candidates.length} 份：${candidates.join(' / ')}` : undefined,
    );
    const same = sha(path.join(DIST, candidates[0])) === sha(src);
    check(
      `dist/ 的 ${t.role} 包与本次构建内容一致`,
      same,
      same ? undefined : '内容不同 → 重新跑 node scripts/build-release.cjs（否则装的是旧包）',
    );
  }
}
console.log('');

console.log(`合计 ${pass + fail} 项：通过 ${pass}，失败 ${fail}\n`);
process.exit(fail === 0 && allGood ? 0 : 1);
