/**
 * 启动前置检查自检。
 *
 * 这些检查的失败方式是「服务根本起不来」，一旦判错，
 * 用户面对的是一个没有任何输出的死进程 —— 所以必须逐分支固化。
 *
 * 重点覆盖两类容易写错的地方：
 *   1. JWT 密钥：`??` 对空串不生效，导致"留空 = 无鉴权"却能启动
 *   2. 时区：容器缺 tzdata 时 TZ 被静默忽略，额度日整天错位
 * 外加一条工程约束：错误提示不能被 process.exit() 截断。
 */
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const {
  inspectJwtSecret,
  inspectTimezone,
  offsetMinutesOf,
  describeOffset,
} = await import('../src/preflight.js');

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

const text = (r) => r.lines.join('\n');

console.log('\n启动前置检查自检\n');

/* ---------------------------------------------------------------- JWT 密钥 */
console.log('[1] JWT 密钥');

check('空密钥被拒绝', inspectJwtSecret('', false).ok === false);
check(
  '空密钥的提示说明了后果与补救方式',
  /没有鉴权|不做鉴权/.test(text(inspectJwtSecret('', false))) &&
    /openssl rand -hex 32/.test(text(inspectJwtSecret('', false))),
);
check('空密钥在生产环境同样被拒绝', inspectJwtSecret('', true).ok === false);

check(
  '生产环境使用内置默认值被拒绝',
  inspectJwtSecret('dev-secret-please-change-in-production', true).ok === false,
);
check(
  '生产环境短密钥被拒绝',
  inspectJwtSecret('abc123', true).ok === false,
);
check(
  '生产环境 31 字符仍被拒绝（边界）',
  inspectJwtSecret('a'.repeat(31), true).ok === false,
);
check(
  '生产环境 32 字符被接受（边界）',
  inspectJwtSecret('a'.repeat(32), true).ok === true,
);
check(
  '开发环境短密钥放行但给出 warn',
  inspectJwtSecret('short', false).ok === true && /warn/.test(text(inspectJwtSecret('short', false))),
);
check(
  '开发环境长密钥无任何提示',
  inspectJwtSecret('a'.repeat(32), false).lines.length === 0,
);

/* ---------------------------------------------------------------- 时区 */
console.log('\n[2] 时区偏移计算');

check('Asia/Shanghai = +480 分钟', offsetMinutesOf('Asia/Shanghai', new Date('2026-09-19T04:00:00Z')) === 480);
check('UTC = 0 分钟', offsetMinutesOf('UTC', new Date('2026-09-19T04:00:00Z')) === 0);
check('Asia/Tokyo = +540 分钟', offsetMinutesOf('Asia/Tokyo', new Date('2026-09-19T04:00:00Z')) === 540);
check(
  'America/New_York 夏令时为 -240',
  offsetMinutesOf('America/New_York', new Date('2026-07-15T16:00:00Z')) === -240,
);
check(
  'America/New_York 冬令时为 -300（证明取的是真实时区数据而非固定偏移）',
  offsetMinutesOf('America/New_York', new Date('2026-01-15T16:00:00Z')) === -300,
);
check('describeOffset 格式正确', describeOffset(480) === 'UTC+08:00' && describeOffset(-300) === 'UTC-05:00');

console.log('\n[3] 时区校验');

const tzCase = (tz, actual, production = true) =>
  inspectTimezone({ tz, actualOffsetMinutes: actual, production });

check('声明与实际一致（+08:00）通过', tzCase('Asia/Shanghai', 480).ok === true);
check('声明与实际一致（UTC）通过', tzCase('UTC', 0).ok === true);
check(
  '声明 +08:00 而实际是 UTC（镜像缺 tzdata）被拒绝',
  tzCase('Asia/Shanghai', 0).ok === false,
);
check(
  '上述提示点明了 tzdata 这个真实原因',
  /tzdata/.test(text(tzCase('Asia/Shanghai', 0))),
);
check(
  '同一问题在开发环境只告警不阻断',
  tzCase('Asia/Shanghai', 0, false).ok === true &&
    /warn/.test(text(tzCase('Asia/Shanghai', 0, false))),
);
check('无效时区名被拒绝', tzCase('Not/AZone', 480).ok === false);
check('无效时区名的提示给了正确示例', /Asia\/Shanghai/.test(text(tzCase('Not/AZone', 480))));
check(
  '未设 TZ 且实际为 UTC 被拒绝',
  tzCase(undefined, 0).ok === false,
);
check(
  '未设 TZ 且实际为 +08:00 通过（系统时区本就正确，不必强求设置 TZ）',
  tzCase(undefined, 480).ok === true,
);
check(
  '拒绝时的提示包含可执行的修复命令',
  /TZ=Asia\/Shanghai/.test(text(tzCase(undefined, 0))),
);

/* ---------------------------------------------------------------- 输出不被截断 */
console.log('\n[4] 错误提示必须真的能被看到');

const preflightUrl = new URL('../src/preflight.js', import.meta.url).href;

/**
 * 这是本模块存在的理由之一。
 * console.error 在 stdout/stderr 为管道时是异步写的，
 * 紧跟其后的 process.exit() 会丢掉尚未刷出的内容 ——
 * 表现为「退出码 1、零输出」，用户完全不知道发生了什么。
 * 这里就让子进程把输出写进管道后立刻退出，验证内容确实到达了父进程。
 */
const probe = `
import { writeStderr } from ${JSON.stringify(preflightUrl)};
writeStderr('__PREFLIGHT_MARK__');
process.exit(1);
`;
const probed = spawnSync(process.execPath, ['--input-type=module', '-e', probe], { encoding: 'utf8' });

check('子进程退出码为 1', probed.status === 1, probed.status);
check(
  '同步写的内容在 process.exit() 之前已到达管道',
  (probed.stderr || '').includes('__PREFLIGHT_MARK__'),
  JSON.stringify((probed.stderr || '').slice(0, 80)),
);

const multiline = `
import { writeStderr } from ${JSON.stringify(preflightUrl)};
for (let i = 1; i <= 5; i += 1) writeStderr('__LINE_' + i + '__');
process.exit(1);
`;
const probed2 = spawnSync(process.execPath, ['--input-type=module', '-e', multiline], { encoding: 'utf8' });
const allLines = ['__LINE_1__', '__LINE_2__', '__LINE_3__', '__LINE_4__', '__LINE_5__'].every((m) =>
  (probed2.stderr || '').includes(m),
);
check('连续 5 行提示全部保住（不被缓冲区截断）', allLines);

/* ---------------------------------------------------------------- 汇总 */
console.log(`\n合计 ${pass + fail} 项：通过 ${pass}，失败 ${fail}\n`);
process.exit(fail === 0 ? 0 : 1);
