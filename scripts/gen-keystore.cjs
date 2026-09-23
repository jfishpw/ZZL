/**
 * 生成 release 签名密钥（keystore），并把签名配置写入 android/local.properties。
 *
 * 用法：
 *   node scripts/gen-keystore.cjs            # 生成（已存在则跳过，保护现有密钥）
 *   node scripts/gen-keystore.cjs --force    # 强制重建（⚠️ 会导致无法覆盖升级）
 *   node scripts/gen-keystore.cjs --show     # 只打印当前签名配置
 *
 * 背景：Android 要求 release APK 必须签名才能安装；自签名证书足够，
 * 不需要付费、不需要上架应用商店。
 *
 * ⚠️ keystore 一旦丢失，后续版本将无法覆盖安装，只能卸载重装。
 *    生成后请立刻把 keystore 文件与 local.properties 一起备份到安全位置。
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');

const ANDROID_DIR = 'D:/pwg/监管软件/android';
const KEYSTORE_REL = 'keystore/zhangzhongling.jks'; // 相对 android/ （build.gradle.kts 用 rootProject.file 解析）
const KEYSTORE_ABS = path.join(ANDROID_DIR, KEYSTORE_REL);
const LOCAL_PROPS = path.join(ANDROID_DIR, 'local.properties');
const ALIAS = 'zhangzhongling';
const VALIDITY_DAYS = 10000; // ≈27 年

const JDK_HOME = 'C:/Users/Administrator/.jdks/temurin-17';
const KEYTOOL = path.join(JDK_HOME, 'bin', 'keytool.exe');

const force = process.argv.includes('--force');
const showOnly = process.argv.includes('--show');

/** 生成 32 位 base62 随机口令（约 190 bit 熵，远高于 keytool 的最短要求） */
function randomPassword() {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  const bytes = crypto.randomBytes(32);
  let out = '';
  for (let i = 0; i < 32; i += 1) out += alphabet[bytes[i] % alphabet.length];
  return out;
}

/** 读取 local.properties 为 { key: value }，保留原始行序以便回写 */
function readProps(file) {
  if (!fs.existsSync(file)) return { lines: [], map: new Map() };
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  const map = new Map();
  for (const line of lines) {
    const m = /^\s*([A-Za-z0-9_.]+)\s*=\s*(.*)$/.exec(line);
    if (m) map.set(m[1], m[2]);
  }
  return { lines, map };
}

// ---------------------------------------------------------------- --show
if (showOnly) {
  const { map } = readProps(LOCAL_PROPS);
  console.log('keystore 文件 : ' + KEYSTORE_ABS + (fs.existsSync(KEYSTORE_ABS) ? '  [存在]' : '  [不存在]'));
  console.log('local.properties: ' + (fs.existsSync(LOCAL_PROPS) ? LOCAL_PROPS : '  [不存在]'));
  for (const k of ['RELEASE_STORE_FILE', 'RELEASE_STORE_PASSWORD', 'RELEASE_KEY_ALIAS', 'RELEASE_KEY_PASSWORD']) {
    const v = map.get(k);
    // 口令不回显全量，只显示长度，避免截图/日志泄漏
    const display = k.endsWith('PASSWORD') ? (v ? `已设置（${v.length} 位）` : '未设置') : (v ?? '未设置');
    console.log(`${k.padEnd(24)}= ${display}`);
  }
  process.exit(0);
}

// ---------------------------------------------------------------- 前置检查
if (!fs.existsSync(KEYTOOL)) {
  console.error('未找到 keytool: ' + KEYTOOL);
  console.error('请先运行 node scripts/setup-jdk.cjs 下载 JDK 17。');
  process.exit(1);
}

if (fs.existsSync(KEYSTORE_ABS) && !force) {
  console.log('keystore 已存在，跳过生成（保护现有密钥，避免破坏后续版本的覆盖升级）:');
  console.log('  ' + KEYSTORE_ABS);
  console.log('');
  console.log('如需查看当前签名配置：node scripts/gen-keystore.cjs --show');
  console.log('⚠️ 仅在确定要放弃覆盖升级能力时才使用 --force 重建。');
  process.exit(0);
}

if (fs.existsSync(KEYSTORE_ABS) && force) {
  const backup = KEYSTORE_ABS + '.old-' + Date.now();
  fs.renameSync(KEYSTORE_ABS, backup);
  console.log('原 keystore 已改名备份到: ' + backup);
}

fs.mkdirSync(path.dirname(KEYSTORE_ABS), { recursive: true });

// ---------------------------------------------------------------- 生成
const storePassword = randomPassword();
const keyPassword = storePassword; // PKCS12 下 keypass 与 storepass 必须一致

console.log('正在生成签名密钥…');
console.log('  文件   ' + KEYSTORE_ABS);
console.log('  别名   ' + ALIAS);
console.log('  算法   RSA 2048 位 / 有效期 ' + VALIDITY_DAYS + ' 天');

const result = spawnSync(
  KEYTOOL,
  [
    '-genkeypair',
    '-v',
    '-keystore', KEYSTORE_ABS,
    '-alias', ALIAS,
    '-keyalg', 'RSA',
    '-keysize', '2048',
    '-validity', String(VALIDITY_DAYS),
    '-storetype', 'PKCS12',
    '-storepass', storePassword,
    '-keypass', keyPassword,
    // 全 ASCII，避免不同 locale 下 keytool 解析 DN 出问题
    '-dname', 'CN=ZhangZhongling, OU=Guardian, O=Personal, L=Hangzhou, ST=Zhejiang, C=CN',
  ],
  { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
);

if (result.status !== 0) {
  console.error('keytool 执行失败（退出码 ' + result.status + '）');
  console.error(result.stderr || result.stdout);
  process.exit(1);
}

// ---------------------------------------------------------------- 写入 local.properties
const { lines, map } = readProps(LOCAL_PROPS);

const wanted = {
  RELEASE_STORE_FILE: KEYSTORE_REL,
  RELEASE_STORE_PASSWORD: storePassword,
  RELEASE_KEY_ALIAS: ALIAS,
  RELEASE_KEY_PASSWORD: keyPassword,
};

const seen = new Set();
const out = [];
for (const line of lines) {
  const m = /^\s*([A-Za-z0-9_.]+)\s*=/.exec(line);
  if (m && Object.prototype.hasOwnProperty.call(wanted, m[1])) {
    // 已注释掉的历史配置不覆盖，跳过（后面统一追加）
    if (!/^\s*#/.test(line)) {
      seen.add(m[1]);
      out.push(`${m[1]}=${wanted[m[1]]}`);
      continue;
    }
  }
  out.push(line);
}

const missing = Object.keys(wanted).filter((k) => !seen.has(k));
if (missing.length > 0) {
  // 去掉尾部的空行，保证追加块紧贴内容
  while (out.length > 0 && out[out.length - 1].trim() === '') out.pop();
  out.push('');
  out.push('# ===== Release 签名（由 scripts/gen-keystore.cjs 生成，勿提交版本库）=====');
  out.push('# ⚠️ 口令丢失将无法覆盖升级，请与 keystore 文件一并备份。');
  for (const k of missing) out.push(`${k}=${wanted[k]}`);
}

fs.writeFileSync(LOCAL_PROPS, out.join('\n') + '\n', 'utf8');

// ---------------------------------------------------------------- 校验
const verify = spawnSync(KEYTOOL, ['-list', '-v', '-keystore', KEYSTORE_ABS, '-storepass', storePassword], {
  encoding: 'utf8',
  stdio: ['ignore', 'pipe', 'pipe'],
});

if (verify.status !== 0) {
  console.error('签名密钥自检失败: ' + (verify.stderr || ''));
  process.exit(1);
}

const sha256 = crypto.createHash('sha256').update(fs.readFileSync(KEYSTORE_ABS)).digest('hex').slice(0, 16).toUpperCase();

console.log('');
console.log('✅ 签名密钥已生成并通过自检');
console.log('   keystore SHA-256(前16位)  ' + sha256);
console.log('   签名配置已写入            ' + LOCAL_PROPS);
console.log('');
console.log('⚠️  请立刻备份以下两项到安全位置（云盘 / 密码管理器）：');
console.log('     1) ' + KEYSTORE_ABS);
console.log('     2) ' + LOCAL_PROPS + '（其中保存着签名口令）');
console.log('   丢失后新版本无法覆盖安装，只能卸载重装并重新配对设备。');
