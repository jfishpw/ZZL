package com.zzl.guardian.child.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zzl.guardian.MainActivity
import com.zzl.guardian.R
import com.zzl.guardian.child.PermissionChecker
import com.zzl.guardian.child.admin.AdminModeManager
import com.zzl.guardian.child.data.AuditAction
import com.zzl.guardian.child.data.AuditLevel
import com.zzl.guardian.child.data.DeviceStateRepository
import com.zzl.guardian.child.data.InstalledAppsReporter
import com.zzl.guardian.child.data.PolicyRepository
import com.zzl.guardian.child.data.UsageRepository
import com.zzl.guardian.child.di.GuardEntryPoint
import com.zzl.guardian.child.di.guardGraph
import com.zzl.guardian.child.engine.CommandRunner
import com.zzl.guardian.child.engine.GuardEngine
import com.zzl.guardian.child.icon.IconController
import com.zzl.guardian.child.keepalive.GuardKeepaliveAlarm
import com.zzl.guardian.child.keepalive.GuardKeepaliveWorker
import com.zzl.guardian.child.keepalive.KeepaliveManager
import com.zzl.guardian.child.keepalive.KeepaliveStore
import com.zzl.guardian.child.data.DayKeys
import com.zzl.guardian.child.engine.BlockReason
import com.zzl.guardian.child.engine.PinEntrySource
import com.zzl.guardian.child.pin.PinStore
import com.zzl.guardian.child.pin.PinVerifyResult
import com.zzl.guardian.child.pin.StoredPin
import com.zzl.guardian.data.pin.PinCrypto
import com.zzl.guardian.child.screenshot.ScreenshotCapturer
import com.zzl.guardian.data.ChildSession
import com.zzl.guardian.data.SettingsStore
import com.zzl.guardian.data.WsClient
import com.zzl.guardian.data.WsEvent
import com.zzl.guardian.data.api.ApiError
import com.zzl.guardian.data.api.ApiService
import com.zzl.guardian.data.api.HardeningReport
import com.zzl.guardian.data.api.HealthState
import com.zzl.guardian.data.api.HeartbeatRequest
import com.zzl.guardian.data.api.GrantDto
import com.zzl.guardian.data.api.TimeRequestCreateRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.HttpException

/**
 * 被控端常驻服务。承担六件事：
 *  1. 前台通知 —— 既是 Android 对长驻进程的要求，也让家长一眼看到管控在运行
 *  2. 心跳上报 —— 前台应用、剩余额度、权限健康度
 *  3. 策略同步 —— WebSocket 实时接收，断线重连后主动拉取兜底
 *  4. **状态对账** —— 锁定与授权整份覆盖，纠正一切丢失的推送
 *  5. **指令执行** —— 在线实时执行，离线时上线补执行
 *  6. 记录上报 —— 使用会话与拦截记录，含清理
 */
class GuardForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var graph: GuardEntryPoint
    private lateinit var engine: GuardEngine
    private lateinit var commandRunner: CommandRunner
    private lateinit var store: SettingsStore
    private lateinit var api: ApiService
    private lateinit var json: Json
    private lateinit var policyRepository: PolicyRepository
    private lateinit var deviceStateRepository: DeviceStateRepository
    private lateinit var usageRepository: UsageRepository
    private lateinit var installedAppsReporter: InstalledAppsReporter
    private lateinit var wsClient: WsClient
    private lateinit var pinStore: PinStore
    private lateinit var keepaliveStore: KeepaliveStore
    private lateinit var iconController: IconController
    private lateinit var capturer: ScreenshotCapturer

    private var session: ChildSession? = null
    private var wsJob: Job? = null
    private var periodicJob: Job? = null
    private var tickCount = 0L
    private var appsUploadedThisRun = false

    /** 已提交但还没拿到结果的加时申请；用于轮询补偿可能丢失的审批推送 */
    private var pendingRequestId: Long? = null

    /**
     * 上一次的健康快照。
     *
     * 用来识别"权限被中途关掉"。系统不会为权限变更发广播，
     * 只能靠定时比对两次快照 —— 这是被控端唯一能感知到这件事的方式。
     */
    private var lastHealth: HealthState? = null

    /** 熄屏期间不计入使用时长，加载与卸载点时都要同步给引擎 */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val now = System.currentTimeMillis()
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> engine.onScreenInteractive(false, now)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT ->
                    engine.onScreenInteractive(true, now)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        graph = guardGraph(this)
        engine = graph.guardEngine()
        commandRunner = graph.commandRunner()
        store = graph.settingsStore()
        api = graph.apiService()
        json = graph.json()
        policyRepository = graph.policyRepository()
        deviceStateRepository = graph.deviceStateRepository()
        usageRepository = graph.usageRepository()
        installedAppsReporter = graph.installedAppsReporter()
        wsClient = graph.wsClient()
        pinStore = graph.pinStore()
        keepaliveStore = graph.keepaliveStore()
        iconController = graph.iconController()
        capturer = graph.screenshotCapturer()

        // 拦截页上的「申请加时」按钮交给引擎触发，引擎自己不碰网络 ——
        // 它必须能在完全离线时照常工作
        engine.requestTimeHandler = { scopeName, packageName ->
            val current = session
            if (current == null) {
                engine.noteTimeRequest("failed:尚未配对，无法提交申请")
            } else {
                scope.launch { submitTimeRequest(current, scopeName, packageName) }
            }
        }

        // 拦截页内联密码：验证走 PinStore（含限流），动作在本服务编排。
        // 引擎只转发回调 —— 离线时 PinStore 全本地校验，照样可用。
        engine.pinVerifyHandler = { pin, level, onResult ->
            scope.launch {
                // runCatching 兜底：验证链任何异常都必须回结果，
                // 否则拦截页会永远停在「验证中…」（真机反馈）
                runCatching { verifyInlinePin(pin, level, onResult) }
                    .onFailure {
                        Log.w(TAG, "内联密码验证异常", it)
                        onResult(false, level, "验证出错，请重试")
                    }
            }
        }
        engine.pinActionHandler = { action, onResult ->
            scope.launch {
                runCatching { runPinAction(action, onResult) }
                    .onFailure {
                        Log.w(TAG, "密码动作执行异常", it)
                        onResult("执行出错，请重试")
                    }
            }
        }

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()

        // 设备管理器被取消激活时会带这个 action 拉起服务：
        // 这是"孩子正在卸载"的最早信号，必须立刻重新评估而不是等下一次巡检
        if (intent?.action == ACTION_REASSESS) {
            val reason = intent.getStringExtra(EXTRA_REASON)
            Log.w(TAG, "收到重新评估请求：$reason")
            scope.launch {
                runCatching { engine.reevaluate() }
                runCatching { session?.let { reportHardening(it) } }
            }
        }

        bootstrap()
        // 被系统回收后自动重建，这是保活的第一道保障
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        // 摘掉回调：引擎是单例，活过服务实例。不摘的话它会一直持有
        // 已销毁服务的引用，孩子点「申请加时」将没有任何反应
        if (engine.requestTimeHandler != null) engine.requestTimeHandler = null
        if (engine.pinVerifyHandler != null) engine.pinVerifyHandler = null
        if (engine.pinActionHandler != null) engine.pinActionHandler = null
        scope.cancel()
        super.onDestroy()
    }

    /* ---------------- 启动 ---------------- */

    private fun bootstrap() {
        scope.launch {
            session = runCatching { store.childSessionOnce() }.getOrNull()
            val current = session
            if (current == null) {
                Log.i(TAG, "尚未配对，前台服务空转等待")
                return@launch
            }

            runCatching { engine.initialize(current.deviceId) }
                .onFailure { Log.w(TAG, "初始化管控引擎失败", it) }

            // 保活的三层手段在这里全部就位：
            // 前台服务（本服务）→ 精确闹钟 → WorkManager 周期任务。
            // 后两层不是二选一 —— 精确闹钟在部分 ROM 会被限制，
            // WorkManager 在部分 ROM 会被延迟，一起用才稳。
            //
            // 注意用 this@GuardForegroundService 而不是 this ——
            // 这里在 scope.launch 内部，裸 this 是 CoroutineScope。
            GuardKeepaliveWorker.schedule(this@GuardForegroundService)
            GuardKeepaliveAlarm.schedule(this@GuardForegroundService)

            syncPolicy(current)
            // 顺序很关键：先补执行离线指令，再对账状态。
            //
            // 反过来的话会出现一个严重的错误：设备离线三天，家长中途「锁定」过又「解除」了，
            // 补发的 immediate_lock（永久有效）会执行，而 clear_lock（6 小时有效）早已过期。
            // 此时只有让权威状态**最后**落地，才能把设备恢复成家长真正想要的"未锁定"。
            runCatching { commandRunner.pullAndRun(current.token, current.deviceId) }
            syncDeviceState(current)
            reportHardening(current)
            if (!appsUploadedThisRun) uploadInstalledApps(current)
            connectSocket(current)
            startPeriodicWork(current)
        }
    }

    /* ---------------- 周期任务 ---------------- */

    private fun startPeriodicWork(current: ChildSession) {
        if (periodicJob?.isActive == true) return

        periodicJob = scope.launch {
            while (isActive) {
                delay(PERIODIC_INTERVAL_MS)
                tickCount += 1

                // 本地健康巡检**先行且不依赖网络**：孩子关掉无障碍是本机事件，
                // 断网或心跳失败时同样必须立刻发现（管控此刻已经失效，越早知道越好）
                runCatching { detectPermissionLoss(PermissionChecker.snapshot(this@GuardForegroundService)) }
                    .onFailure { Log.d(TAG, "本地健康巡检失败: ${it.message}") }

                runCatching { sendHeartbeat(current) }
                    .onFailure { Log.d(TAG, "心跳失败（离线属正常）: ${it.message}") }

                // 每 2 个周期（约 1 分钟）上报一次记录
                if (tickCount % 2 == 0L) {
                    runCatching { usageRepository.uploadPending(current.token, current.deviceId) }
                    runCatching { usageRepository.uploadPendingBlocks(current.token, current.deviceId) }
                    runCatching { usageRepository.uploadPendingPinAttempts(current.token, current.deviceId) }
                    // 审计事件（权限被关、密码被试、绕过尝试）也走同一条批量补传通道。
                    // 这类事件宁可晚报不可漏报，所以不追求实时，但必须每次都对一遍。
                    runCatching { usageRepository.uploadPendingAuditEvents(current.token, current.deviceId) }
                }

                // 每 4 个周期（约 2 分钟）做一次完整对账：补拉指令 + 状态整份覆盖。
                // 顺序同样不能颠倒 —— 状态必须最后落地，否则陈旧的锁定指令会覆盖掉
                // 家长后来解除的锁定状态。这是"最终一致"的保证。
                if (tickCount % 4 == 0L) {
                    runCatching { commandRunner.pullAndRun(current.token, current.deviceId) }
                    syncDeviceState(current)
                    // 加固状态只在启动与重评估时上报会漏掉"孩子中途取消激活"，
                    // 跟着对账一起刷新成本极低
                    runCatching { reportHardening(current) }
                }

                // 有未决申请时轮询结果，防止审批推送在断网期间丢失
                if (pendingRequestId != null) {
                    runCatching { pollTimeRequest(current) }
                }

                // 每 6 小时清理一次陈旧数据
                if (tickCount % 720 == 0L) {
                    runCatching {
                        usageRepository.pruneOldUploaded()
                        usageRepository.pruneOldBlockLogs()
                        usageRepository.pruneOldPinAttempts()
                        usageRepository.pruneOldAuditEvents()
                        deviceStateRepository.prune()
                    }
                }

                updateNotification()
            }
        }
    }

    /**
     * 上报加固状态。
     *
     * 只有设备自己能知道"当前是不是 Device Owner" —— 这是设备本地的既成事实，
     * 服务端无法代劳判断。顺带把保活健康度一起报上去，家长就能看到
     * "这台设备会不会被系统清掉"。
     */
    private suspend fun reportHardening(current: ChildSession) {
        val mode = AdminModeManager.currentMode(this)
        val isOwner = mode == AdminModeManager.MODE_DEVICE_OWNER

        // Device Owner 下顺手把加固项落实：这是唯一能做到系统级强制的时机。
        // 无障碍冻结是条件式的：开启时冻住设置开关（孩子关不掉），
        // 意外关闭时自动解冻（家长能重新打开，不会死锁）。
        if (isOwner) {
            runCatching {
                AdminModeManager.applyDeviceOwnerRestrictions(
                    this,
                    freezeAccessibility = PermissionChecker.isAccessibilityOn(this),
                )
            }
            runCatching { AdminModeManager.blockUninstall(this) }
        }

        val vendorConfirmed = runCatching { keepaliveStore.vendorWhitelistConfirmed() }.getOrDefault(false)
        val keepalive = KeepaliveManager.snapshot(
            context = this,
            vendorWhitelistConfirmed = vendorConfirmed,
            foregroundServiceRunning = true,
        )

        runCatching {
            api.heartbeat(
                authorization = "Bearer ${current.token}",
                deviceId = current.deviceId,
                body = HeartbeatRequest(
                    foregroundPackage = engine.state.value.foregroundPackage,
                    remainingMs = engine.state.value.remainingMs,
                    health = PermissionChecker.snapshot(this),
                    hardening = HardeningReport(
                        adminMode = mode,
                        deviceOwner = isOwner,
                        uninstallBlocked = AdminModeManager.isUninstallBlocked(this),
                    ),
                    keepalive = keepalive,
                ),
            )
        }.onFailure { Log.d(TAG, "上报加固状态失败（离线属正常）: ${it.message}") }
    }

    private suspend fun sendHeartbeat(current: ChildSession) {
        val state = engine.state.value
        val health = PermissionChecker.snapshot(this)

        api.heartbeat(
            authorization = "Bearer ${current.token}",
            deviceId = current.deviceId,
            body = HeartbeatRequest(
                // 上报 null 表示当前没有受管控的应用在前台
                foregroundPackage = state.foregroundPackage,
                remainingMs = state.remainingMs,
                health = health,
            ),
        )

        detectPermissionLoss(health)
    }

    /**
     * 检测权限是否被中途关掉，并留下审计痕迹。
     *
     * 为什么值得单独做这件事：家长问"为什么限制不起作用了"，
     * 最常见的答案就是**孩子把无障碍关了**。而这件事：
     *   - 不会触发系统广播
     *   - 关掉后管控静默失效，从家长视角看与"软件坏了"没有区别
     *   - 孩子完全可以装作不知道
     *
     * 因此每次心跳都比对一次快照，一旦发现权限从"有"变成"没有"，
     * 立刻记一条 warn 级审计事件（断网也会落本地库，联网后补传）。
     */
    private suspend fun detectPermissionLoss(current: HealthState) {
        val lost = PermissionChecker.newlyLost(lastHealth, current)
        lastHealth = current
        if (lost.isEmpty()) return

        val names = lost.joinToString("、") { PermissionChecker.describe(it) }
        Log.w(TAG, "★ 检测到权限被关闭：$names")

        runCatching {
            usageRepository.recordAudit(
                action = AuditAction.PERMISSION_LOST,
                detail = buildJsonObject {
                    put("missing", buildJsonArray { lost.forEach { add(JsonPrimitive(it)) } })
                    put("names", names)
                }.toString(),
                level = AuditLevel.WARN,
            )
        }.onFailure { Log.w(TAG, "记录权限丢失事件失败", it) }
    }

    /* ---------------- 策略与状态同步 ---------------- */

    private suspend fun syncPolicy(current: ChildSession) {
        runCatching { api.ownPolicy("Bearer ${current.token}") }
            .onSuccess { bundle ->
                // 写本地库单独兜异常：协程里未捕获的异常会直接崩掉进程
                runCatching { policyRepository.saveBundle(current.deviceId, bundle) }
                    .onFailure { Log.w(TAG, "策略包写入本地库失败", it) }
                Log.i(
                    TAG,
                    "策略包已同步至 v${bundle.version}（名单 ${bundle.listItems.size} 项，规则 ${bundle.appRules.size} 条）",
                )
            }
            .onFailure { Log.d(TAG, "策略同步失败，继续使用本地副本: ${it.message}") }
    }

    /**
     * 设备状态对账：锁定状态与全部生效授权整份覆盖。
     *
     * 这一步是"最终一致"的落点 —— 不论前面丢了哪条推送、哪次回执，
     * 只要对账跑过一次，本地状态就与家长看到的一致。
     */
    private suspend fun syncDeviceState(current: ChildSession) {
        runCatching { api.deviceState("Bearer ${current.token}") }
            .onSuccess { state ->
                val dayKey = engine.state.value.dayKey
                runCatching { deviceStateRepository.applyState(current.deviceId, state, dayKey) }
                    .onFailure { Log.w(TAG, "设备状态写入本地库失败", it) }

                // 密码备份随状态一起下发：换机恢复与家长远程重置都走这条通道。
                // 只在服务端版本更高时才覆盖本地 —— 否则家长刚在设备上改的密码
                // 会被一份陈旧的云端备份冲掉。
                state.pins?.let { pins ->
                    runCatching {
                        pinStore.applyRemote(pins.levels, pins.version, pins.levelCount)
                    }.onFailure { Log.w(TAG, "应用密码备份失败", it) }
                }

                // 图标隐藏是**设备状态**而不是一次性指令，因此必须在对账时落实。
                // 只靠指令的话，孩子重启一次平板图标就回来了 ——
                // 而家长没有机会再下发一次（他甚至不知道孩子重启过）。
                runCatching { applyIconState(state.iconHidden) }
                    .onFailure { Log.w(TAG, "应用图标状态失败", it) }

                Log.d(
                    TAG,
                    "状态对账完成 v${state.stateVersion}（锁定=${state.locked}，授权 ${state.grants.size} 条，" +
                        "密码 v${state.pins?.version ?: 0}，图标隐藏=${state.iconHidden}）",
                )
            }
            .onFailure { Log.d(TAG, "状态对账失败，继续使用本地副本: ${it.message}") }
    }

    /**
     * 让本地图标状态与家长期望一致。
     *
     * 做成"比对后按需操作"而不是"每次都执行一遍"：
     * 对账每 2 分钟一次，无脑调用会在日志里刷满"已恢复应用图标"，
     * 而且 `setComponentEnabledSetting` 每次都调用会让 PackageManager 反复刷新。
     */
    private fun applyIconState(shouldHide: Boolean) {
        val current = iconController.isHidden()
        if (current == shouldHide) return

        Log.i(TAG, "图标状态需调整：$current → $shouldHide")
        if (shouldHide) iconController.hide() else iconController.show()
    }

    /** 整份上报已安装应用清单。服务每次启动上报一次，加上服务端主动请求。 */
    private suspend fun uploadInstalledApps(current: ChildSession) {
        val count = runCatching { installedAppsReporter.upload(current.token, current.deviceId) }
            .getOrDefault(0)
        if (count > 0) {
            appsUploadedThisRun = true
            Log.i(TAG, "已上报 $count 个已安装应用")
        }
    }

    /* ---------------- 加时申请 ---------------- */

    /* ---------------- 拦截页内联密码 ---------------- */

    /**
     * 拦截页密码框的验证。
     *
     * **家长不关心级别数字**：拦截页从默认级别（1）开始尝试，而家长记住的
     * 可能是超级密码。上一版只在「该级未设置」时降级 —— 家长设置过多级密码时，
     * 在默认级别输入超级密码会被当成「密码错误」，连续几次后默认级别被限流
     * 锁定，**正确的密码也再也进不去**（真机反馈"第二次输密码没反应"）。
     * 所以现在错误/未设置/已锁定时都拿同一密码去匹配其他已设置级别，
     * 命中就按命中级别放行。限流计数只落在被尝试的级别上。
     */
    private suspend fun verifyInlinePin(
        pin: String,
        level: Int,
        onResult: (verified: Boolean, level: Int, message: String?) -> Unit,
    ) {
        when (val result = pinStore.verify(level, pin)) {
            is PinVerifyResult.Ok -> {
                recordPinAttempt(level, success = true)
                onResult(true, level, null)
            }

            is PinVerifyResult.NotSet -> {
                val hit = matchOtherLevel(pin, level)
                if (hit != null) {
                    pinStore.recordSuccess(hit.level)
                    recordPinAttempt(hit.level, success = true)
                    onResult(true, hit.level, null)
                    return
                }
                // 密码对不上任何级别：把输入框切到最近的已设置级别，方便家长对号
                val fallback = pinStore.allStored()
                    .minByOrNull { kotlin.math.abs(it.level - level) }
                if (fallback != null) {
                    onResult(
                        false,
                        fallback.level,
                        "第 $level 级未设置，已切换为「${pinLevelName(fallback.level)}」，请输入该级密码",
                    )
                } else {
                    onResult(false, level, "尚未设置离线密码，请让家长在控制端先设置")
                }
            }

            is PinVerifyResult.Wrong -> {
                val hit = matchOtherLevel(pin, level)
                if (hit != null) {
                    pinStore.recordSuccess(hit.level)
                    // 跨级验证成功：当前级别积累的"误级别失败"一并清掉，
                    // 不让家长为级别错位背上限流
                    pinStore.recordSuccess(level)
                    recordPinAttempt(hit.level, success = true)
                    onResult(true, hit.level, null)
                    return
                }
                recordPinAttempt(level, success = false)
                val message = if (result.attemptsLeft > 0) {
                    "密码错误，还可尝试 ${result.attemptsLeft} 次"
                } else {
                    "密码错误"
                }
                onResult(false, level, message)
            }

            is PinVerifyResult.Locked -> {
                // 当前级别已限流：如果密码匹配的是其他级别仍然放行 ——
                // 限流的目的是防爆破，不是把家长锁在门外
                val hit = matchOtherLevel(pin, level)
                if (hit != null) {
                    pinStore.recordSuccess(hit.level)
                    recordPinAttempt(hit.level, success = true)
                    onResult(true, hit.level, null)
                    return
                }
                recordPinAttempt(level, success = false)
                val minutes = result.remainingMs / 60_000
                onResult(false, level, "尝试次数过多，已锁定 ${minutes + 1} 分钟")
            }
        }
    }

    /** 拿同一密码逐个匹配其他已设置级别（不含 [excludeLevel]），命中返回该级 */
    private suspend fun matchOtherLevel(pin: String, excludeLevel: Int): StoredPin? =
        pinStore.allStored()
            .filter { it.level != excludeLevel }
            .firstOrNull { PinCrypto.verify(pin, it.hash, it.salt, it.iterations) }

    /** 验证通过后的家长动作。action：add_time:N / allow_app / exit_guard */
    private suspend fun runPinAction(action: String, onResult: (String?) -> Unit) {
        val current = session
        // 锁定期间的统一闸门：forcedLocked 压过一切授权，加时/放行落了库也不会解锁。
        // 与其让家长执行一个注定无效的操作，不如在这里就把话说清楚。
        // （遮罩层在锁定态已隐藏这两个按钮，这里是防御其他入口。）
        if (action != ACTION_EXIT_GUARD && engine.state.value.locked) {
            onResult("设备已被锁定，加时与放行不可用；请解除锁定或退出管控")
            return
        }
        when {
            action.startsWith("add_time:") -> {
                val minutes = action.substringAfter("add_time:").toIntOrNull() ?: 0
                if (current == null) {
                    onResult("尚未配对，无法加时")
                    return
                }
                if (minutes !in 1..240) {
                    onResult("加时时长无效")
                    return
                }
                // 与旧版密码键盘同一口径：额度日取引擎现值，断网时本地照常生效
                val dayKey = engine.state.value.dayKey.ifBlank {
                    DayKeys.of(System.currentTimeMillis(), 0)
                }
                deviceStateRepository.addLocalExtra(current.deviceId, minutes, dayKey)
                // 真机反馈：单应用「单日上限」把应用拦下时，总量加时无济于事 ——
                // 逐应用超额判定看的是这个应用自己的用量，不是总量。
                // 所以被拦状态下把加时同时落成对当前应用的放行授权，
                // 家长点「+N 分钟」在任何拦截原因下都有效（总量加时保持不变）。
                val blockedPkg = engine.state.value.blockedPackage
                val blockedReason = engine.state.value.blockedReason
                if (blockedPkg != null && blockedPkg.isNotBlank() &&
                    blockedReason != null && blockedReason != BlockReason.LOCKED
                ) {
                    val now = System.currentTimeMillis()
                    deviceStateRepository.upsertGrant(
                        current.deviceId,
                        GrantDto(
                            id = deviceStateRepository.nextLocalGrantId(),
                            scope = "app_allow",
                            packageName = blockedPkg,
                            appLabel = null,
                            extraMinutes = minutes,
                            expireAt = now + minutes * 60_000L,
                            source = "local_pin",
                            createdAt = now,
                        ),
                    )
                }
                engine.reevaluate()
                onResult(null) // 成功后引擎重新评估，额度够了会自动撤掉遮罩
            }

            action == ACTION_ALLOW_APP -> {
                val pkg = engine.state.value.blockedPackage
                if (current == null || pkg.isNullOrBlank()) {
                    onResult("当前没有可放行的应用")
                    return
                }
                val now = System.currentTimeMillis()
                deviceStateRepository.upsertGrant(
                    current.deviceId,
                    GrantDto(
                        id = deviceStateRepository.nextLocalGrantId(),
                        scope = "app_allow",
                        packageName = pkg,
                        appLabel = null,
                        extraMinutes = ALLOW_APP_MINUTES,
                        expireAt = now + ALLOW_APP_MINUTES * 60_000L,
                        source = "local_pin",
                        createdAt = now,
                    ),
                )
                engine.reevaluate()
                onResult(null)
            }

            action == ACTION_EXIT_GUARD -> {
                // ⚠️ 退出清理**绝不能**跑在 [scope] 里：exitGuardCompletely 会 stopService，
                // onDestroy 随即 scope.cancel()，清理协程在第一个挂起点就被取消 ——
                // 配对/策略/密码只清了一半，遮罩不撤，家长再点一次只会看到
                // 「暂时无法执行」（真机反馈）。必须在不受服务生命周期影响的独立作用域跑完。
                val exitScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                exitScope.launch {
                    runCatching { exitGuardCompletely() }
                        .onFailure { Log.w(TAG, "退出管控清理异常", it) }
                    onResult(null)
                }
                // 不在此处 onResult：成功与否由 exitScope 里的回调负责，
                // 避免"清理还没做完就提示已执行"的假反馈
            }

            else -> onResult("未知操作")
        }
    }

    /** 密码尝试写入本地审计，家长在控制端能看到（含来源与成败） */
    private suspend fun recordPinAttempt(level: Int, success: Boolean) {
        runCatching {
            usageRepository.recordPinAttempt(level, success, PinEntrySource.OVERLAY)
        }.onFailure { Log.w(TAG, "记录密码尝试失败", it) }
    }

    private fun pinLevelName(level: Int) = when (level) {
        3 -> "超级密码"
        2 -> "管理密码"
        else -> "日常密码"
    }

    /**
     * 退出管控的完整清理。
     *
     * 顺序很重要：**先做完所有状态清理，最后才停服务**。
     * 最容易犯的错是在第一步就 stopService —— 那会立刻触发 onDestroy
     * 取消正在跑的清理协程，留下"半清空"的现场（遮罩不撤、配对残留），
     * 保活闹钟还会用残留配对把管控原地复活。
     * 清掉配对之后，所有自启路径自然失效，最后停服务只是收尾。
     */
    private suspend fun exitGuardCompletely() {
        Log.w(TAG, "家长通过离线密码退出管控")

        // 清掉配对会话：这是"不再受管控"的根本标志。
        // GuardBootReceiver / GuardAlarmReceiver 都以它的存在为前提。
        store.clearChildSession()
        deviceStateRepository.clear()
        policyRepository.clear()
        pinStore.clearAll()

        // 立刻撤掉遮罩：策略已清，别等引擎的下一次评估
        engine.clearBlockedState()

        // 恢复图标：隐藏图标是"自救路径之外的隐藏动作"，
        // 退出管控时必须还原，否则家长退出后会面对一个"装了这个 App 但找不到"的困惑局面。
        runCatching { iconController.show() }
            .onFailure { Log.w(TAG, "恢复图标失败（不阻断退出流程）", it) }

        // 解除卸载阻止与 Device Owner 用户限制（含无障碍设置冻结）：
        // 家长已经决定退出管控，再拦着卸载、锁着设置就说不通了。
        AdminModeManager.unblockUninstall(this)
        AdminModeManager.clearDeviceOwnerRestrictions(this)
        // 设备管理器没有程序化取消激活的公开 API，只能引导到系统页面手动操作
        AdminModeManager.requestDeactivation(this)

        // 收尾：保活入口停掉、前台服务退出。必须在所有清理完成之后。
        GuardKeepaliveWorker.cancel(this)
        GuardKeepaliveAlarm.cancel(this)
        stopService(Intent(this, GuardForegroundService::class.java))
    }

    private suspend fun submitTimeRequest(current: ChildSession, scopeName: String, packageName: String?) {
        val appLabel = packageName?.let { labelOf(it) }
        engine.noteTimeRequest("pending")

        runCatching {
            api.createTimeRequest(
                authorization = "Bearer ${current.token}",
                body = TimeRequestCreateRequest(
                    scope = scopeName,
                    packageName = packageName,
                    appLabel = appLabel,
                    requestMin = DEFAULT_REQUEST_MIN,
                    // 带上"当时在用哪个应用"的上下文。
                    // 家长看到「申请加时 30 分钟」和看到「在「微信」里申请加时 30 分钟」，
                    // 判断依据完全不同。
                    reason = appLabel?.let { "当时正在使用「$it」" },
                ),
            )
        }
            .onSuccess { response ->
                val request = response.request
                if (request?.status == "approved") {
                    // 家长恰好同时在控制端操作：审批已经生效，直接对账拉下授权
                    engine.noteTimeRequest("approved")
                    syncDeviceState(current)
                } else {
                    pendingRequestId = request?.id
                    engine.noteTimeRequest("pending")
                }
            }
            .onFailure { error ->
                engine.noteTimeRequest("failed:${friendlyRequestError(error)}")
            }
    }

    /** 轮询自己的申请状态，补偿可能丢失的 `time_request_decided` 推送 */
    private suspend fun pollTimeRequest(current: ChildSession) {
        val requestId = pendingRequestId ?: return
        val response = runCatching { api.myTimeRequests("Bearer ${current.token}") }.getOrNull() ?: return
        val latest = response.requests.firstOrNull { it.id == requestId } ?: return

        when (latest.status) {
            "approved" -> {
                pendingRequestId = null
                engine.noteTimeRequest("approved")
                syncDeviceState(current)
                Log.i(TAG, "加时申请已通过，授权已同步")
            }

            "rejected" -> {
                pendingRequestId = null
                engine.noteTimeRequest("rejected")
            }

            "expired" -> {
                pendingRequestId = null
                engine.noteTimeRequest("failed:家长未在时限内处理，请稍后再试")
            }
        }
    }

    /** 把服务端的错误文案取出来，给孩子的提示才是可读的 */
    private fun friendlyRequestError(error: Throwable): String = when (error) {
        is HttpException -> {
            val raw = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
            runCatching { raw?.let { json.decodeFromString<ApiError>(it).message } }.getOrNull()
                ?: "提交失败（HTTP ${error.code()}）"
        }

        else -> "网络不可用，请让家长在设备上输入密码"
    }

    private fun labelOf(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    /* ---------------- 实时通道 ---------------- */

    private fun connectSocket(current: ChildSession) {
        if (wsJob?.isActive == true) return

        wsJob = scope.launch {
            while (isActive) {
                wsClient.events(current.token).collect { event ->
                    when (event) {
                        is WsEvent.Connected -> {
                            // 重连后立刻补齐可能错过的一切。
                            // 顺序固定：策略 → 补执行离线指令 → 状态对账（权威状态最后落地）
                            syncPolicy(current)
                            runCatching { commandRunner.pullAndRun(current.token, current.deviceId) }
                            syncDeviceState(current)
                            runCatching { usageRepository.uploadPending(current.token, current.deviceId) }
                            runCatching { usageRepository.uploadPendingBlocks(current.token, current.deviceId) }
                            if (!appsUploadedThisRun) launch { uploadInstalledApps(current) }
                        }

                        is WsEvent.PolicyUpdated -> launch {
                            runCatching { policyRepository.saveBundle(current.deviceId, event.bundle) }
                                .onFailure { Log.w(TAG, "策略推送写入本地库失败", it) }
                            Log.i(TAG, "收到策略推送 v${event.bundle.version}")
                        }

                        is WsEvent.DeviceStateUpdated -> launch {
                            val dayKey = engine.state.value.dayKey
                            runCatching {
                                deviceStateRepository.applyState(current.deviceId, event.state, dayKey)
                            }.onFailure { Log.w(TAG, "状态推送写入本地库失败", it) }

                            event.state.pins?.let { pins ->
                                runCatching {
                                    pinStore.applyRemote(pins.levels, pins.version, pins.levelCount)
                                }.onFailure { Log.w(TAG, "应用密码备份失败", it) }
                            }
                        }

                        is WsEvent.CommandReceived -> launch {
                            commandRunner.runOne(current.token, current.deviceId, event.command)
                        }

                        is WsEvent.CommandsPending -> launch {
                            // 只收到条数提示，内容仍需主动拉取 —— 长连接不承载大载荷
                            commandRunner.pullAndRun(current.token, current.deviceId)
                        }

                        is WsEvent.TimeRequestDecided -> launch {
                            val decided = event.request
                            pendingRequestId = null
                            engine.noteTimeRequest(
                                if (decided.status == "approved") "approved" else "rejected",
                            )
                            // 审批通过意味着服务端刚生成了授权，立刻对账把它拉下来
                            if (decided.status == "approved") syncDeviceState(current)
                        }

                        is WsEvent.RequestInstalledApps -> launch {
                            uploadInstalledApps(current)
                        }

                        is WsEvent.DeviceRemoved -> launch {
                            // 家长在控制端删除了本设备：清本地会话回到配对页，
                            // 避免拿着指向已删除设备的死 token 继续心跳与上报
                            runCatching {
                                store.clearChildSession()
                                policyRepository.clear()
                                deviceStateRepository.clear()
                            }.onFailure { Log.w(TAG, "清除被删设备的本地会话失败", it) }
                            Log.i(TAG, "设备已被家长从控制端删除，本地会话已清除")
                            stopSelf()
                        }

                        else -> Unit
                    }
                }
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    /* ---------------- 通知 ---------------- */

    private fun startForegroundSafely() {
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= BUILD_VERSION_ANDROID_14) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "管控服务",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "保持管控功能在后台运行"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("掌中灵正在守护设备")
            .setContentText("应用使用时长受家长管控")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    /** 把剩余额度显示在常驻通知上，孩子自己也能看到还剩多少 */
    private fun updateNotification() {
        val state = engine.state.value
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = when {
            // 关键权限被关时，常驻通知必须变成显眼的告警 ——
            // 这是孩子每次下滑通知栏都能看到的提醒，比只在控制端看审计更及时
            !PermissionChecker.snapshot(this).accessibility -> "⚠ 无障碍服务已关闭，管控已失效，请重新开启"
            state.locked -> "设备已被家长锁定"
            state.unlocked -> "家长已临时解除限制"
            !state.enforcementEnabled -> "管控当前已暂停"
            else -> {
                val minutes = state.remainingMs / 60_000
                val extra = if (state.extraMs > 0) "（含家长加的 ${state.extraMs / 60_000} 分钟）" else ""
                "今日剩余 ${minutes} 分钟$extra"
            }
        }
        runCatching {
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("掌中灵正在守护设备")
                    .setContentText(text)
                    .setOngoing(true)
                    .setSilent(true)
                    .setShowWhen(false)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .build(),
            )
        }
    }

    /**
     * 动作常量必须是 public —— 设备管理器接收器与保活闹钟都要用它们拉起本服务。
     * 其余内部常量保持 private，避免污染对外可见的 API 面。
     */
    companion object {
        /** 由设备管理器接收器发出：孩子可能正在卸载，立刻重新评估 */
        const val ACTION_REASSESS = "com.zzl.guardian.child.action.REASSESS"

        /** 由精确闹钟发出：周期性自我唤醒 */
        const val ACTION_KEEPALIVE = "com.zzl.guardian.child.action.KEEPALIVE"

        const val EXTRA_REASON = "reason"

        /** 拦截页内联密码的动作码（与 BlockOverlay 约定） */
        private const val ACTION_ALLOW_APP = "allow_app"
        private const val ACTION_EXIT_GUARD = "exit_guard"

        /** 「放行当前应用」的固定时长 */
        private const val ALLOW_APP_MINUTES = 30

        private const val TAG = "GuardService"
        private const val CHANNEL_ID = "zzl_guard"
        private const val NOTIFICATION_ID = 1001
        private const val PERIODIC_INTERVAL_MS = 30_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val BUILD_VERSION_ANDROID_14 = 34

        /** 孩子端申请的默认时长；家长可以打折批准 */
        private const val DEFAULT_REQUEST_MIN = 30
    }
}
