package com.zzl.guardian.child.engine

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log

/**
 * 系统组件识别（1.0.19）。
 *
 * 背景：内置应用宝（com.tbsmart.levision）等**系统预装**应用会被当成普通应用
 * 计入使用时长，家长看到"某个不认识的软件占了大部分时间"。这类应用由设备厂商
 * 预置、无法卸载、孩子也不会主动去用，计它没有意义。
 *
 * 判定顺序（任一命中即算系统组件，**不计时**）：
 *  1. 应用未安装（卸载残留的窗口事件）
 *  2. [ApplicationInfo.FLAG_SYSTEM] 或 [FLAG_UPDATED_SYSTEM_APP] —— 系统分区/预装
 *  3. 由系统直接解析出来的桌面/输入法/壁纸/设备管理员等角色
 *  4. 包名特征兜底（厂商预置的怪包名，PM 标志偶尔不靠谱的 ROM）
 *
 * 刻意**不做**的事：不硬编码具体包名黑名单。厂商包名各不相同，
 * 靠"是否系统应用"这一事实判定才跨设备可靠。
 */
class SystemAppFilter(context: Context) {

    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager

    /** 缓存判定结果：安装集合变化才会变，60 秒刷新足够 */
    private val cache = HashMap<String, Boolean>()
    private var cacheAt = 0L

    @Synchronized
    fun isSystemComponent(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        val now = System.currentTimeMillis()
        if (now - cacheAt > CACHE_TTL_MS) {
            cache.clear()
            cacheAt = now
        }
        cache[pkg]?.let { return it }
        val result = compute(pkg)
        cache[pkg] = result
        return result
    }

    private fun compute(pkg: String): Boolean = runCatching {
        // 1) 已卸载（窗口事件滞后于卸载）
        val info = pm.getApplicationInfo(pkg, 0)
            ?: return@runCatching true
        // 2) 系统分区 / 预装应用
        if (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
            return@runCatching true
        }
        // 3) 系统角色（桌面/输入法/壁纸…）——这些也常带 FLAG_SYSTEM，双保险
        if (isSystemRole(pkg)) return@runCatching true
        // 4) 包名特征兜底
        if (pkg.contains("launcher", true) ||
            pkg.contains("desktop", true) ||
            pkg.contains("systemui", true) ||
            pkg.contains("systemuiserver", true) ||
            pkg.contains("inputmethod", true) ||
            pkg.contains("ime", true) ||
            pkg.contains("wallpaper", true)
        ) {
            return@runCatching true
        }
        false
    }.getOrElse { error ->
        // 拿不到信息时**保守放行**（照常计时）：宁可多计也不要漏计真实使用
        Log.d(TAG, "系统组件判定失败，按普通应用计时: $pkg (${error.message})")
        false
    }

    private fun isSystemRole(pkg: String): Boolean {
        // 桌面由引擎的 isDesktopPackage 单独判定（这里不重复，也避免每次查询桌面）
        val ime = Intent("android.intent.action.SHOW_INPUT_METHODS")
        val deviceAdmin = Intent("android.app.action.DEVICE_ADMIN_ENABLED")
        val wallpaper = Intent(Intent.ACTION_SET_WALLPAPER)
        val imeService = Intent("android.view.InputMethod")
        return pm.queryIntentActivities(ime, 0).any { it.resolvePackage(pkg) } ||
            pm.queryIntentServices(deviceAdmin, 0).any { it.resolvePackage(pkg) } ||
            pm.queryIntentActivities(wallpaper, 0).any { it.resolvePackage(pkg) } ||
            pm.queryIntentServices(imeService, 0).any { it.resolvePackage(pkg) }
    }

    private fun ResolveInfo.resolvePackage(target: String): Boolean =
        (activityInfo?.packageName ?: serviceInfo?.packageName) == target

    companion object {
        private const val TAG = "SystemAppFilter"
        private const val CACHE_TTL_MS = 60_000L
    }
}
