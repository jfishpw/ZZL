/**
 * 用指定的 JDK 运行本地缓存的 Gradle。
 * 用法：node scripts/gradle-run.cjs <jdkHome> <gradleArgs...>
 *
 * 输出直接透传（stdio inherit），便于后台运行时观察进度。
 *
 * Gradle 可执行文件按下列顺序探测：
 *   1. 环境变量 GRADLE_BIN（显式指定）
 *   2. Gradle wrapper 本地发行版缓存（~/.gradle/wrapper/dists/ 下的 gradle-8.13-*）
 *
 * 构建目录可用 GRADLE_CWD 覆盖，默认是 android/。
 */
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const jdkHome = process.argv[2];
const args = process.argv.slice(3);

function findGradle() {
  if (process.env.GRADLE_BIN && fs.existsSync(process.env.GRADLE_BIN)) {
    return process.env.GRADLE_BIN;
  }
  const gradleUserHome = process.env.GRADLE_USER_HOME || path.join(os.homedir(), '.gradle');
  const dists = path.join(gradleUserHome, 'wrapper', 'dists');
  try {
    for (const ver of fs.readdirSync(dists)) {
      if (!/^gradle-8\.13-/.test(ver)) continue;
      for (const hash of fs.readdirSync(path.join(dists, ver))) {
        const candidate = path.join(dists, ver, hash, 'gradle-8.13', 'bin', 'gradle.bat');
        if (fs.existsSync(candidate)) return candidate;
      }
    }
  } catch {
    // 目录不存在则走下面的报错
  }
  return null;
}

const GRADLE = findGradle();

if (!GRADLE) {
  console.error('未找到 Gradle 8.13：请先用 gradle wrapper 同步一次（Android Studio 首次打开会自动下载），');
  console.error('或设置环境变量 GRADLE_BIN 指向 gradle.bat。');
  process.exit(1);
}

const DEFAULT_CWD = path.resolve(__dirname, '..', 'android');

const result = spawnSync('cmd.exe', ['/c', GRADLE, ...args], {
  cwd: process.env.GRADLE_CWD || DEFAULT_CWD,
  env: { ...process.env, JAVA_HOME: jdkHome },
  stdio: 'inherit',
});

process.exit(result.status ?? 1);
