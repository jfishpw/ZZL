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
 *
 * ★ **恢复的关键陷阱（实测踩过）**：组件一旦被 [hide] 禁用，
 * `queryIntentActivities` **默认会把它排除在结果之外** —— 恢复时按默认 flag
 * 再查一遍只能查到 null，重新启用的代码被跳过，图标永远回不来（真机反馈
 * "隐藏正常、恢复时报错且无效"）。因此解析必须带
 * [PackageManager.MATCH_DISABLED_COMPONENTS]，且 [hide] 会把组件名持久化，
 * [show] 在查询失败时用存档兜底。
 */
@Singleton
class IconController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 被控端自己的 launcher 组件。
     *
     * 隐藏图标用 `COMPONENT_ENABLED_STATE_DISABLED` 禁用这个组件，
     * 而不是 `setApplicationEnabledSetting` 禁用整个应用 ——
     * 后者会把前台服务、无障碍服务一起干掉，等于自毁管控能力。
     *
     * 组件名通过 `queryIntentActivities` **动态解析**而不是硬编码类名：
     * 类名一旦重命名（或将来换成 alias），硬编码会静默失效。
     * 必须带 [PackageManager.MATCH_DISABLED_COMPONENTS]，否则组件被
     * [hide] 禁用后这里永远返回 null（见类注释）。
     */
    private fun resolveLauncherComponent(): ComponentName? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // 加包名限定，避免把系统桌面自身也匹配进来
        intent.setPackage(context.packageName)

        context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
            .firstOrNull { it.activityInfo?.packageName == context.packageName }
            ?.activityInfo
            ?.let { ComponentName(it.packageName, it.name) }
    }.getOrNull()

    /** hide() 时存下的组件名：查询被禁用组件失败时的兜底 */
    private fun storedLauncherComponent(): ComponentName? =
        prefs.getString(KEY_LAUNCHER_COMPONENT, null)
            ?.let { runCatching { ComponentName.unflattenFromString(it) }.getOrNull() }
            ?.takeIf { it.packageName == context.packageName }

    fun isHidden(): Boolean = runCatching {
        if (AdminModeManager.isDeviceOwner(context)) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            return@runCatching dpm?.isApplicationHidden(
                AdminModeManager.receiverComponent(context),
                context.packageName,
            ) == true
        }

        // 查询带 MATCH_DISABLED_COMPONENTS：禁用后也要能查到它，
        // 否则这里永远读到 false，对账逻辑会误以为"状态已一致"而不补救
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
            // 立刻把组件名存档：禁用后动态查询可能拿不到它（某些 ROM 对
            // MATCH_DISABLED_COMPONENTS 的支持不完整），恢复时用存档兜底
            prefs.edit().putString(KEY_LAUNCHER_COMPONENT, component.flattenToString()).apply()
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
     *
     * @return 是否真的执行了恢复动作。供指令回执与对账判断 ——
     * 返回 false 时上层可以重试，而不是被"成功"假象骗过。
     */
    fun show(): Boolean = runCatching {
        var restored = false

        if (AdminModeManager.isDeviceOwner(context)) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            dpm?.setApplicationHidden(AdminModeManager.receiverComponent(context), context.packageName, false)
            // Device Owner 分支本身就完成了系统级恢复；
            // 此时应用处于 hidden 刚解开的状态，组件查询可能仍为空，不算失败
            restored = true
        }

        // 两种机制都执行一遍恢复：设备可能在两种模式之间切换过，
        // 而两条路径的"已隐藏"状态是独立的（一个是系统级，一个是组件级）。
        // 只恢复当前模式会留下另一种模式的残留隐藏。
        // 查询优先；禁用态查不到（ROM 差异）时用 hide() 存的组件名兜底。
        val component = resolveLauncherComponent() ?: storedLauncherComponent()
        if (component != null) {
            context.packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            restored = true
        }

        if (restored) {
            Log.i(TAG, "已恢复应用图标")
        } else {
            Log.w(TAG, "恢复图标失败：无法定位 launcher 组件（查询与存档均为空）")
        }
        restored
    }.onFailure { Log.w(TAG, "恢复图标失败（已忽略，不阻断上层流程）", it) }
        .getOrDefault(false)

    private companion object {
        const val TAG = "IconController"
        const val PREFS_NAME = "icon_controller"
        const val KEY_LAUNCHER_COMPONENT = "launcher_component"
    }
}
