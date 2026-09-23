package com.zzl.guardian.child.screenshot

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 一张截屏的结果 */
data class CapturedImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    /** accessibility | projection */
    val mode: String,
    /** 缩放后的质量，便于解释"为什么这张体积小" */
    val quality: Int,
)

/** 采集失败的原因。带原因而不是只有一个布尔值，家长才能判断是不是自己操作错了。 */
sealed interface CaptureOutcome {
    data class Success(val image: CapturedImage) : CaptureOutcome
    data class Failed(val reason: String, val message: String) : CaptureOutcome
}

/**
 * 截屏采集。
 *
 * **采集方式只有一个，且是刻意的：**
 *
 * | 方式 | 可用版本 | 是否需要用户操作 |
 * |---|---|---|
 * | `AccessibilityService.takeScreenshot()` | Android 11 (API 30) 起 | **不需要**，无任何系统提示 |
 * | `MediaProjection` | Android 5 起 | **需要**：必须弹系统录屏授权框，且框会一直显示"正在录制" |
 *
 * 换句话说，**Android 10 及以下无法做到"家长远程静默截一张"**。
 *
 * 所以这里对低版本**如实返回失败**，而不是退到 MediaProjection：
 * 那条路会让孩子屏幕上突然弹出"是否允许录制屏幕"—— 孩子看到就知道被监控了，
 * 而他只要点"取消"，功能照样失败。既暴露了意图又拿不到图，
 * 比一句"该设备系统版本过低"糟糕得多。
 *
 * 低版本设备上家长会看到明确的失败原因，从而知道这是系统限制而非软件故障。
 */
@Singleton
class ScreenshotCapturer @Inject constructor() {

    /**
     * 无障碍服务的弱引用。
     *
     * 必须是弱引用：无障碍服务由系统管理，随时可能被销毁重建。
     * 强引用会让已销毁的服务实例（连同它的窗口资源）无法回收。
     */
    @Volatile
    private var serviceRef: WeakReference<AccessibilityService>? = null

    fun attach(service: AccessibilityService) {
        serviceRef = WeakReference(service)
    }

    fun detach() {
        serviceRef = null
    }

    fun isReady(): Boolean = serviceRef?.get() != null

    /**
     * 截取一张。
     *
     * 整体是同步阻塞的（内部用 latch 等回调），调用方应在 IO 协程里执行：
     * 截图 + 缩放的耗时在百毫秒级，放在主线程会卡住 UI。
     */
    fun capture(maxBytes: Int = DEFAULT_MAX_BYTES): CaptureOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return CaptureOutcome.Failed(
                reason = "unsupported_os",
                message = "设备系统为 Android ${Build.VERSION.RELEASE}，静默截屏需要 Android 11 及以上",
            )
        }

        val service = serviceRef?.get()
            ?: return CaptureOutcome.Failed(
                reason = "no_accessibility",
                message = "无障碍服务未运行，无法截屏。请先开启无障碍权限",
            )

        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var errorCode = -1

        val executor = Executor { runnable -> Handler(Looper.getMainLooper()).post(runnable) }

        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            // wrapHardwareBuffer 拿到的是硬件位图，
                            // 必须先 close 释放底层 buffer —— 不关会导致
                            // 系统无法回收这块显存，连续截几次就会失败。
                            val wrapped = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace,
                            )
                            // 复制成普通位图后再关 buffer：硬件位图在 buffer 关闭后不可用
                            bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (error: Throwable) {
                            Log.w(TAG, "解析截屏位图失败", error)
                        } finally {
                            runCatching { screenshot.hardwareBuffer.close() }
                            latch.countDown()
                        }
                    }

                    override fun onFailure(code: Int) {
                        errorCode = code
                        latch.countDown()
                    }
                },
            )
        } catch (error: Throwable) {
            Log.w(TAG, "发起截屏失败", error)
            return CaptureOutcome.Failed(
                reason = "call_failed",
                message = "截屏调用失败：${error.message ?: error::class.java.simpleName}",
            )
        }

        val finished = runCatching { latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)

        if (!finished) {
            return CaptureOutcome.Failed(
                reason = "timeout",
                message = "截屏超时（${CAPTURE_TIMEOUT_MS / 1000} 秒内没有响应）",
            )
        }

        val source = bitmap
            ?: return CaptureOutcome.Failed(
                reason = "capture_failed",
                message = describeErrorCode(errorCode),
            )

        return try {
            val image = compress(source, maxBytes)
            CaptureOutcome.Success(image)
        } catch (error: Throwable) {
            Log.w(TAG, "压缩截屏失败", error)
            CaptureOutcome.Failed(
                reason = "compress_failed",
                message = "图片处理失败：${error.message ?: error::class.java.simpleName}",
            )
        } finally {
            source.recycle()
        }
    }

    /**
     * 缩放 + 压成 JPEG。
     *
     * 超限时先降质量重压一次。只重压一次是有意的：
     * 截屏是即时交互，多轮试探会让家长等上好几秒，
     * 而质量 40-60 对"看孩子在用哪个应用"这个目的完全够用。
     */
    private fun compress(source: Bitmap, maxBytes: Int): CapturedImage {
        val plan = ImageScaling.plan(source.width, source.height)

        val scaled = if (plan.scaled) {
            Bitmap.createScaledBitmap(source, plan.width, plan.height, true)
        } else {
            source
        }

        var bytes = encode(scaled, plan.quality)

        val retryQuality = ImageScaling.qualityForSize(bytes.size, maxBytes)
        if (retryQuality != null && retryQuality < plan.quality) {
            bytes = encode(scaled, retryQuality)
            Log.i(TAG, "图片超限，质量降至 $retryQuality 重压（${bytes.size} 字节）")
            if (scaled !== source) scaled.recycle()
            return CapturedImage(bytes, plan.width, plan.height, MODE, retryQuality)
        }

        if (scaled !== source) scaled.recycle()
        return CapturedImage(bytes, plan.width, plan.height, MODE, plan.quality)
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        // JPEG 不支持透明通道：截屏本身也没有透明区域，用它体积最小
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    private fun describeErrorCode(code: Int): String = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            "截屏过于频繁，请稍后重试"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            "显示设备无效"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "无障碍服务无权截屏，请重新开启无障碍权限"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
            "系统内部错误，请稍后重试"
        else -> "截屏失败（系统返回码 $code）"
    }

    private companion object {
        const val TAG = "ScreenshotCapturer"

        /** 与服务端 config.screenshot.maxBytes 保持一致 */
        const val DEFAULT_MAX_BYTES = 1024 * 1024

        const val CAPTURE_TIMEOUT_MS = 8_000L

        /** Android 11+ 走无障碍，无系统提示 */
        const val MODE = "accessibility"
    }
}
