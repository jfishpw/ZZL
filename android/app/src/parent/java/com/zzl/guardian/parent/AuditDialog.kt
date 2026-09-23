package com.zzl.guardian.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zzl.guardian.data.api.AuditLogDto
import com.zzl.guardian.data.api.AuditSummaryDto

/**
 * 操作记录（审计日志）。
 *
 * 两类来源混排、按时间倒序：
 *   - **家长自己做的事**（改策略、下发指令、重置密码）—— 记录用
 *   - **设备上发生的事**（权限被关、密码被试、卸载尝试）—— 告警用
 *
 * 界面上的关键取舍：**只有 warn 级用红色**。
 * 截屏、登录这类操作是"记录"而不是"告警"，如果都标红，
 * 家长每天会看到一堆红点，真正需要注意的（孩子关了无障碍、在试密码）
 * 反而被淹没 —— 这正是审计日志最常见的失败方式。
 */
@Composable
fun AuditDialog(
    /** null 表示查看全部设备 */
    deviceName: String?,
    logs: List<AuditLogDto>,
    summary: AuditSummaryDto?,
    warnOnly: Boolean,
    busy: Boolean,
    onToggleWarnOnly: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (deviceName == null) "操作记录" else "操作记录 · $deviceName") },
        text = {
            Column(Modifier.heightIn(max = 470.dp)) {
                SummaryBar(summary)
                Spacer(Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = warnOnly,
                        onClick = onToggleWarnOnly,
                        label = { Text("只看需要注意的") },
                    )
                    TextButton(onClick = onRefresh, enabled = !busy) { Text("刷新") }
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (logs.isEmpty()) {
                    Text(
                        if (warnOnly) "没有需要注意的记录" else "暂无记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(logs, key = { it.id }) { log -> AuditRow(log) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 顶部摘要：近 7 天有多少需要注意的事 */
@Composable
private fun SummaryBar(summary: AuditSummaryDto?) {
    if (summary == null) return

    val hasWarnings = summary.warnCount > 0

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (hasWarnings) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .padding(12.dp),
    ) {
        Column {
            if (hasWarnings) {
                Text(
                    "近 ${summary.windowDays} 天有 ${summary.warnCount} 条需要注意",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                summary.lastWarnActionText?.let { text ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "最近一条：$text",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            } else {
                Text(
                    "近 ${summary.windowDays} 天没有异常",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "共 ${summary.total} 条记录",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AuditRow(log: AuditLogDto) {
    val isWarn = log.level == "warn"
    val isFromChild = log.source == "child"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 级别圆点：只有 warn 用红，见类注释
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isWarn -> MaterialTheme.colorScheme.error
                        log.level == "notice" -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    },
                ),
        )

        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Text(
                log.actionText.ifBlank { log.action },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isWarn) FontWeight.Medium else FontWeight.Normal,
                color = if (isWarn) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(formatTimestamp(log.createdAt))
                    // 来源标注很关键：家长需要分清"这是我做的"还是"设备上报的"
                    append(" · ")
                    append(if (isFromChild) "设备上报" else "家长操作")
                    log.deviceName?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 供设备卡片复用：提示"有 N 条需要注意" */
@Composable
internal fun WarnBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError,
        )
    }
}

/** 空内容占位，避免 Compose 对空 Column 报警 */
@Suppress("unused")
private val unusedPadding = PaddingValues(0.dp)
