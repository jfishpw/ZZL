/**
 * 环境变量契约自检。
 *
 * 【为什么需要这个】
 * `deploy/.env.example` 是家长修改服务配置的唯一入口。
 * 一旦 config.js 里的变量名变了、.env.example 忘了跟，
 * 后果是**静默失效**：用户改了配置、服务照常启动、行为毫无变化、什么错都不报。
 * 这不是假设 —— 本文件诞生之前，.env.example 里就有三个名字与 config.js 对不上：
 *
 *     .env.example 写的          config.js 实际读的
 *     COMMAND_TTL_SCREENSHOT  →  COMMAND_TTL_SCREENSHOT_SECONDS
 *     COMMAND_TTL_UNLOCK      →  COMMAND_TTL_CLEAR_LOCK_MINUTES
 *     COMMAND_TTL_GRANT       →  GRANT_TTL_TOTAL_ADD_MINUTES 等三个
 *
 * 同时还有一批变量（截屏目录、截屏上限、加时申请频率……）压根没写进 .env.example。
 *
 * 【校验两个方向】
 *   1. 代码读取、但 .env.example 没写 → 用户不知道它存在，无从调整
 *   2. .env.example 写了、但代码不读 → 用户改了没反应（拼写漂移）
 *
 * 另附：数值型变量的值必须能被解析成有限数字，否则会被 `num()` 静默换成默认值。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const srcDir = path.resolve(here, '../src');
const envExample = path.resolve(here, '../../deploy/.env.example');

/**
 * 不在 .env.example 中暴露的变量：由 Dockerfile 固定，写进 .env 反而容易改坏。
 * 每一项都必须说明"为什么不给用户改"。
 */
const DOCUMENTED_ELSEWHERE = new Map([
  [
    'APP_HOST',
    '容器内必须监听 0.0.0.0，否则 `ports: 8080:8080` 的映射失效、App 连不上；由 Dockerfile 固定',
  ],
]);

let pass = 0;
let fail = 0;
function check(label, ok, extra) {
  if (ok) {
    pass += 1;
    console.log(`  \u2713 ${label}`);
  } else {
    fail += 1;
    console.log(`  \u2717 ${label}${extra !== undefined ? `  → ${JSON.stringify(extra)}` : ''}`);
  }
}

/* ---------------- 收集：代码里读取了哪些变量 ---------------- */

function walkJs(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walkJs(full));
    else if (entry.name.endsWith('.js')) out.push(full);
  }
  return out;
}

const usedInCode = new Map(); // name -> 第一次出现的文件（相对 src）
for (const file of walkJs(srcDir)) {
  const text = fs.readFileSync(file, 'utf8');
  for (const m of text.matchAll(/process\.env\.([A-Z0-9_]+)/g)) {
    if (!usedInCode.has(m[1])) usedInCode.set(m[1], path.relative(srcDir, file));
  }
}

/* ---------------- 收集：.env.example 里声明了哪些变量 ---------------- */

if (!fs.existsSync(envExample)) {
  console.error(`找不到 ${envExample}`);
  process.exit(1);
}

const exampleLines = fs.readFileSync(envExample, 'utf8').split(/\r?\n/);
const declared = new Map(); // name -> value
for (const line of exampleLines) {
  const m = /^([A-Za-z0-9_]+)\s*=\s*(.*)$/.exec(line.trim());
  if (m) declared.set(m[1], m[2]);
}

/* ---------------- 比对 ---------------- */

console.log('\n环境变量契约自检\n');
console.log(`  代码中读取 ${usedInCode.size} 个，.env.example 声明 ${declared.size} 个\n`);

console.log('[1] 代码读取的变量都有文档');
const undocumented = [];
for (const [name, file] of usedInCode) {
  if (declared.has(name)) continue;
  if (DOCUMENTED_ELSEWHERE.has(name)) continue;
  undocumented.push(`${name}（${file}）`);
}
check(
  'config.js 中的每个变量都能在 .env.example 找到',
  undocumented.length === 0,
  undocumented,
);

console.log('\n[2] 白名单里的例外确实没写进 .env.example');
for (const [name, reason] of DOCUMENTED_ELSEWHERE) {
  check(`${name} 未出现在 .env.example`, !declared.has(name), reason);
}

console.log('\n[3] .env.example 里的变量都真的被读取');
const orphans = [];
for (const name of declared.keys()) {
  if (!usedInCode.has(name)) orphans.push(name);
}
check(
  '没有拼写漂移（写了却不生效的变量）',
  orphans.length === 0,
  orphans.length > 0 ? `疑似拼错的变量：${orphans.join(', ')}` : undefined,
);

console.log('\n[4] 数值型变量的取值可解析');
// 键名以这些结尾的变量走 config.js 的 num()，取值非法会被静默换成默认值
const NUMERIC_SUFFIX = /(_TTL_.*|_MINUTES|_SECONDS|_MS|_BYTES|_DAYS|_ATTEMPTS|_PER_HOUR|_MAX_.*|_MIN_INTERVAL_.*|APP_PORT)$/;
const badNumbers = [];
for (const [name, value] of declared) {
  if (!NUMERIC_SUFFIX.test(name)) continue;
  if (value === '') continue; // 留空表示用默认值，允许
  if (!Number.isFinite(Number(value))) badNumbers.push(`${name}=${value}`);
}
check('数值型变量的值都是有限数字', badNumbers.length === 0, badNumbers);

console.log('\n[5] 关键变量非空且合法');
const jwt = declared.get('JWT_SECRET');
check('JWT_SECRET 存在（允许留空，由 install.sh 生成）', jwt !== undefined);
check(
  'JWT_SECRET 若已填写则长度足够',
  jwt === undefined || jwt === '' || jwt.length >= 32,
  jwt && jwt.length < 32 ? `当前仅 ${jwt.length} 字符` : undefined,
);
check(
  'TZ 已声明且不是 UTC 空值',
  declared.get('TZ') !== undefined && declared.get('TZ') !== '',
  declared.get('TZ'),
);
check(
  '截屏目录位于持久化卷 /data 下',
  (declared.get('SCREENSHOT_DIR') ?? '').startsWith('/data'),
  declared.get('SCREENSHOT_DIR'),
);

/* ---------------- 汇总 ---------------- */

console.log(`\n合计 ${pass + fail} 项：通过 ${pass}，失败 ${fail}\n`);
process.exit(fail === 0 ? 0 : 1);
