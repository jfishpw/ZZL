package com.zzl.guardian.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zzl.guardian.data.api.AppRuleDto
import com.zzl.guardian.data.api.InstalledAppDto
import com.zzl.guardian.data.api.PolicyBundleDto
import com.zzl.guardian.data.api.TimeWindowDto

/** 界面上的规则草稿：数值一律用字符串存，允许用户输入到一半 */
private data class RuleDraft(
    val dailyLimitMin: String = "",
    val windows: List<Pair<String, String>> = emptyList(),
    val weekdaysMask: Int = 0b1111111,
    val enabled: Boolean = true,
    /** 该应用的用时不计入当日总时长 */
    val exemptTotal: Boolean = false,
) {
    /** 用户是否动过这一行 —— 动过就要保留草稿，否则输入"0"的瞬间会被清空 */
    val hasInput: Boolean
        get() = dailyLimitMin.isNotBlank() || windows.isNotEmpty() || exemptTotal

    /**
     * 是否值得下发给设备 —— 只填了 0 或全空的规则没有意义。
     *
     * 「只打开不计入总时长、不设任何上限」是一条完全有效的规则
     * （典型用法：学习类应用不受总时长约束），所以它必须计入这里；
     * 否则家长拨完开关一保存，这条规则会被静默丢掉，表现为"开关不生效"。
     */
    val isEffective: Boolean
        get() = (dailyLimitMin.toIntOrNull() ?: 0) > 0 || windows.isNotEmpty() || exemptTotal
}

private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 生效星期的可读摘要。
 *
 * FilterChip 的选中态在部分 ROM 上对比度不明显，家长反映"看不出点了没点"；
 * 一行明确的文字摘要（含全不选的红字警告）比依赖芯片配色更可靠。
 * 全不选（mask=0）时规则在任何一天都不生效 —— 这不是"每天"，必须显式说出来。
 */
private fun weekdaySummary(mask: Int): String = when {
    mask == 0 -> "未选择任何星期：该规则不会在任何一天生效"
    mask == 0b1111111 -> "点亮的星期 = 规则生效日（当前：每天生效）"
    else -> {
        val names = WEEKDAY_LABELS.filterIndexed { bit, _ -> (mask shr bit) and 1 == 1 }
        "点亮的星期 = 规则生效日（当前：仅周${names.joinToString("、")}）"
    }
}

/**
 * 应用管控：黑白名单 + 逐应用规则。
 *
 * 界面上「名单」与「规则」是两个独立维度，可以叠加：
 *   - 名单决定"这个应用能不能用"
 *   - 规则决定"能用的话，能用多久、什么时段能用"
 */
@Composable
fun AppControlDialog(
    deviceName: String,
    bundle: PolicyBundleDto?,
    installedApps: List<InstalledAppDto>,
    busy: Boolean,
    onSave: (listMode: String, listedPackages: Set<String>, rules: List<AppRuleDto>) -> Unit,
    onRefreshApps: () -> Unit,
    onDismiss: () -> Unit,
) {
    var listMode by remember(bundle) { mutableStateOf(bundle?.listMode ?: "blacklist") }
    var listed by remember(bundle) {
        mutableStateOf<Set<String>>(bundle?.listItems?.map { it.packageName }?.toSet() ?: emptySet())
    }
    // 显式指定类型参数：`?: emptyMap()` 会让 Kotlin 把 elvis 的结果推成 Map<out String, out RuleDraft>，
    // 进而导致 remember 的委托类型对不上
    var drafts by remember(bundle) {
        mutableStateOf<Map<String, RuleDraft>>(
            bundle?.appRules?.associate { rule ->
                rule.packageName to RuleDraft(
                    dailyLimitMin = if (rule.dailyLimitMin > 0) rule.dailyLimitMin.toString() else "",
                    windows = rule.timeWindows.map { it.start to it.end },
                    weekdaysMask = rule.weekdaysMask,
                    enabled = rule.enabled,
                    exemptTotal = rule.exemptTotal,
                )
            } ?: emptyMap(),
        )
    }
    var expanded by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("应用管控 · $deviceName") },
        text = {
            if (bundle == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在读取设备策略…", style = MaterialTheme.typography.bodySmall)
                }
                return@AlertDialog
            }

            // 固定高度是为了让内部的 LazyColumn 有确定的滚动空间；
            // 定太高会在小屏机型上把底部内容挤出弹窗可视区
            Column(Modifier.height(420.dp)) {
                // ---- 名单模式 ----
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = listMode == "blacklist",
                        onClick = { listMode = "blacklist" },
                        label = { Text("黑名单") },
                    )
                    FilterChip(
                        selected = listMode == "whitelist",
                        onClick = { listMode = "whitelist" },
                        label = { Text("白名单") },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (listMode == "blacklist") {
                        "名单内的应用一律禁止使用；名单外的应用按下方规则与总时长管控"
                    } else {
                        "仅名单内的应用可以使用；名单外的应用一律禁止"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 白名单为空等于"什么都用不了"，必须显著提示，避免家长误操作后以为设备坏了
                if (listMode == "whitelist" && listed.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "⚠ 白名单为空，保存后设备上所有应用都会被禁止使用（桌面仍可返回）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已安装 ${installedApps.size} 个 · 名单 ${listed.size} 个 · 生效规则 ${drafts.count { it.value.isEffective }} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRefreshApps) { Text("重新获取") }
                }

                HorizontalDivider()
                Spacer(Modifier.height(4.dp))

                if (installedApps.isEmpty()) {
                    Text(
                        "暂无应用清单。设备需在线并已完成一次同步后才会上报。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    return@Column
                }

                LazyColumn(Modifier.weight(1f)) {
                    items(installedApps, key = { it.packageName }) { app ->
                        AppRuleRow(
                            app = app,
                            inList = app.packageName in listed,
                            listBans = listMode == "blacklist",
                            draft = drafts[app.packageName] ?: RuleDraft(),
                            expanded = expanded == app.packageName,
                            onToggleList = { checked ->
                                listed = if (checked) listed + app.packageName else listed - app.packageName
                            },
                            onToggleExpand = {
                                expanded = if (expanded == app.packageName) null else app.packageName
                            },
                            onDraftChange = { draft ->
                                drafts = if (draft.hasInput) {
                                    drafts + (app.packageName to draft)
                                } else {
                                    drafts - app.packageName
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val rules = drafts
                        .filter { it.value.isEffective }
                        .map { (packageName, draft) ->
                            AppRuleDto(
                                packageName = packageName,
                                appLabel = installedApps.firstOrNull { it.packageName == packageName }?.appLabel,
                                dailyLimitMin = draft.dailyLimitMin.toIntOrNull() ?: 0,
                                timeWindows = draft.windows.map { TimeWindowDto(it.first, it.second) },
                                weekdaysMask = draft.weekdaysMask,
                                enabled = draft.enabled,
                                exemptTotal = draft.exemptTotal,
                            )
                        }
                    onSave(listMode, listed, rules)
                },
                enabled = !busy && bundle != null,
            ) {
                Text(if (busy) "保存中…" else "保存并下发")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/* ---------------- 单行 ---------------- */

@Composable
private fun AppRuleRow(
    app: InstalledAppDto,
    inList: Boolean,
    listBans: Boolean,
    draft: RuleDraft,
    expanded: Boolean,
    onToggleList: (Boolean) -> Unit,
    onToggleExpand: () -> Unit,
    onDraftChange: (RuleDraft) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = inList, onCheckedChange = onToggleList)

            Column(Modifier.weight(1f)) {
                Text(
                    text = app.appLabel ?: app.packageName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                Text(
                    text = buildString {
                        append(app.packageName)
                        if (app.isSystem) append(" · 系统")
                        if (draft.isEffective) append(" · 已设规则")
                        // 收起状态也要一眼看得见豁免：这是最容易忘记自己设过的一项
                        if (draft.exemptTotal) append(" · 不计入总时长")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            IconButton(onClick = onToggleExpand) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开规则",
                )
            }
        }

        if (expanded) {
            // 真机反馈：黑名单模式下勾选应用 + 设「单日上限」，家长以为后者能放宽前者，
            // 实际名单拦截优先级更高（一打开就拦）。必须在这里讲清楚。
            if (inList && listBans) {
                Text(
                    text = "该应用已在禁止名单中：一律禁止使用，下方的单日上限与时段都不会生效。" +
                        "想限量使用（如每天 5 分钟），请先取消勾选。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            RuleEditor(draft = draft, onChange = onDraftChange)
            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
        }
    }
}

/* ---------------- 规则编辑 ---------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditor(draft: RuleDraft, onChange: (RuleDraft) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 48.dp, end = 8.dp, bottom = 8.dp)) {
        OutlinedTextField(
            value = draft.dailyLimitMin,
            onValueChange = { text ->
                onChange(draft.copy(dailyLimitMin = text.filter(Char::isDigit).take(4)))
            },
            label = { Text("单日上限（分钟）") },
            supportingText = { Text("留空或 0 表示不单独限制；保存后从现在起还能用这么多分钟（当天已用量不计入）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        Text("允许使用的时段", style = MaterialTheme.typography.bodySmall)
        Text(
            "留空表示时段不受限；结束时间早于开始时间表示跨零点（如 22:00 - 07:00）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        draft.windows.forEachIndexed { index, window ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            ) {
                TimeField(
                    value = window.first,
                    label = "开始",
                    onValueChange = { value ->
                        val updated = draft.windows.toMutableList()
                        updated[index] = value to window.second
                        onChange(draft.copy(windows = updated))
                    },
                    modifier = Modifier.weight(1f),
                )
                Text("至", style = MaterialTheme.typography.bodySmall)
                TimeField(
                    value = window.second,
                    label = "结束",
                    onValueChange = { value ->
                        val updated = draft.windows.toMutableList()
                        updated[index] = window.first to value
                        onChange(draft.copy(windows = updated))
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        onChange(draft.copy(windows = draft.windows.filterIndexed { i, _ -> i != index }))
                    },
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除该时段", Modifier.size(18.dp))
                }
            }
        }

        TextButton(
            onClick = { onChange(draft.copy(windows = draft.windows + ("19:00" to "20:00"))) },
        ) { Text("添加时段") }

        Spacer(Modifier.height(6.dp))
        Text("生效星期", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        // FlowRow 而不是 Row：7 个 FilterChip 一行放不下窄屏对话框，
        // 周五/周六/周日会被裁掉看不见（真机反馈）。放不下时自动换行。
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WEEKDAY_LABELS.forEachIndexed { bit, label ->
                val active = (draft.weekdaysMask shr bit) and 1 == 1
                FilterChip(
                    selected = active,
                    onClick = {
                        val mask = if (active) {
                            draft.weekdaysMask and (1 shl bit).inv()
                        } else {
                            draft.weekdaysMask or (1 shl bit)
                        }
                        onChange(draft.copy(weekdaysMask = mask))
                    },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = weekdaySummary(draft.weekdaysMask),
            style = MaterialTheme.typography.labelSmall,
            color = if (draft.weekdaysMask == 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = draft.enabled,
                onCheckedChange = { onChange(draft.copy(enabled = it)) },
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "启用该规则",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Switch(
                checked = draft.exemptTotal,
                onCheckedChange = { onChange(draft.copy(exemptTotal = it)) },
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("用时不计入当日总时长", style = MaterialTheme.typography.bodyMedium)
                Text(
                    // 把边界写在界面上：家长最担心的就是"开了这个是不是就彻底管不住了"
                    "总时长用完后该应用仍可打开，它消耗的时间不占当日额度；" +
                        "上面的单日上限与允许时段照常生效，不会变成无限使用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** 极简时间输入：只收数字与冒号，自动补成 HH:MM */
@Composable
private fun TimeField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val cleaned = raw.filter { it.isDigit() || it == ':' }.take(5)
            onValueChange(cleaned)
        },
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
}
