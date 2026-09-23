package com.zzl.guardian.child.engine

import android.util.Log
import com.zzl.guardian.child.data.DeviceStateRepository
import com.zzl.guardian.child.data.InstalledAppsReporter
import com.zzl.guardian.child.icon.IconController
import com.zzl.guardian.child.screenshot.ScreenshotUploader
import com.zzl.guardian.data.api.ApiService
import com.zzl.guardian.data.api.CommandAckRequest
import com.zzl.guardian.data.api.CommandDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 指令执行器。
 *
 * 服务端的约定是「先落库、再推送」，因此被控端可能**两次**拿到同一条指令：
 * 一次通过长连接实时推送，一次通过上线后的补发拉取。执行动作因此必须幂等 ——
 * 「立即锁定」重复执行无害，但「加时」重复执行会把额度叠两次。
 * 幂等靠本地台账 [DeviceStateRepository.alreadyExecuted] 保证。
 *
 * 已执行过的指令仍然会补一次回执：服务端可能因为响应丢失而一直显示 pending，
 * 补回执能让家长看到真实状态。
 */
@Singleton
class CommandRunner @Inject constructor(
    private val api: ApiService,
    private val deviceStateRepository: DeviceStateRepository,
    private val installedAppsReporter: InstalledAppsReporter,
    private val screenshotUploader: ScreenshotUploader,
    private val iconController: IconController,
    private val engine: GuardEngine,
) {

    /** 拉取待执行指令并逐条执行，返回真正执行了条数 */
    suspend fun pullAndRun(token: String, deviceId: Long): Int = withContext(Dispatchers.IO) {
        val response = runCatching { api.pendingCommands("Bearer $token") }
            .onFailure { Log.d(TAG, "拉取待执行指令失败（离线属正常）: ${it.message}") }
            .getOrNull() ?: return@withContext 0

        var executed = 0
        for (command in response.commands) {
            if (runOne(token, deviceId, command)) executed += 1
        }
        if (executed > 0) Log.i(TAG, "补执行了 $executed 条指令")
        executed
    }

    /** 执行一条由长连接实时推送的指令 */
    suspend fun runOne(token: String, deviceId: Long, command: CommandDto): Boolean {
        if (command.commandId.isBlank()) return false

        if (deviceStateRepository.alreadyExecuted(command.commandId)) {
            ack(token, command.commandId, "done", "already_executed")
            return false
        }

        val (ok, detail) = try {
            execute(token, deviceId, command)
        } catch (error: Throwable) {
            Log.w(TAG, "执行指令 ${command.type} 抛异常", error)
            false to "exception:${error.message ?: error::class.java.simpleName}"
        }

        deviceStateRepository.recordExecuted(
            commandId = command.commandId,
            type = command.type,
            result = if (ok) "done" else detail,
        )
        ack(token, command.commandId, if (ok) "done" else "failed", detail)

        // 指令会改变管控状态（锁定/授权），立即重新决策，别等下一次巡检
        if (ok) engine.reevaluate()
        return ok
    }

    private suspend fun execute(token: String, deviceId: Long, command: CommandDto): Pair<Boolean, String> =
        when (command.type) {
            "immediate_lock" -> {
                deviceStateRepository.setLocked(deviceId, true)
                true to "locked"
            }

            "clear_lock" -> {
                deviceStateRepository.setLocked(deviceId, false)
                true to "unlocked"
            }

            "request_installed_apps" -> {
                val count = installedAppsReporter.upload(token, deviceId)
                true to "reported:$count"
            }

            /**
             * 按需截屏。
             *
             * 采集方式只有无障碍一条（Android 11+ 无系统提示）；
             * 低版本由 [ScreenshotCapturer] 如实返回失败原因，
             * 不会退到会弹录屏授权框的 MediaProjection —— 那等于当着孩子的面
             * 暴露"正在被监控"，而他只要点取消功能照样失败。
             */
            "screenshot" -> {
                val result = screenshotUploader.captureAndUpload(
                    token = token,
                    commandId = command.commandId,
                    foregroundPackage = engine.state.value.foregroundPackage,
                )
                result.ok to result.message
            }

            /**
             * 隐藏 / 恢复桌面图标。
             *
             * 这里只做「立即动作」；「重启后仍生效」由设备状态对账保证
             * （icon_hidden 是设备状态字段，不是一次性指令）。
             */
            "hide_icon" -> iconController.hide() 
                .let { ok -> ok to if (ok) "icon_hidden" else "hide_failed" }

            "show_icon" -> iconController.show()
                .let { ok -> ok to if (ok) "icon_shown" else "show_failed" }

            else -> false to "unknown_type:${command.type}"
        }

    private suspend fun ack(token: String, commandId: String, status: String, detail: String) {
        runCatching {
            api.ackCommand(
                authorization = "Bearer $token",
                commandId = commandId,
                body = CommandAckRequest(
                    status = status,
                    detail = buildJsonObject { put("detail", detail) },
                ),
            )
        }.onFailure { Log.w(TAG, "回执指令 $commandId 失败: ${it.message}") }
    }

    private companion object {
        const val TAG = "CommandRunner"
    }
}
