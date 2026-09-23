package com.zzl.guardian.parent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zzl.guardian.data.api.BlockLogDto
import com.zzl.guardian.data.api.SessionDetailDto
import com.zzl.guardian.data.api.UsageOverviewDto
import com.zzl.guardian.data.api.UsageRankingDto
import com.zzl.guardian.data.api.UsageTrendDto
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 使用报告。
 *
 * 家长真正想知道的只有四件事，界面按这个顺序组织：
 *   1. 今天还剩多少时间（能不能继续用）
 *   2. 时间都花在哪了（排行）
 *   3. 最近几天是什么趋势（是不是越来越失控）
 *   4. 被拦了几次、为什么（管控是不是真在起作用）
 *
 * 趋势图用自绘 Canvas 而不是引入图表库：这里只需要一组柱状，
 * 为它增加一个几百 KB 的依赖不划算，而且自绘能精确控制与主题色的配合。
 */
@Composable
internal fun UsageReportDialog(
    deviceName: String,
    days: Int,
    overview: UsageOverviewDto?,
    trend: UsageTrendDto?,
    ranking: UsageRankingDto?,
    sessions: List<SessionDetailDto>,
    blocks: List<BlockLogDto>,
    busy: Boolean,
    onDaysChange: (Int) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("使用报告 · $deviceName") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                /* ---------- 今日概览 ---------- */
                if (overview == null) {
                    Text(
                        if (busy) "正在加载…" else "暂无数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TodayOverviewCard(overview)

                    Spacer(Modifier.height(16.dp))
                    SectionHeader("近 $days 天趋势")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(7, 14, 30).forEach { option ->
                            FilterChip(
                                selected = days == option,
                                onClick = { onDaysChange(option) },
                                label = { Text("$option 天") },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    trend?.let { TrendChart(it) }

                    Spacer(Modifier.height(16.dp))
                    SectionHeader("应用使用排行")
                    val apps = ranking?.apps.orEmpty()
                    if (apps.isEmpty()) {
                        EmptyHint("这段时间还没有使用记录")
                    } else {
                        apps.take(8).forEach { app -> RankingRow(app, apps.first().totalMs) }
                    }

                    Spacer(Modifier.height(16.dp))
                    SectionHeader("被拦截记录")
                    if (blocks.isEmpty()) {
                        EmptyHint("没有被拦截的记录，管控暂时没有触发")
                    } else {
                        blocks.take(12).forEach { block -> BlockRow(block) }
                    }

                    Spacer(Modifier.height(16.dp))
                    SectionHeader("最近使用时间线")
                    if (sessions.isEmpty()) {
                        EmptyHint("暂无会话明细")
                    } else {
                        sessions.take(12).forEach { session -> SessionRow(session) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh, enabled = !busy) { Text(if (busy) "刷新中…" else "刷新") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TodayOverviewCard(overview: UsageOverviewDto) {
    val fraction = if (overview.limitMs > 0) {
        (overview.totalMs.toFloat() / overview.limitMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("今日已用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("可用上限", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMinutes(overview.totalMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(formatMinutes(overview.limitMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))

            Text(
                text = if (overview.remainingMs > 0) {
                    "还剩 ${formatMinutes(overview.remainingMs)}"
                } else {
                    "今日额度已用完"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (overview.remainingMs > 0) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )

            if (overview.extraMs > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "其中 ${formatMinutes(overview.extraMs)} 来自临时加时",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 被标记「不计入总时长」的应用，用量照实统计但不占额度。
            // 不把差额说出来，家长会以为报告算漏了 —— 而这恰恰是唯一需要解释的部分。
            if (overview.exemptMs > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "另有 ${formatMinutes(overview.exemptMs)} 不计入总时长（来自已豁免的应用）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    append("共 ${overview.appCount} 个应用 · 打开 ${overview.openCount} 次")
                    if (!overview.enforcementEnabled) append(" · 管控当前已暂停")
                    append(if (overview.listMode == "whitelist") " · 白名单模式" else " · 黑名单模式")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 近 N 日柱状图。
 *
 * 每根柱子上叠一条虚线标出当日额度 —— 只看绝对时长无法判断"是否失控"，
 * 必须和额度对照才有意义。
 */
@Composable
private fun TrendChart(trend: UsageTrendDto) {
    val points = trend.points
    if (points.isEmpty()) {
        EmptyHint("暂无趋势数据")
        return
    }

    val barColor = MaterialTheme.colorScheme.primary
    val limitColor = MaterialTheme.colorScheme.outlineVariant
    val maxValue = maxOf(
        points.maxOfOrNull { it.totalMs } ?: 0L,
        points.maxOfOrNull { it.limitMs } ?: 0L,
        1L,
    )

    Text(
        text = "共 ${formatMinutes(trend.totalMs)} · 日均 ${formatMinutes(trend.averageMs)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val count = points.size
        val slot = size.width / count
        val barWidth = (slot * 0.55f).coerceAtLeast(2f)

        points.forEachIndexed { index, point ->
            val left = slot * index + (slot - barWidth) / 2f

            // 额度参考线
            if (point.limitMs > 0) {
                val limitY = size.height - (point.limitMs.toFloat() / maxValue) * size.height
                drawLine(
                    color = limitColor,
                    start = Offset(left - 2f, limitY),
                    end = Offset(left + barWidth + 2f, limitY),
                    strokeWidth = 2f,
                )
            }

            // 实际使用柱
            val barHeight = (point.totalMs.toFloat() / maxValue) * size.height
            if (barHeight > 0f) {
                drawRect(
                    color = barColor,
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                )
            }
        }
    }

    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            points.first().dayKey.takeLast(5),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            points.last().dayKey.takeLast(5),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RankingRow(app: com.zzl.guardian.data.api.AppRankingDto, maxMs: Long) {
    val fraction = if (maxMs > 0) (app.totalMs.toFloat() / maxMs).coerceIn(0f, 1f) else 0f

    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                app.appLabel ?: app.packageName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(formatMinutes(app.totalMs), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(2.dp))
        Text(
            "打开 ${app.openCount} 次 · 有记录的 ${app.activeDays} 天 · 日均有记录时 ${formatMinutes(app.dailyAverageMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BlockRow(block: BlockLogDto) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                block.appLabel ?: block.packageName,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                block.reasonText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            formatTime(block.ts),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionRow(session: SessionDetailDto) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                session.appLabel ?: session.packageName,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "${formatTime(session.startTs)} - ${formatTime(session.endTs ?: session.startTs + session.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            // 时间线展示的是单条会话：被拦/切走频繁的应用会切成很多不足 1 分钟的小段，
            // 统一按分钟取整会显示成一排「0 分钟」，家长会误以为没有计时（真机反馈）。
            formatDurationPrecise(session.durationMs),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/* ---------------- 格式化 ---------------- */

internal fun formatMinutes(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(0)
    return when {
        minutes >= 60 -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
        else -> "$minutes 分钟"
    }
}

/** 单条会话的时长：不足 1 分钟时显示秒数，避免时间线出现一排「0 分钟」 */
internal fun formatDurationPrecise(ms: Long): String =
    if (ms in 0 until 60_000) "${ms / 1000} 秒" else formatMinutes(ms)

internal fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

/** 趋势图横轴标签需要的「月-日」，目前只用到日，保留以便后续扩展 */
@Suppress("unused")
internal fun dayLabel(timestamp: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    return String.format(Locale.US, "%02d-%02d", calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH))
}

@Suppress("unused")
private val unusedColor: Color = Color.Unspecified
