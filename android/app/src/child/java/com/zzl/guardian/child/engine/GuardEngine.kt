package com.zzl.guardian.child.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.Log
import com.zzl.guardian.child.data.AppLimitBaselineEntity
import com.zzl.guardian.child.data.DayKeys
import com.zzl.guardian.child.data.DeviceStateRepository
import com.zzl.guardian.child.data.GrantEntity
import com.zzl.guardian.child.data.GrantScope
import com.zzl.guardian.child.data.GuardOverrides
import com.zzl.guardian.child.data.GuardPolicy
import com.zzl.guardian.child.data.PolicyRepository
import com.zzl.guardian.child.data.PolicyRules
import com.zzl.guardian.child.data.UsageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 拦截原因，取值与服务端 block_logs.reason 保持一致 */
object BlockReason {
    const val TOTAL_EXHAUSTED = "total_exhausted"
    const val APP_EXHAUSTED = "app_exhausted"
    const val OUT_OF_WINDOW = "out_of_window"
    const val BLACKLIST = "blacklist"
    const val NOT_IN_WHITELIST = "not_in_whitelist"

    /** 家长点了「立即锁定」——与规则无关，是家长的即时决定 */
    const val LOCKED = "lock"

    fun describe(reason: String): String = when (reason) {
        TOTAL_EXHAUSTED -> "今日总时长已用完"
        APP_EXHAUSTED -> "该应用今日时长已用完"
        OUT_OF_WINDOW -> "当前不在允许使用的时间段内"
        BLACKLIST -> "该应用在禁止名单中"
        NOT_IN_WHITELIST -> "该应用不在允许名单中"
        LOCKED -> "已被家长锁定"
        else -> "已被家长限制"
    }
}

data class GuardState(
    val ready: Boolean = false,
    val enforcementEnabled: Boolean = false,
    val foregroundPackage: String? = null,
    val usedTodayMs: Long = 0,
    /** 当日上限，已含加时授权 */
    val limitMs: Long = 0,
    /** 其中来自加时授权的部分，便于界面说明"多出来的时间从哪来" */
    val extraMs: Long = 0,
    val dayKey: String = "",
    val blockedPackage: String? = null,
    val blockedReason: String? = null,
    val policyVersion: Int = 0,
    val listedAppCount: Int = 0,
    val ruleCount: Int = 0,
    /** 家长「立即锁定」是否生效 */
    val locked: Boolean = false,
    /** 是否处于「临时总解封」的有效期内 */
    val unlocked: Boolean = false,
    /** 被单独放行的应用数量 */
    val allowedAppCount: Int = 0,
    /**
     * 最近一次「申请加时」的结果，用于在拦截页上给孩子反馈：
     * null（未申请）| pending | approved | rejected | failed:<原因>
     */
    val timeRequestStatus: String? = null,
) {
    val remainingMs: Long get() = (limitMs - usedTodayMs).coerceAtLeast(0)
    val exhausted: Boolean get() = enforcementEnabled && usedTodayMs >= limitMs

    /** 当前是否处于任何形式的"被拦"状态 */
    val blocking: Boolean get() = blockedReason != null
}

/**
 * 被控端的管控引擎，是整个「本地执行」的中枢。
 *
 * 六条设计原则：
 *  1. **唯一决策入口**：[applyDecision] 是唯一决定"该不该拦"的地方 ——
 *     前台切换、熄屏亮屏、秒级巡检、策略更新、授权变更全部汇入它。
 *     分散成多个判定点必然出现"某条路径漏判"的漏洞。
 *  2. **单锁串行**：这些触发源会并发到来，因此所有状态变更都在 [mutex] 内进行。
 *     少了这把锁会出现"会话被错误地归属到别的应用"。
 *  3. **完全本地判定** —— 策略包与授权都有本地副本，断网照常管控。
 *  4. **判定纯内存** —— [GuardPolicy] 与 [GuardOverrides] 都是不可变结构，
 *     判定时零解析开销、零数据库查询（进行中的会话用内存时间差补足）。
 *  5. **计时与判定分离** —— [usedCommittedMs] 只统计「已收尾」的会话，
 *     进行中的会话用 [inflightMs] 补足，因此额度判定精确到秒又不必每秒写库。
 *     汇总成「今日已用」时还要整段剔除被标记「不计入总时长」的应用
 *     （见 [inflightCountedMs]，历史会话同样在 [todayTotal] 里排除）。
 *  6. **幂等与自恢复** —— 应用被杀后靠 closeDangling 补齐；状态漂移靠定时对账纠正。
 */
@Singleton
class GuardEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val policyRepository: PolicyRepository,
    private val usageRepository: UsageRepository,
    private val deviceStateRepository: DeviceStateRepository,
    private val blockOverlay: BlockOverlay,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 守护所有可变状态。前台切换、熄屏亮屏、巡检、策略更新全部串行执行。
     * 没有它，巡检读到"前台是 A"之后、真正执行拦截之前，A 可能已经被切换掉了。
     */
    private val mutex = Mutex()

    private val _state = MutableStateFlow(GuardState())
    val state: StateFlow<GuardState> = _state.asStateFlow()

    /** 由无障碍服务注入，用于强制返回桌面 */
    @Volatile
    var performHome: (() -> Unit)? = null

    /**
     * 由常驻服务注入，用于把"申请加时"交给网络层提交。
     * 引擎自己不做网络请求 —— 它必须能在完全离线时正常工作。
     */
    @Volatile
    var requestTimeHandler: ((scopeName: String, packageName: String?) -> Unit)? = null

    /**
     * 由常驻服务注入：拦截页**内联**密码框的验证与后续动作。
     *
     * 与"申请加时"同一套思路 —— 引擎只负责把拦截页的回调转发出来，
     * 具体验证（PinStore + 限流）与动作（加时/放行/退出）由服务层编排。
     * 这样引擎保持纯粹的判定职责，也不会因为持有 UI 或网络依赖而无法在离线时工作。
     *
     * 历史上这里是三个隐藏入口 + 独立密码 Activity；真机验证两条路都有问题
     * （入口难发现，Activity 又被遮罩压住），已整体改为内联输入。
     */
    @Volatile
    var pinVerifyHandler: ((pin: String, level: Int, onResult: (Boolean, Int, String?) -> Unit) -> Unit)? = null

    /** 验证通过后的家长动作：add_time:N / allow_app / exit_guard → 反馈文案（null = 成功） */
    @Volatile
    var pinActionHandler: ((action: String, onResult: (String?) -> Unit) -> Unit)? = null

    @Volatile
    private var guard: GuardPolicy? = null

    /** 生效中的授权（本地副本）。与 [locked] 一起构成 [GuardOverrides]。 */
    @Volatile
    private var grants: List<GrantEntity> = emptyList()

    @Volatile
    private var locked = false

    /**
     * 家长在本机用离线密码加的时长（毫秒）及其所属额度日。
     *
     * 与云端授权分开持有：云端授权会被对账整份覆盖，
     * 而本地加时必须活到当天结束 —— 混在一起的话一次对账就把它冲掉了。
     */
    @Volatile
    private var localExtraMs = 0L

    @Volatile
    private var localExtraDayKey: String? = null

    /** 已收尾会话的当日累计（毫秒）。进行中的会话不计入这里。 */
    private var usedCommittedMs = 0L

    private var openPackage: String? = null
    private var openStartTs = 0L

    /** 当前应用在本次会话开始前的当日累计，用于精确判断「该应用单日超额」 */
    private var openAppCommittedMs = 0L

    /** 逐应用限额基线的内存缓存（详见 [netAppUsedMs]），随 (应用, 天, 限额值) 失效 */
    @Volatile
    private var baselineCache: AppLimitBaselineEntity? = null

    /**
     * 最后一次上报的前台应用（意图值）。
     * 与 [openPackage] 分开：openPackage 表示"正在计时"，
     * 这个表示"系统说前台是谁"，用于丢弃重复事件与过期事件。
     */
    @Volatile
    private var desiredPackage: String? = null

    /**
     * 前台切换的「候选包名」与其事件时间。
     *
     * 无障碍事件只作候选，不直接提交：权限弹窗、ColorOS 侧边栏/悬浮球、
     * 安装器等瞬态窗口会伪造前台切换 —— 立刻关会话会把时间线切成大量
     * 0 秒碎片、总时长严重少计（真机实测在应用里停 1 分钟只剩几秒）。
     * 候选要等 [FOREGROUND_CONFIRM_MS] 后经 [commitForeground] 核实才算数。
     */
    @Volatile
    private var pendingPackage: String? = null

    @Volatile
    private var pendingEventAt = 0L

    private var pendingJob: Job? = null

    /**
     * 无障碍窗口树核实回调（服务连接时注入）。
     * 提交切换前用它确认「当前活跃的应用窗口」，候选与核实不一致的按核实结果处理。
     */
    @Volatile
    var foregroundLookup: (() -> String?)? = null

    /** 预警去重的额度日。跨天后清空 [warnedWarnKeys]，各类预警每天各提醒一次。 */
    private var warnDayKey: String? = null

    private val warnedWarnKeys = mutableSetOf<String>()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var screenInteractive = true

    private var tickCount = 0
    private var tickerJob: Job? = null
    private var policyJob: Job? = null
    private var overridesJob: Job? = null

    @Volatile
    private var initialized = false

    private var initializedDeviceId = 0L

    private var lastBlockPackage: String? = null
    private var lastBlockReason: String? = null
    private var lastBlockAt = 0L
    private var lastGoHomeAt = 0L

    /** 系统组件与桌面不参与计时，否则孩子什么都不干也在消耗额度 */
    private val ignoredPackages: Set<String> by lazy {
        buildSet {
            add(context.packageName)
            add("com.android.systemui")
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            runCatching {
                context.packageManager
                    .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo
                    ?.packageName
                    ?.let(::add)
            }
        }
    }

    /* ---------------- 生命周期 ---------------- */

    suspend fun initialize(deviceId: Long) {
        mutex.withLock {
            if (initialized && initializedDeviceId == deviceId) return@withLock

            initializedDeviceId = deviceId
            usageRepository.recoverDanglingSessions()
            guard = policyRepository.observeGuardPolicy(deviceId).first()
            grants = deviceStateRepository.grants(deviceId)
            val state = deviceStateRepository.state(deviceId)
            locked = state?.locked == true
            localExtraMs = state?.localExtraMs ?: 0
            localExtraDayKey = state?.localExtraDayKey
            usedCommittedMs = todayTotal()
            initialized = true
            publishState()
        }

        // 下面几步都在锁外，且各自幂等（subscribe* 会先取消上一个订阅）
        startTicker()
        subscribePolicy(deviceId)
        subscribeOverrides(deviceId)
    }

    /** 策略变更后立即重算，不必等下一个巡检周期 */
    private fun subscribePolicy(deviceId: Long) {
        policyJob?.cancel()
        policyJob = scope.launch {
            policyRepository.observeGuardPolicy(deviceId).collect { latest ->
                mutex.withLock {
                    guard = latest
                    usedCommittedMs = todayTotal()
                }
                reevaluate()
            }
        }
    }

    /**
     * 订阅授权与锁定状态。
     *
     * 收到变更后必须**立刻重新决策**而不是等下一次巡检：
     * 家长刚点完「批准加时」，孩子就守在拦截页上，
     * 等 1 秒才消失已经足够让人怀疑是不是没生效。
     */
    private fun subscribeOverrides(deviceId: Long) {
        // 与策略订阅一样先取消上一个：换设备重新配对时，
        // 旧订阅会继续把旧设备的授权写进内存，导致判定用到错误的覆盖项
        overridesJob?.cancel()
        overridesJob = scope.launch {
            combine(
                deviceStateRepository.observeGrants(deviceId),
                deviceStateRepository.observeState(deviceId),
            ) { latestGrants, state -> latestGrants to state }
                .collect { (latestGrants, state) ->
                    mutex.withLock {
                        grants = latestGrants
                        locked = state?.locked == true
                        localExtraMs = state?.localExtraMs ?: 0
                        localExtraDayKey = state?.localExtraDayKey
                    }
                    reevaluate()
                }
        }
    }

    /** 用最新的策略与授权重新决策一次（当前前台是谁就按谁判） */
    suspend fun reevaluate() {
        val at = System.currentTimeMillis()
        runCatching {
            mutex.withLock {
                usedCommittedMs = todayTotal()
                applyDecision(desiredPackage, at)
                publishState(at)
            }
        }.onFailure { Log.w(TAG, "重新决策失败", it) }
    }

    /* ---------------- 前台变化 ---------------- */

    fun onForegroundChanged(rawPackage: String?, at: Long) {
        val pkg = normalize(rawPackage)
        if (pkg == desiredPackage) {
            // 回到原应用的重复事件：撤销任何尚未确认的切换候选
            cancelPendingCandidate()
            return
        }

        // 快速通道：名单类「确定必拦」的应用（黑名单命中/白名单外）打开时零等待拦截。
        // 3 秒确认期对孩子而言是"打开被拉黑的应用还能玩一会儿"，明显破坏规则体验；
        // 名单判定不依赖用量，可零成本预判。瞬态窗口恰属被拉黑应用的误拦概率极低，
        // 且下方 recheck 会在 3 秒内发现假事件并自动回滚 —— 宁可短促误拦，不可延迟放行。
        val fastReason = pkg?.let { p ->
            guard?.let { g -> RuleJudge.listedBlockReason(g, currentOverrides(), p, at) }
        }
        if (pkg != null && fastReason != null) {
            cancelPendingCandidate()
            scope.launch {
                commitForegroundNow(pkg, at)
                scheduleForegroundRecheck(pkg)
            }
            return
        }

        // 常规路径：候选-核实（防瞬态窗口把会话切碎）
        pendingPackage = pkg
        pendingEventAt = at
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(FOREGROUND_CONFIRM_MS)
            commitForeground(pkg)
        }
    }

    private fun cancelPendingCandidate() {
        pendingJob?.cancel()
        pendingJob = null
        pendingPackage = null
    }

    /**
     * 提交一次前台切换（本函数不做核实，调用方负责确定最终包名）。
     * 快速通道与常规核实路径共用这一段提交逻辑，保证两条路径的行为一致。
     */
    private suspend fun commitForegroundNow(finalPkg: String, eventAt: Long) {
        mutex.withLock {
            if (finalPkg == desiredPackage) return@withLock
            desiredPackage = finalPkg
            // 会话结束时间取事件时间：新前台从事件那一刻就出现了
            closeOpenSession(eventAt)
            usedCommittedMs = todayTotal()
            applyDecision(finalPkg, System.currentTimeMillis())
            publishState(System.currentTimeMillis())
        }
    }

    /**
     * 快速通道的自愈回滚：拦截后核实真实前台，
     * 若已不是刚拦截的包（事件是瞬态窗口伪造的），回滚到真实前台并重新决策。
     * 正常情况（真打开了被拉黑应用）核实结果一致，本函数空转返回。
     */
    private suspend fun scheduleForegroundRecheck(expected: String) {
        delay(FOREGROUND_CONFIRM_MS)
        val verified = foregroundLookup?.let { normalize(it()) } ?: return
        if (verified == expected) return
        mutex.withLock {
            // 期间可能有更新的事件已提交（如孩子又切走了），不覆盖
            if (desiredPackage != expected) return@withLock
            desiredPackage = verified
            closeOpenSession(System.currentTimeMillis())
            usedCommittedMs = todayTotal()
            applyDecision(verified, System.currentTimeMillis())
            publishState(System.currentTimeMillis())
        }
    }

    /**
     * 确认并提交一次前台切换。调用前已等过 [FOREGROUND_CONFIRM_MS]。
     *
     * 核实规则：以无障碍窗口树的「当前活跃应用窗口」为准 ——
     *  - 核实结果 == 现有前台：候选是瞬态窗口伪造的，撤销，会话不动（碎片的主要来源）；
     *  - 核实失败（null）：退回按候选提交，保持旧行为 —— 宁可误切不可漏切；
     *  - 其余：按核实结果提交。
     */
    private suspend fun commitForeground(candidate: String?) {
        mutex.withLock {
            if (pendingPackage != candidate || candidate == desiredPackage) return@withLock
            val verified = foregroundLookup?.let { normalize(it()) }
            val finalPkg = when {
                verified == null -> candidate
                verified == desiredPackage -> {
                    pendingPackage = null
                    return@withLock
                }
                else -> verified
            }
            pendingPackage = null
            desiredPackage = finalPkg
            // 会话结束时间取事件时间：新前台从事件那一刻就出现了
            closeOpenSession(pendingEventAt)
            usedCommittedMs = todayTotal()
            applyDecision(finalPkg, System.currentTimeMillis())
            publishState(System.currentTimeMillis())
        }
    }

    fun onScreenInteractive(interactive: Boolean, at: Long) {
        if (screenInteractive == interactive) return
        screenInteractive = interactive
        scope.launch {
            mutex.withLock {
                closeOpenSession(at)
                usedCommittedMs = todayTotal()
                // 亮屏时前台往往还是同一个应用，系统不会再发窗口切换事件。
                // 不主动恢复计时的话，熄屏前后的这段时间会被整个漏掉。
                applyDecision(desiredPackage, at)
                publishState(at)
            }
        }
    }

    /* ---------------- 唯一决策入口 ---------------- */

    /**
     * 决定「现在该不该拦、该不该计时」。调用方必须已持有 [mutex]。
     *
     * 所有触发源都汇到这里，是为了不可能出现"某条路径漏判"——
     * 例如家长在拦截页上点了批准，那条路径也必须能收起遮罩并恢复计时。
     */
    private suspend fun applyDecision(pkg: String?, at: Long) {
        val overrides = currentOverrides()
        val policy = guard

        // 1. 家长点了「立即锁定」：与前台是谁无关，遮罩必须一直在
        if (overrides.forcedLocked) {
            stopTiming(at)
            showLockedOverlay(at)
            return
        }

        // 2. 没有前台应用（桌面/系统组件）、熄屏、或策略还没到手：不拦也不计时
        if (pkg == null || !screenInteractive || policy == null) {
            stopTiming(at)
            clearBlockedState()
            return
        }

        // 3. 判定
        val totalUsedNow = used()
        // 进行中的会话直接用内存值，避免每秒巡检都查一次库
        val appUsedRaw = if (openPackage == pkg) {
            appUsedMs()
        } else {
            usageRepository.usedTodayMsForApp(currentDayKey(), pkg)
        }
        // 逐应用「单日上限」按**基线后的净用量**判定（见 AppLimitBaselineEntity）：
        // 家长设「单日上限 2 分钟」指的是"从现在起还能用 2 分钟"，
        // 不是"今天总共再用 2 分钟" —— 否则规则一生效应用就被封死。
        // 计时与上报仍用原始值，账本口径不变；基线只影响第 6 条判定与拦截页展示。
        val appUsed = netAppUsedMs(policy, pkg, at, appUsedRaw)

        val reason = RuleJudge.decide(policy, overrides, pkg, at, totalUsedNow, appUsed)

        if (reason == null) {
            clearBlockedState()
            // 额度/时段将尽时的预警（提前 5 分钟，各类每天至多一次）
            maybeWarnBeforeLock(policy, overrides, pkg, at, totalUsedNow, appUsed)
            // 一旦不再被拦，「申请加时」的反馈就完成使命了。
            // 不清掉的话，界面上会一直挂着"家长已批准加时"，
            // 几天后再打开还以为刚被批准过。
            if (_state.value.timeRequestStatus != null) {
                _state.value = _state.value.copy(timeRequestStatus = null)
            }
            if (openPackage != pkg) startSession(pkg, at, appUsedRaw)
            return
        }

        // 4. 拦截。被拦期间不产生使用时长 —— 否则"被拦着不用"也在消耗额度
        stopTiming(at)
        block(pkg, reason, at, totalUsedNow, appUsed, overrides)
    }

    /**
     * 锁定临门预警：任一限制还剩不到 [WARN_BEFORE_LOCK_MS] 时弹一次提示。
     *
     * 三类各查各的（与 RuleJudge 的判定条件保持同一口径，避免"拦的时候没预警过"）：
     *  - total   当日总时长将耗尽
     *  - app:pkg 单应用单日上限将耗尽
     *  - window:pkg 允许时段即将结束（含跨零点时段）
     *
     * 剩余时间回升（家长加时/批授权）超过阈值后自动重新武装，下一次临近再提醒。
     * 调用条件：当前判定为放行（reason == null），否则已经拦了无需预警。
     */
    private suspend fun maybeWarnBeforeLock(
        policy: GuardPolicy,
        overrides: GuardOverrides,
        pkg: String,
        at: Long,
        totalUsedMs: Long,
        appUsedMs: Long,
    ) {
        if (overrides.isUnlocked(at) || !policy.policy.enabled) return

        val dayKey = PolicyRules.dayKey(policy.policy, at)
        if (warnDayKey != dayKey) {
            warnDayKey = dayKey
            warnedWarnKeys.clear()
        }

        val weekdayBit = DayKeys.weekdayBit(at)
        val rule = policy.rules[pkg]
        val ruleActiveToday = rule != null && rule.isActiveOn(weekdayBit)
        val appAllowed = pkg in overrides.allowedApps
        val exemptFromTotal = rule != null && rule.isExemptTotalOn(weekdayBit)

        fun warn(key: String, remainingMs: Long, text: String) {
            if (remainingMs <= 0) return
            if (remainingMs > WARN_BEFORE_LOCK_MS) {
                warnedWarnKeys.remove(key)
                return
            }
            if (!warnedWarnKeys.add(key)) return
            val minutes = ((remainingMs + 59_999) / 60_000).coerceAtLeast(1)
            val fullText = "$text（还剩约 $minutes 分钟）"
            Log.i(TAG, "锁定预警: $fullText")
            mainHandler.post {
                runCatching {
                    Toast.makeText(context, fullText, Toast.LENGTH_LONG).show()
                }
            }
        }

        if (!exemptFromTotal) {
            val remaining = RuleJudge.limitMs(policy, overrides, at) - totalUsedMs
            warn("total", remaining, "设备今日可用时间即将用完")
        }
        if (!appAllowed && rule != null && ruleActiveToday) {
            if (rule.dailyLimitMin > 0) {
                val remaining = rule.dailyLimitMin * 60_000L - appUsedMs
                warn("app:$pkg", remaining, "本应用今日使用时间即将用完")
            }
            if (rule.windows.isNotEmpty()) {
                val nowMin = DayKeys.minuteOfDay(at)
                val window = rule.windows.firstOrNull { it.contains(nowMin) }
                if (window != null) {
                    val remainingMin = window.minutesUntilEnd(nowMin)
                    warn("window:$pkg", remainingMin * 60_000L, "本应用的允许时段即将结束")
                }
            }
        }
    }

    /** 结束进行中的会话并把已收尾部分计入累计 */
    private suspend fun stopTiming(at: Long) {        if (openPackage == null) return
        closeOpenSession(at)
        usedCommittedMs = todayTotal()
    }

    private suspend fun startSession(pkg: String, at: Long, appCommittedMs: Long) {
        usageRepository.openSession(pkg, currentDayKey(), at)
        openPackage = pkg
        openStartTs = at
        openAppCommittedMs = appCommittedMs
    }

    private suspend fun closeOpenSession(at: Long) {
        if (openPackage == null) return
        usageRepository.closeCurrent(at)
        openPackage = null
        openStartTs = 0
        openAppCommittedMs = 0
    }

    /** 退出管控 / 设备被删等场景直接撤掉遮罩（服务层清理完状态后调用） */
    fun clearBlockedState() {
        if (_state.value.blockedPackage == null && !blockOverlay.isShowing) return
        blockOverlay.hide()
        _state.value = _state.value.copy(blockedPackage = null, blockedReason = null)
    }

    /* ---------------- 拦截 ---------------- */

    private suspend fun block(
        pkg: String,
        reason: String,
        at: Long,
        totalUsedMs: Long,
        appUsedMs: Long,
        overrides: GuardOverrides,
    ) {
        // 日志写入节流：无障碍事件抖动时不刷重复记录。
        // 注意只节流「记录」，不节流「拦截动作」——
        // 否则被节流的那几秒里应用会处于"既不记录也不拦截"的裸奔状态。
        if (pkg != lastBlockPackage ||
            reason != lastBlockReason ||
            at - lastBlockAt >= BLOCK_LOG_THROTTLE_MS
        ) {
            lastBlockPackage = pkg
            lastBlockReason = reason
            lastBlockAt = at
            runCatching { usageRepository.recordBlock(pkg, reason, at) }
                .onFailure { Log.w(TAG, "记录拦截日志失败", it) }
        }

        val policy = guard
        val limitMs = policy?.let { RuleJudge.limitMs(it, overrides, at) } ?: 0L

        // 详情文案按拦截原因区分：单应用超额时展示的是**这个应用自己**的
        // 用量与上限 —— 之前一律显示总时长，家长设了"单日上限 20 分钟"
        // 却看到"今日已用 2 小时 · 每日上限 40 分钟"，会以为规则没生效
        // （实际是孩子今天已经用满 20 分钟了）。
        // 用量是扣除基线后的净用量：限额生效（或被修改）后实际用掉的部分。
        val detailText = if (reason == BlockReason.APP_EXHAUSTED) {
            val appLimitMin = policy?.rules?.get(pkg)?.dailyLimitMin ?: 0
            "该应用已用 ${formatDuration(appUsedMs)} · 限额 $appLimitMin 分钟（不含设置前用量）"
        } else {
            "今日已用 ${formatDuration(totalUsedMs)} · 每日上限 ${formatDuration(limitMs)}"
        }

        blockOverlay.show(
            appLabel = appLabelOf(pkg),
            reasonText = BlockReason.describe(reason),
            detailText = detailText,
            // 只有"额度不够"这类情况才值得让孩子开口申请。
            // 名单命中是家长的明确决定，家长主动锁定更是，两者给申请入口
            // 都只会把功能变成讨价还价的噪音。
            canRequestTime = policy?.policy?.allowTimeRequest == true && requestableReason(reason),
            requestHint = _state.value.timeRequestStatus?.let(::requestHintText),
            onRequestTime = {
                requestTimeHandler?.invoke(scopeForReason(reason), pkg)
            },
            // 家长密码**内联在拦截页上**：孩子看得到输入框，验证逻辑由服务层执行
            onPinSubmit = { pin, level, onResult ->
                // handler 为 null（前台服务被 ROM 停掉又还没重启的窗口期）时
                // 必须立刻回结果，否则拦截页的「验证中…」会永远卡住（真机反馈）
                val handler = pinVerifyHandler
                if (handler != null) handler(pin, level, onResult)
                else onResult(false, level, "暂时无法验证，请稍后再试")
            },
            onPinAction = { action, onResult ->
                val handler = pinActionHandler
                if (handler != null) handler(action, onResult)
                else onResult("暂时无法执行，请稍后再试")
            },
            onGoHome = { performHome?.invoke() },
            deviceLocked = false,
        )

        // 强制回桌面单独节流：遮罩要一直显示，但跳转不能过密
        if (at - lastGoHomeAt >= GO_HOME_THROTTLE_MS) {
            lastGoHomeAt = at
            performHome?.invoke()
        }

        _state.value = _state.value.copy(blockedPackage = pkg, blockedReason = reason)
    }

    /** 家长主动锁定：不针对某个应用，遮罩覆盖整个设备 */
    private suspend fun showLockedOverlay(at: Long) {
        val pkg = desiredPackage
        if (pkg != null && pkg != lastBlockPackage) {
            runCatching { usageRepository.recordBlock(pkg, BlockReason.LOCKED, at) }
        }
        lastBlockPackage = pkg
        lastBlockReason = BlockReason.LOCKED

        blockOverlay.show(
            appLabel = pkg?.let(::appLabelOf) ?: "本设备",
            reasonText = BlockReason.describe(BlockReason.LOCKED),
            // 把"为什么加时/放行没用"直接说清楚，避免家长在锁定页上白忙一场
            detailText = "设备已被家长临时锁定，加时与放行在锁定期间无效；" +
                "输入密码后可退出管控，或请家长在控制端解除锁定",
            // 主动锁定是家长的明确决定，不给申请入口 —— 否则功能立刻变成讨价还价
            canRequestTime = false,
            requestHint = null,
            onRequestTime = null,
            // 但密码入口必须保留：家长自己就在这台设备旁边，
            // 锁定时不让他用密码解锁等于把自己也关在门外
            onPinSubmit = { pin, level, onResult ->
                // handler 为 null（前台服务被 ROM 停掉又还没重启的窗口期）时
                // 必须立刻回结果，否则拦截页的「验证中…」会永远卡住（真机反馈）
                val handler = pinVerifyHandler
                if (handler != null) handler(pin, level, onResult)
                else onResult(false, level, "暂时无法验证，请稍后再试")
            },
            onPinAction = { action, onResult ->
                val handler = pinActionHandler
                if (handler != null) handler(action, onResult)
                else onResult("暂时无法执行，请稍后再试")
            },
            onGoHome = { performHome?.invoke() },
            deviceLocked = true,
        )

        if (at - lastGoHomeAt >= GO_HOME_THROTTLE_MS) {
            lastGoHomeAt = at
            performHome?.invoke()
        }

        _state.value = _state.value.copy(
            blockedPackage = pkg ?: LOCKED_SENTINEL,
            blockedReason = BlockReason.LOCKED,
        )
    }

    /**
     * 哪些拦截原因值得开放「申请加时」入口。
     *
     *  - 额度类（总时长用尽 / 该应用时长用尽）：正是申请存在的意义
     *  - 时段类：孩子可能确实需要"就用 10 分钟"，也开放
     *  - 名单类：家长已经明确表态"这个应用不许用"，再让孩子申请等于
     *    把家长的决定降级成可谈判项
     *  - 主动锁定：家长此刻要求停用，谈都不用谈
     */
    private fun requestableReason(reason: String): Boolean = when (reason) {
        BlockReason.TOTAL_EXHAUSTED,
        BlockReason.APP_EXHAUSTED,
        BlockReason.OUT_OF_WINDOW,
        -> true

        else -> false
    }

    /**
     * 申请的范围。
     *
     * 总时长用尽时申请的是"加时"，而应用自身的时长/时段问题申请的应该是
     * "放行这个应用" —— 用错范围的话，家长批了也不会生效，
     * 这是一个纯粹由参数决定、却让孩子白等一场的错误。
     */
    private fun scopeForReason(reason: String): String = when (reason) {
        BlockReason.APP_EXHAUSTED,
        BlockReason.OUT_OF_WINDOW,
        -> GrantScope.APP_ALLOW

        else -> GrantScope.TOTAL_ADD
    }

    private fun requestHintText(status: String): String = when {
        status == "pending" -> "已发送申请，等待家长处理…"
        status.startsWith("approved") -> "家长已批准，正在恢复使用"
        status == "rejected" -> "家长未批准本次申请"
        status.startsWith("failed:") -> status.removePrefix("failed:")
        else -> "如需继续使用，请让家长在控制端临时放行"
    }

    /* ---------------- 加时申请 ---------------- */

    /** 由网络层在提交/收到审批结果后回填，拦截页据此给孩子反馈 */
    fun noteTimeRequest(status: String?) {
        _state.value = _state.value.copy(timeRequestStatus = status)
        // 状态文字变了，若遮罩正显示着就刷新一下文案
        val blocked = _state.value.blockedPackage
        val reason = _state.value.blockedReason
        if (blocked != null && reason != null && reason != BlockReason.LOCKED) {
            scope.launch { reevaluate() }
        }
    }

    /* ---------------- 秒级巡检 ---------------- */

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { Log.w(TAG, "巡检异常", it) }
                // 有应用在使用或被拦时按秒巡检（额度与时段判定必须及时）；
                // 其余情况降频，减少唤醒
                val active = _state.value.foregroundPackage != null || _state.value.blocking
                delay(if (active) FAST_TICK_MS else SLOW_TICK_MS)
            }
        }
    }

    private suspend fun tick() {
        val at = System.currentTimeMillis()
        tickCount += 1

        mutex.withLock {
            // 每 5 秒把进行中会话的时长刷进库，把进程崩溃的损失控制在 5 秒内
            if (openPackage != null && tickCount % 5 == 0) {
                usageRepository.refreshOpenDuration(at)
            }

            // 孩子可能一直停留在同一个应用里，此时没有任何前台切换事件，
            // 只能靠巡检发现「额度用尽」「时段结束」以及「家长刚批准了加时」
            applyDecision(openPackage ?: desiredPackage, at)

            publishState(at)
        }
    }

    /** 强制重新计算（家长在界面上点了刷新时用） */
    suspend fun refreshUsage() {
        mutex.withLock {
            usedCommittedMs = todayTotal()
            publishState()
        }
    }

    /* ---------------- 工具 ---------------- */

    private fun currentOverrides(): GuardOverrides {
        val policy = guard
        val dayKey = if (policy != null) {
            PolicyRules.dayKey(policy.policy, System.currentTimeMillis())
        } else {
            DayKeys.of(System.currentTimeMillis(), 0)
        }
        // 本地加时只在它所属的额度日里生效 —— 少了这一层，昨天的加时会一直留着
        val local = if (localExtraDayKey == dayKey) localExtraMs else 0L
        return GuardOverrides.of(locked, grants, dayKey, System.currentTimeMillis(), local)
    }

    /**
     * 当日已用总时长 = 已收尾会话累计 + 进行中会话的实时时长。
     * 前者来自本地库，后者用内存时间差补足，因此判定精确到秒且不必每秒写库。
     */
    private fun used(): Long = usedCommittedMs + inflightCountedMs()

    /** 当前应用当日已用时长 = 会话开始前累计 + 本段实时时长 */
    private fun appUsedMs(): Long = openAppCommittedMs + inflightMs()

    /**
     * 逐应用限额判定用的净用量 = 当日已用 − 生效基线。
     *
     * 基线规则（详见 [com.zzl.guardian.child.data.AppLimitBaselineEntity]）：
     *   - 该应用的限额第一次出现（或数值被修改）→ 基线记为当下已用，等于重新给满额度；
     *   - 跨天 → 基线归零，新的一天按全天用量正常累计；
     *   - 其余情况沿用已存基线，保证强杀进程、重启设备都无法重置额度。
     *
     * 只有「规则今天生效且设了上限」才需要基线；其余情况原样返回。
     * 基线读取走内存缓存：每秒巡检也会进到这里，不能每秒查一次库。
     */
    private suspend fun netAppUsedMs(policy: GuardPolicy, pkg: String, at: Long, appUsedRaw: Long): Long {
        val rule = policy.rules[pkg] ?: return appUsedRaw
        if (rule.dailyLimitMin <= 0 || !rule.isActiveOn(DayKeys.weekdayBit(at))) return appUsedRaw

        val dayKey = currentDayKey()
        var existing = baselineCache
        if (existing == null || existing.packageName != pkg ||
            existing.dayKey != dayKey || existing.limitMin != rule.dailyLimitMin
        ) {
            existing = runCatching { usageRepository.limitBaselineFor(pkg) }.getOrNull()
            baselineCache = existing
        }
        val baseline = when {
            existing == null -> appUsedRaw
            existing.dayKey != dayKey -> 0L
            existing.limitMin != rule.dailyLimitMin -> appUsedRaw
            else -> existing.baselineMs
        }
        if (existing == null || existing.dayKey != dayKey ||
            existing.limitMin != rule.dailyLimitMin || existing.baselineMs != baseline
        ) {
            runCatching { usageRepository.saveLimitBaseline(pkg, dayKey, rule.dailyLimitMin, baseline) }
                .onFailure { Log.w(TAG, "保存限额基线失败", it) }
            baselineCache = AppLimitBaselineEntity(pkg, dayKey, rule.dailyLimitMin, baseline)
        }
        return (appUsedRaw - baseline).coerceAtLeast(0L)
    }

    private fun inflightMs(): Long =
        if (openPackage == null) 0L
        else (System.currentTimeMillis() - openStartTs).coerceAtLeast(0)

    /**
     * 进行中会话里**应当计入当日总时长**的部分。
     *
     * 被标记「不计入总时长」的应用在用时，额度必须纹丝不动 —— 这正是该开关的语义。
     *
     * 刻意不去改 [inflightMs] 本身：它还被 [appUsedMs] 用来判该应用自己的单日上限，
     * 那里的时长必须照实计算，否则豁免会顺带把这个应用的单日上限一起废掉，
     * 开关就变成了"无限使用"。
     */
    private fun inflightCountedMs(): Long {
        val pkg = openPackage ?: return 0L
        val exempt = guard?.isExemptFromTotal(pkg, System.currentTimeMillis()) == true
        return if (exempt) 0L else inflightMs()
    }

    /**
     * 当日已收尾会话的累计用量，只统计**计入总时长**的部分。
     *
     * 必须整段剔除被标记「不计入总时长」的应用：只扣在途时长是不够的，
     * 已经收尾的历史会话同样不能算 —— 否则孩子一整天都在用豁免应用，
     * 额度还是会被悄悄扣光。
     */
    private suspend fun todayTotal(): Long {
        val exempt = guard?.exemptPackagesAt(System.currentTimeMillis()) ?: emptySet()
        return usageRepository.usedTodayMs(currentDayKey(), exempt)
    }

    private fun currentDayKey(): String {
        val current = guard
        return if (current != null) {
            PolicyRules.dayKey(current.policy, System.currentTimeMillis())
        } else {
            DayKeys.of(System.currentTimeMillis(), 0)
        }
    }

    private fun publishState(now: Long = System.currentTimeMillis()) {
        val current = guard
        val overrides = currentOverrides()
        _state.value = _state.value.copy(
            ready = initialized,
            foregroundPackage = openPackage,
            usedTodayMs = used(),
            limitMs = current?.let { RuleJudge.limitMs(it, overrides, now) } ?: 0L,
            extraMs = overrides.extraTotalMs,
            dayKey = currentDayKey(),
            enforcementEnabled = current?.policy?.enabled == true,
            policyVersion = current?.policy?.version ?: 0,
            listedAppCount = current?.listedPackages?.size ?: 0,
            ruleCount = current?.rules?.size ?: 0,
            locked = overrides.forcedLocked,
            unlocked = overrides.isUnlocked(now),
            allowedAppCount = overrides.allowedApps.size,
        )
    }

    private fun normalize(rawPackage: String?): String? {
        val pkg = rawPackage?.takeIf { it.isNotBlank() } ?: return null
        return if (pkg in ignoredPackages) null else pkg
    }

    private fun appLabelOf(pkg: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    private fun formatDuration(ms: Long): String {
        val totalMinutes = (ms / 60_000).coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours} 小时 ${minutes} 分钟" else "${minutes} 分钟"
    }

    private companion object {
        const val TAG = "GuardEngine"
        const val FAST_TICK_MS = 1_000L
        const val SLOW_TICK_MS = 5_000L
        const val BLOCK_LOG_THROTTLE_MS = 3_000L
        const val GO_HOME_THROTTLE_MS = 3_000L

        /**
         * 前台切换候选的确认窗口。瞬态窗口（权限弹窗/侧边栏/悬浮球）大多活不过
         * 这个时长；真正的应用切换只是让拦截与计时晚 3 秒生效，可以接受。
         */
        const val FOREGROUND_CONFIRM_MS = 3_000L

        /** 锁定临门预警提前量 */
        const val WARN_BEFORE_LOCK_MS = 5 * 60_000L

        /**
         * 「整个设备被锁定」时 [GuardState.blockedPackage] 的占位值。
         * 用哨兵而非 null，是为了让"没有前台应用"和"设备被锁定"这两种
         * 语义完全不同的状态在状态对象里可区分。
         */
        const val LOCKED_SENTINEL = "*device*"
    }
}
