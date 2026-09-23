package com.zzl.guardian.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class HealthResponse(
    val ok: Boolean = false,
    val service: String? = null,
    val serverTime: Long = 0,
)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
)

@Serializable
data class RegisterResponse(
    val userId: Long,
    val username: String,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val token: String,
    val userId: Long,
    val username: String,
    val role: String,
)

@Serializable
data class PairCodeResponse(
    val code: String,
    val expiresAt: Long,
    val ttlSeconds: Int,
)

@Serializable
data class PairClaimRequest(
    val code: String,
    @SerialName("childUuid") val childUuid: String,
    val name: String? = null,
    val model: String? = null,
    val androidVer: Int? = null,
)

@Serializable
data class PairClaimResponse(
    val deviceId: Long,
    val token: String,
    val role: String,
)

/** 被控端本机权限与管控组件的健康状态 */
@Serializable
data class HealthState(
    val accessibility: Boolean = false,
    val usageAccess: Boolean = false,
    val overlay: Boolean = false,
    val deviceAdmin: Boolean = false,
)

@Serializable
data class DeviceView(
    val id: Long,
    val name: String,
    val model: String? = null,
    val androidVersion: Int? = null,
    val adminMode: String? = null,
    /** 归一化后的实际生效模式：device_owner | device_admin | none */
    val effectiveAdminMode: String = "none",
    val capabilities: AdminModeCapabilityDto? = null,
    val uninstallBlocked: Boolean = false,
    /** 离线密码是否已设置 —— 没设置的话家长忘记密码时无法自救 */
    val pinReady: Boolean = false,
    val online: Boolean = false,
    val lastSeen: Long? = null,
    val foregroundPackage: String? = null,
    val health: HealthState? = null,
    val keepalive: KeepaliveState? = null,
    /** 家长「立即锁定」的持久状态 */
    val locked: Boolean = false,
    /**
     * 桌面图标是否已被隐藏。
     *
     * 列表上要显式标出来 —— 图标隐藏是"看不见的"，不标的话
     * 家长会忘了自己藏过，然后因为"找不到这个 App"而困惑。
     */
    val iconHidden: Boolean = false,
    val createdAt: Long = 0,
)

@Serializable
data class DeviceListResponse(
    val devices: List<DeviceView> = emptyList(),
)

@Serializable
data class HeartbeatRequest(
    val foregroundPackage: String? = null,
    val remainingMs: Long? = null,
    val health: HealthState? = null,
    /** 加固状态由设备上报 —— 只有设备自己能知道"当前是不是 Device Owner" */
    val hardening: HardeningReport? = null,
    val keepalive: KeepaliveState? = null,
)

@Serializable
data class OkResponse(
    val ok: Boolean = false,
    /** 部分接口会返回：指令是否已实时送达在线设备 */
    val delivered: Boolean? = null,
    /** 走指令队列的接口会返回指令 ID，供控制端追踪执行状态 */
    val commandId: String? = null,
)

/* ---------------- 策略 ---------------- */

@Serializable
data class TimeWindowDto(
    /** HH:MM。end <= start 表示跨零点，例如 22:00 - 07:00 */
    val start: String,
    val end: String,
)

@Serializable
data class PolicyListItemDto(
    val packageName: String,
    val appLabel: String? = null,
)

@Serializable
data class AppRuleDto(
    val packageName: String,
    val appLabel: String? = null,
    /** 0 表示不单独限制 */
    val dailyLimitMin: Int = 0,
    val timeWindows: List<TimeWindowDto> = emptyList(),
    /** 生效星期位掩码：bit0 = 周一 … bit6 = 周日 */
    val weekdaysMask: Int = 127,
    val enabled: Boolean = true,
    /**
     * 用时不计入当日总时长：总时长耗尽后该应用仍可打开。
     * 缺省 false —— 老设备收到不带该字段的策略时，行为与从前完全一致。
     */
    val exemptTotal: Boolean = false,
)

/**
 * 完整策略包。
 *
 * 基础策略、黑白名单、逐应用规则一次性下发 —— 被控端必须能在单次请求里
 * 拿到完整的本地规则副本，否则会出现"只同步了一半"的中间态。
 */
@Serializable
data class PolicyBundleDto(
    val deviceId: Long = 0,
    val weekdayTotalMin: Int = 60,
    val weekendTotalMin: Int = 120,
    val resetHour: Int = 0,
    val listMode: String = "blacklist",
    val allowTimeRequest: Boolean = true,
    val enabled: Boolean = true,
    val version: Int = 1,
    val updatedAt: Long = 0,
    val listItems: List<PolicyListItemDto> = emptyList(),
    val appRules: List<AppRuleDto> = emptyList(),
    /** 仅写接口响应携带：策略是否已实时推送到在线设备 */
    val delivered: Boolean? = null,
)

@Serializable
data class PolicyUpdateRequest(
    val weekdayTotalMin: Int,
    val weekendTotalMin: Int,
    val resetHour: Int,
    val enabled: Boolean,
    /** 是否允许孩子申请加时；null 表示不改动 */
    val allowTimeRequest: Boolean? = null,
)

@Serializable
data class PolicyListsRequest(
    val listMode: String,
    val items: List<PolicyListItemDto>,
)

@Serializable
data class PolicyAppRulesRequest(
    val rules: List<AppRuleDto>,
)

/**
 * 名单与规则一次性提交。
 * 应用管控界面必须用它而不是分两次调 lists / app-rules ——
 * 分两次会在服务端留下"名单换了但规则没换"的半套配置，被控端会照着它执行管控。
 */
@Serializable
data class PolicyBundleUpdateRequest(
    val listMode: String,
    val items: List<PolicyListItemDto>,
    val rules: List<AppRuleDto>,
)

/* ---------------- 已安装应用清单 ---------------- */

@Serializable
data class InstalledAppDto(
    val packageName: String,
    val appLabel: String? = null,
    val isSystem: Boolean = false,
)

@Serializable
data class InstalledAppsRequest(
    val apps: List<InstalledAppDto>,
)

@Serializable
data class InstalledAppsResponse(
    val apps: List<InstalledAppDto> = emptyList(),
    val lastReportedAt: Long? = null,
)

@Serializable
data class InstalledAppsReportResponse(
    val ok: Boolean = false,
    val accepted: Int = 0,
)

/* ---------------- 使用记录 ---------------- */

@Serializable
data class UsageSessionDto(
    val clientKey: String,
    val packageName: String,
    val startTs: Long,
    val endTs: Long? = null,
    val durationMs: Long,
    val dayKey: String,
)

@Serializable
data class UsageReportRequest(
    val sessions: List<UsageSessionDto>,
)

@Serializable
data class UsageReportResponse(
    val ok: Boolean = false,
    val accepted: Int = 0,
    val duplicated: Int = 0,
    val rejected: Int = 0,
)

@Serializable
data class AppUsageDto(
    val packageName: String,
    val totalMs: Long = 0,
    val openCount: Int = 0,
)

@Serializable
data class DailyUsageDto(
    val date: String = "",
    val totalMs: Long = 0,
    val apps: List<AppUsageDto> = emptyList(),
)

@Serializable
data class ApiError(
    val error: String? = null,
    val message: String? = null,
)

/* ---------------- 指令队列（M4） ---------------- */

/**
 * 一条下行指令。
 *
 * 服务端「先落库再推送」，因此被控端在线时通过长连接、离线时通过拉取接口
 * 拿到的是同一种结构，无需两套解析。
 */
@Serializable
data class CommandDto(
    val commandId: String = "",
    val deviceId: Long = 0,
    val type: String = "",
    val payload: JsonElement? = null,
    /** pending | sent | done | failed | expired */
    val status: String = "pending",
    val expireAt: Long? = null,
    val createdAt: Long = 0,
    val pushedAt: Long? = null,
    val executedAt: Long? = null,
    val result: JsonElement? = null,
)

@Serializable
data class CommandListResponse(
    val commands: List<CommandDto> = emptyList(),
    val serverTime: Long = 0,
    val locked: Boolean = false,
)

@Serializable
data class CommandAckRequest(
    val status: String,
    val detail: JsonElement? = null,
)

@Serializable
data class CommandAckResponse(
    val ok: Boolean = false,
    val command: CommandDto? = null,
    /** 指令不存在时服务端返回它，被控端据此停止重试 */
    val ignored: Boolean? = null,
)

/* ---------------- 临时授权（M4） ---------------- */

@Serializable
data class GrantDto(
    val id: Long = 0,
    /** total_add（加时）| app_allow（单应用放行）| unlock（临时总解封） */
    val scope: String = "",
    val packageName: String? = null,
    val appLabel: String? = null,
    val extraMinutes: Int? = null,
    /** 加时类授权绑定的「额度日」；跨过归日点后不再叠加 */
    val dayKey: String? = null,
    val expireAt: Long? = null,
    val source: String = "manual",
    val createdAt: Long = 0,
    val revoked: Boolean = false,
    /** 仅在授权列表接口中带出：是否仍在生效 */
    val active: Boolean = false,
)

@Serializable
data class GrantCreateRequest(
    val scope: String,
    val packageName: String? = null,
    val appLabel: String? = null,
    val extraMinutes: Int? = null,
    val ttlMinutes: Int? = null,
)

@Serializable
data class GrantCreateResponse(
    val ok: Boolean = false,
    val grant: GrantDto? = null,
    val delivered: Boolean? = null,
    val notice: String? = null,
)

@Serializable
data class GrantListResponse(
    val grants: List<GrantDto> = emptyList(),
    val active: List<GrantDto> = emptyList(),
)

/**
 * 被控端对账用的设备状态。
 *
 * 锁定与全部生效授权一次性拿全 —— 这是"最终一致"的兜底通道：
 * 断网期间漏掉的推送、丢失回执的指令，都会在下一次对账时被纠正。
 */
@Serializable
data class DeviceStateDto(
    val deviceId: Long = 0,
    val locked: Boolean = false,
    val lockedAt: Long? = null,
    val grants: List<GrantDto> = emptyList(),
    /**
     * 离线密码备份。换机恢复与家长远程重置都靠它 ——
     * 放进对账通道，两条路径共用同一份数据，不需要额外逻辑。
     */
    val pins: PinBackupDto? = null,
    /** 家长侧期望的加固配置（能否做到由设备本地的既成事实决定） */
    val hardening: HardeningSnapshot? = null,
    /**
     * 桌面图标是否应被隐藏。
     *
     * 它是**持续状态**而不是一次性指令，因此必须随每次对账一起下发 ——
     * 只靠指令的话，孩子重启一次平板图标就回来了。
     */
    val iconHidden: Boolean = false,
    val stateVersion: Int = 1,
    val serverTime: Long = 0,
)

@Serializable
data class HardeningSnapshot(
    val adminMode: String? = null,
    val deviceOwner: Boolean = false,
    val uninstallBlocked: Boolean = false,
)

/* ---------------- 加时申请（M4） ---------------- */

@Serializable
data class TimeRequestCreateRequest(
    val scope: String,
    val packageName: String? = null,
    val appLabel: String? = null,
    val requestMin: Int,
    val reason: String? = null,
)

@Serializable
data class TimeRequestDto(
    val id: Long = 0,
    val deviceId: Long = 0,
    val deviceName: String? = null,
    val scope: String = "total_add",
    val packageName: String? = null,
    val appLabel: String? = null,
    val requestMin: Int = 0,
    val decidedMin: Int? = null,
    val reason: String? = null,
    /** pending | approved | rejected | expired */
    val status: String = "pending",
    val createdAt: Long = 0,
    val expireAt: Long = 0,
    val decidedAt: Long? = null,
    /** pending 且未过期，家长界面据此决定是否可点「批准」 */
    val decidable: Boolean = false,
)

@Serializable
data class TimeRequestResponse(
    val ok: Boolean = false,
    val request: TimeRequestDto? = null,
    /** 同一申请被重复提交时为 true（服务端复用而非新建） */
    val duplicated: Boolean? = null,
    val grant: GrantDto? = null,
    val delivered: Boolean? = null,
)

@Serializable
data class TimeRequestListResponse(
    val requests: List<TimeRequestDto> = emptyList(),
    val pendingCount: Int = 0,
)

@Serializable
data class TimeRequestDecideRequest(
    val approve: Boolean,
    /** 折扣批准：可以少批，但不能超过申请值 */
    val decidedMin: Int? = null,
)

/* ---------------- 使用报告（M4） ---------------- */

@Serializable
data class AppUsageDetailDto(
    val packageName: String,
    val appLabel: String? = null,
    val totalMs: Long = 0,
    val openCount: Int = 0,
    /** 该应用的逐应用单日上限；0 表示未单独设置 */
    val limitMin: Int = 0,
    /** 该应用当天是否不计入总时长（用量照常展示，但不占用额度） */
    val exemptTotal: Boolean = false,
)

@Serializable
data class UsageOverviewDto(
    val date: String = "",
    /** 当日**计入总时长**的用量，与设备端执行的额度同口径 */
    val totalMs: Long = 0,
    /** 其中被标记为「不计入总时长」的部分，仅用于向家长解释差额 */
    val exemptMs: Long = 0,
    val openCount: Int = 0,
    val appCount: Int = 0,
    /** 策略里的常规额度（不含加时） */
    val baseLimitMs: Long = 0,
    /** 生效中的加时授权累计 */
    val extraMs: Long = 0,
    val limitMs: Long = 0,
    val remainingMs: Long = 0,
    val enforcementEnabled: Boolean = true,
    val listMode: String = "blacklist",
    val apps: List<AppUsageDetailDto> = emptyList(),
)

@Serializable
data class TrendPointDto(
    val dayKey: String,
    val totalMs: Long = 0,
    val openCount: Int = 0,
    val limitMs: Long = 0,
)

@Serializable
data class UsageTrendDto(
    val days: Int = 7,
    val points: List<TrendPointDto> = emptyList(),
    val totalMs: Long = 0,
    val averageMs: Long = 0,
    val peak: TrendPointDto? = null,
)

@Serializable
data class AppRankingDto(
    val packageName: String,
    val appLabel: String? = null,
    val totalMs: Long = 0,
    val openCount: Int = 0,
    val activeDays: Int = 0,
    val dailyAverageMs: Long = 0,
)

@Serializable
data class UsageRankingDto(
    val days: Int = 7,
    val apps: List<AppRankingDto> = emptyList(),
)

@Serializable
data class BlockLogDto(
    val id: Long = 0,
    val packageName: String = "",
    val appLabel: String? = null,
    val reason: String = "",
    val reasonText: String = "",
    val ts: Long = 0,
    /** 上报去重键；家长端读取时为 null */
    val clientKey: String? = null,
)

@Serializable
data class BlockLogsResponse(
    val date: String? = null,
    val blocks: List<BlockLogDto> = emptyList(),
)

@Serializable
data class BlockReportRequest(
    val logs: List<BlockLogDto>,
)

@Serializable
data class BlockReportResponse(
    val ok: Boolean = false,
    val accepted: Int = 0,
    val duplicated: Int = 0,
    val rejected: Int = 0,
)

@Serializable
data class SessionDetailDto(
    val id: Long = 0,
    val packageName: String = "",
    val appLabel: String? = null,
    val startTs: Long = 0,
    val endTs: Long? = null,
    val durationMs: Long = 0,
    val dayKey: String = "",
)

@Serializable
data class SessionListResponse(
    val sessions: List<SessionDetailDto> = emptyList(),
)

/* ---------------- 离线密码与设备加固（M5） ---------------- */

/**
 * 一级密码的备份形态。
 *
 * **只含哈希、盐与迭代次数，从不含明文** ——
 * 服务端拿到它也无法反推密码，它只是"换机恢复"与"远程重置"的载体。
 */
@Serializable
data class PinLevelDto(
    val level: Int,
    val hash: String? = null,
    val salt: String? = null,
    val iterations: Int? = null,
    val hint: String? = null,
    /** 仅控制端视图携带：该级的名称与可执行操作 */
    val name: String? = null,
    val actions: List<String> = emptyList(),
)

/** 某一级的可执行操作说明，由服务端统一下发以保证两端口径一致 */
@Serializable
data class PinCapabilityDto(
    val name: String = "",
    val actions: List<String> = emptyList(),
)

@Serializable
data class PinBackupDto(
    val deviceId: Long = 0,
    val levelCount: Int = 3,
    val levels: List<PinLevelDto> = emptyList(),
    val version: Int = 0,
    val updatedAt: Long? = null,
    val capabilities: Map<String, PinCapabilityDto> = emptyMap(),
)

@Serializable
data class PinUploadRequest(
    val levelCount: Int,
    val level1: PinLevelDto? = null,
    val level2: PinLevelDto? = null,
    val level3: PinLevelDto? = null,
)

@Serializable
data class PinResetRequest(
    val level: Int,
    /** true 表示清除该级（家长忘记密码时的补救） */
    val clear: Boolean? = null,
    val entry: PinLevelDto? = null,
)

@Serializable
data class PinResetResponse(
    val ok: Boolean = false,
    val pins: PinBackupDto? = null,
    val delivered: Boolean? = null,
    val notice: String? = null,
)

/** 密码尝试记录（含失败）。家长靠它知道"有人在试密码"。 */
@Serializable
data class PinAttemptDto(
    val id: Long = 0,
    val deviceId: Long = 0,
    val level: Int = 1,
    val success: Boolean = false,
    /** overlay（连点标题）| corner（长按角落）| dialer（拨号暗码） */
    val source: String? = null,
    val ts: Long = 0,
    /** 上报去重键；控制端读取时为 null */
    val clientKey: String? = null,
)

@Serializable
data class PinAttemptsRequest(
    val attempts: List<PinAttemptDto>,
)

@Serializable
data class PinAttemptsResponse(
    val ok: Boolean = false,
    val accepted: Int = 0,
    val duplicated: Int = 0,
    val rejected: Int = 0,
    val attempts: List<PinAttemptDto> = emptyList(),
    val failedTotal: Int = 0,
    /** 有过失败尝试即置为 true，界面据此给出"有人在试密码"的提醒 */
    val suspicious: Boolean = false,
)

/* ==================== 截屏（M6） ==================== */

/**
 * 一张截屏的元信息。
 *
 * **刻意不含图片本体**：列表接口会返回几十条，
 * 带上 base64 图片会让响应体膨胀到几 MB，而列表界面根本不需要图片内容。
 * 需要看图时单独请求 `/image` 接口（走二进制流）。
 */
@Serializable
data class ScreenshotDto(
    val id: Long = 0,
    val deviceId: Long = 0,
    val commandId: String? = null,
    val byteSize: Int = 0,
    val mime: String = "image/jpeg",
    val width: Int? = null,
    val height: Int? = null,
    val foregroundPackage: String? = null,
    /** accessibility（Android 11+ 静默）| projection（低版本录屏降级） */
    val captureMode: String? = null,
    val createdAt: Long = 0,
    val viewedAt: Long? = null,
)

@Serializable
data class ScreenshotListResponse(
    val screenshots: List<ScreenshotDto> = emptyList(),
)

@Serializable
data class ScreenshotUploadResponse(
    val ok: Boolean = false,
    val screenshot: ScreenshotDto? = null,
)

/** 请求截屏的响应：指令是否已实时送达 */
@Serializable
data class ScreenshotRequestResponse(
    val ok: Boolean = false,
    val commandId: String? = null,
    val delivered: Boolean? = null,
    val expireAt: Long? = null,
    val notice: String? = null,
)

/* ==================== 审计日志（M6） ==================== */

@Serializable
data class AuditLogDto(
    val id: Long = 0,
    val deviceId: Long? = null,
    val deviceName: String? = null,
    val action: String = "",
    /** 服务端统一映射的中文描述 —— 客户端不自己拼，避免两端文案不一致 */
    val actionText: String = "",
    val detail: JsonElement? = null,
    /** server（家长操作）| child（设备上报的本地事件） */
    val source: String = "server",
    /** info | notice | warn */
    val level: String = "info",
    val createdAt: Long = 0,
)

@Serializable
data class AuditSummaryDto(
    val windowDays: Int = 7,
    val total: Int = 0,
    val warnCount: Int = 0,
    val lastWarnAction: String? = null,
    val lastWarnActionText: String? = null,
    val lastWarnAt: Long? = null,
)

@Serializable
data class AuditLogsResponse(
    val logs: List<AuditLogDto> = emptyList(),
    val summary: AuditSummaryDto? = null,
)

/** 被控端上报本地事件。detail 用自由结构的 JSON 字符串，各类事件字段差异大。 */
@Serializable
data class AuditEventDto(
    val clientKey: String,
    val action: String,
    val detail: String? = null,
    val level: String = "info",
    val ts: Long = 0,
)

@Serializable
data class AuditEventsRequest(
    val events: List<AuditEventDto> = emptyList(),
)

@Serializable
data class AuditEventsResponse(
    val ok: Boolean = false,
    val accepted: Int = 0,
    val duplicated: Int = 0,
)

/** 图标隐藏开关 */
@Serializable
data class IconVisibilityRequest(
    val hidden: Boolean,
)

@Serializable
data class IconVisibilityResponse(
    val ok: Boolean = false,
    val hidden: Boolean = false,
    val delivered: Boolean? = null,
    val commandId: String? = null,
    val notice: String? = null,
)

/** 管控模式的能力矩阵。服务端下发以保证控制端说明与被控端行为一致。 */
@Serializable
data class AdminModeCapabilityDto(
    val label: String = "",
    val strength: String = "",
    val can: List<String> = emptyList(),
    val limits: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
)

@Serializable
data class ActivationHintDto(
    val deviceOwner: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

/** 设备加固状态：当前生效的管控模式 + 能力矩阵 + 保活健康度 */
@Serializable
data class HardeningDto(
    val deviceId: Long = 0,
    val adminMode: String? = null,
    /** 归一化后的实际生效模式：device_owner | device_admin | none */
    val effectiveAdminMode: String = "none",
    val capabilities: AdminModeCapabilityDto? = null,
    val allModes: Map<String, AdminModeCapabilityDto> = emptyMap(),
    val deviceOwner: Boolean = false,
    val uninstallBlocked: Boolean = false,
    val pinReady: Boolean = false,
    val keepalive: KeepaliveState? = null,
    val activationHint: ActivationHintDto? = null,
)

/** 保活健康度：三项都到位才不会被系统清掉 */
@Serializable
data class KeepaliveState(
    val batteryOptimized: Boolean = false,
    val exactAlarmAllowed: Boolean = false,
    val notificationEnabled: Boolean = false,
    val foregroundService: Boolean = false,
    /** 厂商白名单是否已确认设置（无法程序化检测，由家长在界面上勾选确认） */
    val vendorWhitelistConfirmed: Boolean = false,
    val vendor: String? = null,
    val vendorHint: String? = null,
)

/** 心跳里携带的加固状态（仅被控端上报） */
@Serializable
data class HardeningReport(
    /** device_owner | device_admin | none */
    val adminMode: String,
    val deviceOwner: Boolean,
    val uninstallBlocked: Boolean,
)
