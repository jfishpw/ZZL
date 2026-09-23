package com.zzl.guardian.child

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import com.zzl.guardian.child.admin.AdminModeManager
import com.zzl.guardian.data.api.HealthState

/**
 * 检测被控端所需的各项系统授权状态。
 *
 * 一个关键区分：**前四项是"权限"，第五项（管控模式）是"状态"**。
 * 权限只有开/关两种可能，而管控模式有三档且强度差距是数量级的 ——
 * 所以它不进 [HealthState]，而是单独作为加固状态上报。
 */
object PermissionChecker {

    fun snapshot(context: Context): HealthState = HealthState(
        accessibility = isAccessibilityEnabled(context),
        usageAccess = hasUsageAccess(context),
        overlay = canDrawOverlays(context),
        deviceAdmin = AdminModeManager.isAdminActive(context),
    )

    /** 无障碍服务当前是否开启（供 Device Owner 条件式冻结判断，见 AdminModeManager） */
    fun isAccessibilityOn(context: Context): Boolean = runCatching { isAccessibilityEnabled(context) }.getOrDefault(false)

    /**
     * 还缺哪些关键权限。
     *
     * 注意：**设备管理器不在这个列表里**。它不是"管控生效的必要条件"，
     * 而是"防止管控被绕过的加固项" —— 混在一起会让家长看到
     * "还有 1 项未开启，管控无法生效"，而实际上管控已经在生效了。
     */
    fun missing(context: Context): List<String> {
        val state = snapshot(context)
        return buildList {
            if (!state.accessibility) add("accessibility")
            if (!state.usageAccess) add("usageAccess")
            if (!state.overlay) add("overlay")
        }
    }

    /**
     * 找出「上一份快照有、这一份没有」的权限。
     *
     * 用于识别**权限被中途关掉**——这是家长最需要立刻知道的事
     * （管控实际上已经失效了），但它不会触发任何系统广播，
     * 只能靠定时比对两次快照。
     *
     * @return 新丢失的权限标识列表；没有变化返回空列表
     */
    fun newlyLost(previous: HealthState?, current: HealthState): List<String> {
        if (previous == null) return emptyList()
        return buildList {
            if (previous.accessibility && !current.accessibility) add("accessibility")
            if (previous.usageAccess && !current.usageAccess) add("usageAccess")
            if (previous.overlay && !current.overlay) add("overlay")
        }
    }

    /** 权限标识到家长能看懂的中文。 */
    fun describe(key: String): String = when (key) {
        "accessibility" -> "无障碍服务"
        "usageAccess" -> "使用情况访问"
        "overlay" -> "悬浮窗"
        "deviceAdmin" -> "设备管理器"
        else -> key
    }

    /**
     * 无障碍服务是否已开启。
     *
     * 系统在 Settings.Secure 里存的可能是 `包名/完整类名` 也可能是 `包名/.简写类名`，
     * 因此统一用 ComponentName 反解后比较包名与类名，而不是比字符串。
     */
    private fun isAccessibilityEnabled(context: Context): Boolean {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val target = ComponentName(context.packageName, ACCESSIBILITY_CLASS)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(raw)

        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next()) ?: continue
            if (component.packageName == target.packageName &&
                component.className == target.className
            ) {
                return true
            }
        }
        return false
    }

    /** 「使用情况访问」权限是否已授予 */
    private fun hasUsageAccess(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        false
    }

    /** 悬浮窗（全屏遮盖拦截所需）是否已授予 */
    private fun canDrawOverlays(context: Context): Boolean = try {
        Settings.canDrawOverlays(context)
    } catch (_: Throwable) {
        false
    }

    const val ACCESSIBILITY_CLASS = "com.zzl.guardian.child.service.GuardAccessibilityService"
}
