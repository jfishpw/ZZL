/**
 * 重新打包服务器部署包 zzl-deploy.tar.gz。
 *
 * 用法：node scripts/pack-deploy.cjs
 *
 * 包内只含 server/ 与 deploy/ 两个目录 —— 服务器上不需要 android/ 与 docs/，
 * 更不该带上本机的 node_modules（在目标机上由 Docker 构建时安装）
 * 与 data/（那是本机的数据库与截屏，属于隐私数据）。
 *
 * 上传到服务器后：
 *   tar -xzf zzl-deploy.tar.gz -C /opt/zzl
 *   cd /opt/zzl/deploy && ./install.sh
 */
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..');
const OUT = path.join(ROOT, 'zzl-deploy.tar.gz');

const ENTRIES = ['server', 'deploy'];

/** 这些目录/文件不进包，每一项都有明确理由 */
const EXCLUDES = [
  'node_modules', // 目标机上由 Docker 构建时安装（且含本机架构的原生模块）
  '_old_node_modules', // 重置依赖时留下的旧目录
  'data', // 本机数据库与截屏文件 —— 隐私数据，绝不外传
  '.cache', // JDK 等下载缓存
  '.env', // 含 JWT 密钥，服务器上由 install.sh 单独生成
  '*.log',
];

for (const entry of ENTRIES) {
  if (!fs.existsSync(path.join(ROOT, entry))) {
    console.error(`缺少目录：${entry}`);
    process.exit(1);
  }
}

// 打好包后先删掉旧包：tar 若中途失败，留下一个半截的包比没有更危险
if (fs.existsSync(OUT)) fs.rmSync(OUT, { force: true });

const args = ['-czf', path.basename(OUT)];
for (const pattern of EXCLUDES) args.push(`--exclude=${pattern}`);
args.push(...ENTRIES);

/*
 * ⚠️ 归档名必须用**相对文件名**（配合 cwd），不能用 `OUT` 这个绝对路径。
 *
 * GNU tar 把「主机:路径」解析为远程归档，而 Windows 盘符里的冒号会被误判成主机名：
 *   tar -czf D:\pwg\监管软件\zzl-deploy.tar.gz ...
 *   → tar (child): Cannot connect to D: resolve failed   （退出码 2）
 * 这类报错在 Windows 上极具迷惑性，看起来像路径不存在或权限问题。
 * 传相对名对 GNU tar 与 Windows 自带的 bsdtar 都成立。
 */
try {
  execFileSync('tar', args, { cwd: ROOT, encoding: 'utf8' });
} catch (err) {
  // 原实现用 stdio:'inherit'，报错时只剩一句 "Command failed"，
  // 排查还得手工复现一次。这里把 tar 的真实 stderr 打出来。
  console.error(`\n❌ tar 打包失败（退出码 ${err.status ?? '?'}）`);
  if (err.stderr) console.error(String(err.stderr).trim());
  console.error('\n排查提示：若提示 "Cannot connect to X:"，就是归档路径带了盘符冒号；');
  console.error('         若提示找不到文件，先确认当前目录下存在 server/ 与 deploy/。');
  process.exit(1);
}

if (!fs.existsSync(OUT)) {
  console.error('打包失败：未生成 ' + OUT);
  process.exit(1);
}

/* ---------------- 自检：解压清单里不该出现被排除的内容 ---------------- */

const listing = execFileSync('tar', ['-tzf', path.basename(OUT)], { cwd: ROOT, encoding: 'utf8' })
  // Windows 上可能是 bsdtar，清单行以 CRLF 结尾；不处理的话
  // 每行都会带一个尾随 \r，与预期路径怎么比都不相等（自检会全部误报）
  .split(/\r?\n/)
  .map((line) => line.trim())
  .filter(Boolean);

const leaked = listing.filter((p) => EXCLUDES.some((e) => !e.startsWith('*') && p.split('/').includes(e)));
const leakedLogs = listing.filter((p) => p.endsWith('.log'));

console.log('');
console.log(`✅ 已生成 ${path.basename(OUT)}`);
console.log(`   大小   ${(fs.statSync(OUT).size / 1024).toFixed(1)} KB`);
console.log(`   条目   ${listing.length} 个`);

if (leaked.length > 0) {
  console.error(`\n❌ 包内出现了本应排除的内容：\n   ${leaked.slice(0, 10).join('\n   ')}`);
  process.exit(1);
}
if (leakedLogs.length > 0) {
  console.error(`\n❌ 包内出现了日志文件：\n   ${leakedLogs.slice(0, 10).join('\n   ')}`);
  process.exit(1);
}

// 关键文件必须都在，少一个服务器上就会部署失败
const mustHave = [
  'server/Dockerfile',
  'server/package.json',
  'server/package-lock.json',
  'server/src/index.js',
  'server/src/config.js',
  'server/src/db.js',
  'deploy/docker-compose.yml',
  'deploy/.env.example',
  'deploy/install.sh',
  'deploy/activate-device-owner.sh',
];
const missing = mustHave.filter((f) => !listing.includes(f));
if (missing.length > 0) {
  console.error(`\n❌ 包内缺少关键文件：\n   ${missing.join('\n   ')}`);
  process.exit(1);
}

console.log(`   自检   无 node_modules / data / .env 泄漏，${mustHave.length} 个关键文件齐全`);
console.log('');
console.log('上传：scp zzl-deploy.tar.gz root@<服务器IP>:/opt/');
