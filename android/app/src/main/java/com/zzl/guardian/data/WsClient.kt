package com.zzl.guardian.data

import com.zzl.guardian.data.api.CommandDto
import com.zzl.guardian.data.api.DeviceStateDto
import com.zzl.guardian.data.api.PolicyBundleDto
import com.zzl.guardian.data.api.ScreenshotDto
import com.zzl.guardian.data.api.TimeRequestDto
import com.zzl.guardian.di.ServerConfigHolder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

/** 服务端推送事件（控制端侧关心的部分） */
sealed interface WsEvent {
    data object Connected : WsEvent

    data object Disconnected : WsEvent

    data class Failure(val reason: String) : WsEvent

    data class DeviceStatus(
        val deviceId: Long,
        val online: Boolean,
        val foregroundPackage: String?,
        val remainingMs: Long?,
    ) : WsEvent

    data class PermissionLost(
        val deviceId: Long,
        val missing: List<String>,
    ) : WsEvent

    /** 家长改完策略后实时下发完整策略包。被控端收到即落本地库，无需额外拉取。 */
    data class PolicyUpdated(val bundle: PolicyBundleDto) : WsEvent

    /** 服务端要求被控端重新上报已安装应用清单 */
    data object RequestInstalledApps : WsEvent

    /** 被控端：本设备已被家长从控制端删除，应清掉本地会话回到配对页 */
    data object DeviceRemoved : WsEvent

    /** 服务端下发了一条指令（在线路径；离线时由拉取接口补齐） */
    data class CommandReceived(val command: CommandDto) : WsEvent

    /** 服务端提示「有 N 条待执行指令」——指令内容由被控端主动拉取，避免长连接承载大载荷 */
    data class CommandsPending(val count: Int) : WsEvent

    /**
     * 设备状态变化：锁定与否、生效中的授权。
     *
     * 用整份状态而非增量事件，是因为被控端需要的是「最终一致」——
     * 增量一旦丢一条就会永久偏离，整份状态则天然自愈。
     */
    data class DeviceStateUpdated(val state: DeviceStateDto) : WsEvent

    /** 控制端：收到孩子的加时申请 */
    data class TimeRequestCreated(val request: TimeRequestDto) : WsEvent

    /** 被控端：家长已审批（可能是折扣批准，需读 decidedMin） */
    data class TimeRequestDecided(val request: TimeRequestDto) : WsEvent

    /** 控制端：某条申请已超过审批时限 */
    data class TimeRequestExpired(val requestId: Long) : WsEvent

    /** 控制端：指令执行结果回执 */
    data class CommandResult(val command: CommandDto) : WsEvent

    /**
     * 控制端：设备刚上传了一张截屏。
     *
     * 有了它，家长点「立即截屏」后不必盯着转圈或反复下拉刷新 ——
     * 图一到就会自动出现在列表里。
     */
    data class ScreenshotReady(
        val deviceId: Long,
        val screenshot: ScreenshotDto,
    ) : WsEvent

    /** 其他暂未建模的事件类型，保留类型名以便后续里程碑接管 */
    data class Other(val type: String) : WsEvent
}

/**
 * WebSocket 客户端。连接地址每次都从 [ServerConfigHolder] 现取，
 * 因此切换服务器后重连即生效。
 */
@Singleton
class WsClient @Inject constructor(
    private val client: OkHttpClient,
    private val holder: ServerConfigHolder,
    private val json: Json,
) {

    fun events(token: String): Flow<WsEvent> = callbackFlow {
        val socket = client.newWebSocket(buildRequest(token), object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(WsEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parse(text)?.let { trySend(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(WsEvent.Failure(t.message ?: "连接中断"))
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(WsEvent.Disconnected)
                close()
            }
        })

        awaitClose { socket.close(1000, null) }
    }

    /** 被控端向服务端上报状态 */
    fun sendStatus(socket: WebSocket, foregroundPackage: String?, remainingMs: Long?) {
        val payload = buildString {
            append("{\"type\":\"status\"")
            foregroundPackage?.let { append(",\"foregroundPackage\":\"").append(it).append('"') }
            remainingMs?.let { append(",\"remainingMs\":").append(it) }
            append('}')
        }
        socket.send(payload)
    }

    private fun buildRequest(token: String): Request {
        val url = "${holder.current.wsUrl}?token=$token"
        return Request.Builder().url(url).build()
    }

    private fun parse(text: String): WsEvent? {
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null

        return when (type) {
            "device_status" -> WsEvent.DeviceStatus(
                deviceId = obj["deviceId"]?.jsonPrimitive?.longOrNull ?: return null,
                online = obj["online"]?.jsonPrimitive?.booleanOrNull ?: false,
                foregroundPackage = obj["foregroundPackage"]?.jsonPrimitive?.contentOrNull,
                remainingMs = obj["remainingMs"]?.jsonPrimitive?.longOrNull,
            )

            "permission_lost" -> WsEvent.PermissionLost(
                deviceId = obj["deviceId"]?.jsonPrimitive?.longOrNull ?: return null,
                missing = runCatching {
                    obj["missing"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                }.getOrNull().orEmpty(),
            )

            "policy_updated" -> {
                val bundleElement = obj["bundle"] ?: return null
                runCatching {
                    WsEvent.PolicyUpdated(json.decodeFromJsonElement(PolicyBundleDto.serializer(), bundleElement))
                }.getOrNull()
            }

            "request_installed_apps" -> WsEvent.RequestInstalledApps

            "device_removed" -> WsEvent.DeviceRemoved

            "command" -> {
                val element = obj["command"] ?: return null
                runCatching {
                    WsEvent.CommandReceived(json.decodeFromJsonElement(CommandDto.serializer(), element))
                }.getOrNull()
            }

            "commands_pending" -> WsEvent.CommandsPending(
                obj["count"]?.jsonPrimitive?.intOrNull ?: 0,
            )

            "device_state_updated" -> {
                val element = obj["state"] ?: return null
                runCatching {
                    WsEvent.DeviceStateUpdated(json.decodeFromJsonElement(DeviceStateDto.serializer(), element))
                }.getOrNull()
            }

            "time_request" -> {
                val element = obj["request"] ?: return null
                runCatching {
                    WsEvent.TimeRequestCreated(json.decodeFromJsonElement(TimeRequestDto.serializer(), element))
                }.getOrNull()
            }

            "time_request_decided" -> {
                val element = obj["request"] ?: return null
                runCatching {
                    WsEvent.TimeRequestDecided(json.decodeFromJsonElement(TimeRequestDto.serializer(), element))
                }.getOrNull()
            }

            "time_request_expired" -> WsEvent.TimeRequestExpired(
                obj["requestId"]?.jsonPrimitive?.longOrNull ?: return null,
            )

            "command_result" -> {
                val element = obj["command"] ?: return null
                runCatching {
                    WsEvent.CommandResult(json.decodeFromJsonElement(CommandDto.serializer(), element))
                }.getOrNull()
            }

            "screenshot_ready" -> {
                val deviceId = obj["deviceId"]?.jsonPrimitive?.longOrNull ?: return null
                val element = obj["screenshot"] ?: return null
                runCatching {
                    WsEvent.ScreenshotReady(
                        deviceId = deviceId,
                        screenshot = json.decodeFromJsonElement(ScreenshotDto.serializer(), element),
                    )
                }.getOrNull()
            }

            "ready", "pong", "noop" -> null

            else -> WsEvent.Other(type)
        }
    }
}
