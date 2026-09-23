package com.zzl.guardian.child

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzl.guardian.child.admin.AdminModeManager
import com.zzl.guardian.child.data.DeviceStateRepository
import com.zzl.guardian.child.data.GrantEntity
import com.zzl.guardian.child.data.GrantScope
import com.zzl.guardian.child.data.PolicyEntity
import com.zzl.guardian.child.data.PolicyRepository
import com.zzl.guardian.child.data.UsageRepository
import com.zzl.guardian.child.engine.GuardEngine
import com.zzl.guardian.child.engine.GuardState
import com.zzl.guardian.child.keepalive.KeepaliveManager
import com.zzl.guardian.child.keepalive.KeepaliveStore
import com.zzl.guardian.child.pin.PinStore
import com.zzl.guardian.child.service.GuardForegroundService
import com.zzl.guardian.data.ChildSession
import com.zzl.guardian.data.SettingsStore
import com.zzl.guardian.data.api.ApiError
import com.zzl.guardian.data.api.ApiService
import com.zzl.guardian.data.api.HealthState
import com.zzl.guardian.data.api.KeepaliveState
import com.zzl.guardian.data.api.PairClaimRequest
import com.zzl.guardian.di.ServerConfigHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import javax.inject.Inject

@HiltViewModel
class ChildViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService,
    private val settingsStore: SettingsStore,
    private val policyRepository: PolicyRepository,
    private val deviceStateRepository: DeviceStateRepository,
    private val usageRepository: UsageRepository,
    private val pinStore: PinStore,
    private val keepaliveStore: KeepaliveStore,
    private val engine: GuardEngine,
    private val holder: ServerConfigHolder,
    private val json: Json,
) : ViewModel() {

    data class UiState(
        val booting: Boolean = true,
        val session: ChildSession? = null,
        val health: HealthState = HealthState(),
        val guard: GuardState = GuardState(),
        val policy: PolicyEntity? = null,
        /** 生效中的临时授权：孩子也能看到"家长给我加了多少时间" */
        val grants: List<GrantEntity> = emptyList(),
        val busy: Boolean = false,
        val error: String? = null,
        val notice: String? = null,
        val serverDisplay: String = "",
        /** 当前管控模式：device_owner | device_admin | none */
        val adminMode: String = "none",
        val uninstallBlocked: Boolean = false,
        val keepalive: KeepaliveState? = null,
        val pinReady: Boolean = false,
        val vendorWhitelistConfirmed: Boolean = false,
        /** 失败次数超过 0 说明有人试过密码 —— 家长在设备上也能看到 */
        val failedPinAttempts: Int = 0,
    ) {
        /** 无障碍 / 使用情况 / 悬浮窗三项缺一不可，管控才真正生效 */
        val missingCount: Int
            get() = listOf(health.accessibility, health.usageAccess, health.overlay).count { !it }

        val fullyArmed: Boolean
            get() = missingCount == 0 && guard.ready && guard.enforcementEnabled

        val usedText: String get() = formatMinutes(guard.usedTodayMs)
        val limitText: String get() = formatMinutes(guard.limitMs)
        val remainingText: String get() = formatMinutes(guard.remainingMs)

        /** 加固强度的一句话描述，家长在设备上也能核对 */
        val modeText: String
            get() = when (adminMode) {
                "device_owner" -> "设备所有者模式（无法卸载）"
                "device_admin" -> "设备管理器模式（可被取消激活）"
                else -> "未激活管控模式（可被直接卸载）"
            }

        val keepaliveMissing: List<String>
            get() {
                val k = keepalive ?: return emptyList()
                return buildList {
                    if (!k.notificationEnabled) add("通知权限未开启")
                    if (!k.batteryOptimized) add("未加入电池优化白名单")
                    if (!k.exactAlarmAllowed) add("精确闹钟权限未开启")
                    if (!k.vendorWhitelistConfirmed) add("未确认厂商后台白名单")
                }
            }

        /** 生效中的授权摘要，例如「加时 30 分钟 · 放行 微信」 */
        val grantSummary: String
            get() = grants.mapNotNull { grant ->
                when (grant.scope) {
                    GrantScope.TOTAL_ADD -> grant.extraMinutes?.let { "加时 $it 分钟" }
                    GrantScope.APP_ALLOW -> "放行 ${grant.appLabel ?: grant.packageName ?: "应用"}"
                    GrantScope.UNLOCK -> "临时解除限制"
                    else -> null
                }
            }.joinToString(" · ")

        private fun formatMinutes(ms: Long): String {
            val minutes = (ms / 60_000).coerceAtLeast(0)
            return if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分钟" else "$minutes 分钟"
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var healthJob: Job? = null

    init {
        viewModelScope.launch {
            holder.warmUp()
            _state.update {
                it.copy(
                    booting = false,
                    serverDisplay = holder.current.display,
                    health = PermissionChecker.snapshot(context),
                )
            }

            val session = settingsStore.childSession.first()
            _state.update { it.copy(session = session) }
            if (session != null) onPaired(session)

            observePolicy(session?.deviceId)
            observeGrants(session?.deviceId)
            observeFailedPinAttempts()
            startHealthWatch()
        }

        // 引擎状态直接映射到界面：前台应用、今日已用、剩余额度
        viewModelScope.launch {
            engine.state.collect { guard -> _state.update { it.copy(guard = guard) } }
        }

        // 加固状态不常变，初始化时探测一次即可（家长从系统设置返回时会经 refreshHealth 刷新）
        refreshHardening()
    }

    /* ---------------- 配对 ---------------- */

    fun pair(code: String) {
        val trimmed = code.trim()
        if (trimmed.length != 6 || !trimmed.all(Char::isDigit)) {
            _state.update { it.copy(error = "请输入控制端显示的 6 位配对码") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }

            val uuid = settingsStore.childUuid()
            val displayName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "儿童平板" }

            runCatching {
                api.claimPair(
                    PairClaimRequest(
                        code = trimmed,
                        childUuid = uuid,
                        name = displayName,
                        model = Build.MODEL,
                        androidVer = Build.VERSION.SDK_INT,
                    ),
                )
            }
                .onSuccess { response ->
                    val session = ChildSession(
                        token = response.token,
                        deviceId = response.deviceId,
                        deviceName = displayName,
                    )
                    settingsStore.saveChildSession(session)
                    _state.update { it.copy(busy = false, session = session, notice = "绑定成功") }
                    onPaired(session)
                    observePolicy(session.deviceId)
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, error = friendly(e)) }
                }
        }
    }

    fun unpair() {
        viewModelScope.launch {
            stopGuardService()
            settingsStore.clearChildSession()
            policyRepository.clear()
            deviceStateRepository.clear()
            _state.update {
                it.copy(session = null, policy = null, grants = emptyList(), notice = "已解除绑定")
            }
        }
    }

    private fun onPaired(session: ChildSession) {
        startGuardService()
        viewModelScope.launch {
            runCatching { engine.initialize(session.deviceId) }
                .onFailure { _state.update { current -> current.copy(error = "初始化管控引擎失败：${it.message}") } }
        }
    }

    /**
     * 服务器设置面板关闭时调用。
     * 切换服务器会作废设备令牌，必须重新读一次本地会话，否则界面仍显示"已受管控"，
     * 而后台服务已在用失效令牌反复重连。
     */
    fun reloadSession() {
        viewModelScope.launch {
            val session = settingsStore.childSession.first()
            _state.update { it.copy(serverDisplay = holder.current.display) }

            if (session == null) {
                stopGuardService()
                _state.update { it.copy(session = null, policy = null, guard = GuardState()) }
            } else if (session.token != _state.value.session?.token) {
                _state.update { it.copy(session = session) }
                onPaired(session)
                observePolicy(session.deviceId)
            }
        }
    }

    /* ---------------- 管控服务 ---------------- */

    private fun startGuardService() {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GuardForegroundService::class.java),
            )
        }.onFailure {
            _state.update { current -> current.copy(error = "启动管控服务失败：${it.message}") }
        }
    }

    private fun stopGuardService() {
        runCatching { context.stopService(Intent(context, GuardForegroundService::class.java)) }
    }

    /* ---------------- 状态观察 ---------------- */

    private fun observePolicy(deviceId: Long?) {
        val id = deviceId ?: return
        viewModelScope.launch {
            policyRepository.observe(id).collect { policy ->
                _state.update { it.copy(policy = policy) }
            }
        }
    }

    private fun observeGrants(deviceId: Long?) {
        val id = deviceId ?: return
        viewModelScope.launch {
            deviceStateRepository.observeGrants(id).collect { grants ->
                val now = System.currentTimeMillis()
                _state.update {
                    it.copy(
                        grants = grants.filter { grant ->
                            grant.expireAt == null || grant.expireAt > now
                        },
                    )
                }
            }
        }
    }

    /** 失败次数也显示在设备上 —— 让孩子知道"试密码会被家长看到"本身就是一种威慑 */
    private fun observeFailedPinAttempts() {
        viewModelScope.launch {
            usageRepository.observeFailedPinAttempts().collect { count ->
                _state.update { it.copy(failedPinAttempts = count) }
            }
        }
    }

    fun refreshHealth() {
        _state.update { it.copy(health = PermissionChecker.snapshot(context)) }
        refreshHardening()
    }

    /**
     * 刷新加固与保活状态。
     *
     * 这些都是**设备本地的既成事实**（是不是 Device Owner、有没有进电池白名单），
     * 服务端无法代劳判断，因此由设备自己探测后展示给家长核对。
     */
    fun refreshHardening() {
        _state.update {
            it.copy(
                adminMode = AdminModeManager.currentMode(context),
                uninstallBlocked = AdminModeManager.isUninstallBlocked(context),
                pinReady = false, // 由下面的协程补齐
            )
        }
        viewModelScope.launch {
            val pinReady = runCatching { pinStore.hasAny() }.getOrDefault(false)
            val vendorConfirmed = runCatching { keepaliveStore.vendorWhitelistConfirmed() }.getOrDefault(false)
            _state.update {
                it.copy(
                    pinReady = pinReady,
                    vendorWhitelistConfirmed = vendorConfirmed,
                    keepalive = KeepaliveManager.snapshot(
                        context = context,
                        vendorWhitelistConfirmed = vendorConfirmed,
                        foregroundServiceRunning = true,
                    ),
                )
            }
        }
    }

    /** 家长在设备上确认"已设置厂商白名单" —— 系统没有 API 可查，只能靠人确认 */
    fun confirmVendorWhitelist(confirmed: Boolean) {
        viewModelScope.launch {
            keepaliveStore.setVendorWhitelistConfirmed(confirmed)
            refreshHardening()
        }
    }

    /** 激活设备管理器（跳到系统激活页，家长需手动勾选） */
    fun activateDeviceAdmin() = AdminModeManager.openActivation(context)

    fun refreshUsage() {
        viewModelScope.launch { engine.refreshUsage() }
    }

    /** 本地轮询授权状态，家长从系统设置返回后界面立刻更新 */
    private fun startHealthWatch() {
        healthJob?.cancel()
        healthJob = viewModelScope.launch {
            while (isActive) {
                refreshHealth()
                delay(2_000)
            }
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, notice = null) }

    private fun friendly(error: Throwable): String = when (error) {
        is HttpException -> {
            val raw = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
            val parsed = raw?.let { runCatching { json.decodeFromString<ApiError>(it) }.getOrNull() }
            parsed?.message ?: "请求失败（HTTP ${error.code()}）"
        }

        else -> error.message ?: "网络异常，请检查服务器地址"
    }
}
