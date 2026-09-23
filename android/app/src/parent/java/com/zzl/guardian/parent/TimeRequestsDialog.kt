package com.zzl.guardian.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zzl.guardian.data.api.TimeRequestDto

/**
 * 加时申请审批。
 *
 * 关键设计是**打折批准**：申请 60 分钟可以只批 20 分钟。
 * 没有这个能力的话，家长面对"要么全给要么全不给"只能选拒绝，
 * 而每次都拒绝会让孩子彻底不再使用这个通道 —— 功能就废了。
 *
 * 折扣档位给的是申请值的比例，而不是固定数字：
 * 孩子申请 10 分钟和申请 120 分钟，"批一半"的含义完全不同。
 */
@Composable
internal fun TimeRequestsDialog(
    requests: List<TimeRequestDto>,
    decidingId: Long?,
    onDecide: (id: Long, approve: Boolean, decidedMin: Int?) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pending = requests.filter { it.decidable }
    val history = requests.filterNot { it.decidable }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加时申请") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (requests.isEmpty()) {
                    Text(
                        "还没有收到申请。孩子在被拦截时可以直接向你申请加时。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (pending.isNotEmpty()) {
                    Text(
                        "待处理（超过审批时限会自动作废）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    pending.forEach { request ->
                        PendingRequestCard(
                            request = request,
                            busy = decidingId == request.id,
                            onDecide = { approve, minutes -> onDecide(request.id, approve, minutes) },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }

                if (history.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已处理",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    history.take(10).forEach { request -> HistoryRow(request) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh) { Text("刷新") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun PendingRequestCard(
    request: TimeRequestDto,
    busy: Boolean,
    onDecide: (approve: Boolean, decidedMin: Int?) -> Unit,
) {
    var showDiscount by remember(request.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                request.deviceName ?: "设备",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (request.scope == "app_allow") {
                    "申请放行「${request.appLabel ?: request.packageName ?: "应用"}」${request.requestMin} 分钟"
                } else {
                    "申请加时 ${request.requestMin} 分钟"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )

            request.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "理由：$reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "还有 ${remainingMinutes(request.expireAt)} 分钟可处理",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))

            if (!showDiscount) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onDecide(true, null) },
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) { Text(if (busy) "处理中…" else "批准 ${request.requestMin} 分钟") }

                    OutlinedButton(
                        onClick = { showDiscount = true },
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) { Text("少批一点") }

                    OutlinedButton(
                        onClick = { onDecide(false, null) },
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) { Text("拒绝") }
                }
            } else {
                Text(
                    "批准多少分钟？",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 按申请值的比例给档位：申请 10 分钟时的「四分之一」和申请 120 分钟时
                    // 的含义完全不同，固定数字档位是没有意义的
                    discountOptions(request.requestMin).forEach { minutes ->
                        OutlinedButton(
                            onClick = { onDecide(true, minutes) },
                            enabled = !busy,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        ) { Text("$minutes 分钟") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { showDiscount = false }, enabled = !busy) { Text("返回") }
            }
        }
    }
}

@Composable
private fun HistoryRow(request: TimeRequestDto) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = buildString {
                    append(request.deviceName ?: "设备")
                    append(" · ")
                    if (request.scope == "app_allow") {
                        append(request.appLabel ?: request.packageName ?: "应用")
                    } else {
                        append("加时")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = when (request.status) {
                    "approved" -> "已批准 ${request.decidedMin ?: request.requestMin} 分钟" +
                        if (request.decidedMin != null && request.decidedMin < request.requestMin) {
                            "（申请 ${request.requestMin} 分钟）"
                        } else {
                            ""
                        }
                    "rejected" -> "已拒绝（申请 ${request.requestMin} 分钟）"
                    "expired" -> "已超时作废（申请 ${request.requestMin} 分钟）"
                    else -> "待处理"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 折扣档位：申请值的 1/4、1/2、3/4（去重、去零、去等于申请值的项）。
 * 用比例而不是固定分钟，是为了让「少批一点」在任何申请量下都说得通。
 */
internal fun discountOptions(requestMin: Int): List<Int> {
    if (requestMin <= 1) return emptyList()
    val candidates = listOf(
        requestMin / 4,
        requestMin / 2,
        requestMin * 3 / 4,
    )
    return candidates
        .filter { it >= 1 && it < requestMin }
        .distinct()
}
