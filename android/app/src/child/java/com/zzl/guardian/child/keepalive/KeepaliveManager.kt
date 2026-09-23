package com.zzl.guardian.child.keepalive

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.zzl.guardian.data.api.KeepaliveState

/**
 * 厂商识别与保活健康度检测。
 *
 * 为什么要单独做厂商识别：**保活的失败几乎全部发生在厂商 ROM 上**。
 * 原生 Android 只需前台服务 + 电池优化白名单就够了，
 * 而小米/华为/荣耀/OPPO/vivo 各自还有一套"自启动管理""后台运行管理"，
 * 不设置的话前台服务会在息屏后几分钟内被杀。
 *
 * 这些设置**无法程序化检测，也无法程序化开启**（厂商没有公开 API，
 * 且刻意不让 App 自己加白名单）。因此这里的策略是：
 * 程序能查的（电池优化、精确闹钟、通知）如实查，查不到的引导家长手动确认。
 */
object KeepaliveManager {

    private const val TAG = "Keepalive"

    /** 识别到的厂商 */
    enum class Vendor(val displayName: String, val hint: String) {
        XIAOMI(
            "小米 / 红米",
            "设置 → 应用设置 → 应用管理 → 掌中灵 → 省电策略选「无限制」\n" +
                "并在「自启动管理」里允许自启动",
        ),
        HUAWEI(
            "华为 / 荣耀",
            "设置 → 应用 → 应用启动管理 → 掌中灵 → 关闭「自动管理」，\n" +
                "并手动勾选「自启动」「关联启动」「后台活动」",
        ),
        OPPO(
            "OPPO / 一加 / realme",
            "设置 → 电池 → 应用耗电管理 → 掌中灵 → 允许「后台运行」\n" +
                "并在「应用速冻」里排除掌中灵",
        ),
        VIVO(
            "vivo / iQOO",
            "设置 → 电池 → 后台高耗电 → 允许掌中灵\n" +
                "并在「自启动」里开启掌中灵",
        ),
        SAMSUNG(
            "三星",
            "设置 → 电池 → 后台使用限制 → 从「休眠应用」里移除掌中灵\n" +
                "并在「自动运行应用」里添加掌中灵",
        ),
        MEIZU("魅族", "设置 → 应用管理 → 权限 → 后台管理 → 允许后台运行"),
        ONEPLUS("一加", "设置 → 电池 → 电池优化 → 掌中灵 → 不优化"),
        OTHER("其他机型", "请在系统设置中找到「自启动」「后台运行」「省电策略」相关项，允许掌中灵常驻"),
    }

    /** 按 Build.MANUFACTURER 识别厂商。大小写不统一，统一转小写后做包含判断。 */
    fun detectVendor(): Vendor {
        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") -> Vendor.XIAOMI
            brand.contains("huawei") || brand.contains("honor") -> Vendor.HUAWEI
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
                Vendor.OPPO
            brand.contains("vivo") || brand.contains("iqoo") -> Vendor.VIVO
            brand.contains("samsung") -> Vendor.SAMSUNG
            brand.contains("meizu") -> Vendor.MEIZU
            else -> Vendor.OTHER
        }
    }

    /** 是否已加入电池优化白名单（即"不受电池优化限制"） */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        power.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /** 精确闹钟权限（Android 12+ 需要显式授权，用于定时自我唤醒） */
    fun canScheduleExactAlarms(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.canScheduleExactAlarms()
    }.getOrDefault(true)

    /** 通知是否开启 —— 前台服务的常驻通知被关掉后，服务存活率会明显下降 */
    fun isNotificationEnabled(context: Context): Boolean = runCatching {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }.getOrDefault(false)

    /**
     * 采集健康度。
     *
     * `vendorWhitelistConfirmed` 无法程序化检测，需要由调用方从本地存储读入
     * （家长在界面上手动勾选"我已设置"）。
     */
    fun snapshot(
        context: Context,
        vendorWhitelistConfirmed: Boolean,
        foregroundServiceRunning: Boolean,
    ): KeepaliveState {
        val vendor = detectVendor()
        return KeepaliveState(
            batteryOptimized = isIgnoringBatteryOptimizations(context),
            exactAlarmAllowed = canScheduleExactAlarms(context),
            notificationEnabled = isNotificationEnabled(context),
            foregroundService = foregroundServiceRunning,
            vendorWhitelistConfirmed = vendorWhitelistConfirmed,
            vendor = vendor.displayName,
            vendorHint = vendor.hint,
        )
    }

    /** 是否全部到位。任一项缺失都会显著降低存活率。 */
    fun isHealthy(state: KeepaliveState): Boolean =
        state.batteryOptimized &&
            state.exactAlarmAllowed &&
            state.notificationEnabled &&
            state.foregroundService &&
            // 只有非"其他机型"才强制要求厂商白名单确认 ——
            // 原生 Android 设备没有这一层，强行要求会让家长一直看到"不健康"
            (state.vendorWhitelistConfirmed || state.vendor == Vendor.OTHER.displayName)

    /** 逐项列出还缺什么，界面直接展示 */
    fun missingItems(state: KeepaliveState): List<String> = buildList {
        if (!state.notificationEnabled) add("通知权限未开启，常驻通知不可见")
        if (!state.batteryOptimized) add("未加入电池优化白名单，息屏后可能被系统清理")
        if (!state.exactAlarmAllowed) add("精确闹钟权限未开启，定时唤醒不可靠")
        if (!state.foregroundService) add("管控服务未在运行")
        if (!state.vendorWhitelistConfirmed && state.vendor != Vendor.OTHER.displayName) {
            add("尚未在「${state.vendor}」的后台白名单中允许掌中灵")
        }
    }

    /** 打开电池优化设置 */
    fun openBatterySettings(context: Context) {
        val candidates = listOf(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        startFirst(context, candidates, "电池优化设置")
    }

    /** 打开精确闹钟授权页（Android 12+） */
    fun openExactAlarmSettings(context: Context) {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
        startFirst(context, candidates, "精确闹钟设置")
    }

    /** 打开应用详情页（厂商白名单的入口大多在这里） */
    fun openAppDetails(context: Context) {
        startFirst(
            context,
            listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))),
            "应用详情页",
        )
    }

    /** 打开自启动管理页（部分厂商有公开 Action，多数没有，失败则退回应用详情页） */
    fun openAutoStartSettings(context: Context) {
        val vendor = detectVendor()
        val candidates = buildList {
            // 这些是厂商私有的 Action，能用则用，不能用就走兜底
            when (vendor) {
                Vendor.XIAOMI -> add(Intent().setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ))
                Vendor.HUAWEI -> add(Intent().setClassName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ))
                Vendor.OPPO -> add(Intent().setClassName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ))
                Vendor.VIVO -> add(Intent().setClassName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ))
                Vendor.SAMSUNG -> add(Intent().setClassName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                ))
                else -> Unit
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
        startFirst(context, candidates, "自启动管理")
    }

    private fun startFirst(context: Context, candidates: List<Intent>, label: String) {
        for (intent in candidates) {
            val ok = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
        Log.w(TAG, "无法打开 $label，也没有可用的兜底入口")
    }
}
