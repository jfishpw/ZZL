// 临时脚本：本地服务修正（用完即删）
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

const SRV = 'android/app/src/child/java/com/zzl/guardian/child/local/LocalHttpServer.kt';
const ENG = 'android/app/src/child/java/com/zzl/guardian/child/engine/GuardEngine.kt';

patch(SRV, [
  // 1) 缺失字段
  ["    private var pairingToken: String? = null",
   "    private var pairingToken: String? = null\n    private var pairingTokenIssuedAt: Long = 0L"],
  ["        running = true\n        pairingToken = String.format(\"%06d\", random.nextInt(1000000))",
   "        running = true\n        pairingToken = String.format(\"%06d\", random.nextInt(1000000))\n        pairingTokenIssuedAt = System.currentTimeMillis()"],
  // 2) 非法扩展属性 → 普通工具函数
  ["    private fun kotlinx.serialization.json.JsonElement.jsonObject get() =\n        (this as? kotlinx.serialization.json.JsonObject) ?: error(\"not object\")\n",
   ""],
  ["        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()\n            ?: return false",
   "        val obj = runCatching { json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject }.getOrNull()\n            ?: return false"],
  ["        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()\n            ?: return \"400\" to \"{\\\"error\\\":\\\"bad_json\\\"}\"",
   "        val obj = runCatching { json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject }.getOrNull()\n            ?: return \"400\" to \"{\\\"error\\\":\\\"bad_json\\\"}\""],
]);

/* 引擎侧：本地锁定开关 + 设备 ID 快照 + 策略快照 */
patch(ENG, [
  ["    /** 空闲挂起中：亮屏但无人触摸，一切计时暂停 */\n    @Volatile\n    private var idleSuspended = false",
   "    /** 空闲挂起中：亮屏但无人触摸，一切计时暂停 */\n    @Volatile\n    private var idleSuspended = false\n\n" +
   "    /** 本地模式锁定开关（家长端局域网直连时设置；独立于服务器下发的 locked，服务器模式零影响） */\n" +
   "    @Volatile\n    var localLocked = false"],
  ["    /**\n     * 引擎持有的完整本地策略副本。",
   "    /** 当前策略绑定的设备 ID（未配对时为 0，本地模式据此落库） */\n    fun currentPolicyDeviceId(): Long = initializedDeviceId\n\n" +
   "    /** 策略快照（本地通道拉取策略用） */\n    fun guardPolicySnapshot(): GuardPolicy? = guard\n\n" +
   "    /**\n     * 引擎持有的完整本地策略副本。"],
  ["            // 1. 家长点了「立即锁定」：与前台是谁无关，遮罩必须一直在\n            if (overrides.forcedLocked) {",
   "            // 1. 家长点了「立即锁定」：与前台是谁无关，遮罩必须一直在\n            //    本地模式的锁定开关同样压过一切（独立字段，服务器模式零影响）\n            if (overrides.forcedLocked || localLocked) {"],
]);
console.log('ALL FIXED');
