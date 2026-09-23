/*
 * 敏感信息体检：分享代码库或文档之前跑一次。
 *
 *   node scripts/check-sensitive.cjs
 *
 * 只报告「文件:行 + 掩码后的大致形状」，永远不回显任何口令或完整地址，
 * 因此可以安全地把输出贴给别人。
 *
 * 已排除：依赖锁文件、本机私有配置（android/local.properties）、
 *         签名密钥目录、以及 .workbuddy/memory 工作日志。
 * 白名单：RFC 5737 文档示例地址（203.0.113.0/24 等）、私有网段、
 *         以及各类公共服务的域名。
 */
const fs = require('fs');
const path = require('path');
const root = process.cwd();
const skip = /^(node_modules|build|\.gradle|\.git|\.idea|dist)$/;
const keep = /\.(kt|kts|js|mjs|cjs|json|md|html|sh|yml|yaml|properties|example|xml|txt|java|pro|env)$/;
const patterns = [
  ['公网IP', /\b(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\b/g],
  ['疑似口令', /(?:JWT_SECRET|STORE_PASSWORD|KEY_PASSWORD|KEYSTORE_PASS|PASSWORD|SECRET|TOKEN|ACCESS_KEY_ID|ACCESS_KEY_SECRET|ACCESS_KEY)\s*[:=]\s*(\S+)/gi],
  ['链接', /https?:\/\/([^\s"'<>)\]/]+)/gi],
  ['手机号', /\b1[3-9]\d{9}\b/g],
  ['邮箱', /\b([\w.+-]+)@([\w-]+\.[\w.]+)\b/g],
];
const safeIp = /^(127\.|0\.0\.0\.0|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.|255\.|203\.0\.113\.|198\.51\.100\.|192\.0\.2\.)/;
const safeHost = /(localhost|example\.(com|org|net)|w3\.org|schema\.org|json-schema|nodejs\.org|gradle\.org|maven\.apache\.org|googleapis\.com|android\.com|kotlinlang\.org|jetbrains\.com|apache\.org|npmjs\.(com|org)|github\.com|githubusercontent|opensource\.org|fastify\.dev|socket\.io|jsdelivr|unpkg|gstatic|sqlite\.org|github\.io|docker\.com|ipify\.org|aliyuncs\.com|adoptium\.net|oracle\.com|tuna\.tsinghua|aliyun\.com)/i;
const hideSecret = (s) => '«已设置» (len=' + s.length + ')';
const mask = (s) => (s.length <= 6 ? s[0] + '***' : s.slice(0, 4) + '***' + s.slice(-2) + ' (len=' + s.length + ')');
const EXCLUDE = /(android\/local\.properties$|\.workbuddy\/memory\/|keystore\/|package-lock\.json$)/;
const groups = {};
const add = (k, loc, d) => { (groups[k] = groups[k] || []).push(loc + '  ' + d); };
function check(p) {
  const rel = path.relative(root, p).split(path.sep).join('/');
  if (EXCLUDE.test(rel)) return;
  fs.readFileSync(p, 'utf8').split(/\r?\n/).forEach((t, i) => {
    for (const [k, re] of patterns) {
      re.lastIndex = 0;
      let m;
      while ((m = re.exec(t)) !== null) {
        const v = m[1] || m[0];
        if (k === '公网IP') { if (!safeIp.test(m[0]) && !safeHost.test(m[0])) add(k, rel + ':' + (i + 1), mask(m[0])); }
        else if (k === '链接') { if (!safeHost.test(v)) add(k, rel + ':' + (i + 1), mask(v)); }
        else if (k === '疑似口令') { if (!/^(\$\{|<|""|''|your|YOUR|changeme|CHANGE|xxx|\*\*\*)/.test(v) && v.length >= 8) add(k, rel + ':' + (i + 1), hideSecret(v)); }
        else add(k, rel + ':' + (i + 1), k === '邮箱' ? mask(m[1]) + '@' + m[2] : mask(m[0]));
      }
    }
  });
}
function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    if (skip.test(e.name)) continue;
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (keep.test(e.name)) check(p);
  }
}
walk(root);
const keys = Object.keys(groups).sort();
if (!keys.length) console.log('未发现需要关注的敏感信息。');
for (const k of keys) {
  console.log('\n## ' + k + '（' + groups[k].length + ' 处）');
  for (const l of groups[k]) console.log('   ' + l);
}
console.log('\n提示：口令类条目只报长度；请人工确认这些是否为真实凭据。');
