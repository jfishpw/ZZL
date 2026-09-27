package com.zzl.guardian.child.timing

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.zzl.guardian.child.PermissionChecker
import com.zzl.guardian.child.data.UsageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统用量对账：用 UsageStatsManager 的前台时长作为"第二意见"，
 * 修补我们事件计时覆盖不到的形态（分屏/小窗里被系统记账但我们没记到的时间）。
 *
 * 语义是**只增不减**（max）：
 *   - 系统口径 > 我方口径 + 阈值 → 差额落成一条"校准会话"，额度与报告自动带上；
 *   - 系统口径偏小（各家 ROM 统计口径差异）→ 不回退，我方记录为准。
 *
 * 校准会话和普通会话走同一张表、同一条上报通道，因此：
 * 额度判定、使用报告、时间线全部自动一致，无需任何特殊处理。
 *
 * 不可用（未授权使用情况访问权 / 查询失败）时如实返回，调用方只提示、不阻断管控。
 */
@Singleton
class SystemUsageReconciler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usageRepository: UsageRepository,
) {

    data class Result(
        /** 系统用量是否可用（不可用时控制端会收到审计提示，但管控照常） */
        val available: Boolean,
        /** 本次补记了几个应用的差额 */
        val correctedApps: Int,
        /** 本次补记的总毫秒数 */
        val correctedMs: Long,
    )

    fun hasAccess(): Boolean = PermissionChecker.hasUsageAccessPublic(context)

    /**
     * 对账一次。
     *
     * @param resetHour 额度日起点（对账窗口 = 本额度日以来）
     * @param dayKey 当前额度日的 dayKey（校准会话归属）
     */
    suspend fun reconcile(resetHour: Int, dayKey: String): Result {
        if (!hasAccess()) return Result(available = false, correctedApps = 0, correctedMs = 0)

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return Result(available = false, correctedApps = 0, correctedMs = 0)

        val now = System.currentTimeMillis()
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, dayStartMs(resetHour, now), now)
        }.getOrNull() ?: return Result(available = false, correctedApps = 0, correctedMs = 0)

        val ours = runCatching { usageRepository.rawPerAppMs(dayKey) }.getOrDefault(emptyMap())

        var correctedApps = 0
        var correctedMs = 0L
        for (stat in stats) {
            val pkg = stat.packageName ?: continue
            if (pkg == context.packageName) continue
            val sysMs = stat.totalTimeInForeground
            if (sysMs <= 0) continue

            val delta = sysMs - (ours[pkg] ?: 0L)
            if (delta > CALIBRATION_THRESHOLD_MS) {
                runCatching {
                    usageRepository.recordClosedSession(
                        packageName = pkg,
                        dayKey = dayKey,
                        startTs = now - delta,
                        endTs = now,
                        clientKey = "syscal-${UUID.randomUUID()}",
                    )
                }.onFailure { Log.w(TAG, "写入校准会话失败 pkg=$pkg", it) }
                correctedApps += 1
                correctedMs += delta
            }
        }
        return Result(available = true, correctedApps = correctedApps, correctedMs = correctedMs)
    }

    /** 本额度日的起点毫秒值（今天 resetHour 点；还没到则从昨天 resetHour 算） */
    private fun dayStartMs(resetHour: Int, now: Long): Long = runCatching {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, resetHour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis > now) cal.add(Calendar.DAY_OF_MONTH, -1)
        cal.timeInMillis
    }.getOrDefault(now - 24 * 60 * 60 * 1000L)

    private companion object {
        const val TAG = "SysUsageReconciler"

        /** 差额低于 1 分钟不补：系统统计的秒级抖动不应制造碎片校准 */
        const val CALIBRATION_THRESHOLD_MS = 60_000L
    }
}
