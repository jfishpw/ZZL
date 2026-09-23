package com.zzl.guardian.data.api

import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * baseUrl 使用占位地址，真实主机与端口由 BaseUrlInterceptor 在运行时替换，
 * 因此切换服务器无需重建 Retrofit 实例。
 */
interface ApiService {

    @GET("api/health")
    suspend fun health(): HealthResponse

    /* ---------------- 鉴权 ---------------- */

    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): RegisterResponse

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("api/auth/me")
    suspend fun me(@Header("Authorization") authorization: String): LoginResponse

    /* ---------------- 配对 ---------------- */

    @POST("api/pair/code")
    suspend fun createPairCode(@Header("Authorization") authorization: String): PairCodeResponse

    @POST("api/pair/claim")
    suspend fun claimPair(@Body body: PairClaimRequest): PairClaimResponse

    /* ---------------- 设备 ---------------- */

    @GET("api/devices")
    suspend fun devices(@Header("Authorization") authorization: String): DeviceListResponse

    @GET("api/devices/{id}")
    suspend fun device(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
    ): DeviceView

    @PATCH("api/devices/{id}")
    suspend fun renameDevice(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Body body: Map<String, String>,
    ): OkResponse

    /**
     * 控制端：删除设备记录。
     *
     * 典型场景是被控端已解除绑定后清理列表里的离线条目。
     * 服务端会级联清掉策略、授权、使用记录与截屏，并通知被控端自行清除会话；
     * 设备以后重新配对：记录已删除就新增一条，还在就沿用原记录。
     */
    @DELETE("api/devices/{id}")
    suspend fun deleteDevice(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
    ): OkResponse

    @POST("api/devices/{id}/heartbeat")
    suspend fun heartbeat(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Body body: HeartbeatRequest,
    ): OkResponse

    /* ---------------- 策略 ---------------- */

    /** 被控端：一次性拉取完整策略包（策略 + 名单 + 逐应用规则） */
    @GET("api/policy")
    suspend fun ownPolicy(@Header("Authorization") authorization: String): PolicyBundleDto

    /** 控制端：读取基础策略 */
    @GET("api/devices/{id}/policy")
    suspend fun devicePolicy(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
    ): PolicyBundleDto

    /** 控制端：读取完整策略包 */
    @GET("api/devices/{id}/policy/bundle")
    suspend fun policyBundle(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
    ): PolicyBundleDto

    /** 控制端：更新基础策略 */
    @PUT("api/devices/{id}/policy")
    suspend fun updatePolicy(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Body body: PolicyUpdateRequest,
    ): PolicyBundleDto

    /** 控制端：整份替换黑白名单 */
    @PUT("api/devices/{id}/policy/lists")
    suspend fun updatePolicyLists(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Body body: PolicyListsRequest,
    ): PolicyBundleDto

    /** 控制端：整份替换逐应用规则 */
    @PUT("api/devices/{id}/policy/app-rules")
    suspend fun updateAppRules(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Body body: PolicyAppRulesRequest,
    ): PolicyBundleDto

    /**
     * 控制端：名单与规则一次性原子替换。
     * 应用管控界面走这个接口，避免分两步写留下半套配置。
     */
    @PUT("api/devices/{id}/policy/bundle")
    suspend fun updatePolicyBundle(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Body body: PolicyBundleUpdateRequest,
    ): PolicyBundleDto

    /* ---------------- 已安装应用 ---------------- */

    /** 被控端：上报已安装应用清单（整份替换） */
    @POST("api/devices/{id}/installed-apps")
    suspend fun reportInstalledApps(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Body body: InstalledAppsRequest,
    ): InstalledAppsReportResponse

    /** 控制端：读取已安装应用清单 */
    @GET("api/devices/{id}/installed-apps")
    suspend fun installedApps(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
    ): InstalledAppsResponse

    /** 控制端：请求被控端重新上报清单 */
    @POST("api/devices/{id}/installed-apps/refresh")
    suspend fun refreshInstalledApps(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
    ): OkResponse

    /* ---------------- 使用记录 ---------------- */

    /** 被控端：批量上报使用会话（服务端按 clientKey 幂等去重） */
    @POST("api/devices/{id}/usage/report")
    suspend fun reportUsage(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Body body: UsageReportRequest,
    ): UsageReportResponse

    /** 控制端：某日使用汇总 */
    @GET("api/devices/{id}/usage/daily")
    suspend fun usageDaily(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Query("date") date: String,
    ): DailyUsageDto

    /* ---------------- 指令队列（M4） ---------------- */

    /** 控制端：立即锁定设备 */
    @POST("api/devices/{id}/lock-now")
    suspend fun lockNow(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
    ): OkResponse

    /** 控制端：解除锁定 */
    @POST("api/devices/{id}/unlock")
    suspend fun unlock(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
    ): OkResponse

    /** 控制端：指令历史与执行状态（含已失效） */
    @GET("api/devices/{id}/commands")
    suspend fun deviceCommands(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Query("limit") limit: Int = 50,
    ): CommandListResponse

    /** 被控端：拉取待执行指令（离线期间下发的指令在这里补齐） */
    @GET("api/commands/pending")
    suspend fun pendingCommands(
        @Header("Authorization") authorization: String,
    ): CommandListResponse

    /** 被控端：指令回执 */
    @POST("api/commands/{commandId}/ack")
    suspend fun ackCommand(
        @Header("Authorization") authorization: String,
        @Path("commandId") commandId: String,
        @Body body: CommandAckRequest,
    ): CommandAckResponse

    /* ---------------- 临时授权（M4） ---------------- */

    /** 控制端：创建临时授权（加时 / 单应用放行 / 临时总解封） */
    @POST("api/devices/{id}/grants")
    suspend fun createGrant(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Body body: GrantCreateRequest,
    ): GrantCreateResponse

    /** 控制端：授权列表（active 区分生效与失效） */
    @GET("api/devices/{id}/grants")
    suspend fun deviceGrants(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
    ): GrantListResponse

    /** 控制端：撤销授权 */
    @DELETE("api/grants/{grantId}")
    suspend fun revokeGrant(
        @Header("Authorization") authorization: String,
        @Path("grantId") grantId: Long,
    ): GrantCreateResponse

    /** 被控端：设备状态对账（锁定状态 + 全部生效授权） */
    @GET("api/device-state")
    suspend fun deviceState(
        @Header("Authorization") authorization: String,
    ): DeviceStateDto

    /* ---------------- 加时申请（M4） ---------------- */

    /** 被控端：提交加时 / 放行申请 */
    @POST("api/time-requests")
    suspend fun createTimeRequest(
        @Header("Authorization") authorization: String,
        @Body body: TimeRequestCreateRequest,
    ): TimeRequestResponse

    /** 被控端：查看自己提交的申请（推送丢失时的补偿通道） */
    @GET("api/time-requests/mine")
    suspend fun myTimeRequests(
        @Header("Authorization") authorization: String,
    ): TimeRequestListResponse

    /** 控制端：申请列表 */
    @GET("api/time-requests")
    suspend fun timeRequests(
        @Header("Authorization") authorization: String,
        @Query("status") status: String? = null,
        @Query("deviceId") deviceId: Long? = null,
        @Query("limit") limit: Int = 50,
    ): TimeRequestListResponse

    /** 控制端：审批（decidedMin 支持打折批准） */
    @POST("api/time-requests/{id}/decide")
    suspend fun decideTimeRequest(
        @Header("Authorization") authorization: String,
        @Path("id") requestId: Long,
        @Body body: TimeRequestDecideRequest,
    ): TimeRequestResponse

    /* ---------------- 使用报告（M4） ---------------- */

    /** 控制端：使用报告总览（含额度与剩余） */
    @GET("api/devices/{id}/usage/overview")
    suspend fun usageOverview(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Query("date") date: String? = null,
    ): UsageOverviewDto

    /** 控制端：近 N 日趋势 */
    @GET("api/devices/{id}/usage/trend")
    suspend fun usageTrend(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Query("days") days: Int = 7,
    ): UsageTrendDto

    /** 控制端：近 N 日应用使用排行 */
    @GET("api/devices/{id}/usage/ranking")
    suspend fun usageRanking(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Query("days") days: Int = 7,
    ): UsageRankingDto

    /** 控制端：会话时间线 */
    @GET("api/devices/{id}/usage/sessions")
    suspend fun usageSessions(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Query("date") date: String? = null,
        @Query("limit") limit: Int = 200,
    ): SessionListResponse

    /** 控制端：拦截记录 */
    @GET("api/devices/{id}/blocks")
    suspend fun blockLogs(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Query("limit") limit: Int = 100,
    ): BlockLogsResponse

    /** 被控端：批量上报拦截记录（按 clientKey 幂等去重） */
    @POST("api/devices/{id}/blocks")
    suspend fun reportBlocks(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Body body: BlockReportRequest,
    ): BlockReportResponse

    /* ---------------- 离线密码与设备加固（M5） ---------------- */

    /** 被控端：上传密码备份（只传哈希、盐与迭代次数，从不传明文） */
    @PUT("api/pins")
    suspend fun uploadPins(
        @Header("Authorization") authorization: String,
        @Body body: PinUploadRequest,
    ): PinBackupDto

    /** 被控端：读取自己的密码备份（重装后恢复同一套密码） */
    @GET("api/pins")
    suspend fun ownPins(
        @Header("Authorization") authorization: String,
    ): PinBackupDto

    /** 被控端：上报密码尝试记录（含失败） */
    @POST("api/pin-attempts")
    suspend fun reportPinAttempts(
        @Header("Authorization") authorization: String,
        @Body body: PinAttemptsRequest,
    ): PinAttemptsResponse

    /** 控制端：密码概览（只暴露哪几级已设置，不回传哈希） */
    @GET("api/devices/{id}/pins")
    suspend fun devicePins(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
    ): PinBackupDto

    /** 控制端：远程重置或清除某一级密码 */
    @POST("api/devices/{id}/pins/reset")
    suspend fun resetPin(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Body body: PinResetRequest,
    ): PinResetResponse

    /** 控制端：孩子尝试输入密码的记录 */
    @GET("api/devices/{id}/pin-attempts")
    suspend fun pinAttempts(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Query("limit") limit: Int = 50,
    ): PinAttemptsResponse

    /** 控制端：设备加固状态与能力矩阵 */
    @GET("api/devices/{id}/hardening")
    suspend fun hardening(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
    ): HardeningDto

    /* ---------------- 截屏（M6） ---------------- */

    /** 控制端：请求设备立即截一张（走指令队列，设备离线时会在其上线后补发） */
    @POST("api/devices/{id}/screenshot")
    suspend fun requestScreenshot(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
    ): ScreenshotRequestResponse

    /** 控制端：某设备的截屏列表（不含图片本体） */
    @GET("api/devices/{id}/screenshots")
    suspend fun screenshots(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Query("limit") limit: Int = 30,
    ): ScreenshotListResponse

    /**
     * 被控端：上传截屏图片。
     *
     * body 是**原始二进制**（`RequestBody`）而不是 JSON 里的 base64 ——
     * base64 会让体积膨胀 33%，而截屏是本系统最大的单体上传。
     * 元信息（尺寸、前台应用、采集方式）走 query 参数，因为 body 已被图片占用。
     */
    @POST("api/screenshots")
    suspend fun uploadScreenshot(
        @Header("Authorization") authorization: String,
        @Query("width") width: Int? = null,
        @Query("height") height: Int? = null,
        @Query("foreground") foreground: String? = null,
        @Query("mode") mode: String? = null,
        @Query("commandId") commandId: String? = null,
        @Body body: RequestBody,
    ): ScreenshotUploadResponse

    /** 控制端：删除某张截屏 */
    @DELETE("api/devices/{id}/screenshots/{shotId}")
    suspend fun deleteScreenshot(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Path("shotId") screenshotId: Long,
    ): OkResponse

    /* ---------------- 审计日志（M6） ---------------- */

    /** 控制端：某设备的审计日志 */
    @GET("api/devices/{id}/audit")
    suspend fun deviceAudit(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Query("limit") limit: Int = 100,
        @Query("level") level: String? = null,
    ): AuditLogsResponse

    /** 控制端：全部设备的审计日志（可按级别过滤，只看需关注的） */
    @GET("api/audit")
    suspend fun audit(
        @Header("Authorization") authorization: String,
        @Query("deviceId") deviceId: Long? = null,
        @Query("limit") limit: Int = 100,
        @Query("level") level: String? = null,
    ): AuditLogsResponse

    /**
     * 被控端：补传本地审计事件（断网期间先落本地库，联网后批量上报）。
     *
     * 路径里不带设备 ID —— "当前设备"完全由令牌决定。
     * 路径里再带一个 id 只会多出一处可以不匹配的地方
     * （而且 Retrofit 对没有 `{id}` 的 `@Path` 会在运行时直接抛异常）。
     */
    @POST("api/audit/events")
    suspend fun reportAuditEvents(
        @Header("Authorization") authorization: String,
        @Body body: AuditEventsRequest,
    ): AuditEventsResponse

    /* ---------------- 图标隐藏（M6） ---------------- */

    /** 控制端：隐藏 / 恢复被控端的桌面图标 */
    @POST("api/devices/{id}/icon")
    suspend fun setIconVisibility(
        @Header("Authorization") authorization: String,
        @Path("id") deviceId: Long,
        @Body body: IconVisibilityRequest,
    ): IconVisibilityResponse
}
