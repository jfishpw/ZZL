package com.zzl.guardian.child.icon

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.zzl.guardian.child.admin.AdminModeManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 桌面图标的隐藏与恢复。
 *
 * 两种模式用的是**完全不同**的机制，强度也差一个量级：
 *
 * | 模式 | 机制 | 强度 |
 * |---|---|---|
 * | Device Owner | `setApplicationHidden` | 系统级：应用在设置的应用列表里也看不到 |
 * | Device Admin / 普通 | 禁用 launcher 组件 | 仅隐藏桌面图标：应用列表里仍能找到，孩子能从这里打开 |
 *
 * 之所以还要实现第二种：它不需要 ADB，家长在手机上点一下就能用。
 * 代价是"图标没了但应用还能用"—— 这个局限必须如实告诉家长，
 * 否则他会以为"孩子打不开了"。
 *
 * ⚠️ **隐藏图标是有风险的操作，必须有一个绝对可靠的恢复路径**：
 * 被控端的设置页入口、以及控制端的远程恢复。少了恢复路径，
 * 一次误操作就可能让家长自己也进不去。
 * 这也是为什么"退出管控"时必须调用 [show] 恢复图标。
 */
@Singleton
class IconController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 被控端自己的 launcher 组件。
     *
     * 隐藏图标用 `COMPONENT_ENABLED_STATE_DISABLED` 禁用这个组件，
     * 而不是 `setApplicationEnabledSetting` 禁用整个应用 ——
     * 后者会把前台服务、无障碍服务一起干掉，等于自毁管控能力。
     *
     * 组件名通过 `queryIntentActivities` **动态解析**而不是硬编码类名：
     * 类名一旦重命名（或将来换成 alias），硬编码会静默失效
     * —— 表现为"提示隐藏成功，但图标还在"，而且不报任何错。
     */
    private fun resolveLauncherComponent(): ComponentName? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // 加包名限定，避免把系统桌面自身也匹配进来
        intent.setPackage(context.packageName)

        context.packageManager
            .queryIntentActivities(intent, 0)
            .firstOrNull { it.activityInfo?.packageName == context.packageName }
            ?.activityInfo
            ?.let { ComponentName(it.packageName, it.name) }
    }.getOrNull()

    fun isHidden(): Boolean = runCatching {
        if (AdminModeManager.isDeviceOwner(context)) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            return@runCatching dpm?.isApplicationHidden(
                AdminModeManager.receiverComponent(context),
                context.packageName,
            ) == true
        }

        val component = resolveLauncherComponent() ?: return@runCatching false
        context.packageManager.getComponentEnabledSetting(component) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }.getOrDefault(false)

    /**
     * 隐藏图标。
     *
     * @return 是否成功
     */
    fun hide(): Boolean = runCatching {
        if (AdminModeManager.isDeviceOwner(context)) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            // Device Owner 走系统级隐藏：应用在「设置 → 应用」里也看不到
            dpm.setApplicationHidden(AdminModeManager.receiverComponent(context), context.packageName, true)
            Log.i(TAG, "已通过 Device Owner 隐藏整个应用")
        } else {
            val component = resolveLauncherComponent()
                ?: return false
            // 只禁用桌面入口，保留服务与无障碍能力
            context.packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            Log.i(TAG, "已隐藏桌面图标（设备管理器模式）")
        }
        true
    }.onFailure { Log.w(TAG, "隐藏图标失败", it) }
        .getOrDefault(false)

    /**
     * 恢复图标。
     *
     * 刻意做成"尽力而为、永不抛异常"：它出现在退出管控、卸载、
     * 以及家长远程恢复这几条关键路径上，任何一条失败都可能让家长被困住。
     * 因此失败只记日志，不让上层中断。
     */
    fun show(): Boolean = runCatching {
        if (AdminModeManager.isDeviceOwner(context)) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            dpm?.setApplicationHidden(AdminModeManager.receiverComponent(context), context.packageName, false)
        }

        // 两种模式都执行一遍恢复：设备可能在两种模式之间切换过，
        // 而两条路径的"已隐藏"状态是独立的（一个是系统级，一个是组件级）。
        // 只恢复当前模式会留下另一种模式的残留隐藏。
        resolveLauncherComponent()?.let { component ->
            context.packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        Log.i(TAG, "已恢复应用图标")
        true
    }.onFailure { Log.w(TAG, "恢复图标失败（已忽略，不阻断上层流程）", it) }
        .getOrDefault(false)

    private companion object {
        const val TAG = "IconController"
    }
}
