/**
 * 构建双角色 release APK（含签名）。
 *
 * 用法：node scripts/build-release.cjs [--minify]
 *
 * ---------------------------------------------------------------------------
 * 构建路径说明
 * ---------------------------------------------------------------------------
 * 默认从 android/ 的真实路径构建。若你的项目路径包含非 ASCII 字符，
 * 需要保留 android/gradle.properties 里的 `android.overridePathCheck=true`，
 * 否则 AGP 在 Windows 上会直接拒绝构建。
 *
 * ---------------------------------------------------------------------------
 * 保留下来的第二类故障处置：构建中间产物被并发占用
 * ---------------------------------------------------------------------------
 * 症状（同一根因会在不同任务上换着出现）：
 *   mergeChildReleaseJavaResource → ...\incremental\...\zip-cache\<hash>= (拒绝访问。)
 *   dexBuilderChildRelease        → ...\project_dex_archive\...jar (AccessDenied)
 *
 * 实测诱因是**同时跑两个 Gradle 构建**（哪怕先起的那个"看起来"已结束）。
 * 因此清理 + `--no-parallel` + 失败重试一次的处置依然保留 ——
 * 代价很低，而它能挡掉一整类让人误判方向的假故障。
 *
 * ⚠️ 不要在别处同时跑 gradle 或跑满载 CPU 的任务，会重现同一错误。
 */
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const ROOT = path.resolve(__dirname, '..');
const JDK = process.env.JDK_HOME || path.join(os.homedir(), '.jdks', 'temurin-17');
const GRADLE_RUN = path.join(ROOT, 'scripts', 'gradle-run.cjs');
const LOG = path.join(ROOT, '.workbuddy', 'release-build.log');

/**
 * 构建目录：默认用**真实路径**（见文件头说明）。
 * gradle-run.cjs 读取 GRADLE_CWD 决定工作目录，这里同时用它定位中间产物，
 * 保证"构建写到哪里"和"清理清哪里"始终是同一个地方。
 */
const BUILD_DIR = process.env.GRADLE_CWD || path.join(ROOT, 'android');

const minify = process.argv.includes('--minify');
const NODE = process.execPath;

function log(msg) {
  console.log(msg);
  fs.appendFileSync(LOG, msg + '\n');
}

fs.mkdirSync(path.dirname(LOG), { recursive: true });
fs.writeFileSync(LOG, `release 构建 — ${new Date().toISOString()}\n构建目录 ${BUILD_DIR}\n\n`);

/* ---------------------------------------------------------------- 1. 停守护进程 */

/**
 * 停止 Gradle 守护进程。
 *
 * ⚠️ **必须带超时**。实测 `gradle --stop` 在守护进程注册表异常时会**无限挂起** ——
 * 整个构建脚本就卡在这一行，日志停在"停止 Gradle 守护进程…"十几分钟不动，
 * 看起来像构建卡死，实际连编译都还没开始。
 * 这一步只是"尽量释放句柄"的优化，失败或超时都不该阻塞构建。
 */
const STOP_TIMEOUT_MS = 30_000;

function stopDaemons() {
  const r = spawnSync(NODE, [GRADLE_RUN, JDK, '--stop'], {
    stdio: 'ignore',
    timeout: STOP_TIMEOUT_MS,
    killSignal: 'SIGKILL',
    env: { ...process.env, GRADLE_CWD: BUILD_DIR },
  });
  if (r.error || r.signal) {
    log(`    (--stop 未在 ${STOP_TIMEOUT_MS / 1000}s 内结束，跳过 —— 不影响后续构建)`);
  }
}

log('[*] 停止 Gradle 守护进程（释放被占用的缓存文件句柄）…');
log(`    上限 ${STOP_TIMEOUT_MS / 1000}s，超时则跳过`);
stopDaemons();
log('    完成');

/* ---------------------------------------------------------------- 2. 清中间产物 */

const INTERMEDIATES = path.join(BUILD_DIR, 'app', 'build', 'intermediates');

/**
 * 清掉 Release 变体的中间产物；返回清除数量。
 *
 * ⚠️ 这里必须看**两层**目录，只看一层等于什么都没删。
 * AGP 的顶层中间产物目录名**不含变体名**，变体是第二层：
 *     intermediates/project_dex_archive/childRelease/...      ← 报错的是这个
 *     intermediates/merged_java_res/childRelease/...
 * 唯一的例外是 incremental/，变体名就在它自己的第一层：
 *     intermediates/incremental/childRelease-mergeJavaRes/
 * 两类都覆盖，才不会出现"清理成功但实际没清"这种更难查的空操作。
 */
function clearReleaseIntermediates() {
  let removed = 0;
  if (!fs.existsSync(INTERMEDIATES)) return removed;

  const rm = (target, label) => {
    try {
      // maxRetries 别给太高：这些目录里动辄上万个中间文件，
      // 一旦有文件被占用，每个都要重试 `maxRetries × retryDelay` 毫秒，
      // 累加起来能让"清理"这一步独自跑十几分钟（实测踩过）。
      fs.rmSync(target, { recursive: true, force: true, maxRetries: 2, retryDelay: 200 });
      removed += 1;
      return true;
    } catch (e) {
      // 删不掉就继续：硬失败反而会挡住本来能成功的构建
      log(`    ⚠ 清除 ${label} 失败（${e.code || e.message}），继续尝试构建`);
      return false;
    }
  };

  for (const top of fs.readdirSync(INTERMEDIATES)) {
    const topPath = path.join(INTERMEDIATES, top);
    let topStat;
    try {
      topStat = fs.statSync(topPath);
    } catch {
      continue;
    }
    if (!topStat.isDirectory()) continue;

    // 第一层就带变体名的（incremental 这类）
    if (/Release/.test(top)) {
      rm(topPath, top);
      continue;
    }

    // 第二层带变体名的（project_dex_archive / classes / javac ... 这类）
    let children;
    try {
      children = fs.readdirSync(topPath);
    } catch {
      continue;
    }
    for (const child of children) {
      if (!/Release/.test(child)) continue;
      rm(path.join(topPath, child), `${top}/${child}`);
    }
  }
  return removed;
}

/* ---------------------------------------------------------------- 3. 串行构建 */

/**
 * 构建任务。默认两个角色都编；
 * 也可显式指定，例如只編被控端以便更快拿到反馈：
 *   node scripts/build-release.cjs :app:assembleChildRelease
 * 只编一个角色能把等待时间减半，适合排错时快速验证。
 */
const cliTasks = process.argv.slice(2).filter((a) => a.startsWith(':'));
const tasks = cliTasks.length > 0
  ? cliTasks
  : [':app:assembleChildRelease', ':app:assembleParentRelease'];

/**
 * `--no-parallel` 是这里的关键：
 * gradle.properties 里开着 org.gradle.parallel=true，会让多个 dex/jar worker
 * 同时写同一个 build 目录 —— 在 Windows + junction 环境下正是产生
 * AccessDeniedException 的直接原因。release 构建慢一点无所谓。
 *
 * 输出直接写进日志文件的**文件描述符**而不是先缓冲到内存：
 * 一次 release 构建要 3–15 分钟，攒到最后才落盘的话，
 * 期间完全看不到进度，也没法判断它到底是在编译还是已经卡死。
 */
function runBuild() {
  const args = [GRADLE_RUN, JDK, ...tasks, '--no-parallel'];
  if (minify) args.push('-PenableMinify=true');

  const fd = fs.openSync(LOG, 'a');
  const startOffset = fs.statSync(LOG).size;

  let status;
  try {
    const r = spawnSync(NODE, args, {
      cwd: ROOT,
      stdio: ['ignore', fd, fd],
      // 显式传 GRADLE_CWD：gradle-run.cjs 用它决定工作目录，
      // 必须与上面算 INTERMEDIATES 用的 BUILD_DIR 是同一个值
      env: { ...process.env, GRADLE_CWD: BUILD_DIR },
    });
    status = r.status;
  } finally {
    fs.closeSync(fd);
  }

  // 只回读本次构建产生的那一段，用于判定失败类型与打印摘要
  const all = fs.readFileSync(LOG, 'utf8');
  return { status, out: all.slice(startOffset) };
}

let build = { status: 1, out: '' };

/**
 * 先直接构建，**只有失败了才清理重试**。
 *
 * 不要在开头无条件清一次：这些目录动辄上万个中间文件，
 * 递归删除本身就是分钟级操作，还会因少量被占用的文件反复重试 ——
 * 实测"先清再建"这一步单独就能耗掉十几分钟，
 * 而绝大多数情况下原本的中间产物是好的、根本不需要清。
 * 把清理挪到失败之后，正常情况下完全省掉这笔开销。
 */
for (let attempt = 1; attempt <= 2; attempt += 1) {
  log('');
  log(`[*] 开始构建${minify ? '（已开启 R8 混淆）' : ''}${attempt > 1 ? `（第 ${attempt} 次尝试，已清理中间产物）` : ''}…`);
  log(`    任务 ${tasks.join(' ')} --no-parallel`);
  log('');

  build = runBuild();
  if (build.status === 0) break;

  // 只有"文件被拒绝访问"这一类失败值得重试：成因是瞬时锁，
  // 清掉残留状态后重来往往能过。代码错误重试一百次也一样，
  // 硬重试只会让人误以为问题会自己好。
  const accessDenied = /AccessDeniedException|拒绝访问|FileNotFoundException/.test(build.out);
  if (!accessDenied) {
    log('[!] 失败原因不是文件访问冲突，不做重试');
    break;
  }

  // 被沙箱拦下的情况重试没有意义 —— 那是策略问题，不是瞬时锁。
  // 而且它的表象（AccessDenied）与文件锁完全一样，必须显式点破，
  // 否则会一直在"清理缓存再试"的圈子里打转（实测绕了很久）。
  if (/沙箱|sandbox/i.test(build.out)) {
    log('');
    log('[!] 检测到失败原因是**命令沙箱拦截**，不是文件锁。重试无用。');
    log('    沙箱不解析目录联接，路径字符串落在工作目录之外就会被拒绝。');
    log(`    当前构建目录：${BUILD_DIR}`);
    log('    若它不在 android/ 真实路径下（GRADLE_CWD 指向了别处），请去掉 GRADLE_CWD 重试。');
    break;
  }

  if (attempt === 2) break;

  log('[!] 构建失败于文件访问冲突，停止守护进程并清理中间产物后重试…');
  stopDaemons();
  const cleared = clearReleaseIntermediates();
  log(`    已清除 ${cleared} 个 Release 中间产物目录`);
}

if (build.status !== 0) {
  log('');
  log(`[x] 构建失败（退出码 ${build.status}）`);
  const lines = build.out.split(/\r?\n/);
  const start = lines.findIndex((l) => /FAILURE: Build failed|What went wrong/.test(l));
  if (start >= 0) {
    log('');
    log('--- 失败详情 ---');
    log(lines.slice(start, start + 14).join('\n'));
  }
  log('');
  log(`完整日志：${LOG}`);
  process.exit(1);
}

/* ---------------------------------------------------------------- 4. 校验产物 */

const APK_BASE = path.join(ROOT, 'android', 'app', 'build', 'outputs', 'apk');
const ALL = [
  ['child', 'app-child-release.apk', ':app:assembleChildRelease'],
  ['parent', 'app-parent-release.apk', ':app:assembleParentRelease'],
];
// 只校验这次真正要求构建的那些产物
const expected = ALL.filter(([, , task]) => tasks.includes(task)).map(([role, file]) => [role, file]);

log('');
log('[✓] 构建成功');

const missing = [];
for (const [role, file] of expected) {
  const p = path.join(APK_BASE, role, 'release', file);
  if (!fs.existsSync(p)) {
    missing.push(`${role}/release/${file}`);
    continue;
  }
  const mb = (fs.statSync(p).size / 1048576).toFixed(2);
  log(`    ${role.padEnd(7)}release/${file.padEnd(26)}${mb} MB`);
}

if (missing.length > 0) {
  log('');
  log(`[x] 构建报告成功，但产物缺失：${missing.join(', ')}`);
  process.exit(1);
}

/* ------------------------------------------------------- 5. 同步产物到 dist/ */
/*
 * 必须自动同步：dist/ 是真正拿去装机的那份，如果只更新 build/ 而不更新 dist/，
 * 目录里会静静躺着上一轮构建的旧包，装到平板上才发现功能不对。
 * 版本号取自 AGP 生成的 output-metadata.json（versionName 形如 1.0.0-child），
 * 去掉角色后缀后拼成 dist/zzl-<role>-v<版本>-release.apk。
 */
const DIST = path.join(ROOT, 'dist');
fs.mkdirSync(DIST, { recursive: true });
const synced = [];
for (const [role, file] of expected) {
  const src = path.join(APK_BASE, role, 'release', file);
  let version = '1.0.0';
  const meta = path.join(APK_BASE, role, 'release', 'output-metadata.json');
  if (fs.existsSync(meta)) {
    try {
      const name = JSON.parse(fs.readFileSync(meta, 'utf8'))?.elements?.[0]?.versionName;
      if (name) version = String(name).replace(new RegExp('-' + role + '$'), '');
    } catch {
      /* 读不到元数据就退回默认版本号，不影响拷贝 */
    }
  }
  const dst = path.join(DIST, `zzl-${role}-v${version}-release.apk`);
  fs.copyFileSync(src, dst);
  synced.push(`    dist/${path.basename(dst)}`);
}
log('');
log('[✓] 已同步到 dist/');
log(synced.join('\n'));

log('');
log('下一步：node scripts/verify-apk.cjs   （校验签名与清单）');
