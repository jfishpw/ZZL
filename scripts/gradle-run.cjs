/**
 * 用指定的 JDK 运行本地缓存的 Gradle。
 * 用法：node scripts/gradle-run.cjs <jdkHome> <gradleArgs...>
 *
 * 输出直接透传（stdio inherit），便于后台运行时观察进度。
 *
 * 构建目录可用 GRADLE_CWD 覆盖，默认是 android/ 的**真实路径**。
 */
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const jdkHome = process.argv[2];
const args = process.argv.slice(3);

const GRADLE = 'C:/Users/Administrator/.gradle/wrapper/dists/gradle-8.13-all/54h0s9kvb6g2sinako7ub77ku/gradle-8.13/bin/gradle.bat';

if (!fs.existsSync(GRADLE)) {
  console.error('Gradle 未找到: ' + GRADLE);
  process.exit(1);
}

/**
 * ★ 默认从**真实路径**构建，而不是 D:\pwg\zzl-build 这个 ASCII 联接。
 *
 * 历史上创建该联接是为了绕开 AGP 的「路径含非 ASCII 字符」拒绝。
 * 但现在 android/gradle.properties 里已开启 android.overridePathCheck=true，
 * 真实路径可以直接构建 —— 而联接反而带来一个更难查的问题：
 *
 *   **命令沙箱不解析目录联接。** 它取命令行里的路径字符串，
 *   判定 D:\pwg\zzl-build\... 落在工作目录（D:\pwg\监管软件）之外，直接拒绝读写。
 *   底层报出来的却是 AccessDeniedException / FileNotFoundException，
 *   看起来完全像文件锁或杀软，实测为此排查了很久。
 *
 * 两者产物是同一个目录（联接本就指向 android/），所以改用真实路径没有任何副作用。
 * 若某个工具链确实因中文路径失败，可显式传 GRADLE_CWD 切回联接
 * （但那时需要以允许访问该路径的方式运行）。
 */
const DEFAULT_CWD = path.resolve(__dirname, '..', 'android');

const result = spawnSync('cmd.exe', ['/c', GRADLE, ...args], {
  cwd: process.env.GRADLE_CWD || DEFAULT_CWD,
  env: { ...process.env, JAVA_HOME: jdkHome },
  stdio: 'inherit',
});

process.exit(result.status ?? 1);
