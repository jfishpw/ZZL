package com.zzl.guardian.child.admin

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.zzl.guardian.child.PermissionChecker
import com.zzl.guardian.child.service.GuardAccessibilityService

/**
 * 无障碍自愈（1.0.20）。
 *
 * 背景：国产 ROM（本机联想 ZUI）会在「安全清理 / 省电优化 / 权限自动回收」时
 * 把无障碍服务关掉，家长的管控随之失效。系统不会给任何广播，
 * 只能靠巡检发现后**主动写回设置**。
 *
 * 实现方式：利用已授予的 WRITE_SECURE_SETTINGS（DNS 防护同款，adb 一次授权即可），
 * 把本服务重新写进 `enabled_accessibility_services` 并打开总开关。
 * 不需要 root，也不需要 Device Owner。
 *
 * 安全阀（重要）：只有家长**没有主动退出管控**时才会自愈。
 * 退出管控会把 [setEnabled] 置为 false —— 否则家长想关管控时会被系统反复打开，
 * 变成"关不掉"，那是灾难性的体验。
 */
object AccessibilityRestorer {

    private const val TAG = "A11yRestorer"
    private const val PREFS = "zzl_a11y_restore"
    private const val KEY_ENABLED = "enabled"

    enum class Result {
        /** 写回成功，且已确认服务在跑 */
        RESTORED,

        /** 写回成功但系统尚未完成绑定（下一轮巡检确认） */
        PENDING,

        /** 缺少 WRITE_SECURE_SETTINGS，无法自愈 */
        NO_PERMISSION,

        /** 写回后仍未生效（ROM 拒绝） */
        FAILED,

        /** 家长已退出管控，不应自愈 */
        DISABLED,
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    /** 尝试把无障碍服务重新打开 */
    fun restore(context: Context): Result {
        if (!isEnabled(context)) return Result.DISABLED
        val component = runCatching {
            ComponentName(context, GuardAccessibilityService::class.java).flattenToString()
        }.getOrNull() ?: return Result.FAILED

        val cr = context.contentResolver
        val wrote = runCatching {
            val current = Settings.Secure.getString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
            val services = current.split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()
            if (component !in services) services.add(component)
            Settings.Secure.putString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"),
            )
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.onFailure {
            Log.w(TAG, "写回无障碍设置失败（多半是缺 WRITE_SECURE_SETTINGS）", it)
        }.getOrDefault(false)

        if (!wrote) return Result.NO_PERMISSION

        // 系统绑定是异步的：此刻可能还读不到，交给下一轮巡检确认
        return if (PermissionChecker.isAccessibilityOn(context)) Result.RESTORED else Result.PENDING
    }
}
