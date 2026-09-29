@file:OptIn(ExperimentalMaterial3Api::class)

package com.zzl.guardian.parent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zzl.guardian.data.api.AppRankingDto
import com.zzl.guardian.data.api.BlockLogDto
import com.zzl.guardian.data.api.DailyUsageDto
import com.zzl.guardian.data.api.SessionDetailDto
import com.zzl.guardian.data.api.UsageOverviewDto
import com.zzl.guardian.data.api.UsageRankingDto
import com.zzl.guardian.data.api.UsageTrendDto
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
    selectedDay: String?,
    rangeFrom: String?,
    rangeTo: String?,
    dayAppUsage: DailyUsageDto?,
    onDaysChange: (Int) -> Unit,
    onCustomRange: (String, String) -> Unit,
    onClearRange: () -> Unit,
    onSelectDay: (String) -> Unit,
    onClearDay: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 自定义范围的日期选择器：null = 关闭；FROM/TO = 正在选哪一端
    var rangePickerTarget by remember { mutableStateOf<RangePickerTarget?>(null) }

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
                    val customRange = rangeFrom != null && rangeTo != null
                    SectionHeader(
                        if (customRange) "${shortDay(rangeFrom)} ~ ${shortDay(rangeTo)} 趋势" else "近 $days 天趋势",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(7, 14, 30).forEach { option ->
                            FilterChip(
                                selected = !customRange && days == option,
                                onClick = { onDaysChange(option) },
                                label = { Text("$option 天") },
                            )
                        }
                        // 自定义范围：选中后展开日期选择行
                        FilterChip(
                            selected = customRange,
                            onClick = { if (customRange) onClearRange() else rangePickerTarget = RangePickerTarget.FROM },
                            label = { Text("自定义") },
                        )
                    }

                    if (customRange) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { rangePickerTarget = RangePickerTarget.FROM }) {
                                Text(shortDay(rangeFrom))
                            }
                            Text("~", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { rangePickerTarget = RangePickerTarget.TO }) {
                                Text(shortDay(rangeTo))
                            }
                            TextButton(onClick = onClearRange) { Text("重置") }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    trend?.let {
                        TrendChart(
                            trend = it,
                            selectedDay = selectedDay,
                            onSelectDay = onSelectDay,
                        )
                    }

                    // 按日筛选横幅：点柱子后生效，再点同一根柱子或点横幅取消
                    if (selectedDay != null) {
                        Spacer(Modifier.height(6.dp))
                        val dayTotal = trend?.points?.firstOrNull { it.dayKey == selectedDay }?.totalMs ?: 0L
                        FilterChip(
                            selected = true,
                            onClick = onClearDay,
                            label = { Text("只看 ${shortDay(selectedDay)} · 共 ${formatMinutes(dayTotal)}（点击取消）") },
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    SectionHeader(if (selectedDay != null) "${shortDay(selectedDay)} 应用排行" else "应用使用排行")
                    if (selectedDay != null) {
                        // 按日筛选：显示该日的单应用用量（服务端按日汇总）
                        val dayApps = dayAppUsage?.apps.orEmpty().sortedByDescending { it.totalMs }
                        if (dayApps.isEmpty()) {
                            EmptyHint("${shortDay(selectedDay)} 没有应用使用记录")
                        } else {
                            val maxMs = dayApps.maxOf { it.totalMs }.coerceAtLeast(1L)
                            dayApps.take(8).forEach { app ->
                                RankingRow(
                                    AppRankingDto(
                                        packageName = app.packageName,
                                        appLabel = app.appLabel,
                                        totalMs = app.totalMs,
                                        openCount = app.openCount,
                                        activeDays = 1,
                                        dailyAverageMs = app.totalMs,
                                    ),
                                    maxMs,
                                )
                            }
                            if ((dayAppUsage?.exemptMs ?: 0L) > 0L) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "另有 ${formatMinutes(dayAppUsage?.exemptMs ?: 0L)} 来自不计入总时长的应用",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        val apps = ranking?.apps.orEmpty()
                        if (apps.isEmpty()) {
                            EmptyHint("这段时间还没有使用记录")
                        } else {
                            apps.take(8).forEach { app -> RankingRow(app, apps.first().totalMs) }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    SectionHeader(if (selectedDay != null) "${shortDay(selectedDay)} 被拦截记录" else "被拦截记录")
                    if (blocks.isEmpty()) {
                        EmptyHint("没有被拦截的记录，管控暂时没有触发")
                    } else {
                        blocks.take(12).forEach { block -> BlockRow(block) }
                    }

                    Spacer(Modifier.height(16.dp))
                    SectionHeader(if (selectedDay != null) "${shortDay(selectedDay)} 使用明细" else "最近使用时间线")
                    if (sessions.isEmpty()) {
                        EmptyHint("暂无会话明细")
                    } else {
                        sessions.take(30).forEach { session -> SessionRow(session) }
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

    // 自定义范围的日期选择弹窗（与报告对话框平级，选一端关一次）
    rangePickerTarget?.let { target ->
        DatePickerDialog(
            onDismissRequest = { rangePickerTarget = null },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { rangePickerTarget = null }) { Text("取消") }
            },
        ) {
            val initialMillis = when (target) {
                RangePickerTarget.FROM -> rangeFrom?.let(::dayKeyToUtcMillis)
                RangePickerTarget.TO -> rangeTo?.let(::dayKeyToUtcMillis)
                null -> null
            } ?: System.currentTimeMillis()
            val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
            DatePicker(
                state = pickerState,
                title = {
                    Text(
                        if (target == RangePickerTarget.FROM) "选择开始日期" else "选择结束日期",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                },
                showModeToggle = false,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        val millis = pickerState.selectedDateMillis ?: return@TextButton
                        val picked = utcMillisToDayKey(millis)
                        when (target) {
                            RangePickerTarget.FROM -> {
                                // 开始晚于已有结束：把结束跟着抬过来，保证 from<=to
                                val to = rangeTo ?: picked
                                onCustomRange(picked, if (picked > to) picked else to)
                            }
                            RangePickerTarget.TO -> {
                                val from = rangeFrom ?: picked
                                onCustomRange(if (picked < from) picked else from, picked)
                            }
                            null -> Unit
                        }
                        rangePickerTarget = null
                    },
                ) { Text("确定") }
            }
        }
    }
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
 *
 * 支持点击柱子按日筛选下方明细：pointerInput 里把点击横坐标换算成柱子序号。
 * 再点同一根柱子由上层取消筛选（selectReportDay 内处理）。
 */
@Composable
private fun TrendChart(
    trend: UsageTrendDto,
    selectedDay: String? = null,
    onSelectDay: (String) -> Unit = {},
) {
    val points = trend.points
    if (points.isEmpty()) {
        EmptyHint("暂无趋势数据")
        return
    }

    val barColor = MaterialTheme.colorScheme.primary
    val dimColor = barColor.copy(alpha = 0.32f)
    val limitColor = MaterialTheme.colorScheme.outlineVariant
    val maxValue = maxOf(
        points.maxOfOrNull { it.totalMs } ?: 0L,
        points.maxOfOrNull { it.limitMs } ?: 0L,
        1L,
    )

    Text(
        text = "共 ${formatMinutes(trend.totalMs)} · 日均 ${formatMinutes(trend.averageMs)} · 点击柱子可按日筛选",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .pointerInput(points) {
                detectTapGestures { offset ->
                    val slot = size.width.toFloat() / points.size
                    val index = (offset.x / slot).toInt().coerceIn(0, points.size - 1)
                    onSelectDay(points[index].dayKey)
                }
            },
    ) {
        val count = points.size
        val slot = size.width / count
        val barWidth = (slot * 0.55f).coerceAtLeast(2f)

        points.forEachIndexed { index, point ->
            val left = slot * index + (slot - barWidth) / 2f
            val isSelected = point.dayKey == selectedDay

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

            // 实际使用柱：选中日高亮，其余淡化
            val barHeight = (point.totalMs.toFloat() / maxValue) * size.height
            if (barHeight > 0f) {
                drawRect(
                    color = if (selectedDay == null || isSelected) barColor else dimColor,
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
                if (session.endTs == null) "${formatTime(session.startTs)} - 进行中"
                else "${formatTime(session.startTs)} - ${formatTime(session.endTs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            // 时间线展示的是聚合条目：碎片会话已按应用合并（segments>1 表示反复抢前台）。
            // 不足 1 分钟显示秒数，避免一排「0 分钟」（真机反馈）。
            if (session.segments > 1)
                "${formatDurationPrecise(session.durationMs)}（${session.segments}段）"
            else
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

/* ---------------- 日期范围（自定义筛选） ---------------- */

/** dayKey 的短显示："2026-09-26" → "09-26" */
internal fun shortDay(dayKey: String?): String = dayKey?.takeLast(5) ?: ""

/**
 * dayKey → DatePicker 期望的毫秒值。
 * Material3 DatePicker 内部按 UTC 零点对齐，必须用 UTC 换算，
 * 用本地时区会出现差一天的初始选中。
 */
internal fun dayKeyToUtcMillis(dayKey: String): Long = runCatching {
    val parts = dayKey.split('-').map { it.toInt() }
    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(parts[0], parts[1] - 1, parts[2], 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}.getOrDefault(System.currentTimeMillis())

/** DatePicker 的毫秒值 → dayKey（同样按 UTC 解，与上面成对） */
internal fun utcMillisToDayKey(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(millis))

/** 自定义范围选择器正在选哪一端 */
private enum class RangePickerTarget { FROM, TO }

@Suppress("unused")
private val unusedColor: Color = Color.Unspecified
