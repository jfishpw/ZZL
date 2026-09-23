package com.zzl.guardian.child.keepalive

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zzl.guardian.child.di.guardGraph
import com.zzl.guardian.child.service.GuardForegroundService
import java.util.concurrent.TimeUnit

/**
 * 保活的最后一道兜底。
 *
 * 前台服务是主要保活手段，但厂商 ROM 可能在息屏后直接杀掉整个进程。
 * 这时 AlarmManager 的精确闹钟和 WorkManager 的周期任务就成了唯一能
 * 把进程拉回来的手段 —— 它们的存活级别高于普通应用进程。
 *
 * 三层递进（越往下越激进，也越耗电，所以只在需要时启用）：
 *  1. 前台服务（常驻）
 *  2. 精确闹钟（每 15 分钟自我唤醒一次）
 *  3. WorkManager 周期任务（最小 15 分钟间隔，系统统一调度，耗电更低）
 *
 * 第 2、3 层不是二选一 —— 精确闹钟在部分 ROM 上会被限制，
 * WorkManager 在部分 ROM 上会被延迟，两个一起用才稳。
 */
class GuardKeepaliveWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext

        // 只有已配对的设备才需要保活，未配对时白白耗电
        val session = runCatching {
            guardGraph(context).settingsStore().childSessionOnce()
        }.getOrNull()

        if (session == null) {
            // 不只跳过本次：周期任务会一直续下去，必须显式取消，
            // 否则家长退出管控后这个任务会永久空转（每 15 分钟醒一次白耗电）
            Log.i(TAG, "已退出管控，取消保活任务")
            cancel(context)
            return Result.success()
        }

        val started = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GuardForegroundService::class.java)
                    .setAction(GuardForegroundService.ACTION_KEEPALIVE),
            )
        }.isSuccess

        Log.i(TAG, if (started) "保活任务已重新拉起管控服务" else "保活任务拉起服务失败")
        return Result.success()
    }

    companion object {
        private const val TAG = "KeepaliveWorker"

        /** WorkManager 的最小周期就是 15 分钟，再短也不会更频繁地执行 */
        private const val INTERVAL_MINUTES = 15L

        private const val WORK_NAME = "zzl_guard_keepalive"

        /**
         * 注册周期保活任务。
         *
         * 用 KEEP 策略而不是 REPLACE：每次服务启动都调用本方法，
         * REPLACE 会把已有任务取消重建，导致执行周期被不断推迟 ——
         * 那正是我们最不希望发生的（服务反复重启恰恰说明它正被系统杀）。
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GuardKeepaliveWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        // 不需要网络：保活本身不联网，断网时更要能拉起服务
                        .build(),
                )
                .build()

            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }.onFailure { Log.w(TAG, "注册保活任务失败", it) }
        }

        /** 退出管控时取消保活 —— 不再受管控的设备没有理由常驻 */
        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
                .onFailure { Log.w(TAG, "取消保活任务失败", it) }
        }
    }
}
