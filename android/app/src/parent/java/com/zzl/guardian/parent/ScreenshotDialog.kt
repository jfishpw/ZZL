package com.zzl.guardian.parent

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zzl.guardian.data.api.ScreenshotDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 截屏面板。
 *
 * 设计上的三个要点：
 *
 * 1. **图片按需加载**。列表一次可能返回几十条，全部下载就是几十 MB 流量。
 *    这里只有点开某条时才加载对应图片，并且字节走 [com.zzl.guardian.data.AuthedImageLoader]
 *    的内存缓存，重复查看不重复下载。
 *
 * 2. **明确告知采集方式**。Android 11 以下无法静默截屏，
 *    低版本设备会显示失败原因。这比让家长对着"点了没反应"猜要好得多。
 *
 * 3. **请求发出后不等在原地**。截屏要经过"指令下发 → 设备采集 → 图片上传"
 *    三步，几秒钟；期间显示"正在截取…"，图片到达后由 WebSocket 推送自动出现。
 */
@Composable
fun ScreenshotDialog(
    deviceName: String,
    screenshots: List<ScreenshotDto>,
    busy: Boolean,
    capturePending: Boolean,
    captureNotice: String?,
    /** 图片字节加载器（含鉴权）。由界面层从 ViewModel 传入。 */
    loadImage: suspend (path: String) -> Bitmap?,
    onCapture: () -> Unit,
    onDelete: (Long) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var previewShot by remember { mutableStateOf<ScreenshotDto?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("查看屏幕 · $deviceName") },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                Text(
                    "按需截取一张当前屏幕。截屏仅用于了解使用情况，请勿外传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onCapture, enabled = !busy) {
                        Text(if (capturePending) "正在截取…" else "立即截屏")
                    }
                    OutlinedButton(onClick = onRefresh, enabled = !busy) { Text("刷新列表") }
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }

                if (capturePending) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "指令已下发，等待设备返回…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else if (!captureNotice.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        captureNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(14.dp))

                if (screenshots.isEmpty()) {
                    Text(
                        "还没有截屏。点「立即截屏」让设备传一张回来。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(screenshots, key = { it.id }) { shot ->
                            ScreenshotRow(
                                shot = shot,
                                loadImage = loadImage,
                                onDelete = { onDelete(shot.id) },
                                onOpen = { previewShot = shot },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )

    previewShot?.let { shot ->
        ScreenshotPreview(
            shot = shot,
            loadImage = loadImage,
            onDismiss = { previewShot = null },
        )
    }
}

@Composable
private fun ScreenshotRow(
    shot: ScreenshotDto,
    loadImage: suspend (String) -> Bitmap?,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            // 只对最新一张加载缩略图：列表可能很长，
            // 每张都加载会让打开面板时一次性发起几十个请求
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(
                    shot = shot,
                    loadImage = loadImage,
                    modifier = Modifier
                        .size(width = 48.dp, height = 64.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        formatTimestamp(shot.createdAt),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            append(shot.foregroundPackage?.let { "前台：$it" } ?: "前台应用未知")
                            shot.width?.let { w -> shot.height?.let { h -> append(" · ${w}×$h") } }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${shot.byteSize / 1024} KB · ${captureModeLabel(shot.captureMode)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onOpen,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                ) { Text("查看") }
                OutlinedButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                ) { Text("删除") }
            }
        }
    }
}

/** 加载失败的占位：告诉家长"这张图还在或已被清理"，而不是一片空白 */
@Composable
private fun Thumbnail(
    shot: ScreenshotDto,
    loadImage: suspend (String) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(shot.id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(shot.id) { mutableStateOf(false) }

    LaunchedEffect(shot.id) {
        val loaded = loadImage(shot.path())
        if (loaded == null) failed = true else bitmap = loaded
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        when {
            image != null -> Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "屏幕截图缩略图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            failed -> Text(
                "—",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )

            else -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * 全屏预览。
 *
 * 按真实宽高比显示：截屏的宽高比因设备而异（手机竖屏、平板横屏），
 * 写死比例会把图拉变形，看起来像"截图坏了"。
 */
@Composable
private fun ScreenshotPreview(
    shot: ScreenshotDto,
    loadImage: suspend (String) -> Bitmap?,
    onDismiss: () -> Unit,
) {
    var bitmap by remember(shot.id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(shot.id) { mutableStateOf(false) }

    LaunchedEffect(shot.id) {
        val loaded = loadImage(shot.path())
        if (loaded == null) failed = true else bitmap = loaded
    }

    val ratio = shot.width?.let { w ->
        shot.height?.let { h -> if (h > 0) w.toFloat() / h else DEFAULT_RATIO }
    } ?: DEFAULT_RATIO

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(formatTimestamp(shot.createdAt)) },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val image = bitmap
                    when {
                        image != null -> Image(
                            bitmap = image.asImageBitmap(),
                            contentDescription = "屏幕截图",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )

                        failed -> Text(
                            "图片加载失败，可能已被清理。可点「刷新列表」重试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                        )

                        else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        append(shot.foregroundPackage?.let { "前台：$it" } ?: "前台应用未知")
                        append(" · ${shot.byteSize / 1024} KB")
                        append(" · ${captureModeLabel(shot.captureMode)}")
                        shot.viewedAt?.let { append("\n已于 ${formatTimestamp(it)} 查看过") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 图片接口的相对路径。
 *
 * 用相对路径而不是绝对 URL：host/port 由网络层的拦截器在请求时重写，
 * 因此换服务器之后这里不需要跟着改。
 */
private fun ScreenshotDto.path(): String = "api/devices/$deviceId/screenshots/$id/image"

private const val DEFAULT_RATIO = 0.5f

private fun captureModeLabel(mode: String?): String = when (mode) {
    "accessibility" -> "无障碍采集（无系统提示）"
    "projection" -> "录屏采集（低版本降级）"
    null -> "采集方式未知"
    else -> mode
}

private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

internal fun formatTimestamp(ts: Long): String =
    if (ts <= 0) "—" else timeFormat.format(Date(ts))
