/**
 * 下载并解压 OpenJDK 17 到 ~/.jdks/temurin-17
 * 用途：AGP 8.7.2 不支持 JDK 25，真实编译需要 JDK 17/21。
 *
 * 说明：Adoptium 官方源在部分网络下会 ECONNRESET，这里改用华为云镜像，
 * 并实现断点续传 + 自动重试。
 *
 * 用法：node scripts/setup-jdk.cjs
 */
const fs = require('node:fs');
const path = require('node:path');
const https = require('node:https');
const { execFileSync } = require('node:child_process');

const URL = 'https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip';

const cacheDir = 'D:/pwg/监管软件/.cache';
const zipPath = path.join(cacheDir, 'openjdk17.zip');
const jdksDir = 'C:/Users/Administrator/.jdks';
const target = path.join(jdksDir, 'temurin-17');

if (fs.existsSync(path.join(target, 'bin', 'java.exe'))) {
  console.log('JDK 17 已存在: ' + target);
  process.exit(0);
}

fs.mkdirSync(cacheDir, { recursive: true });
fs.mkdirSync(jdksDir, { recursive: true });

/** 支持断点续传的单次下载 */
function downloadOnce(url, dest, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > 10) return reject(new Error('重定向次数过多'));

    const startAt = fs.existsSync(dest) ? fs.statSync(dest).size : 0;
    const headers = { 'user-agent': 'node' };
    if (startAt > 0) headers.range = `bytes=${startAt}-`;

    const req = https.get(url, { headers, timeout: 60000 }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        return resolve(downloadOnce(res.headers.location, dest, redirects + 1));
      }

      // 206 = 续传成功；200 = 服务端不支持续传，需从头写
      if (res.statusCode === 200 && startAt > 0) {
        fs.rmSync(dest, { force: true });
      } else if (res.statusCode !== 200 && res.statusCode !== 206) {
        res.resume();
        return reject(new Error('HTTP ' + res.statusCode));
      }

      const total = Number(res.headers['content-length'] || 0) + (res.statusCode === 206 ? startAt : 0);
      let received = res.statusCode === 206 ? startAt : 0;
      let lastReported = -1;

      const out = fs.createWriteStream(dest, { flags: res.statusCode === 206 ? 'a' : 'w' });

      res.on('data', (chunk) => {
        received += chunk.length;
        if (total > 0) {
          const pct = Math.floor((received / total) * 100);
          if (pct !== lastReported && pct % 10 === 0) {
            lastReported = pct;
            console.log(`  ${pct}%  ${(received / 1048576).toFixed(1)} / ${(total / 1048576).toFixed(1)} MB`);
          }
        }
      });

      res.pipe(out);
      out.on('finish', () => out.close(() => resolve(dest)));
      out.on('error', reject);
      res.on('error', reject);
    });

    req.on('error', reject);
    req.on('timeout', () => req.destroy(new Error('连接超时')));
  });
}

async function downloadWithRetry(url, dest, attempts = 6) {
  for (let i = 1; i <= attempts; i += 1) {
    try {
      await downloadOnce(url, dest);
      return;
    } catch (e) {
      console.log(`  第 ${i} 次下载中断（${e.code || e.message}），准备续传…`);
      if (i === attempts) throw e;
      await new Promise((r) => setTimeout(r, 2000));
    }
  }
}

(async () => {
  console.log('开始下载 OpenJDK 17（华为云镜像）…');
  await downloadWithRetry(URL, zipPath);
  console.log('下载完成: ' + (fs.statSync(zipPath).size / 1048576).toFixed(1) + ' MB');

  console.log('解压中 …');
  // Windows 自带 bsdtar，可直接解压 zip
  execFileSync('tar', ['-xf', zipPath, '-C', jdksDir], { stdio: 'inherit' });

  const entries = fs
    .readdirSync(jdksDir)
    .filter((n) => /^jdk-?17/i.test(n) && fs.existsSync(path.join(jdksDir, n, 'bin', 'java.exe')));

  if (entries.length === 0) throw new Error('解压后未找到 JDK 17 目录');

  const extracted = path.join(jdksDir, entries[0]);
  if (extracted !== target) {
    if (fs.existsSync(target)) fs.rmSync(target, { recursive: true, force: true });
    fs.renameSync(extracted, target);
  }

  console.log('已完成: ' + target);
  console.log(execFileSync(path.join(target, 'bin', 'java.exe'), ['-version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }));
})().catch((e) => {
  console.error('失败: ' + (e.message || e.code));
  process.exit(1);
});
