package com.zzl.guardian.child.data

import com.zzl.guardian.data.api.ApiService
import com.zzl.guardian.data.api.AuditEventDto
import com.zzl.guardian.data.api.AuditEventsRequest
import com.zzl.guardian.data.api.AuditEventsResponse
import com.zzl.guardian.data.api.BlockLogDto
import com.zzl.guardian.data.api.BlockReportRequest
import com.zzl.guardian.data.api.DeviceStateDto
import com.zzl.guardian.data.api.GrantDto
import com.zzl.guardian.data.api.PinAttemptDto
import com.zzl.guardian.data.api.PinAttemptsRequest
import com.zzl.guardian.data.api.PolicyBundleDto
import com.zzl.guardian.data.api.TimeWindowDto
import com.zzl.guardian.data.api.UsageReportRequest
import com.zzl.guardian.data.api.UsageSessionDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PolicyRepository @Inject constructor(
    private val policyDao: PolicyDao,
    private val listDao: PolicyListDao,
    private val ruleDao: AppRuleDao,
    private val json: Json,
) {
    fun observe(deviceId: Long): Flow<PolicyEntity?> = policyDao.observe(deviceId)

    /**
     * 完整本地策略副本：基础策略 + 黑白名单 + 逐应用规则。
     * 合并成一个不可变对象后，引擎的规则判定就是纯内存计算，完全不碰数据库。
     */
    fun observeGuardPolicy(deviceId: Long): Flow<GuardPolicy?> = combine(
        policyDao.observe(deviceId),
        listDao.observe(deviceId),
        ruleDao.observe(deviceId),
    ) { policy, lists, rules ->
        policy?.let {
            GuardPolicy(
                policy = it,
                listMode = it.listMode,
                listedPackages = lists.mapTo(mutableSetOf()) { item -> item.packageName },
                rules = rules.associate { rule -> rule.packageName to rule.toGuardRule() },
            )
        }
    }

    suspend fun cached(deviceId: Long): PolicyEntity? = policyDao.get(deviceId)

    /** 用服务端返回的策略包整份覆盖本地副本（来自主动拉取或 WebSocket 推送） */
    suspend fun saveBundle(deviceId: Long, bundle: PolicyBundleDto) {
        policyDao.upsert(
            PolicyEntity(
                deviceId = deviceId,
                weekdayTotalMin = bundle.weekdayTotalMin,
                weekendTotalMin = bundle.weekendTotalMin,
                resetHour = bundle.resetHour,
                listMode = bundle.listMode,
                allowTimeRequest = bundle.allowTimeRequest,
                enabled = bundle.enabled,
                version = bundle.version,
                updatedAt = bundle.updatedAt,
                syncedAt = System.currentTimeMillis(),
            ),
        )

        listDao.clearForDevice(deviceId)
        if (bundle.listItems.isNotEmpty()) {
            listDao.insertAll(
                bundle.listItems
                    .filter { it.packageName.isNotBlank() }
                    .distinctBy { it.packageName }
                    .map { PolicyListEntity(deviceId, it.packageName, it.appLabel) },
            )
        }

        ruleDao.clearForDevice(deviceId)
        if (bundle.appRules.isNotEmpty()) {
            ruleDao.insertAll(
                bundle.appRules
                    .filter { it.packageName.isNotBlank() }
                    .distinctBy { it.packageName }
                    .map { rule ->
                        AppRuleEntity(
                            deviceId = deviceId,
                            packageName = rule.packageName,
                            appLabel = rule.appLabel,
                            dailyLimitMin = rule.dailyLimitMin,
                            timeWindowsJson = if (rule.timeWindows.isEmpty()) {
                                null
                            } else {
                                json.encodeToString(
                                    ListSerializer(TimeWindowDto.serializer()),
                                    rule.timeWindows,
                                )
                            },
                            weekdaysMask = rule.weekdaysMask,
                            enabled = rule.enabled,
                            exemptTotal = rule.exemptTotal,
                        )
                    },
            )
        }
    }

    suspend fun clear() {
        policyDao.clear()
        listDao.clearAll()
        ruleDao.clearAll()
    }

    /* ---------------- 转换 ---------------- */

    private fun AppRuleEntity.toGuardRule() = GuardAppRule(
        packageName = packageName,
        dailyLimitMin = dailyLimitMin,
        windows = parseWindows(timeWindowsJson),
        weekdaysMask = weekdaysMask,
        enabled = enabled,
        exemptTotal = exemptTotal,
    )

    /** 解析失败一律当作"没有时段限制"，避免一条脏数据把应用永久锁死 */
    private fun parseWindows(raw: String?): List<TimeWindow> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(TimeWindowDto.serializer()), raw)
                .mapNotNull { window ->
                    val start = DayKeys.parseHhMm(window.start)
                    val end = DayKeys.parseHhMm(window.end)
                    if (start == null || end == null) null else TimeWindow(start, end)
                }
        }.getOrDefault(emptyList())
    }
}

/** 由策略推导出的运行时参数。集中放在这里，避免各处重复算错归日点。 */
object PolicyRules {

    /** 该时刻适用的每日总时长（毫秒） */
    fun dailyLimitMs(policy: PolicyEntity, at: Long): Long {
        val minutes = if (DayKeys.isWeekendAt(at)) policy.weekendTotalMin else policy.weekdayTotalMin
        return minutes.coerceAtLeast(0) * 60_000L
    }

    fun dayKey(policy: PolicyEntity, at: Long): String = DayKeys.of(at, policy.resetHour)
}

/**
 * 设备状态与临时授权的本地副本。
 *
 * 服务端是唯一真相，这里只是它的一份离线可用快照。
 * 因此**写入一律是整份覆盖**而不是增量合并：增量一旦丢一条就会永久偏离，
 * 整份覆盖天然自愈 —— 这正是"最终一致"最省心的实现方式。
 */
@Singleton
class DeviceStateRepository @Inject constructor(
    private val grantDao: GrantDao,
    private val stateDao: DeviceStateDao,
    private val commandLogDao: CommandLogDao,
) {

    /** 本地授权 id 序列：从 -1 递减，见 [nextLocalGrantId] */
    private val localGrantIdSeq = AtomicLong(0)

    fun observeGrants(deviceId: Long): Flow<List<GrantEntity>> = grantDao.observe(deviceId)

    fun observeState(deviceId: Long): Flow<DeviceStateEntity?> = stateDao.observe(deviceId)

    suspend fun grants(deviceId: Long): List<GrantEntity> = grantDao.getAll(deviceId)

    suspend fun state(deviceId: Long): DeviceStateEntity? = stateDao.get(deviceId)

    /**
     * 用服务端的权威状态整份覆盖本地副本。
     *
     * 版本号用于识别乱序到达的旧状态：「撤销授权」与「新增授权」两个推送
     * 顺序颠倒的话，本地会保留一个早已撤销的授权。旧的直接丢弃即可。
     *
     * ⚠️ **本地加时必须原样带过去。** 这个实体是整行替换，
     * 漏掉 `localExtraMs` 就等于"每次对账都把家长刚在设备上加的时间清掉"。
     */
    suspend fun applyState(deviceId: Long, state: DeviceStateDto, dayKey: String) {
        val current = stateDao.get(deviceId)
        if (current != null && state.stateVersion < current.stateVersion) return

        // 本地密码落的授权（source=local_pin）服务器并不知道，整份覆盖会把它冲掉——
        // 真机表现：家长在拦截页「放行当前应用 30 分钟」，下一次对账（周期 30 秒、
        // 每次切应用都会触发）授权就被清空，几十秒后又被拦回来。
        // 覆盖前先把未过期的本地授权捞出来，对账后原样放回；生命周期由 expireAt 自管。
        val now = System.currentTimeMillis()
        val survivingLocals = grantDao.getAll(deviceId)
            .filter { it.source == SOURCE_LOCAL_PIN && (it.expireAt == null || it.expireAt > now) }

        stateDao.upsert(
            DeviceStateEntity(
                deviceId = deviceId,
                locked = state.locked,
                stateVersion = state.stateVersion,
                syncedAt = System.currentTimeMillis(),
                localExtraMs = current?.localExtraMs ?: 0,
                localExtraDayKey = current?.localExtraDayKey ?: dayKey,
            ),
        )

        grantDao.clearForDevice(deviceId)
        val serverGrants = state.grants
            .distinctBy { it.id }
            .map { it.toEntity(deviceId) }
        val merged = serverGrants + survivingLocals.distinctBy { it.grantId }
        if (merged.isNotEmpty()) {
            grantDao.upsertAll(merged)
        }
    }

    /**
     * 本地授权的独立 id。服务端授权 id 是自增正数，本地用递减负数，
     * 永不相撞 —— 之前本地授权一律用默认 id=0，第二条会把第一条顶掉
     * （加时落的应用放行和「放行当前应用」互相覆盖）。
     */
    fun nextLocalGrantId(): Long = localGrantIdSeq.decrementAndGet()

    /**
     * 仅更新锁定状态（走「立即锁定」指令时用）。
     * 不动版本号：下一次对账会带回权威版本并校正，这里只需让界面与拦截立刻生效。
     */
    suspend fun setLocked(deviceId: Long, locked: Boolean) {
        val current = stateDao.get(deviceId)
        stateDao.upsert(
            DeviceStateEntity(
                deviceId = deviceId,
                locked = locked,
                stateVersion = current?.stateVersion ?: 1,
                syncedAt = System.currentTimeMillis(),
                localExtraMs = current?.localExtraMs ?: 0,
                localExtraDayKey = current?.localExtraDayKey,
            ),
        )
    }

    /**
     * 家长在设备上用离线密码加时。
     *
     * 同一额度日内**累加**而不是覆盖：家长先加 15 分钟发现不够、又加 30 分钟，
     * 期望的是 45 分钟。跨过归日点后从零开始 —— 昨天的加时不能留到今天。
     */
    suspend fun addLocalExtra(deviceId: Long, minutes: Int, dayKey: String) {
        if (minutes <= 0) return
        val current = stateDao.get(deviceId)
        val base = if (current?.localExtraDayKey == dayKey) current.localExtraMs else 0L

        stateDao.upsert(
            DeviceStateEntity(
                deviceId = deviceId,
                locked = current?.locked ?: false,
                stateVersion = current?.stateVersion ?: 1,
                syncedAt = System.currentTimeMillis(),
                localExtraMs = base + minutes * 60_000L,
                localExtraDayKey = dayKey,
            ),
        )
    }

    /** 读取当日的本地加时；跨过归日点自动返回 0 */
    suspend fun localExtraMs(deviceId: Long, dayKey: String): Long {
        val current = stateDao.get(deviceId) ?: return 0
        return if (current.localExtraDayKey == dayKey) current.localExtraMs else 0
    }

    suspend fun upsertGrant(deviceId: Long, grant: GrantDto) {
        grantDao.upsert(grant.toEntity(deviceId))
    }

    suspend fun removeGrant(deviceId: Long, grantId: Long) {
        grantDao.delete(deviceId, grantId)
    }

    /* ---------------- 指令台账 ---------------- */

    /** 指令是否已经执行过。指令可能被重复投递（长连接一次 + 补发拉取一次） */
    suspend fun alreadyExecuted(commandId: String): Boolean = commandLogDao.count(commandId) > 0

    suspend fun recordExecuted(commandId: String, type: String, result: String) {
        commandLogDao.insert(
            CommandLogEntity(
                commandId = commandId,
                type = type,
                result = result,
                executedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun recentCommands(limit: Int = 20): List<CommandLogEntity> = commandLogDao.recent(limit)

    /** 台账保留 7 天：够覆盖任何合理的补发窗口，又不会无限增长 */
    suspend fun prune() {
        val now = System.currentTimeMillis()
        commandLogDao.pruneOlderThan(now - 7L * 24 * 3600 * 1000)
        grantDao.pruneExpired(now - 7L * 24 * 3600 * 1000)
    }

    suspend fun clear() {
        grantDao.clearAll()
        stateDao.clear()
    }

    companion object {
        /** 被控端用离线密码本地落的授权标记（服务器不知道，对账时必须保留） */
        const val SOURCE_LOCAL_PIN = "local_pin"
    }

    private fun GrantDto.toEntity(deviceId: Long) = GrantEntity(
        grantId = id,
        deviceId = deviceId,
        scope = scope,
        packageName = packageName,
        appLabel = appLabel,
        extraMinutes = extraMinutes,
        dayKey = dayKey,
        expireAt = expireAt,
        source = source,
        createdAt = createdAt,
    )
}

@Singleton
class UsageRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val blockLogDao: BlockLogDao,
    private val pinAttemptDao: PinAttemptDao,
    private val auditEventDao: AuditEventDao,
    private val limitBaselineDao: AppLimitBaselineDao,
    private val api: ApiService,
) {

    /** 进程被杀导致会话未收尾时，按已记录的时长补齐 */
    suspend fun recoverDanglingSessions() = sessionDao.closeDangling()

    suspend fun openSession(packageName: String, dayKey: String, at: Long) {
        sessionDao.open(
            SessionEntity(
                clientKey = UUID.randomUUID().toString(),
                packageName = packageName,
                startTs = at,
                dayKey = dayKey,
            ),
        )
    }

    suspend fun currentOpen(): SessionEntity? = sessionDao.currentOpen()

    /** 定期刷新当前会话时长，把进程崩溃的损失限制在刷新间隔内 */
    suspend fun refreshOpenDuration(at: Long) {
        val open = sessionDao.currentOpen() ?: return
        sessionDao.refreshDuration(open.id, (at - open.startTs).coerceAtLeast(0))
    }

    suspend fun closeCurrent(at: Long) {
        val open = sessionDao.currentOpen() ?: return
        sessionDao.close(open.id, at, (at - open.startTs).coerceAtLeast(0))
    }

    /**
     * 当日**计入总时长**的用量。
     *
     * @param excludePackages 被标记「不计入总时长」的应用。
     *   空集合必须走另一条查询：SQLite 不接受空的 `IN ()`，
     *   Room 会原样拼出 `NOT IN ()` 而直接报语法错。
     */
    suspend fun usedTodayMs(dayKey: String, excludePackages: Set<String> = emptySet()): Long =
        if (excludePackages.isEmpty()) {
            sessionDao.totalForDay(dayKey)
        } else {
            sessionDao.totalForDayExcluding(dayKey, excludePackages.toList())
        }

    suspend fun usedTodayMsForApp(dayKey: String, packageName: String): Long =
        sessionDao.totalForApp(dayKey, packageName)

    /** 逐应用限额的生效基线（见 [AppLimitBaselineEntity] 的语义说明） */
    suspend fun limitBaselineFor(packageName: String): AppLimitBaselineEntity? =
        limitBaselineDao.get(packageName)

    suspend fun saveLimitBaseline(
        packageName: String,
        dayKey: String,
        limitMin: Int,
        baselineMs: Long,
    ) {
        limitBaselineDao.upsert(
            AppLimitBaselineEntity(
                packageName = packageName,
                dayKey = dayKey,
                limitMin = limitMin,
                baselineMs = baselineMs,
            ),
        )
    }

    suspend fun clearLimitBaseline(packageName: String) = limitBaselineDao.delete(packageName)

    suspend fun sessionsForDay(dayKey: String): List<SessionEntity> = sessionDao.sessionsForDay(dayKey)

    suspend fun recordBlock(packageName: String, reason: String, at: Long) {
        blockLogDao.insert(
            BlockLogEntity(
                // 去重键必须在本地生成：服务端要靠它认出重传的同一条记录
                clientKey = UUID.randomUUID().toString(),
                packageName = packageName,
                reason = reason,
                ts = at,
            ),
        )
    }

    fun observeRecentBlocks(limit: Int = 20): Flow<List<BlockLogEntity>> = blockLogDao.observeRecent(limit)

    /**
     * 上报未上传的会话。
     * 只有服务端明确受理（accepted + duplicated）后才标记为已上传，
     * 保证「响应丢失 → 重传」不会丢数据，也不会因为重复上报而重复计费。
     */
    suspend fun uploadPending(token: String, deviceId: Long): Int = withContext(Dispatchers.IO) {
        val pending = sessionDao.pendingUpload(BATCH_SIZE)
        if (pending.isEmpty()) return@withContext 0

        val response = runCatching {
            api.reportUsage(
                authorization = "Bearer $token",
                deviceId = deviceId,
                body = UsageReportRequest(pending.map { it.toDto() }),
            )
        }.getOrNull() ?: return@withContext 0

        if (!response.ok) return@withContext 0

        val handled = response.accepted + response.duplicated
        if (handled > 0) {
            sessionDao.markUploaded(pending.take(handled).map { it.id })
        }
        response.accepted
    }

    /**
     * 上报未上传的拦截记录，与使用会话同一套幂等思路。
     * 家长正是靠这批数据知道"孩子被拦了几次、因为什么"，
     * 缺一条都可能让家长误判管控是否有效。
     */
    suspend fun uploadPendingBlocks(token: String, deviceId: Long): Int = withContext(Dispatchers.IO) {
        val pending = blockLogDao.pendingUpload(BLOCK_BATCH)
        if (pending.isEmpty()) return@withContext 0

        val response = runCatching {
            api.reportBlocks(
                authorization = "Bearer $token",
                deviceId = deviceId,
                body = BlockReportRequest(
                    pending.map {
                        BlockLogDto(
                            packageName = it.packageName,
                            reason = it.reason,
                            ts = it.ts,
                            clientKey = it.clientKey,
                        )
                    },
                ),
            )
        }.getOrNull() ?: return@withContext 0

        if (!response.ok) return@withContext 0

        val handled = response.accepted + response.duplicated
        if (handled > 0) {
            blockLogDao.markUploaded(pending.take(handled).map { it.id })
        }
        response.accepted
    }

    /** 已成功上报且超过 3 天的会话明细可以清理，避免本地库无限增长 */
    suspend fun pruneOldUploaded() {
        sessionDao.pruneUploaded(System.currentTimeMillis() - 3L * 24 * 3600 * 1000)
    }

    /** 拦截记录在本地保留 30 天，足够家长回看，又不至于无限增长 */
    suspend fun pruneOldBlockLogs() {
        blockLogDao.pruneOlderThan(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
    }

    /* ---------------- 离线密码尝试（M5） ---------------- */

    /**
     * 记录一次密码尝试。
     *
     * **成功与失败都记** —— 只记失败的话，"孩子试对了密码"这件事反而是不可见的，
     * 而那恰恰是家长最需要知道的。
     */
    suspend fun recordPinAttempt(level: Int, success: Boolean, source: String) {
        pinAttemptDao.insert(
            PinAttemptEntity(
                clientKey = UUID.randomUUID().toString(),
                level = level,
                success = success,
                source = source,
                ts = System.currentTimeMillis(),
            ),
        )
    }

    fun observeFailedPinAttempts(): Flow<Int> = pinAttemptDao.observeFailedCount()

    fun observeRecentPinAttempts(limit: Int = 20): Flow<List<PinAttemptEntity>> =
        pinAttemptDao.observeRecent(limit)

    /** 上报未上传的密码尝试记录，与使用会话同一套幂等思路 */
    suspend fun uploadPendingPinAttempts(token: String, deviceId: Long): Int = withContext(Dispatchers.IO) {
        val pending = pinAttemptDao.pendingUpload(PIN_BATCH)
        if (pending.isEmpty()) return@withContext 0

        val response = runCatching {
            api.reportPinAttempts(
                authorization = "Bearer $token",
                body = PinAttemptsRequest(
                    pending.map {
                        PinAttemptDto(
                            level = it.level,
                            success = it.success,
                            source = it.source,
                            ts = it.ts,
                            clientKey = it.clientKey,
                        )
                    },
                ),
            )
        }.getOrNull() ?: return@withContext 0

        if (!response.ok) return@withContext 0

        val handled = response.accepted + response.duplicated
        if (handled > 0) {
            pinAttemptDao.markUploaded(pending.take(handled).map { it.id })
        }
        response.accepted
    }

    /** 密码尝试记录保留 90 天：这是安全事件，比普通使用记录更值得留存 */
    suspend fun pruneOldPinAttempts() {
        pinAttemptDao.pruneOlderThan(System.currentTimeMillis() - 90L * 24 * 3600 * 1000)
    }

    /* ---------------- 本地审计事件（M6） ---------------- */

    /**
     * 记录一条家长应该知道的本地事件。
     *
     * 这是"被控端视角的审计"：权限被关、密码被试、设备管理器被取消激活 ——
     * 这些事只在设备本地发生，服务端不可能自己知道。
     *
     * 不断网判断、不尝试立即上报：统一交给心跳周期的批量补传。
     * 理由是这类事件**宁可晚报、不可漏报**，而"边发生边上报"在网络抖动时
     * 反而更容易丢（请求失败又没落库）。
     */
    suspend fun recordAudit(
        action: String,
        detail: String? = null,
        level: String = AuditLevel.INFO,
    ) {
        auditEventDao.insert(
            AuditEventEntity(
                clientKey = UUID.randomUUID().toString(),
                action = action,
                detail = detail,
                level = level,
                ts = System.currentTimeMillis(),
            ),
        )
    }

    fun observeRecentAuditEvents(limit: Int = 30): Flow<List<AuditEventEntity>> =
        auditEventDao.observeRecent(limit)

    fun observePendingAuditCount(): Flow<Int> = auditEventDao.observePendingCount()

    /** 上报未上传的审计事件，与服务会话同一套幂等思路（clientKey + 唯一索引） */
    suspend fun uploadPendingAuditEvents(token: String, deviceId: Long): Int = withContext(Dispatchers.IO) {
        val pending = auditEventDao.pendingUpload(AUDIT_BATCH)
        if (pending.isEmpty()) return@withContext 0

        val response = runCatching {
            api.reportAuditEvents(
                authorization = "Bearer $token",
                body = AuditEventsRequest(
                    pending.map {
                        AuditEventDto(
                            clientKey = it.clientKey,
                            action = it.action,
                            detail = it.detail,
                            level = it.level,
                            ts = it.ts,
                        )
                    },
                ),
            )
        }.getOrNull() ?: return@withContext 0

        if (!response.ok) return@withContext 0

        // 只有服务端明确受理（含"已收到过"）才标记已上传：
        // 响应丢失时保持 pending，下次重传（服务端按 clientKey 去重）
        val handled = response.accepted + response.duplicated
        if (handled > 0) {
            auditEventDao.markUploaded(pending.take(handled).map { it.id })
        }
        response.accepted
    }

    /** 审计事件保留 180 天，与服务端 audit.pruneAudit 的保留期一致 */
    suspend fun pruneOldAuditEvents() {
        auditEventDao.pruneOlderThan(System.currentTimeMillis() - 180L * 24 * 3600 * 1000)
    }

    private companion object {
        const val BATCH_SIZE = 50
        const val BLOCK_BATCH = 100
        const val PIN_BATCH = 100
        const val AUDIT_BATCH = 100
    }
}

/**
 * 审计事件的级别常量。
 *
 * 与服务端 `audit.js` 的 `levelOf` 保持同一套取值 ——
 * 两端对"什么算告警"必须一致，否则家长在设备界面看到的是普通记录、
 * 在控制端却变成红色告警（或者反过来被淹没）。
 */
object AuditLevel {
    const val INFO = "info"
    const val NOTICE = "notice"
    const val WARN = "warn"
}

/** 本地审计事件的动作码。与服务端 audit.js 的 ACTION_TEXT 键一一对应。 */
object AuditAction {
    const val PERMISSION_LOST = "permission.lost"
    const val ADMIN_DISABLED = "device.admin_disabled"
    const val UNINSTALL_ATTEMPT = "device.uninstall_attempt"
    const val GUARD_EXITED = "device.guard_exited"
    const val PIN_FAILED = "pin.attempt.failed"
    const val PIN_SUCCESS = "pin.attempt.success"
    const val PIN_LOCKED = "pin.locked"
    const val SCREENSHOT_UPLOAD = "screenshot.upload"

    /**
     * 截屏**采集失败**（没有生成图片）。
     *
     * 必须与 [SCREENSHOT_UPLOAD] 分开：家长在审计里看到「设备上传截屏」
     * 却在截图列表里找不到图，曾经被误读成"传了但控制端不显示"——
     * 真相是根本没采集成功。拆成独立动作码 + warn 级，
     * 让「为什么没有截图」在审计列表里一眼可见。
     */
    const val SCREENSHOT_FAILED = "screenshot.failed"
}

private fun SessionEntity.toDto() = UsageSessionDto(
    clientKey = clientKey,
    packageName = packageName,
    startTs = startTs,
    endTs = endTs,
    durationMs = durationMs,
    dayKey = dayKey,
)
