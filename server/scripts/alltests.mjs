/**
 * 后端全量自检总调度器。
 *
 * 用法：node scripts/alltests.mjs [--no-smoke]
 *
 * 【为什么不用 `npm test`】
 * npm 执行 scripts 时要拉起 `/usr/bin/env bash`，而本机（Git Bash 精简环境）
 * 没有该路径 —— 表现为 `npm test` 直接以
 * `/usr/bin/env: 'bash': No such file or directory` 失败，一条测试都没跑。
 * 本脚本直接用 process.execPath 逐个子进程调用，完全绕开 shell 依赖。
 *
 * 【两阶段】
 *   阶段一（独立库，可与服务并存）：envcheck / preflighttest / dbtest / migrationtest / statetest
 *   阶段二（端到端，需要服务已在运行）：smoke / smoke-m4 / smoke-m5 / smoke-m6
 *
 * 阶段二失败时给出「是不是服务没起」的明确提示 ——
 * 否则一个连不上服务器的 ECONNREFUSED 会被误读成功能回归。
 */
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const serverRoot = path.resolve(here, '..');

const noSmoke = process.argv.includes('--no-smoke');

const PHASE1 = [
  ['envcheck', '环境变量契约'],
  ['preflighttest', '启动前置检查'],
  ['dbtest', '数据库与密码学'],
  ['migrationtest', '老库升级'],
  ['statetest', '指令队列与授权'],
];

const PHASE2 = [
  ['smoke', 'M1–M3 端到端'],
  ['smoke-m4', 'M4 端到端'],
  ['smoke-m5', 'M5 端到端'],
  ['smoke-m6', 'M6 端到端'],
];

const IS_MJS = new Set(['envcheck', 'preflighttest', 'dbtest', 'migrationtest', 'statetest']);
const fileOf = (name) => path.join(here, IS_MJS.has(name) ? `${name}.mjs` : `${name}.js`);

/** 从输出里解析出「N 项通过，M 项失败」 */
function parseCounts(out) {
  const m = /(\d+)\s*项通过[，,]\s*(\d+)\s*项失败/.exec(out);
  if (m) return { passed: Number(m[1]), failed: Number(m[2]) };
  // envcheck 用的是「合计 8 项：通过 8，失败 0」
  const m2 = /通过\s*(\d+)[，,]\s*失败\s*(\d+)/.exec(out);
  if (m2) return { passed: Number(m2[1]), failed: Number(m2[2]) };
  return null;
}

function runSuite(name, label) {
  const file = fileOf(name);
  if (!fs.existsSync(file)) {
    return { name, label, ok: false, error: `脚本不存在：${path.relative(serverRoot, file)}` };
  }

  const started = Date.now();
  const result = spawnSync(process.execPath, ['--disable-warning=ExperimentalWarning', file], {
    cwd: serverRoot,
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    env: process.env,
  });

  const out = `${result.stdout || ''}${result.stderr || ''}`;
  const counts = parseCounts(out);

  return {
    name,
    label,
    ok: result.status === 0,
    status: result.status,
    counts,
    ms: Date.now() - started,
    out,
  };
}

const results = [];

console.log('\n' + '='.repeat(64));
console.log('  掌中灵 — 后端全量自检');
console.log('='.repeat(64) + '\n');

console.log('【阶段一】独立库，不依赖服务\n');
for (const [name, label] of PHASE1) {
  process.stdout.write(`  ${label.padEnd(18)} 运行中… `);
  const r = runSuite(name, label);
  results.push(r);
  const c = r.counts ? `${r.counts.passed}/${r.counts.passed + r.counts.failed}` : (r.ok ? 'ok' : '失败');
  console.log(`${r.ok ? '\u2713' : '\u2717'} ${c}  (${(r.ms / 1000).toFixed(1)}s)`);
  if (!r.ok && !r.counts) {
    console.log(`      ${(r.error || r.out.trim().split('\n').slice(-3).join('\n      '))}`);
  }
}

if (!noSmoke) {
  console.log('\n【阶段二】端到端（需要服务已在 8080 运行）\n');
  for (const [name, label] of PHASE2) {
    process.stdout.write(`  ${label.padEnd(18)} 运行中… `);
    const r = runSuite(name, label);
    results.push(r);
    const c = r.counts ? `${r.counts.passed}/${r.counts.passed + r.counts.failed}` : (r.ok ? 'ok' : '失败');
    console.log(`${r.ok ? '\u2713' : '\u2717'} ${c}  (${(r.ms / 1000).toFixed(1)}s)`);
    if (!r.ok) {
      const connRefused = /ECONNREFUSED|fetch failed|socket hang up/i.test(r.out);
      if (connRefused) {
        console.log('      ⚠ 看起来服务器没在运行 —— 阶段二全部需要它。');
        console.log('        启动：JWT_SECRET=<32位以上密钥> npm start');
        break;
      }
    }
  }
} else {
  console.log('\n（已跳过阶段二：--no-smoke）');
}

/* ---------------------------------------------------------------- 汇总 */

const totalPassed = results.reduce((s, r) => s + (r.counts?.passed ?? 0), 0);
const totalFailed = results.reduce((s, r) => s + (r.counts?.failed ?? 0), 0);
const broken = results.filter((r) => !r.counts && !r.ok);
const allOk = results.every((r) => r.ok) && totalFailed === 0 && broken.length === 0;

console.log('\n' + '='.repeat(64));
console.log(`  合计 ${totalPassed + totalFailed} 项：通过 ${totalPassed}，失败 ${totalFailed}`);
if (broken.length > 0) {
  console.log(`  ⚠ ${broken.length} 套未能给出结果：${broken.map((r) => r.name).join(', ')}`);
}
console.log('='.repeat(64) + '\n');

// 失败时把出错那套的最后若干行打出来，省去手工翻日志
for (const r of results) {
  if (r.ok) continue;
  console.log(`--- ${r.label}（${r.name}）失败输出 ---`);
  const lines = r.out.trim().split(/\r?\n/);
  console.log(lines.slice(-25).join('\n'));
  console.log('');
}

process.exit(allOk ? 0 : 1);
