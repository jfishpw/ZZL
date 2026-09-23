package com.zzl.guardian.child.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.zzl.guardian.child.service.GuardForegroundService

/**
 * 设备管理器/设备所有者的接收器。
 *
 * 它是**防卸载体系里唯一能感知"被绕过"的位置**：孩子在
 * 「设置 → 安全 → 设备管理应用」里取消激活时，系统会回调 [onDisabled]。
 * 这是我们能拿到的最早信号 —— 必须在这一刻做三件事：
 *
 *  1. 立刻弹出全屏遮罩（不等下一次巡检，孩子正在卸载的路上）
 *  2. 上报家长（控制端立刻看到告警）
 *  3. 本地落库（离线也要留下痕迹，上线后补报）
 *
 * 做不到"阻止"，但能做到"无法悄悄进行" —— 这是 Device Admin 模式的全部价值。
 */
class GuardDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "设备管理器已激活")
        notifyGuard(context, "设备管理器已激活")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "★ 设备管理器被取消激活 —— 孩子可能正在卸载")

        // 设备管理器被取消，设备所有者身份也随之失效，先同步一次本地状态
        notifyGuard(context, "设备管理器已被取消激活")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // 系统在取消激活前会展示这段文字，这是最后一次"劝阻"的机会
        return "取消后，家长将无法再限制本设备的使用时间。确定要继续吗？"
    }

    /**
     * 通知常驻服务立刻重新评估。
     *
     * 不在这里直接弹遮罩的原因：遮罩挂在 WindowManager 上，由引擎统一管理。
     * 从广播接收器直接操作会绕过引擎的状态机，导致"遮罩显示着但引擎认为没拦"。
     */
    private fun notifyGuard(context: Context, reason: String) {
        val appContext = context.applicationContext
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, GuardForegroundService::class.java)
                    .setAction(GuardForegroundService.ACTION_REASSESS)
                    .putExtra(GuardForegroundService.EXTRA_REASON, reason),
            )
        }.onFailure { Log.w(TAG, "拉起管控服务失败", it) }
    }

    private companion object {
        const val TAG = "GuardDeviceAdmin"
    }
}

/**
 * 管控模式的探测与操作。
 *
 * 一个必须讲清楚的事实：**两种模式的强度差距是数量级的**。
 * Device Owner 是系统级的"不可绕过"，Device Admin 只是"提高门槛 + 暴露行为"。
 * 因此这里不做"能自动升级就自动升级"的猜测 —— 模式由首次装机时是否做过
 * ADB 激活决定，是设备本地的既成事实，代码只能如实探测与呈现。
 */
object AdminModeManager {

    private const val TAG = "AdminMode"

    fun receiverComponent(context: Context) =
        ComponentName(context, GuardDeviceAdminReceiver::class.java)

    private fun dpm(context: Context): DevicePolicyManager? =
        runCatching {
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        }.getOrNull()

    /** 是否已激活设备管理器（Device Owner 也必然是激活的设备管理器） */
    fun isAdminActive(context: Context): Boolean = runCatching {
        dpm(context)?.isAdminActive(receiverComponent(context)) == true
    }.getOrDefault(false)

    /** 是否是设备所有者（厂商级强度，无法取消激活） */
    fun isDeviceOwner(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return false
        dpm(context)?.isDeviceOwnerApp(context.packageName) == true
    }.getOrDefault(false)

    /** 归一化的当前模式 */
    fun currentMode(context: Context): String = when {
        isDeviceOwner(context) -> MODE_DEVICE_OWNER
        isAdminActive(context) -> MODE_DEVICE_ADMIN
        else -> MODE_NONE
    }

    /**
     * 尝试阻止卸载本应用。
     *
     * - Device Owner：系统级生效，无任何入口可绕过
     * - Device Admin：API 24+ 可用，但孩子取消激活后即失效
     *
     * @return 是否成功设置
     */
    fun blockUninstall(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val manager = dpm(context) ?: return false
        manager.setUninstallBlocked(receiverComponent(context), context.packageName, true)
        true
    }.onFailure { Log.w(TAG, "阻止卸载失败（通常是没有 Device Owner 权限）", it) }
        .getOrDefault(false)

    fun isUninstallBlocked(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        dpm(context)?.isUninstallBlocked(receiverComponent(context), context.packageName) == true
    }.getOrDefault(false)

    /**
     * 解除对本应用的卸载阻止。
     *
     * ⚠️ **这是 L3 超级密码的必备配套，漏掉会造成死局。**
     *
     * Device Owner 模式下 `setUninstallBlocked(true)` 是系统级强制，
     * 而 Device Owner 身份本身**无法通过系统界面取消激活**（这正是它的价值所在）。
     * 于是会出现：家长用超级密码选择"卸载本应用"，系统却始终拒绝卸载，
     * 而家长找不到任何可操作的入口 —— 自救通道被自己的加固措施堵死了。
     *
     * 因此卸载流程必须**先解除阻止**，再引导取消激活，最后才是系统卸载。
     */
    fun unblockUninstall(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val manager = dpm(context) ?: return false
        manager.setUninstallBlocked(receiverComponent(context), context.packageName, false)
        true
    }.onFailure { Log.w(TAG, "解除卸载阻止失败", it) }
        .getOrDefault(false)

    /**
     * Device Owner 专属的加固项。
     *
     * @param freezeAccessibility 是否冻结系统无障碍开关。**只在无障碍当前正常开启时为 true**：
     *   冻结会同时禁止"关"与"开"，若在无障碍已被系统关掉时还冻着，
     *   设置页就打不开了，家长将没有任何办法重新授权（死锁）。
     *   条件式冻结 = 正常时孩子关不掉（事前防护）+ 意外关闭时自动解冻可重开（自愈）。
     *
     * ⚠️ **不包含 `DISALLOW_FACTORY_RESET`**：禁用后家长忘记离线密码且完全离线时
     * 将无法自救，设备直接变砖。保留恢复出厂这条后路是刻意的选择。
     */
    fun applyDeviceOwnerRestrictions(context: Context, freezeAccessibility: Boolean): List<String> {
        val manager = dpm(context) ?: return emptyList()
        if (!isDeviceOwner(context)) return emptyList()

        val applied = mutableListOf<String>()
        val component = receiverComponent(context)

        // 禁用安全模式：孩子无法通过安全模式绕过管控
        runCatching {
            manager.addUserRestriction(component, "no_safe_boot")
            applied += "no_safe_boot"
        }

        // 禁用 USB 调试之外的开发者选项调整（保留 adb 以便家长后续维护）
        runCatching {
            manager.addUserRestriction(component, "no_debugging_features")
            applied += "no_debugging_features"
        }

        // 冻结系统设置里的无障碍开关：这是"防关闭无障碍"唯一的事前手段 ——
        // 检测与审计都只能事后补救（管控在那段时间已经失效）。
        // 见方法 KDoc：条件式冻结，避免"关掉后冻住设置页"的死锁。
        // 限制键用字面量：与 no_safe_boot / no_debugging_features 同风格，
        // 也绕开个别 compileSdk 下 UserManager 常量解析不一致的问题。
        if (freezeAccessibility) {
            runCatching {
                manager.addUserRestriction(component, RESTRICTION_ACCESSIBILITY_CONFIG)
                applied += RESTRICTION_ACCESSIBILITY_CONFIG
            }
        } else {
            clearUserRestrictionSafely(manager, component, RESTRICTION_ACCESSIBILITY_CONFIG)
        }

        return applied
    }

    /** 解除本应用设置的全部用户限制（退出管控时调用），与 [applyDeviceOwnerRestrictions] 清单一致 */
    fun clearDeviceOwnerRestrictions(context: Context) {
        val manager = dpm(context) ?: return
        if (!isDeviceOwner(context)) return
        val component = receiverComponent(context)
        listOf("no_safe_boot", "no_debugging_features", RESTRICTION_ACCESSIBILITY_CONFIG)
            .forEach { restriction ->
                clearUserRestrictionSafely(manager, component, restriction)
            }
    }

    private fun clearUserRestrictionSafely(
        manager: DevicePolicyManager,
        component: ComponentName,
        restriction: String,
    ) {
        runCatching { manager.clearUserRestriction(component, restriction) }
            .onFailure { Log.w(TAG, "解除用户限制 $restriction 失败", it) }
    }

    /** 冻结系统无障碍开关的用户限制键（UserManager.DISALLOW_CONFIG_ACCESSIBILITY 的键值） */
    private const val RESTRICTION_ACCESSIBILITY_CONFIG = "no_config_accessibility"

    /** 打开系统的设备管理器激活页，让家长手动勾选 */
    fun openActivation(context: Context) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, receiverComponent(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "激活后本应用将无法被直接卸载，管控不会中途失效。",
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(intent); true }
            .onFailure { Log.w(TAG, "打开设备管理器激活页失败", it) }
            .getOrDefault(false)
        if (!opened) {
            // 个别 ROM 裁剪了定向激活页，退而求其次打开设备管理应用列表
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** 主动请求取消激活（家长用超级密码退出管控时） */
    fun requestDeactivation(context: Context) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, receiverComponent(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "确认后本应用将不再限制设备使用。",
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // 系统没有"程序化取消激活"的公开 API（这是刻意的安全设计），
        // 只能引导家长到系统页面手动操作
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "打开设备管理器页面失败", it) }
    }

    const val MODE_DEVICE_OWNER = "device_owner"
    const val MODE_DEVICE_ADMIN = "device_admin"
    const val MODE_NONE = "none"
}
