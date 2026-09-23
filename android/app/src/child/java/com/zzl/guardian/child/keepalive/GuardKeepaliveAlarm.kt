package com.zzl.guardian.child.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.zzl.guardian.child.service.GuardForegroundService

/**
 * 精确闹钟自我唤醒。
 *
 * 这是保活的第二层。它与 WorkManager 的区别在于**调度精度**：
 * WorkManager 由系统统一调度，在低电量或待机时可能被推迟到几小时之后；
 * 而 `setExactAndAllowWhileIdle` 即使在 Doze 模式下也会被唤醒
 * （代价是每 9 分钟最多一次，系统有硬性限流）。
 *
 * 三层一起用的理由：没有哪一层在所有 ROM 上都可靠 ——
 * 前台服务会被厂商省电策略杀，精确闹钟在部分 ROM 上被忽略，
 * WorkManager 在部分 ROM 上被延迟。三个一起上，任何一个活着就能把服务拉回来。
 */
object GuardKeepaliveAlarm {

    private const val TAG = "KeepaliveAlarm"

    /** 与 WorkManager 的最小周期对齐；系统对 Doze 下的精确闹钟也有 9 分钟限流 */
    private const val INTERVAL_MS = 15 * 60 * 1000L

    private const val REQUEST_CODE = 0x5A1A

    fun schedule(context: Context) {
        val manager = runCatching {
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        }.getOrNull() ?: return

        val pending = pendingIntent(context) ?: return
        val triggerAt = System.currentTimeMillis() + INTERVAL_MS

        val ok = runCatching {
            if (canScheduleExact(manager)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                // 没有精确闹钟权限时退回非精确：精度差一些，但比完全不设要好。
                // 不在这里弹权限请求 —— 保活是后台行为，弹窗会打断家长正在做的事。
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }.isSuccess

        Log.i(TAG, if (ok) "已安排下一次自我唤醒" else "安排自我唤醒失败")
    }

    fun cancel(context: Context) {
        val manager = runCatching {
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        }.getOrNull() ?: return
        pendingIntent(context)?.let { manager.cancel(it) }
    }

    private fun canScheduleExact(manager: AlarmManager): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        manager.canScheduleExactAlarms()
    }.getOrDefault(false)

    /**
     * 闹钟的 PendingIntent。
     *
     * 用 `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`：
     * 前者保证重复安排时复用同一个 Intent 而不是堆出一串待触发项，
     * 后者是 Android 12+ 的强制要求。
     */
    private fun pendingIntent(context: Context): PendingIntent? = runCatching {
        val intent = Intent(context, GuardForegroundService::class.java)
            .setAction(GuardForegroundService.ACTION_KEEPALIVE)

        PendingIntent.getForegroundService(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.onFailure { Log.w(TAG, "构造 PendingIntent 失败", it) }
        .getOrNull()

    /** 供接收器调用：醒来后重新安排下一次（一次性闹钟不会自动重复） */
    fun rescheduleAfterWake(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GuardForegroundService::class.java)
                    .setAction(GuardForegroundService.ACTION_KEEPALIVE),
            )
        }
        schedule(context)
    }
}
