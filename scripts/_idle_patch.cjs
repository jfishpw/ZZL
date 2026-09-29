// 临时脚本：计时修正 A+C（桌面不计时 + 空闲挂起 + 逐应用空闲豁免）（用完即删）
const fs = require('fs');
function patch(path, pairs) {
  let t = fs.readFileSync(path, 'utf8');
  for (const [a, b] of pairs) {
    if (!t.includes(a)) throw new Error(path + ' anchor missing: ' + String(a).slice(0, 60));
    t = t.split(a).join(b);
  }
  fs.writeFileSync(path, t);
  console.log('patched', path);
}

/* ---------- 服务端 0.2.5 ---------- */
patch('server/src/db.js', [
  ["  addColumnIfMissing('policies', 'timing_mode', \"TEXT NOT NULL DEFAULT 'standard'\");",
   "  addColumnIfMissing('policies', 'timing_mode', \"TEXT NOT NULL DEFAULT 'standard'\");\n" +
   "  // 逐应用：空闲（无人触摸）时是否继续计时（看视频/网课只看不摸的场景）\n" +
   "  addColumnIfMissing('app_rules', 'keep_timing_on_idle', 'INTEGER NOT NULL DEFAULT 0');"],
]);
patch('server/src/routes/policy.js', [
  ["    exemptTotal: !!row.exempt_total,",
   "    exemptTotal: !!row.exempt_total,\n    keepTimingOnIdle: !!row.keep_timing_on_idle,"],
  ["      exemptTotal: item.exemptTotal === undefined ? false : !!item.exemptTotal,",
   "      exemptTotal: item.exemptTotal === undefined ? false : !!item.exemptTotal,\n" +
   "      keepTimingOnIdle: item.keepTimingOnIdle === undefined ? false : !!item.keepTimingOnIdle,"],
  ["             (device_id, package_name, app_label, daily_limit_min, time_windows, weekdays_mask, enabled, exempt_total)",
   "             (device_id, package_name, app_label, daily_limit_min, time_windows, weekdays_mask, enabled, exempt_total, keep_timing_on_idle)"],
  ["            rule.exemptTotal ? 1 : 0,",
   "            rule.exemptTotal ? 1 : 0,\n            rule.keepTimingOnIdle ? 1 : 0,"],
  ["          rule.exemptTotal ? 1 : 0,",
   "          rule.exemptTotal ? 1 : 0,\n          rule.keepTimingOnIdle ? 1 : 0,"],
]);
const pkg = JSON.parse(fs.readFileSync('server/package.json', 'utf8'));
pkg.version = '0.2.5';
fs.writeFileSync('server/package.json', JSON.stringify(pkg, null, 2) + '\n');
console.log('server 0.2.5');

/* ---------- 共享 DTO ---------- */
patch('android/app/src/main/java/com/zzl/guardian/data/api/Dtos.kt', [
  ["    val enabled: Boolean = true,\n\n", "    val enabled: Boolean = true,\n    /** 空闲（无人触摸）时是否继续计时（网课/视频只看不摸） */\n    val keepTimingOnIdle: Boolean = false,\n\n"],
  ["                                enabled = rule.enabled,\n                                exemptTotal = draft.exemptTotal,\n                            )",
   "                                enabled = rule.enabled,\n                                exemptTotal = draft.exemptTotal,\n                                keepTimingOnIdle = draft.keepTimingOnIdle,\n                            )"],
]);

/* ---------- 子端实体/映射 ---------- */
patch('android/app/src/child/java/com/zzl/guardian/child/data/GuardEntities.kt', [
  ["    /** 生效星期位掩码：bit0 = 周一 … bit6 = 周日 */\n    val weekdaysMask: Int,\n    val enabled: Boolean,",
   "    /** 生效星期位掩码：bit0 = 周一 … bit6 = 周日 */\n    val weekdaysMask: Int,\n    val enabled: Boolean,\n    /** 空闲（无人触摸）时继续计时 */\n    val keepTimingOnIdle: Boolean = false,"],
  ["    val exemptTotal: Boolean = false,\n) {\n    fun isActiveOn(weekdayBit: Int): Boolean = enabled && (weekdaysMask shr weekdayBit) and 1 == 1",
   "    val exemptTotal: Boolean = false,\n    /** 空闲（无人触摸）时继续计时（网课/视频） */\n    val keepTimingOnIdle: Boolean = false,\n) {\n    fun isActiveOn(weekdayBit: Int): Boolean = enabled && (weekdaysMask shr weekdayBit) and 1 == 1\n\n" +
   "    /** 空闲挂起时该应用是否例外（继续计时） */\n    fun keepsTimingOnIdle(): Boolean = keepTimingOnIdle && enabled"],
]);
patch('android/app/src/child/java/com/zzl/guardian/child/data/GuardRepositories.kt', [
  ["        enabled = enabled,\n        exemptTotal = exemptTotal,\n    )",
   "        enabled = enabled,\n        exemptTotal = exemptTotal,\n        keepTimingOnIdle = keepTimingOnIdle,\n    )"],
]);

/* ---------- 无障碍：事件类型 + 触摸时间戳 ---------- */
patch('android/app/src/child/res/xml/accessibility_service_config.xml', [
  ['android:accessibilityEventTypes="typeWindowStateChanged|typeWindowsChanged"',
   'android:accessibilityEventTypes="typeWindowStateChanged|typeWindowsChanged|typeTouchInteractionStart|typeTouchInteractionEnd"'],
]);
patch('android/app/src/child/java/com/zzl/guardian/child/service/GuardAccessibilityService.kt', [
  ["    override fun onAccessibilityEvent(event: AccessibilityEvent?) {\n        val current = event ?: return\n        if (current.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&\n            current.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED\n        ) {\n            return\n        }",
   "    override fun onAccessibilityEvent(event: AccessibilityEvent?) {\n        val current = event ?: return\n\n        // 空闲检测的输入：任何触摸交互都刷新“最后交互时刻”\n        if (current.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||\n            current.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END\n        ) {\n            lastTouchAt = System.currentTimeMillis()\n        }\n\n        if (current.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&\n            current.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED\n        ) {\n            return\n        }"],
  ["                engine.visiblePackagesLookup = { visibleAppPackages() }",
   "                engine.visiblePackagesLookup = { visibleAppPackages() }\n                // 空闲检测输入：最后一次触摸时刻\n                engine.interactionLookup = { lastTouchAt }"],
]);
// lastTouchAt 字段（放 foregroundLookup 附近由服务持有）
patch('android/app/src/child/java/com/zzl/guardian/child/service/GuardAccessibilityService.kt', [
  ["                engine.interactionLookup = { lastTouchAt }",
   "                engine.interactionLookup = { lastTouchAt }"],
]);
// 在类里声明字段：找 currentForegroundPackage 前插入
{
  const p = 'android/app/src/child/java/com/zzl/guardian/child/service/GuardAccessibilityService.kt';
  let t = fs.readFileSync(p, 'utf8');
  if (!t.includes('private var lastTouchAt')) {
    const a = '    private fun currentForegroundPackage(): String? = runCatching {';
    if (!t.includes(a)) throw new Error('lastTouch anchor');
    t = t.replace(a, '    /** 最后一次触摸交互时刻（空闲检测输入）；服务启动时置为当前，避免开机即判空闲 */\n    private var lastTouchAt: Long = System.currentTimeMillis()\n\n' + a);
    fs.writeFileSync(p, t);
  }
  console.log('service lastTouchAt OK');
}

/* ---------- 引擎：桌面主路径过滤 + 空闲挂起/恢复 ---------- */
patch('android/app/src/child/java/com/zzl/guardian/child/engine/GuardEngine.kt', [
  // 注入空闲输入
  ["    var visiblePackagesLookup: (() -> Set<String>?)? = null",
   "    var visiblePackagesLookup: (() -> Set<String>?)? = null\n\n" +
   "    /** 最后一次用户触摸时刻（无障碍注入）。null = 无数据，按非空闲处理 */\n    var interactionLookup: (() -> Long?)? = null"],
  // applyDecision 顶部：桌面不计时（主路径，三档统一）
  ["        // 1. 家长点了「立即锁定」：与前台是谁无关，遮罩必须一直在\n        if (overrides.forcedLocked) {\n            stopTiming(at)\n            showLockedOverlay(at)\n            return\n        }",
   "        // 1. 家长点了「立即锁定」：与前台是谁无关，遮罩必须一直在\n        if (overrides.forcedLocked) {\n            stopTiming(at)\n            showLockedOverlay(at)\n            return\n        }\n\n" +
   "        // 1.5 桌面/系统桌面不算使用：焦点在桌面时不开会话。\n        // 亮屏停在桌面上 previously 会被计成 launcher 的使用时长（真机 09-29 复现）\n        if (pkg != null && isDesktopPackage(pkg)) {\n            stopTiming(at)\n            clearBlockedState()\n            return\n        }"],
  // 常量
  ["        const val MIN_PARALLEL_SESSION_MS = 5_000L",
   "        const val MIN_PARALLEL_SESSION_MS = 5_000L\n\n        /** 无触摸多久判为空闲（亮屏但没人碰 → 计时挂起） */\n        const val IDLE_THRESHOLD_MS = 120_000L"],
  // tick 改造
  ["            // 并行会话有收尾时刷新累计，让总额度判定把它们算进来\n            if (tickCount % 5 == 0 && parallelRecorded) {\n                usedCommittedMs = todayTotal()\n                parallelRecorded = false\n            }\n\n            // 孩子可能一直停留在同一个应用里，此时没有任何前台切换事件，\n            // 只能靠巡检发现「额度用尽」「时段结束」以及「家长刚批准了加时」\n            applyDecision(openPackage ?: desiredPackage, at)\n\n            publishState(at)",
   "            // 并行会话有收尾时刷新累计，让总额度判定把它们算进来\n            if (tickCount % 5 == 0 && parallelRecorded) {\n                usedCommittedMs = todayTotal()\n                parallelRecorded = false\n            }\n\n" +
   "            // 空闲检测：亮屏但超过阈值无触摸 → 计时挂起（恢复触摸自动续上）。\n" +
   "            // 挂起时主会话/未豁免并行会话都截到「最后一次触摸」，空闲段不进额度。\n" +
   "            val lastTouch = if (screenInteractive) interactionLookup?.invoke() else null\n" +
   "            val idle = screenInteractive && lastTouch != null && (at - lastTouch) >= IDLE_THRESHOLD_MS\n" +
   "            when {\n" +
   "                idle && !idleSuspended -> {\n" +
   "                    val end = lastTouch ?: at\n" +
   "                    if (openPackage != null && !keepsTimingOnIdle(openPackage)) closeOpenSession(end)\n" +
   "                    closeParallelOnIdle(end)\n" +
   "                    idleSuspended = true\n" +
   "                }\n" +
   "                !idle && idleSuspended -> {\n" +
   "                    idleSuspended = false\n" +
   "                    usedCommittedMs = todayTotal()\n" +
   "                }\n" +
   "            }\n\n" +
   "            // 孩子可能一直停留在同一个应用里，此时没有任何前台切换事件，\n            // 只能靠巡检发现「额度用尽」「时段结束」以及「家长刚批准了加时」\n            // 空闲挂起期间跳过（否则会立刻重开会话）\n            if (!idleSuspended) applyDecision(openPackage ?: desiredPackage, at)\n\n            publishState(at)"],
  // 并行：空闲时冻结新增
  ["    private suspend fun updateParallelSessions(at: Long) {\n        if (guard?.policy?.timingMode != \"recommended\" || !screenInteractive || _state.value.blocking) {",
   "    private suspend fun updateParallelSessions(at: Long) {\n        if (idleSuspended) return\n        if (guard?.policy?.timingMode != \"recommended\" || !screenInteractive || _state.value.blocking) {"],
  // 空闲相关成员 + 豁免判断
  ["    /** 本轮是否有并行会话收尾（决定要不要刷新额度累计） */\n    @Volatile\n    private var parallelRecorded = false",
   "    /** 本轮是否有并行会话收尾（决定要不要刷新额度累计） */\n    @Volatile\n    private var parallelRecorded = false\n\n" +
   "    /** 空闲挂起中：亮屏但无人触摸，一切计时暂停 */\n    @Volatile\n    private var idleSuspended = false\n\n" +
   "    /** 该应用是否豁免空闲挂起（网课/视频只看不摸仍计时） */\n    private fun keepsTimingOnIdle(pkg: String?): Boolean =\n        pkg != null && guard?.rules[pkg]?.keepTimingOnIdle == true\n\n" +
   "    /** 空闲挂起时收尾未豁免的并行会话（豁免的保留继续计时） */\n    private suspend fun closeParallelOnIdle(end: Long) {\n        for (pkg in parallelOpen.keys.toList()) {\n            if (!keepsTimingOnIdle(pkg)) closeParallelEntry(pkg, end, record = true)\n        }\n    }"],
]);
// closeParallelEntry 用 record + at 参数已存在，无需改。

/* ---------- 版本 ---------- */
{
  const p = 'android/app/build.gradle.kts';
  let t = fs.readFileSync(p, 'utf8');
  if (!t.includes('versionCode = 7')) throw new Error('version anchor');
  t = t.replace('versionCode = 7', 'versionCode = 8').replace('versionName = "1.0.6"', 'versionName = "1.0.7"');
  fs.writeFileSync(p, t);
  console.log('version 1.0.7 (code 8)');
}
console.log('ALL PATCHED');
