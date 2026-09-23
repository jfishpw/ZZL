package com.zzl.guardian.parent

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzl.guardian.data.AuthedImageLoader
import com.zzl.guardian.data.ParentSession
import com.zzl.guardian.data.SettingsStore
import com.zzl.guardian.data.WsClient
import com.zzl.guardian.data.WsEvent
import com.zzl.guardian.data.api.ApiError
import com.zzl.guardian.data.api.ApiService
import com.zzl.guardian.data.api.AppRuleDto
import com.zzl.guardian.data.api.AuditLogDto
import com.zzl.guardian.data.api.AuditSummaryDto
import com.zzl.guardian.data.api.BlockLogDto
import com.zzl.guardian.data.api.CommandDto
import com.zzl.guardian.data.api.DeviceView
import com.zzl.guardian.data.api.GrantCreateRequest
import com.zzl.guardian.data.api.GrantDto
import com.zzl.guardian.data.api.IconVisibilityRequest
import com.zzl.guardian.data.api.HardeningDto
import com.zzl.guardian.data.api.InstalledAppDto
import com.zzl.guardian.data.api.LoginRequest
import com.zzl.guardian.data.api.PairCodeResponse
import com.zzl.guardian.data.api.PinAttemptDto
import com.zzl.guardian.data.api.PinAttemptsResponse
import com.zzl.guardian.data.api.PinBackupDto
import com.zzl.guardian.data.api.PinLevelDto
import com.zzl.guardian.data.api.PinResetRequest
import com.zzl.guardian.data.api.PolicyBundleDto
import com.zzl.guardian.data.api.PolicyBundleUpdateRequest
import com.zzl.guardian.data.api.PolicyListItemDto
import com.zzl.guardian.data.api.PolicyUpdateRequest
import com.zzl.guardian.data.api.RegisterRequest
import com.zzl.guardian.data.api.ScreenshotDto
import com.zzl.guardian.data.api.SessionDetailDto
import com.zzl.guardian.data.api.TimeRequestDecideRequest
import com.zzl.guardian.data.api.TimeRequestDto
import com.zzl.guardian.data.api.UsageOverviewDto
import com.zzl.guardian.data.api.UsageRankingDto
import com.zzl.guardian.data.api.UsageTrendDto
import com.zzl.guardian.data.pin.PinCrypto
import com.zzl.guardian.di.ServerConfigHolder
import dagger.hilt.android.lifecycle.HiltViewModel
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
class ParentViewModel @Inject constructor(
    private val api: ApiService,
    private val settingsStore: SettingsStore,
    private val wsClient: WsClient,
    private val holder: ServerConfigHolder,
    private val json: Json,
    private val imageLoader: AuthedImageLoader,
) : ViewModel() {

    data class UiState(
        val booting: Boolean = true,
        val session: ParentSession? = null,
        val devices: List<DeviceView> = emptyList(),
        val pairCode: PairCodeResponse? = null,
        val wsConnected: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        val notice: String? = null,
        val serverDisplay: String = "",
        /** 当前正在编辑基础策略的设备；null 表示未打开编辑面板 */
        val editingDeviceId: Long? = null,
        val policy: PolicyBundleDto? = null,
        val policyBusy: Boolean = false,
        /** 当前正在编辑应用管控的设备 */
        val appControlDeviceId: Long? = null,
        val bundle: PolicyBundleDto? = null,
        val installedApps: List<InstalledAppDto> = emptyList(),
        val appControlBusy: Boolean = false,

        /* ---------------- 限制工具（M4） ---------------- */
        /** 当前打开「限制工具」的设备：临时锁定 + 临时授权 */
        val toolsDeviceId: Long? = null,
        val grants: List<GrantDto> = emptyList(),
        val commands: List<CommandDto> = emptyList(),
        val toolsBusy: Boolean = false,

        /* ---------------- 使用报告（M4） ---------------- */
        val reportDeviceId: Long? = null,
        val reportDays: Int = 7,
        val overview: UsageOverviewDto? = null,
        val trend: UsageTrendDto? = null,
        val ranking: UsageRankingDto? = null,
        val sessions: List<SessionDetailDto> = emptyList(),
        val blocks: List<BlockLogDto> = emptyList(),
        val reportBusy: Boolean = false,

        /* ---------------- 加时申请（M4） ---------------- */
        val showTimeRequests: Boolean = false,
        val timeRequests: List<TimeRequestDto> = emptyList(),
        val decidingId: Long? = null,

        /* ---------------- 设备加固与离线密码（M5） ---------------- */
        /** 当前打开「设备加固」面板的设备 */
        val hardeningDeviceId: Long? = null,
        val hardening: HardeningDto? = null,
        val pins: PinBackupDto? = null,
        val pinAttempts: List<PinAttemptDto> = emptyList(),
        val pinFailedTotal: Int = 0,
        val pinSuspicious: Boolean = false,
        val hardeningBusy: Boolean = false,

        /* ---------------- 截屏与审计（M6） ---------------- */
        /** 当前打开「截屏」面板的设备 */
        val screenshotDeviceId: Long? = null,
        val screenshots: List<ScreenshotDto> = emptyList(),
        val screenshotsBusy: Boolean = false,
        /** 刚发出的截屏请求；用于在等待回图时显示"正在截取…" */
        val capturePending: Boolean = false,
        val captureNotice: String? = null,

        /** 当前打开「操作记录」面板的设备；null 表示全部设备 */
        val auditDeviceId: Long? = null,
        val showAudit: Boolean = false,
        val auditLogs: List<AuditLogDto> = emptyList(),
        val auditSummary: AuditSummaryDto? = null,
        /** 只看需要注意的（warn 级） */
        val auditWarnOnly: Boolean = false,
        val auditBusy: Boolean = false,

        /* ---------------- 删除设备 ---------------- */
        /** 待确认删除的设备；非空表示确认框打开中 */
        val deletingDeviceId: Long? = null,
        val deleteBusy: Boolean = false,
    ) {
        val editingDevice: DeviceView?
            get() = devices.firstOrNull { it.id == editingDeviceId }

        val appControlDevice: DeviceView?
            get() = devices.firstOrNull { it.id == appControlDeviceId }

        val toolsDevice: DeviceView?
            get() = devices.firstOrNull { it.id == toolsDeviceId }

        val reportDevice: DeviceView?
            get() = devices.firstOrNull { it.id == reportDeviceId }

        val hardeningDevice: DeviceView?
            get() = devices.firstOrNull { it.id == hardeningDeviceId }

        val screenshotDevice: DeviceView?
            get() = devices.firstOrNull { it.id == screenshotDeviceId }

        val auditDevice: DeviceView?
            get() = devices.firstOrNull { it.id == auditDeviceId }

        val deletingDevice: DeviceView?
            get() = devices.firstOrNull { it.id == deletingDeviceId }

        /** 生效中的授权（仅用于展示，真正生效与否由服务端的 active 标记决定） */
        val activeGrants: List<GrantDto>
            get() = grants.filter { it.active }

        /** 待审批数量。设备列表上的红点与入口角标都用它 */
        val pendingRequestCount: Int
            get() = timeRequests.count { it.decidable }

        /** 有没有设备的管控强度需要提醒（未激活设备管理器，或没设离线密码） */
        val weakDevices: List<DeviceView>
            get() = devices.filter {
                it.effectiveAdminMode == "none" || !it.pinReady
            }

        /**
         * 需要家长注意的事（近 7 天）。
         *
         * 只统计 warn 级 —— 截屏、登录这类操作是"记录"不是"告警"，
         * 混在一起会让家长每天看到一堆红点，真正该注意的反而被淹没。
         */
        val warnCount: Int
            get() = auditSummary?.warnCount ?: 0
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var wsJob: Job? = null
    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            holder.warmUp()
            _state.update { it.copy(serverDisplay = holder.current.display) }

            val session = settingsStore.parentSession.first()
            _state.update { it.copy(booting = false, session = session) }

            if (session != null) {
                onLoggedIn(session)
            }
        }
    }

    /* ---------------- 账号 ---------------- */

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _state.update { it.copy(error = "请填写用户名和密码") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            runCatching { api.login(LoginRequest(username.trim(), password)) }
                .onSuccess { response ->
                    val session = ParentSession(response.token, response.userId, response.username)
                    settingsStore.saveParentSession(session)
                    _state.update { it.copy(busy = false, session = session) }
                    onLoggedIn(session)
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, error = friendly(e)) }
                }
        }
    }

    fun register(username: String, password: String) {
        if (username.trim().length < 3) {
            _state.update { it.copy(error = "用户名至少 3 个字符") }
            return
        }
        if (password.length < 6) {
            _state.update { it.copy(error = "密码至少 6 位") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            runCatching { api.register(RegisterRequest(username.trim(), password)) }
                .onSuccess {
                    _state.update { it.copy(busy = false, notice = "注册成功，正在登录…") }
                    login(username, password)
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, error = friendly(e)) }
                }
        }
    }

    fun logout() {
        viewModelScope.launch {
            wsJob?.cancel()
            pollJob?.cancel()
            settingsStore.clearParentSession()
            _state.update {
                it.copy(session = null, devices = emptyList(), pairCode = null, wsConnected = false)
            }
        }
    }

    /**
     * 服务器设置面板关闭时调用。
     * 切换服务器会作废全部凭据，这里重新读一次本地会话；已经失效就退回登录页，
     * 否则界面会停留在设备列表，而后续每个请求都返回 401。
     */
    fun reloadSession() {
        viewModelScope.launch {
            val session = settingsStore.parentSession.first()
            _state.update { it.copy(serverDisplay = holder.current.display) }

            when {
                session == null -> {
                    wsJob?.cancel()
                    pollJob?.cancel()
                    _state.update {
                        it.copy(
                            session = null,
                            devices = emptyList(),
                            pairCode = null,
                            wsConnected = false,
                        )
                    }
                }

                session.token != _state.value.session?.token -> {
                    _state.update { it.copy(session = session) }
                    onLoggedIn(session)
                }
            }
        }
    }

    /* ---------------- 设备 ---------------- */

    fun refreshDevices() {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            runCatching { api.devices(bearer(session.token)) }
                .onSuccess { response ->
                    _state.update { it.copy(devices = response.devices, error = null) }
                }
                .onFailure { e ->
                    _state.update { it.copy(error = friendly(e)) }
                }
        }
    }

    fun requestPairCode() {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { api.createPairCode(bearer(session.token)) }
                .onSuccess { code ->
                    _state.update { it.copy(busy = false, pairCode = code) }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, error = friendly(e)) }
                }
        }
    }

    fun dismissPairCode() = _state.update { it.copy(pairCode = null) }

    fun renameDevice(deviceId: Long, name: String) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            runCatching { api.renameDevice(bearer(session.token), deviceId, mapOf("name" to name)) }
                .onSuccess { refreshDevices() }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    /* ---------------- 删除设备 ---------------- */

    /** 点「删除」只先记录待删设备，由确认框的「删除」按钮二次触发真正删除 */
    fun requestDeleteDevice(deviceId: Long) {
        _state.update { it.copy(deletingDeviceId = deviceId) }
    }

    fun dismissDeleteDevice() {
        _state.update { it.copy(deletingDeviceId = null) }
    }

    fun confirmDeleteDevice() {
        val session = _state.value.session ?: return
        val deviceId = _state.value.deletingDeviceId ?: return
        val device = _state.value.devices.firstOrNull { it.id == deviceId } ?: return
        viewModelScope.launch {
            _state.update { it.copy(deleteBusy = true, error = null) }
            runCatching { api.deleteDevice(bearer(session.token), deviceId) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            deleteBusy = false,
                            deletingDeviceId = null,
                            devices = it.devices.filterNot { d -> d.id == deviceId },
                            notice = "已删除「${device.name}」。该设备重新配对时会作为新设备加入",
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(deleteBusy = false, error = friendly(e)) }
                }
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, notice = null) }

    /* ---------------- 策略编辑 ---------------- */

    /** 两个编辑面板互斥：同时打开会叠出两个 Dialog，且保存后状态会互相覆盖 */
    fun openPolicyEditor(deviceId: Long) {
        _state.update {
            it.copy(
                editingDeviceId = deviceId,
                policy = null,
                appControlDeviceId = null,
                bundle = null,
                installedApps = emptyList(),
                toolsDeviceId = null,
                grants = emptyList(),
                commands = emptyList(),
                reportDeviceId = null,
            )
        }
        loadPolicy(deviceId)
    }

    fun closePolicyEditor() = _state.update { it.copy(editingDeviceId = null, policy = null) }

    fun loadPolicy(deviceId: Long) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(policyBusy = true) }
            runCatching { api.devicePolicy(bearer(session.token), deviceId) }
                .onSuccess { policy -> _state.update { it.copy(policyBusy = false, policy = policy) } }
                .onFailure { e -> _state.update { it.copy(policyBusy = false, error = friendly(e)) } }
        }
    }

    fun savePolicy(
        deviceId: Long,
        weekdayTotalMin: Int,
        weekendTotalMin: Int,
        resetHour: Int,
        enabled: Boolean,
        allowTimeRequest: Boolean? = null,
    ) {
        val session = _state.value.session ?: return
        if (weekdayTotalMin !in 0..1440 || weekendTotalMin !in 0..1440) {
            _state.update { it.copy(error = "每日总时长需在 0 - 1440 分钟之间") }
            return
        }
        if (resetHour !in 0..23) {
            _state.update { it.copy(error = "额度重置时间需在 0 - 23 点之间") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(policyBusy = true, error = null) }
            runCatching {
                api.updatePolicy(
                    authorization = bearer(session.token),
                    deviceId = deviceId,
                    body = PolicyUpdateRequest(
                        weekdayTotalMin = weekdayTotalMin,
                        weekendTotalMin = weekendTotalMin,
                        resetHour = resetHour,
                        enabled = enabled,
                        allowTimeRequest = allowTimeRequest,
                    ),
                )
            }
                .onSuccess { policy ->
                    _state.update {
                        it.copy(
                            policyBusy = false,
                            policy = policy,
                            notice = if (policy.delivered == false) {
                                "已保存。设备当前离线，将在其上线后自动生效"
                            } else {
                                "已保存并下发到设备"
                            },
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(policyBusy = false, error = friendly(e)) } }
        }
    }

    /* ---------------- 应用管控 ---------------- */

    fun openAppControl(deviceId: Long) {
        _state.update {
            it.copy(
                appControlDeviceId = deviceId,
                bundle = null,
                installedApps = emptyList(),
                // 与其余面板互斥
                editingDeviceId = null,
                policy = null,
                toolsDeviceId = null,
                grants = emptyList(),
                commands = emptyList(),
                reportDeviceId = null,
            )
        }
        loadAppControl(deviceId)
    }

    fun closeAppControl() = _state.update {
        it.copy(appControlDeviceId = null, bundle = null, installedApps = emptyList())
    }

    fun loadAppControl(deviceId: Long) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(appControlBusy = true) }
            runCatching {
                // 策略包与应用清单一起取，避免界面出现「有规则但没应用列表」的中间态
                api.policyBundle(bearer(session.token), deviceId) to
                    api.installedApps(bearer(session.token), deviceId)
            }
                .onSuccess { (bundle, apps) ->
                    _state.update {
                        it.copy(appControlBusy = false, bundle = bundle, installedApps = apps.apps)
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(appControlBusy = false, error = friendly(e)) }
                }
        }
    }

    /** 请求设备重新上报已安装应用（换机装了新应用后用） */
    fun requestInstalledAppsRefresh(deviceId: Long) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            runCatching { api.refreshInstalledApps(bearer(session.token), deviceId) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            notice = when {
                                !result.ok -> "请求失败"
                                result.delivered == false -> "设备当前离线，将在其上线后自动上报"
                                else -> "已请求设备上报，稍后刷新即可看到"
                            },
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    /**
     * 名单与逐应用规则一起保存。
     *
     * 走服务端的原子接口，一次请求完成替换 —— 分两次调用会在服务端留下
     * "名单已换、规则没换"的半套配置，而这段时间被控端是照着它执行管控的。
     */
    fun saveAppControl(
        deviceId: Long,
        listMode: String,
        listedPackages: Set<String>,
        rules: List<AppRuleDto>,
    ) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(appControlBusy = true, error = null, notice = null) }

            val labelByPackage = _state.value.installedApps.associate { it.packageName to it.appLabel }

            runCatching {
                api.updatePolicyBundle(
                    authorization = bearer(session.token),
                    deviceId = deviceId,
                    body = PolicyBundleUpdateRequest(
                        listMode = listMode,
                        items = listedPackages.map { PolicyListItemDto(it, labelByPackage[it]) },
                        rules = rules,
                    ),
                )
            }
                .onSuccess { bundle ->
                    _state.update {
                        it.copy(
                            appControlBusy = false,
                            bundle = bundle,
                            notice = if (bundle.delivered == false) {
                                "已保存。设备当前离线，将在其上线后自动生效"
                            } else {
                                "已保存并下发到设备"
                            },
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(appControlBusy = false, error = friendly(e)) }
                }
        }
    }

    /* ---------------- 限制工具：临时锁定与授权（M4） ---------------- */

    fun openTools(deviceId: Long) {
        _state.update {
            it.copy(
                toolsDeviceId = deviceId,
                grants = emptyList(),
                commands = emptyList(),
                // 所有面板互斥：同时打开会叠出多个 Dialog，保存后状态互相覆盖
                editingDeviceId = null,
                policy = null,
                appControlDeviceId = null,
                bundle = null,
                installedApps = emptyList(),
                reportDeviceId = null,
                screenshotDeviceId = null,
                screenshots = emptyList(),
                auditDeviceId = null,
                showAudit = false,
            )
        }
        loadTools(deviceId)
    }

    fun closeTools() = _state.update { it.copy(toolsDeviceId = null, grants = emptyList(), commands = emptyList()) }

    fun loadTools(deviceId: Long) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(toolsBusy = true) }
            runCatching {
                ToolsData(
                    grants = api.deviceGrants(bearer(session.token), deviceId).grants,
                    commands = api.deviceCommands(bearer(session.token), deviceId, 20).commands,
                    // 「单独放行某个应用」需要应用清单，而这份清单原本只在应用管控面板中加载。
                    // 不在这里一并取回的话，工具面板里的应用选择器永远是空的。
                    installedApps = api.installedApps(bearer(session.token), deviceId).apps,
                )
            }
                .onSuccess { data ->
                    _state.update {
                        it.copy(
                            toolsBusy = false,
                            grants = data.grants,
                            commands = data.commands,
                            installedApps = data.installedApps,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(toolsBusy = false, error = friendly(e)) } }
        }
    }

    private data class ToolsData(
        val grants: List<GrantDto>,
        val commands: List<CommandDto>,
        val installedApps: List<InstalledAppDto>,
    )

    fun lockNow(deviceId: Long) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(toolsBusy = true, error = null, notice = null) }
            runCatching { api.lockNow(bearer(session.token), deviceId) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            toolsBusy = false,
                            notice = if (result.delivered == false) {
                                "设备当前离线，锁定将在其上线后立即生效"
                            } else {
                                "已下发锁定"
                            },
                        )
                    }
                    refreshDevices()
                    loadTools(deviceId)
                }
                .onFailure { e -> _state.update { it.copy(toolsBusy = false, error = friendly(e)) } }
        }
    }

    fun unlock(deviceId: Long) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(toolsBusy = true, error = null, notice = null) }
            runCatching { api.unlock(bearer(session.token), deviceId) }
                .onSuccess {
                    _state.update { it.copy(toolsBusy = false, notice = "已解除锁定") }
                    refreshDevices()
                    loadTools(deviceId)
                }
                .onFailure { e -> _state.update { it.copy(toolsBusy = false, error = friendly(e)) } }
        }
    }

    /**
     * 创建临时授权。
     * 三种范围的含义差异很大，界面上必须写清楚，否则家长会误以为"放行"是无限额度。
     */
    fun createGrant(
        deviceId: Long,
        scope: String,
        packageName: String? = null,
        appLabel: String? = null,
        extraMinutes: Int? = null,
        ttlMinutes: Int? = null,
    ) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(toolsBusy = true, error = null, notice = null) }
            runCatching {
                api.createGrant(
                    authorization = bearer(session.token),
                    deviceId = deviceId,
                    body = GrantCreateRequest(
                        scope = scope,
                        packageName = packageName,
                        appLabel = appLabel,
                        extraMinutes = extraMinutes,
                        ttlMinutes = ttlMinutes,
                    ),
                )
            }
                .onSuccess { result ->
                    val notice = result.notice ?: when (scope) {
                        "total_add" -> "已加时 ${extraMinutes ?: 0} 分钟"
                        "app_allow" -> "已放行「${appLabel ?: packageName}」"
                        else -> "已临时解除限制"
                    }
                    _state.update { it.copy(toolsBusy = false, notice = notice) }
                    loadTools(deviceId)
                }
                .onFailure { e -> _state.update { it.copy(toolsBusy = false, error = friendly(e)) } }
        }
    }

    fun revokeGrant(grantId: Long, deviceId: Long) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(toolsBusy = true, error = null, notice = null) }
            runCatching { api.revokeGrant(bearer(session.token), grantId) }
                .onSuccess {
                    _state.update { it.copy(toolsBusy = false, notice = "已撤销该授权") }
                    loadTools(deviceId)
                }
                .onFailure { e -> _state.update { it.copy(toolsBusy = false, error = friendly(e)) } }
        }
    }

    /**
     * 隐藏 / 恢复被控端桌面图标。
     *
     * 服务端会同时更新持久状态（重启、离线都不失效）并下发即时指令。
     * 隐藏后孩子看不到入口，家长自己则从这里随时恢复；
     * 被控端已开启密码门禁时，从应用列表打开也要先输密码。
     */
    fun setDeviceIcon(deviceId: Long, hidden: Boolean) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(toolsBusy = true, error = null, notice = null) }
            runCatching {
                api.setIconVisibility(
                    authorization = bearer(session.token),
                    deviceId = deviceId,
                    body = IconVisibilityRequest(hidden),
                )
            }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            toolsBusy = false,
                            notice = result.notice
                                ?: if (hidden) "已隐藏桌面图标" else "已恢复桌面图标",
                        )
                    }
                    refreshDevices()
                }
                .onFailure { e -> _state.update { it.copy(toolsBusy = false, error = friendly(e)) } }
        }
    }

    /* ---------------- 使用报告（M4） ---------------- */

    fun openReport(deviceId: Long) {
        _state.update {
            it.copy(
                reportDeviceId = deviceId,
                overview = null,
                trend = null,
                ranking = null,
                sessions = emptyList(),
                blocks = emptyList(),
                // 与编辑类面板互斥
                editingDeviceId = null,
                policy = null,
                appControlDeviceId = null,
                bundle = null,
                installedApps = emptyList(),
                toolsDeviceId = null,
                grants = emptyList(),
                commands = emptyList(),
            )
        }
        loadReport(deviceId, _state.value.reportDays)
    }

    fun closeReport() = _state.update {
        it.copy(reportDeviceId = null, overview = null, trend = null, ranking = null, sessions = emptyList(), blocks = emptyList())
    }

    fun setReportDays(days: Int) {
        val deviceId = _state.value.reportDeviceId ?: return
        _state.update { it.copy(reportDays = days) }
        loadReport(deviceId, days)
    }

    /**
     * 报告一次性取全。
     * 分多次请求会出现「今天的数据已经更新、趋势还是旧的」这种自相矛盾的界面，
     * 家长看到的数字必须能互相对上。
     */
    fun loadReport(deviceId: Long, days: Int = _state.value.reportDays) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(reportBusy = true) }
            runCatching {
                ReportData(
                    overview = api.usageOverview(bearer(session.token), deviceId, null),
                    trend = api.usageTrend(bearer(session.token), deviceId, days),
                    ranking = api.usageRanking(bearer(session.token), deviceId, days),
                    sessions = api.usageSessions(bearer(session.token), deviceId, null, 100).sessions,
                    blocks = api.blockLogs(bearer(session.token), deviceId, 50).blocks,
                )
            }
                .onSuccess { data ->
                    _state.update {
                        it.copy(
                            reportBusy = false,
                            overview = data.overview,
                            trend = data.trend,
                            ranking = data.ranking,
                            sessions = data.sessions,
                            blocks = data.blocks,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(reportBusy = false, error = friendly(e)) } }
        }
    }

    private data class ReportData(
        val overview: UsageOverviewDto,
        val trend: UsageTrendDto,
        val ranking: UsageRankingDto,
        val sessions: List<SessionDetailDto>,
        val blocks: List<BlockLogDto>,
    )

    /* ---------------- 加时申请审批（M4） ---------------- */

    fun openTimeRequests() {
        _state.update { it.copy(showTimeRequests = true) }
        loadTimeRequests()
    }

    fun closeTimeRequests() = _state.update { it.copy(showTimeRequests = false) }

    fun loadTimeRequests(status: String? = null) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            runCatching { api.timeRequests(bearer(session.token), status, null, 50) }
                .onSuccess { response ->
                    _state.update { it.copy(timeRequests = response.requests) }
                }
                .onFailure { e -> _state.update { it.copy(error = friendly(e)) } }
        }
    }

    /**
     * 审批。decidedMin 支持「打折批准」——
     * 申请 60 分钟只批 20 分钟，否则家长面对"要么全给要么全不给"只能选拒绝。
     */
    fun decideTimeRequest(requestId: Long, approve: Boolean, decidedMin: Int? = null) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(decidingId = requestId, error = null, notice = null) }
            runCatching {
                api.decideTimeRequest(
                    authorization = bearer(session.token),
                    requestId = requestId,
                    body = TimeRequestDecideRequest(approve = approve, decidedMin = decidedMin),
                )
            }
                .onSuccess { response ->
                    val request = response.request
                    _state.update {
                        it.copy(
                            decidingId = null,
                            notice = if (approve) {
                                "已批准 ${request?.decidedMin ?: decidedMin ?: 0} 分钟" +
                                    if (response.delivered == false) "（设备离线，上线后生效）" else ""
                            } else {
                                "已拒绝该申请"
                            },
                        )
                    }
                    loadTimeRequests()
                }
                .onFailure { e -> _state.update { it.copy(decidingId = null, error = friendly(e)) } }
        }
    }

    /* ---------------- 设备加固与离线密码（M5） ---------------- */

    fun openHardening(deviceId: Long) {
        _state.update {
            it.copy(
                hardeningDeviceId = deviceId,
                hardening = null,
                pins = null,
                pinAttempts = emptyList(),
                // 与其余面板互斥
                editingDeviceId = null,
                policy = null,
                appControlDeviceId = null,
                bundle = null,
                installedApps = emptyList(),
                toolsDeviceId = null,
                grants = emptyList(),
                commands = emptyList(),
                reportDeviceId = null,
            )
        }
        loadHardening(deviceId)
    }

    /* ---------------- 截屏（M6） ---------------- */

    /**
     * 打开截屏面板。
     *
     * 截屏是全系统最敏感的数据，因此这个面板**只做两件事**：
     * 请求一张、看已有的。不做"定时自动截屏"——那会产生大量家长根本不看的
     * 隐私图片，而且流量与存储消耗都不小。
     */
    fun openScreenshots(deviceId: Long) {
        _state.update {
            it.copy(
                screenshotDeviceId = deviceId,
                screenshots = emptyList(),
                captureNotice = null,
                capturePending = false,
                // 与其余面板互斥
                editingDeviceId = null,
                policy = null,
                appControlDeviceId = null,
                bundle = null,
                installedApps = emptyList(),
                toolsDeviceId = null,
                grants = emptyList(),
                commands = emptyList(),
                reportDeviceId = null,
                hardeningDeviceId = null,
                hardening = null,
                pins = null,
                pinAttempts = emptyList(),
                auditDeviceId = null,
                showAudit = false,
            )
        }
        loadScreenshots(deviceId)
    }

    fun closeScreenshots() = _state.update {
        it.copy(
            screenshotDeviceId = null,
            screenshots = emptyList(),
            captureNotice = null,
            capturePending = false,
        )
    }

    fun loadScreenshots(deviceId: Long) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(screenshotsBusy = true) }
            runCatching { api.screenshots(bearer(session.token), deviceId, 30) }
                .onSuccess { res ->
                    _state.update { it.copy(screenshotsBusy = false, screenshots = res.screenshots) }
                }
                .onFailure { e ->
                    _state.update { it.copy(screenshotsBusy = false, error = friendly(e)) }
                }
        }
    }

    /**
     * 请求设备立即截一张。
     *
     * 这里**不假装是同步操作**：指令要先送到设备、设备再采集、再上传，
     * 整个过程几秒钟。因此立刻给出"正在截取…"的提示，
     * 真正的图通过 WebSocket 的 `screenshot_ready` 事件到达后再刷新列表。
     *
     * 设备离线时如实告诉家长"将在其上线后 60 秒内有效" ——
     * 截屏指令的 TTL 只有 60 秒，太旧的截图没有意义。
     */
    fun requestScreenshot(deviceId: Long) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(screenshotsBusy = true, captureNotice = null) }
            runCatching { api.requestScreenshot(bearer(session.token), deviceId) }
                .onSuccess { res ->
                    _state.update {
                        it.copy(
                            screenshotsBusy = false,
                            capturePending = res.delivered == true,
                            captureNotice = res.notice ?: if (res.delivered == true) {
                                "正在截取，通常几秒内返回"
                            } else {
                                "设备当前离线，指令将在其上线后生效"
                            },
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(screenshotsBusy = false, error = friendly(e)) }
                }
        }
    }

    fun deleteScreenshot(deviceId: Long, screenshotId: Long) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(screenshotsBusy = true) }
            runCatching { api.deleteScreenshot(bearer(session.token), deviceId, screenshotId) }
                .onSuccess {
                    // 同时清掉内存缓存，否则重新打开这个列表还能看到已删除的图
                    imageLoader.invalidate(screenshotImageUrl(deviceId, screenshotId))
                    _state.update {
                        it.copy(
                            screenshotsBusy = false,
                            screenshots = it.screenshots.filterNot { s -> s.id == screenshotId },
                            notice = "已删除该截屏",
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(screenshotsBusy = false, error = friendly(e)) }
                }
        }
    }

    /**
     * 取图片的完整 URL。
     *
     * 拼 URL 而不是让 Retrofit 下载：图片要交给 Compose 的图片加载器，
     * 它需要的是一个可寻址的 URL。
     *
     * 鉴权走 URL 里的 token 是不行的（会进日志），因此这里返回的是
     * Retrofit 会自己带上 Authorization 头的相对路径 ——
     * 具体拼接由界面层的图片请求器完成。
     */
    fun screenshotImageUrl(deviceId: Long, screenshotId: Long): String =
        "api/devices/$deviceId/screenshots/$screenshotId/image"

    /**
     * 加载图片字节并解码。
     *
     * 放在 ViewModel 而不是界面层，是为了让"令牌从哪里来"只有一处：
     * 界面只需要给出相对路径并拿到 Bitmap，不必知道要带什么鉴权头。
     *
     * 走 [AuthedImageLoader] 的内存缓存，重复查看同一张不重复下载。
     */
    suspend fun loadImageByPath(path: String): Bitmap? {
        val session = _state.value.session ?: return null
        val bytes = imageLoader.loadBytes(path, bearer(session.token)) ?: return null
        return imageLoader.decode(bytes)
    }

    /* ---------------- 审计日志（M6） ---------------- */

    /**
     * 打开操作记录面板。
     *
     * @param deviceId null 表示看全部设备
     */
    fun openAudit(deviceId: Long?) {
        _state.update {
            it.copy(
                auditDeviceId = deviceId,
                showAudit = true,
                auditLogs = emptyList(),
                auditWarnOnly = false,
                // 与其余面板互斥
                editingDeviceId = null,
                policy = null,
                appControlDeviceId = null,
                bundle = null,
                installedApps = emptyList(),
                toolsDeviceId = null,
                grants = emptyList(),
                commands = emptyList(),
                reportDeviceId = null,
                hardeningDeviceId = null,
                hardening = null,
                pins = null,
                pinAttempts = emptyList(),
                screenshotDeviceId = null,
                screenshots = emptyList(),
            )
        }
        loadAudit()
    }

    fun closeAudit() = _state.update {
        it.copy(showAudit = false, auditDeviceId = null, auditLogs = emptyList(), auditSummary = null)
    }

    /** 切换"只看需要注意的" */
    fun toggleAuditWarnOnly() {
        _state.update { it.copy(auditWarnOnly = !it.auditWarnOnly) }
        loadAudit()
    }

    fun loadAudit() {
        val session = _state.value.session ?: return
        val current = _state.value
        viewModelScope.launch {
            _state.update { it.copy(auditBusy = true) }
            val level = if (current.auditWarnOnly) "warn" else null

            runCatching {
                if (current.auditDeviceId != null) {
                    api.deviceAudit(bearer(session.token), current.auditDeviceId, 100, level)
                } else {
                    api.audit(bearer(session.token), null, 100, level)
                }
            }
                .onSuccess { res ->
                    _state.update {
                        it.copy(auditBusy = false, auditLogs = res.logs, auditSummary = res.summary)
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(auditBusy = false, error = friendly(e)) }
                }
        }
    }

    fun closeHardening() = _state.update {
        it.copy(
            hardeningDeviceId = null,
            hardening = null,
            pins = null,
            pinAttempts = emptyList(),
        )
    }

    fun loadHardening(deviceId: Long) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(hardeningBusy = true) }
            runCatching {
                HardeningData(
                    hardening = api.hardening(bearer(session.token), deviceId),
                    pins = api.devicePins(bearer(session.token), deviceId),
                    attempts = api.pinAttempts(bearer(session.token), deviceId, 50),
                )
            }
                .onSuccess { data ->
                    _state.update {
                        it.copy(
                            hardeningBusy = false,
                            hardening = data.hardening,
                            pins = data.pins,
                            pinAttempts = data.attempts.attempts,
                            pinFailedTotal = data.attempts.failedTotal,
                            pinSuspicious = data.attempts.suspicious,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(hardeningBusy = false, error = friendly(e)) } }
        }
    }

    private data class HardeningData(
        val hardening: HardeningDto,
        val pins: PinBackupDto,
        val attempts: PinAttemptsResponse,
    )

    /**
     * 远程清除某一级离线密码。
     *
     * 只在"孩子可能已经知道密码"或"家长自己忘了"这两种情况下用 ——
     * 清除后该级的能力就没了，家长需要在设备上重新设置。
     * 因此界面上必须给出明确的后果说明，而不是一个孤零零的"清除"按钮。
     */
    fun clearPin(deviceId: Long, level: Int) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(hardeningBusy = true, error = null, notice = null) }
            runCatching {
                api.resetPin(
                    authorization = bearer(session.token),
                    deviceId = deviceId,
                    body = PinResetRequest(level = level, clear = true),
                )
            }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            hardeningBusy = false,
                            pins = result.pins,
                            notice = result.notice ?: "已清除第 $level 级密码",
                        )
                    }
                    loadHardening(deviceId)
                }
                .onFailure { e -> _state.update { it.copy(hardeningBusy = false, error = friendly(e)) } }
        }
    }

    /**
     * 远程设定某一级密码。
     *
     * 哈希在这里算好再上传 —— 服务端从不接触明文。
     * 控制端与被控端用同一套 PBKDF2 参数，因此设备端能验证通过。
     */
    fun setPin(deviceId: Long, level: Int, pin: String, hint: String?) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            _state.update { it.copy(hardeningBusy = true, error = null, notice = null) }

            val salt = PinCrypto.newSalt()
            val hash = PinCrypto.hash(pin, salt)

            runCatching {
                api.resetPin(
                    authorization = bearer(session.token),
                    deviceId = deviceId,
                    body = PinResetRequest(
                        level = level,
                        entry = PinLevelDto(
                            level = level,
                            hash = hash,
                            salt = salt,
                            iterations = PinCrypto.DEFAULT_ITERATIONS,
                            hint = hint,
                        ),
                    ),
                )
            }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            hardeningBusy = false,
                            pins = result.pins,
                            notice = result.notice ?: "已设置第 $level 级密码，请告知家长新密码",
                        )
                    }
                    loadHardening(deviceId)
                }
                .onFailure { e -> _state.update { it.copy(hardeningBusy = false, error = friendly(e)) } }
        }
    }

    /* ---------------- 登录后的实时通道 ---------------- */

    private fun onLoggedIn(session: ParentSession) {
        refreshDevices()
        subscribeRealtime(session)
        startPolling()
    }

    private fun subscribeRealtime(session: ParentSession) {
        wsJob?.cancel()
        wsJob = viewModelScope.launch {
            while (isActive) {
                wsClient.events(session.token).collect { event ->
                    when (event) {
                        is WsEvent.Connected -> _state.update { it.copy(wsConnected = true) }

                        is WsEvent.Disconnected,
                        is WsEvent.Failure,
                        -> _state.update { it.copy(wsConnected = false) }

                        is WsEvent.DeviceStatus -> _state.update { current ->
                            current.copy(
                                devices = current.devices.map { device ->
                                    if (device.id == event.deviceId) {
                                        device.copy(
                                            online = event.online,
                                            foregroundPackage = event.foregroundPackage ?: device.foregroundPackage,
                                            lastSeen = System.currentTimeMillis(),
                                        )
                                    } else {
                                        device
                                    }
                                },
                            )
                        }

                        is WsEvent.PermissionLost -> _state.update { current ->
                            current.copy(
                                notice = "设备「${current.devices.firstOrNull { it.id == event.deviceId }?.name ?: event.deviceId}」" +
                                    "的管控权限已被取消：${event.missing.joinToString("、")}",
                            )
                        }

                        // 服务端已删除某设备（家长自己删的，或另一台控制端删的）。
                        // 自己删的那次 confirmDeleteDevice 已刷新过；这里是兜底，
                        // 保证另一端删掉时本机不会残留一张永远"离线"的卡片。
                        is WsEvent.DeviceRemoved -> refreshDevices()

                        is WsEvent.Other -> if (event.type == "installed_apps_updated") {
                            // 设备上报了新清单，若正在编辑应用管控则立即刷新
                            _state.value.appControlDeviceId?.let { loadAppControl(it) }
                        }

                        // 孩子提交了加时申请：立刻提示，别让家长错过
                        is WsEvent.TimeRequestCreated -> _state.update { current ->
                            val name = event.request.deviceName
                                ?: current.devices.firstOrNull { it.id == event.request.deviceId }?.name
                                ?: "设备"
                            current.copy(
                                timeRequests = listOf(event.request) +
                                    current.timeRequests.filterNot { it.id == event.request.id },
                                notice = "「$name」申请加时 ${event.request.requestMin} 分钟" +
                                    (event.request.reason?.let { "：$it" } ?: ""),
                            )
                        }

                        // 超过审批时限：把按钮的可用状态改掉，避免家长点了才报错
                        is WsEvent.TimeRequestExpired -> _state.update { current ->
                            current.copy(
                                timeRequests = current.timeRequests.map { request ->
                                    if (request.id == event.requestId) {
                                        request.copy(status = "expired", decidable = false)
                                    } else {
                                        request
                                    }
                                },
                            )
                        }

                        // 指令回执：若正开着限制工具面板就刷新执行状态
                        is WsEvent.CommandResult -> _state.update { current ->
                            val updated = current.commands.map { command ->
                                if (command.commandId == event.command.commandId) event.command else command
                            }
                            if (current.toolsDeviceId == event.command.deviceId) {
                                current.copy(commands = updated)
                            } else {
                                current
                            }
                        }

                        /**
                         * 设备上传了一张截屏。
                         *
                         * 只插入**当前正打开的那台设备**的列表 —— 否则家长在查看
                         * 甲设备时，乙设备的截图会突然混进来。
                         */
                        is WsEvent.ScreenshotReady -> _state.update { current ->
                            if (current.screenshotDeviceId != event.deviceId) {
                                current
                            } else {
                                current.copy(
                                    capturePending = false,
                                    captureNotice = "已收到新截屏",
                                    // 去重：同一条可能因为重连被推到两次
                                    screenshots = (
                                        listOf(event.screenshot) + current.screenshots
                                        ).distinctBy { it.id }
                                        .sortedByDescending { it.createdAt },
                                )
                            }
                        }

                        // 状态推送是发给被控端的，控制端本地不消费
                        is WsEvent.CommandReceived,
                        is WsEvent.CommandsPending,
                        is WsEvent.DeviceStateUpdated,
                        is WsEvent.TimeRequestDecided,
                        -> Unit

                        // 策略推送是发给被控端的，控制端本地不消费；
                        // 家长自己刚保存过，界面已是新值
                        is WsEvent.PolicyUpdated -> Unit

                        // 该指令由服务端下发给被控端，控制端不需要处理
                        is WsEvent.RequestInstalledApps -> Unit
                    }
                }
                // 断线后重连
                delay(5_000)
            }
        }
    }

    /** 兜底轮询：WebSocket 不可用时仍能刷新状态与待审批申请 */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                refreshDevices()
                loadTimeRequests()
            }
        }
    }

    /* ---------------- 工具 ---------------- */

    private fun bearer(token: String) = "Bearer $token"

    private fun friendly(error: Throwable): String = when (error) {
        is HttpException -> {
            val raw = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
            val parsed = raw?.let { runCatching { json.decodeFromString<ApiError>(it) }.getOrNull() }
            parsed?.message ?: "请求失败（HTTP ${error.code()}）"
        }

        else -> error.message ?: "网络异常，请检查服务器地址"
    }
}
