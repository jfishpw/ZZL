package com.zzl.guardian.child.screenshot

import android.util.Log
import com.zzl.guardian.child.data.AuditAction
import com.zzl.guardian.child.data.AuditLevel
import com.zzl.guardian.child.data.UsageRepository
import com.zzl.guardian.data.api.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次截屏的完整流程：采集 → 上传 → 记审计。
 *
 * 独立成一个类而不是塞进指令执行器，是因为这条链路有它自己的失败语义：
 * 「采集失败」和「上传失败」对家长的含义完全不同 ——
 * 前者是系统不给（低版本 / 权限没了），后者是网络问题（可以重试）。
 * 混在一起回执只能给一句笼统的"失败"。
 */
@Singleton
class ScreenshotUploader @Inject constructor(
    private val capturer: ScreenshotCapturer,
    private val api: ApiService,
    private val usageRepository: UsageRepository,
) {

    /**
     * 采集并上传。
     *
     * 不接收 deviceId：上传接口的"当前设备"完全由令牌决定，
     * 多传一个 id 只会多出一处可以不匹配的地方。
     *
     * @param foregroundPackage 当前前台应用，随图一起上报 ——
     *                          家长看截图时最想知道的就是"他当时在用什么"
     * @return 结果说明，会作为指令回执的内容回传给家长
     */
    suspend fun captureAndUpload(
        token: String,
        commandId: String?,
        foregroundPackage: String?,
    ): Result = withContext(Dispatchers.IO) {
        when (val outcome = capturer.capture()) {
            is CaptureOutcome.Failed -> {
                Log.w(TAG, "截屏采集失败：${outcome.reason} ${outcome.message}")
                // 采集失败是家长最该知道的事 —— 不写清楚，家长会以为是
                // "传了但控制端没显示"。用独立的 action + warn 级让原因直出列表
                runCatching {
                    usageRepository.recordAudit(
                        action = AuditAction.SCREENSHOT_FAILED,
                        detail = """{"ok":false,"reason":"${outcome.reason}","message":"${outcome.message}"}""",
                        level = AuditLevel.WARN,
                    )
                }
                Result(ok = false, message = outcome.message)
            }

            is CaptureOutcome.Success -> {
                val image = outcome.image
                Log.i(
                    TAG,
                    "截屏成功 ${image.width}x${image.height} ${image.bytes.size} 字节 " +
                        "质量 ${image.quality} 方式 ${image.mode}",
                )
                upload(token, commandId, foregroundPackage, image)
            }
        }
    }

    private suspend fun upload(
        token: String,
        commandId: String?,
        foregroundPackage: String?,
        image: CapturedImage,
    ): Result {
        val requestBody = image.bytes.toRequestBody(MIME.toMediaType())

        val response = runCatching {
            api.uploadScreenshot(
                authorization = "Bearer $token",
                width = image.width,
                height = image.height,
                foreground = foregroundPackage,
                mode = image.mode,
                commandId = commandId,
                body = requestBody,
            )
        }.onFailure { Log.w(TAG, "上传截屏失败: ${it.message}") }
            .getOrNull()

        if (response == null) {
            // 采集成功但上传失败：告诉家长是网络问题，可以重试
            return Result(ok = false, message = "图片已采集，但上传失败（网络问题，可重试）")
        }
        if (!response.ok) {
            return Result(ok = false, message = "服务端拒绝接收图片")
        }

        runCatching {
            usageRepository.recordAudit(
                action = AuditAction.SCREENSHOT_UPLOAD,
                detail = """{"ok":true,"byteSize":${image.bytes.size},"mode":"${image.mode}"}""",
                level = AuditLevel.NOTICE,
            )
        }

        return Result(
            ok = true,
            message = "已上传 ${image.width}x${image.height}，${image.bytes.size / 1024} KB",
        )
    }

    data class Result(val ok: Boolean, val message: String)

    private companion object {
        const val TAG = "ScreenshotUploader"
        const val MIME = "image/jpeg"
    }
}
