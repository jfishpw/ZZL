package com.zzl.guardian.child.local

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 被控端「本地连接」弹窗：家长手机在同一 WiFi 下配对时，从这里抄三项信息。
 *
 * 二维码需要引入 zxing 依赖（当前构建环境无法下载），
 * 一期先提供大字号 + 一键复制的手动配对信息；接口预留（见 docs/本地模式设计.md）。
 */
@Composable
fun LocalPairingDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val info = remember { LocalPairingInfo(context) }
    var token by remember { mutableStateOf(info.token()) }
    var remaining by remember { mutableStateOf(info.tokenRemainingSeconds()) }

    // 二维码载荷与控制端「扫码配对」约定一致（见 docs/本地模式设计.md §3.2）
    val payload = """{"v":1,"code":"${info.deviceCode}","token":"$token","ip":"${info.ip()}","port":${info.port}}"""
    val qr = remember(payload) {
        runCatching {
            com.journeyapps.barcodescanner.BarcodeEncoder()
                .encodeBitmap(payload, com.google.zxing.BarcodeFormat.QR_CODE, 560, 560)
        }.getOrNull()
    }
    LaunchedEffect(token) {
        while (true) {
            remaining = info.tokenRemainingSeconds()
            if (remaining <= 0) {
                token = info.token()
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本地连接（局域网直连）") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "家长手机连同一个 WiFi，用控制端「本地模式 → 扫码配对」扫下方二维码；也可以手动输入这三项。",
                    fontSize = 13.sp,
                )
                if (qr != null) {
                    Spacer(Modifier.height(12.dp))
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "配对二维码",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(220.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))

                PairingRow("设备码", info.deviceCode, context)
                Spacer(Modifier.height(10.dp))
                PairingRow("配对令牌", token, context)
                Text(
                    "令牌 " + (remaining / 60) + " 分 " + (remaining % 60) + " 秒后自动更新",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                PairingRow("地址", info.ip() + ":" + info.port, context)

                Spacer(Modifier.height(14.dp))
                Text(
                    "配对成功后令牌立即作废；换网络/IP 变化无需重新配对，家长端会自动重连。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { token = info.token() }) { Text("刷新令牌") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun PairingRow(label: String, value: String, context: Context) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Text(
            value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(label, value))
            }
        }) { Text("复制") }
    }
}
