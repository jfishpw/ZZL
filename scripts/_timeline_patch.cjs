// 临时脚本：时间线聚合 + 进行中会话实时上报（用完即删）
const fs = require('fs');
function patch(path, pairs) {
  let t = fs.readFileSync(path, 'utf8');
  for (const [a, b] of pairs) {
    if (!t.includes(a)) throw new Error(path + ' anchor missing: ' + String(a).slice(0, 50));
    t = t.split(a).join(b);
  }
  fs.writeFileSync(path, t);
  console.log('patched', path);
}

/* 服务端 0.2.6 */
patch('server/src/routes/usage.js', [
  // 1) 上报：支持按 clientKey 更新（进行中会话的时长刷新）
  [`      const inserted = run(
        \`INSERT OR IGNORE INTO usage_sessions
           (client_key, device_id, package_name, start_ts, end_ts, duration_ms, day_key)
         VALUES (?, ?, ?, ?, ?, ?, ?)\`,
        clientKey, device.id, packageName, startTs, endTsRaw, durationMs, dayKey,
      );

      if (inserted.changes === 1) {
        accepted += 1;`,
   `      const existing = one(
        'SELECT id, duration_ms FROM usage_sessions WHERE client_key = ?',
        clientKey,
      );

      if (existing) {
        // 已存在：只允许时长增长时更新（进行中会话的周期刷新 / 重传兜底）。
        // 日汇总只补增量，绝不重复计费。
        if (durationMs > existing.duration_ms) {
          run(
            'UPDATE usage_sessions SET end_ts = ?, duration_ms = ? WHERE id = ?',
            endTsRaw, durationMs, existing.id,
          );
          run(
            \`UPDATE usage_daily SET total_ms = total_ms + ?
             WHERE device_id = ? AND day_key = ? AND package_name = ?\`,
            durationMs - existing.duration_ms, device.id, dayKey, packageName,
          );
        }
        updated += 1;
      } else {
        run(
          \`INSERT INTO usage_sessions
             (client_key, device_id, package_name, start_ts, end_ts, duration_ms, day_key)
           VALUES (?, ?, ?, ?, ?, ?, ?)\`,
          clientKey, device.id, packageName, startTs, endTsRaw, durationMs, dayKey,
        );
        accepted += 1;`],
  [`        // 会话明细与日汇总在同一事务里更严谨；此处单条 upsert 已足够，SQLite 单写者模型下不会并发冲突
        run(
          \`INSERT INTO usage_daily (device_id, day_key, package_name, total_ms, open_count)
           VALUES (?, ?, ?, ?, 1)
           ON CONFLICT(device_id, day_key, package_name)
           DO UPDATE SET total_ms = total_ms + excluded.total_ms,
                         open_count = open_count + 1\`,
          device.id, dayKey, packageName, durationMs,
        );
      } else {
        duplicated += 1;
      }`,
   `        run(
          \`INSERT INTO usage_daily (device_id, day_key, package_name, total_ms, open_count)
           VALUES (?, ?, ?, ?, 1)
           ON CONFLICT(device_id, day_key, package_name)
           DO UPDATE SET total_ms = total_ms + excluded.total_ms,
                         open_count = open_count + 1\`,
          device.id, dayKey, packageName, durationMs,
        );
      } else {
        duplicated += 1;
      }`],
  // 2) updated 计数器与响应
  [`  let accepted = 0;`, `  let accepted = 0;\n  let updated = 0;`],
  [`    return { ok: true, accepted, duplicated, rejected };`,
   `    return { ok: true, accepted, updated, duplicated, rejected };`],
  // 3) 时间线查询：同应用相邻（间隔 ≤ 60 秒）会话聚合
  [`    const labels = labelMapFor(device.id);
    return {
      sessions: rows.map((r) => ({
        id: r.id,
        packageName: r.package_name,
        appLabel: labels.get(r.package_name) ?? null,
        startTs: r.start_ts,
        endTs: r.end_ts,
        durationMs: r.duration_ms,
        dayKey: r.day_key,
      })),
    };`,
   `    const labels = labelMapFor(device.id);

    // 同应用相邻会话聚合：间隔 ≤ 60 秒的碎片（课程类应用反复抢前台）合并为一条，
    // 只影响展示 —— 原始会话不动，额度判定与排行不受影响。
    const asc = [...rows].reverse();
    const merged = [];
    for (const r of asc) {
      const last = merged[merged.length - 1];
      const lastEnd = last?.end_ts ?? last?.start_ts ?? 0;
      if (last && last.package_name === r.package_name && r.start_ts - lastEnd <= 60_000) {
        last.duration_ms += r.duration_ms;
        last.end_ts = r.end_ts === null ? null : (last.end_ts === null ? last.end_ts : r.end_ts);
        if (r.end_ts === null) last.end_ts = null;
        last.segments += 1;
      } else {
        merged.push({ ...r, segments: 1 });
      }
    }
    merged.reverse();

    return {
      sessions: merged.map((r) => ({
        id: r.id,
        packageName: r.package_name,
        appLabel: labels.get(r.package_name) ?? null,
        startTs: r.start_ts,
        endTs: r.end_ts,
        durationMs: r.duration_ms,
        dayKey: r.day_key,
        segments: r.segments,
      })),
    };`],
]);
{
  const p = JSON.parse(fs.readFileSync('server/package.json', 'utf8'));
  p.version = '0.2.6';
  fs.writeFileSync('server/package.json', JSON.stringify(p, null, 2) + '\n');
  console.log('server 0.2.6');
}

/* DTO */
patch('android/app/src/main/java/com/zzl/guardian/data/api/Dtos.kt', [
  ["    val durationMs: Long = 0,\n    val dayKey: String = \"\",\n)\n\n@Serializable",
   "    val durationMs: Long = 0,\n    val dayKey: String = \"\",\n    /** 聚合后包含的原始会话数（>1 表示该应用在反复抢前台） */\n    val segments: Int = 1,\n)\n\n@Serializable"],
  ["    val accepted: Int = 0,\n    val duplicated: Int = 0,\n    val rejected: Int = 0,",
   "    val accepted: Int = 0,\n    val updated: Int = 0,\n    val duplicated: Int = 0,\n    val rejected: Int = 0,"],
]);

/* 子端：进行中会话周期上报 */
patch('android/app/src/child/java/com/zzl/guardian/child/data/GuardRepositories.kt', [
  ["    /**\n     * 上报未上传的拦截记录，与使用会话同一套幂等思路。",
   "    /**\n     * 上报当前**进行中**的会话：服务端按 clientKey 更新时长。\n     * 没有这一步的话，正在运行的应用要等被关闭才出现在报告里（真机反馈）。\n     * 不标记 uploaded —— 收尾后仍走批量通道做最终上报。\n     */\n" +
   "    suspend fun uploadOpenSession(token: String, deviceId: Long) {\n" +
   "        val open = sessionDao.currentOpen() ?: return\n" +
   "        val now = System.currentTimeMillis()\n" +
   "        runCatching {\n" +
   "            api.reportUsage(\n" +
   "                authorization = \"Bearer \\$token\",\n" +
   "                deviceId = deviceId,\n" +
   "                body = UsageReportRequest(\n" +
   "                    listOf(open.copy(endTs = null, durationMs = (now - open.startTs).coerceAtLeast(0)).toDto()),\n" +
   "                ),\n" +
   "            )\n" +
   "        }\n" +
   "    }\n\n" +
   "    /**\n     * 上报未上传的拦截记录，与使用会话同一套幂等思路。"],
]);
patch('android/app/src/child/java/com/zzl/guardian/child/service/GuardForegroundService.kt', [
  ["                    runCatching { usageRepository.uploadPending(current.token, current.deviceId) }",
   "                    runCatching { usageRepository.uploadPending(current.token, current.deviceId) }\n" +
   "                    // 进行中的会话也周期上报，报告实时可见\n" +
   "                    runCatching { usageRepository.uploadOpenSession(current.token, current.deviceId) }"],
]);

/* 控制端时间线显示 */
patch('android/app/src/parent/java/com/zzl/guardian/parent/UsageReportDialog.kt', [
  ["            Text(\n                \"\\${formatTime(session.startTs)} - \\${formatTime(session.endTs ?: session.startTs + session.durationMs)}\",\n                style = MaterialTheme.typography.labelSmall,\n                color = MaterialTheme.colorScheme.onSurfaceVariant,\n            )",
   "            Text(\n                if (session.endTs == null) \"\\${formatTime(session.startTs)} - 进行中\"\n                else \"\\${formatTime(session.startTs)} - \\${formatTime(session.endTs)}\",\n                style = MaterialTheme.typography.labelSmall,\n                color = MaterialTheme.colorScheme.onSurfaceVariant,\n            )"],
  ["        Text(\n            // 时间线展示的是单条会话：被拦/切走频繁的应用会切成很多不足 1 分钟的小段，\n            // 统一按分钟取整会显示成一排「0 分钟」，家长会误以为没有计时（真机反馈）。\n            formatDurationPrecise(session.durationMs),\n            style = MaterialTheme.typography.labelSmall,\n        )",
   "        Text(\n            // 时间线展示的是聚合条目：碎片会话已按应用合并（segments>1 表示反复抢前台）。\n            // 不足 1 分钟显示秒数，避免一排「0 分钟」（真机反馈）。\n            if (session.segments > 1)\n                \"\\${formatDurationPrecise(session.durationMs)}（\\${session.segments}段）\"\n            else\n                formatDurationPrecise(session.durationMs),\n            style = MaterialTheme.typography.labelSmall,\n        )"],
]);

/* 版本 */
{
  const p = 'android/app/build.gradle.kts';
  let t = fs.readFileSync(p, 'utf8');
  if (!t.includes('versionCode = 8')) throw new Error('version anchor');
  t = t.replace('versionCode = 8', 'versionCode = 9').replace('versionName = "1.0.7"', 'versionName = "1.0.8"');
  fs.writeFileSync(p, t);
  console.log('version 1.0.8 (code 9)');
}
console.log('ALL PATCHED');
