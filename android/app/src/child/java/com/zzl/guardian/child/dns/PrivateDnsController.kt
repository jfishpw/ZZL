package com.zzl.guardian.child.dns

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.zzl.guardian.child.admin.AdminModeManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 私人 DNS（DNS-over-TLS）的读取、写入与故障探测。
 *
 * 权限模型：写入 `Settings.Global` 的 private DNS 键需要
 * `WRITE_SECURE_SETTINGS` —— Device Owner 自动持有；普通设备管理器模式
 * 通过一次 ADB `pm grant` 授予（孩子无法在设置里看到或撤销）。
 * 两者都没有时本控制器处于"只读"状态，只上报状态不做控制。
 *
 * 判定逻辑在 [PrivateDnsPolicy]（纯函数）；本类负责系统调用与探测。
 */
@Singleton
class PrivateDnsController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 期望主机 853 端口可达性（null = 尚未探测） */
    @Volatile
    var lastDotReachable: Boolean? = null
        private set

    /** 金丝雀域名解析连续失败次数 */
    @Volatile
    var canaryFailures: Int = 0
        private set

    /** 是否处于 fail-open 降级（已临时清除私人 DNS） */
    @Volatile
    var isFailOpen: Boolean = false
        private set

    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** 是否具备控制能力：API 28+ 且（被授予 WRITE_SECURE_SETTINGS 或为 Device Owner） */
    fun canControl(): Boolean {
        if (!supported()) return false
        if (AdminModeManager.isDeviceOwner(context)) return true
        return ContextCompat.checkSelfPermission(
            context,
            "android.permission.WRITE_SECURE_SETTINGS",
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** 家长期望的主机名（控制端下发的配置，本地持久化，离线也不丢） */
    fun expected(): String? = prefs.getString(KEY_EXPECTED_HOST, null)?.takeIf { it.isNotEmpty() }

    fun setExpected(host: String?) {
        prefs.edit().putString(KEY_EXPECTED_HOST, host?.takeIf { it.isNotEmpty() }).apply()
    }

    /** 设备当前实际的私人 DNS 主机名（null = 未设置/未启用） */
    fun current(): String? = runCatching {
        Settings.Global.getString(context.contentResolver, KEY_SPECIFIER)
    }.getOrNull()

    /** 给加固状态上报用的简短状态描述 */
    fun statusText(): String = when {
        !supported() -> "unsupported"
        isFailOpen -> "fail-open"
        current() != null -> current()!!
        else -> "off"
    }

    /**
     * 写入或清除私人 DNS。
     *
     * @return 是否真正写成功（无权限时返回 false，上层据此告警）
     */
    fun apply(host: String?): Boolean {
        val ok = runCatching {
            val resolver = context.contentResolver
            if (host == null) {
                // 恢复安卓默认的"自动"模式，并清掉主机名
                Settings.Global.putString(resolver, KEY_MODE, "opportunistic")
                Settings.Global.putString(resolver, KEY_SPECIFIER, null)
            } else {
                // 严格模式：全部查询强制走该 DoT 服务器
                Settings.Global.putString(resolver, KEY_MODE, "on")
                Settings.Global.putString(resolver, KEY_SPECIFIER, host)
            }
            true
        }.getOrDefault(false)

        if (ok) {
            canaryFailures = 0
            // DO 下顺带冻结设置入口（API 29+）；非 DO 靠巡检写回兜底
            setConfigFrozen(host != null && AdminModeManager.isDeviceOwner(context))
            if (host != null) {
                setFailOpen(false)
            }
        }
        return ok
    }

    /** 进入/退出 fail-open 降级状态（状态标记由调用方配合 apply 使用） */
    fun setFailOpen(active: Boolean) {
        isFailOpen = active
        prefs.edit().putBoolean(KEY_FAIL_OPEN, active).apply()
    }

    /** 退出管控 / 清理时调用：清状态标记，私人 DNS 恢复默认解析 */
    fun clearAll() {
        if (canControl()) apply(null)
        setExpected(null)
        setFailOpen(false)
        setConfigFrozen(false)
        canaryFailures = 0
        lastDotReachable = null
    }

    /**
     * 启动一轮探测（异步，结果写入 [lastDotReachable] / [canaryFailures]，
     * 供下一轮 tick 的判定使用 —— 探测绝不能阻塞巡检线程）。
     */
    fun startProbes(expectedHost: String?) {
        if (expectedHost == null) {
            canaryFailures = 0
            return
        }
        probeScope.launch {
            lastDotReachable = probeDotPort(expectedHost)
        }
        probeScope.launch {
            val ok = withTimeoutOrNull(CANARY_TIMEOUT_MS) {
                runCatching {
                    InetAddress.getAllByName(CANARY_HOST).isNotEmpty()
                }.getOrDefault(false)
            } == true
            canaryFailures = if (ok) 0 else canaryFailures + 1
        }
    }

    /** 设备当前是否有可用网络（只看 IP 层，不涉及 DNS） */
    fun networkUp(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.activeNetwork != null
    }.getOrDefault(false)

    /**
     * 探测期望主机的 853 端口。
     * 直接 TCP 连接，不经过 DNS 的主机名解析仍会发生 —— 但解析的是
     * 过滤服务自己的域名（父域），它挂的通常只是过滤进程而非权威 DNS。
     */
    private fun probeDotPort(host: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, DOT_PORT), DOT_PROBE_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    /**
     * DO 下冻结系统设置里的私人 DNS 入口（API 29+）。
     * 与无障碍冻结同一原则：只在防护启用时冻结，关闭/退出时解除。
     */
    private fun setConfigFrozen(frozen: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!AdminModeManager.isDeviceOwner(context)) return
        runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as? android.app.admin.DevicePolicyManager ?: return
            val admin = adminComponent() ?: return
            if (frozen) {
                dpm.addUserRestriction(admin, RESTRICTION)
            } else {
                dpm.clearUserRestriction(admin, RESTRICTION)
            }
        }.onFailure { Log.w(TAG, "切换私人 DNS 冻结状态失败", it) }
    }

    private fun adminComponent(): ComponentName? = runCatching {
        val pm = context.packageManager
        val intent = Intent("android.app.action.DEVICE_ADMIN_ENABLED").setPackage(context.packageName)
        pm.queryBroadcastReceivers(intent, 0)
            .firstOrNull { it.activityInfo?.packageName == context.packageName }
            ?.let { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    }.getOrNull() ?: AdminModeManager.receiverComponent(context)

    private companion object {
        const val TAG = "PrivateDns"
        const val PREFS_NAME = "private_dns"
        const val KEY_EXPECTED_HOST = "expected_host"
        const val KEY_FAIL_OPEN = "fail_open"

        /** Settings.Global 的隐藏键（API 28+），没有公开常量，只能用字面量 */
        const val KEY_MODE = "private_dns_mode"
        const val KEY_SPECIFIER = "private_dns_specifier"

        /** Device Owner 专属：禁止用户在设置里改私人 DNS */
        const val RESTRICTION = "no_config_private_dns"

        const val DOT_PORT = 853
        const val DOT_PROBE_TIMEOUT_MS = 2_000
        const val CANARY_TIMEOUT_MS = 6_000L
        const val CANARY_HOST = "www.baidu.com"
    }
}
