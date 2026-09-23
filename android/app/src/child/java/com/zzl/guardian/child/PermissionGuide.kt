package com.zzl.guardian.child

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 各权限的跳转入口。
 *
 * 一个前提认知：**跳过去之后需要家长手动点开关，App 无法代劳**。
 * 所以这里的职责是"尽量一次跳到位"，并在跳不动时给出可退的兜底路径
 * —— 各家 ROM 的设置页 Activity 名字差别很大，硬编码单一入口必然有设备打不开。
 */
object PermissionGuide {

    private const val TAG = "PermissionGuide"

    private fun newTask(intent: Intent) = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 无障碍设置。部分 ROM 收不到标准 Action，退回到应用详情页让家长自己找。 */
    fun openAccessibility(context: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent().setComponent(
                ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings\$AccessibilitySettingsActivity",
                ),
            ),
        )
        startFirstAvailable(context, candidates, "无障碍设置")
    }

    /** 使用情况访问（读取应用使用时长的前提） */
    fun openUsageAccess(context: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        startFirstAvailable(context, candidates, "使用情况访问")
    }

    /** 悬浮窗权限：用于弹出全屏拦截页 */
    fun openOverlay(context: Context) {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                add(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
            add(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
        startFirstAvailable(context, candidates, "悬浮窗权限")
    }

    /** 电池优化白名单：不进白名单的话前台服务会被系统清掉 */
    fun openBatteryOptimization(context: Context) {
        val candidates = listOf(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        startFirstAvailable(context, candidates, "电池优化设置")
    }

    /** 通知权限（Android 13+）：前台服务的常驻通知需要它才可见 */
    fun openNotificationSettings(context: Context) {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
            add(appDetailsIntent(context))
        }
        startFirstAvailable(context, candidates, "通知设置")
    }

    /**
     * 应用详情页。
     * Android 13+ 的「允许受限设置」入口就在这一页的右上角菜单里 ——
     * 侧载应用不开这一步，无障碍开关会一直是灰的，是装机时最容易卡住的地方。
     */
    fun openAppDetails(context: Context) {
        startFirstAvailable(context, listOf(appDetailsIntent(context)), "应用详情页")
    }

    private fun appDetailsIntent(context: Context) = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )

    private fun startFirstAvailable(context: Context, candidates: List<Intent>, label: String) {
        for (intent in candidates) {
            val result = runCatching { context.startActivity(newTask(intent)) }
            if (result.isSuccess) return
            Log.d(TAG, "$label 入口不可用，尝试下一个: ${result.exceptionOrNull()?.message}")
        }
        // 全部失败时退回应用详情页，至少让家长有个落脚点
        runCatching { context.startActivity(newTask(appDetailsIntent(context))) }
            .onFailure { Log.w(TAG, "无法打开 $label，也没有可用的兜底入口", it) }
    }
}
