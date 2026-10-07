// 临时脚本：1.0.12 四项修复（用完即删）
const fs = require('fs');
function patch(path, pairs) {
  let t = fs.readFileSync(path, 'utf8');
  for (const [a, b] of pairs) {
    const n = t.split(a).length - 1;
    if (n !== 1) throw new Error(path + ' anchor x' + n + ': ' + String(a).slice(0, 50));
    t = t.replace(a, b);
  }
  fs.writeFileSync(path, t);
  console.log('patched', path);
}

const ENG = 'android/app/src/child/java/com/zzl/guardian/child/engine/GuardEngine.kt';
const SVC = 'android/app/src/child/java/com/zzl/guardian/child/service/GuardForegroundService.kt';
const ACC = 'android/app/src/child/java/com/zzl/guardian/child/service/GuardAccessibilityService.kt';

/* 修复1+2：引擎侧 —— 截断钳制 + 触摸通道自愈 */
patch(ENG, [
  ["                    val end = lastTouch ?: at\n                    if (openPackage != null && !keepsTimingOnIdle(openPackage)) closeOpenSession(end)",
   "                    // 钳制：结束时间不得早于本段会话开始（触摸通道失效时 lastTouch 可能远早于会话起点）\n                    val end = maxOf(lastTouch ?: at, openStartTs)\n                    if (openPackage != null && !keepsTimingOnIdle(openPackage)) closeOpenSession(end)"],
  ["    /** 空闲挂起中：亮屏但无人触摸，一切计时暂停 */\n    @Volatile\n    private var idleSuspended = false",
   "    /** 空闲挂起中：亮屏但无人触摸，一切计时暂停 */\n    @Volatile\n    private var idleSuspended = false\n\n" +
   "    /** 触摸通道自愈：触摸计数停滞且窗口事件仍在流动 → 通道失效，停用空闲挂起 */\n    var touchStatsLookup: (() -> Pair<Long, Int>)? = null\n" +
   "    private var lastTouchCountSeen = -1\n    private var touchStallSince: Long? = null\n    private var idleHealAuditFired = false"],
  ["            val lastTouch = if (screenInteractive) interactionLookup?.invoke() else null\n            val idle = screenInteractive && lastTouch != null && (at - lastTouch) >= IDLE_THRESHOLD_MS",
   "            // 触摸通道自愈检测：屏幕亮着、前台在切换（设备在用），但触摸计数 10 分钟纹丝不动\n            // → 说明无障碍触摸事件没送达（更新后未重开无障碍等），空闲挂起会永久误判，必须停用并提示\n            val touchStats = touchStatsLookup?.invoke()\n            if (screenInteractive && touchStats != null) {\n                val (touchAt, touchCount) = touchStats\n                if (touchCount != lastTouchCountSeen) {\n                    lastTouchCountSeen = touchCount\n                    touchStallSince = null\n                    idleHealAuditFired = false\n                } else if (touchStallSince == null) {\n                    touchStallSince = at\n                } else if (!idleHealAuditFired && at - touchStallSince!! >= 10 * 60_000L) {\n                    idleHealAuditFired = true\n                    scope.launch {\n                        runCatching {\n                            usageRepository.recordAudit(\n                                action = \"touch_channel.stalled\",\n                                detail = \"亮屏且前台在切换，但 10 分钟未收到触摸事件：空闲挂起已自动停用，请重新关闭再打开无障碍\",\n                                level = \"warn\",\n                            )\n                        }\n                    }\n                }\n            }\n\n" +
   "            val lastTouch = if (screenInteractive) interactionLookup?.invoke() else null\n" +
   "            val idle = screenInteractive && !idleHealAuditFired && lastTouch != null && (at - lastTouch) >= IDLE_THRESHOLD_MS"],
]);

/* 修复2 子端事件计数 + 修复4：死亡区间审计 */
patch(ACC, [
  ["        ) {\n            lastTouchAt = System.currentTimeMillis()\n        }",
   "        ) {\n            lastTouchAt = System.currentTimeMillis()\n            touchEventCount += 1\n        }"],
  ["    private var lastTouchAt: Long = System.currentTimeMillis()",
   "    private var lastTouchAt: Long = System.currentTimeMillis()\n\n    /** 触摸交互事件计数（空闲通道自愈检测用） */\n    var touchEventCount: Int = 0\n        private set"],
]);
patch(SVC, [
  ["                engine.interactionLookup = { lastTouchAt }",
   "                engine.interactionLookup = { lastTouchAt }\n                engine.touchStatsLookup = { lastTouchAt to touchEventCount }"],
  ["    private lateinit var iconController: IconController",
   "    private lateinit var iconController: IconController"],
]);
// 服务启动审计 + 心跳存档（onCreate 末尾附近：用 interactionLookup 注册点之后插不进 onCreate 作用域，
// 改为在 periodic 的 tickCount+=1 处存活 + onStartCommand 前的 onCreate 已有 scope —— 用注册点后追加协程）
patch(SVC, [
  ["                engine.touchStatsLookup = { lastTouchAt to touchEventCount }",
   "                engine.touchStatsLookup = { lastTouchAt to touchEventCount }\n\n" +
   "                // 服务启动审计：进程被系统清理后重启时，把「死亡区间」定量写进操作记录\n" +
   "                scope.launch {\n" +
   "                    runCatching {\n" +
   "                        val prefs = getSharedPreferences(\"zzl_local_service\", Context.MODE_PRIVATE)\n" +
   "                        val prev = prefs.getLong(\"last_alive\", 0L)\n" +
   "                        prefs.edit().putLong(\"last_alive\", System.currentTimeMillis()).apply()\n" +
   "                        if (prev > 0) {\n" +
   "                            val gapMin = (System.currentTimeMillis() - prev) / 60_000\n" +
   "                            if (gapMin >= 5) {\n" +
   "                                usageRepository.recordAudit(\n" +
   "                                    action = \"service.restarted\",\n" +
   "                                    detail = \"距上次运行约 \\$gapMin 分钟：进程可能被系统清理（请在系统电池设置中把掌中灵设为无限制）\",\n" +
   "                                    level = \"warn\",\n" +
   "                                )\n" +
   "                            }\n" +
   "                        }\n" +
   "                    }\n" +
   "                }"],
  ["                tickCount += 1",
   "                tickCount += 1\n\n" +
   "                // 心跳存档：每 60 周期（约 1 分钟）记录一次存活时间，供重启审计推断死亡区间\n" +
   "                if (tickCount % 60 == 0L) {\n" +
   "                    runCatching {\n" +
   "                        getSharedPreferences(\"zzl_local_service\", Context.MODE_PRIVATE)\n" +
   "                            .edit().putLong(\"last_alive\", System.currentTimeMillis()).apply()\n" +
   "                    }\n" +
   "                }"],
]);

/* 版本 1.0.12 */
{
  const p = 'android/app/build.gradle.kts';
  let t = fs.readFileSync(p, 'utf8');
  if (!t.includes('versionCode = 12')) throw new Error('version anchor');
  t = t.replace('versionCode = 12', 'versionCode = 13').replace('versionName = "1.0.11"', 'versionName = "1.0.12"');
  fs.writeFileSync(p, t);
  console.log('version 1.0.12 (code 13)');
}
console.log('ALL PATCHED');
