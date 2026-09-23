package com.zzl.guardian.parent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.zzl.guardian.data.api.CommandDto
import com.zzl.guardian.data.api.GrantDto
import com.zzl.guardian.data.api.InstalledAppDto

/**
 * 「限制工具」面板：家长的临时手段。
 *
 * 与「时长规则」的区别在于**时效性**：规则是长期策略，这里是"现在就做点什么"。
 * 因此每个动作都要求选择时长 —— 临时手段必须有终点，
 * 否则"临时放行"会悄悄变成永久放行，这是这类产品最常见的失控点。
 */
@Composable
internal fun DeviceToolsDialog(
    deviceName: String,
    deviceLocked: Boolean,
    iconHidden: Boolean,
    grants: List<GrantDto>,
    commands: List<CommandDto>,
    installedApps: List<InstalledAppDto>,
    busy: Boolean,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    onCreateGrant: (scope: String, packageName: String?, appLabel: String?, extraMinutes: Int?, ttlMinutes: Int?) -> Unit,
    onRevokeGrant: (Long) -> Unit,
    onToggleIcon: (hidden: Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pickingApp by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("限制工具 · $deviceName") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                /* ---------- 立即锁定 ---------- */
                SectionTitle("立即锁定")
                Text(
                    "锁定后设备上的所有应用立即无法使用，直到你在这里解除。适合「该写作业了」这种场景。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (deviceLocked) {
                        Button(onClick = onUnlock, enabled = !busy) { Text("解除锁定") }
                        Text(
                            "当前已锁定",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    } else {
                        Button(
                            onClick = onLock,
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("立即锁定") }
                    }
                }

                Spacer(Modifier.height(18.dp))

                /* ---------- 临时加时 ---------- */
                SectionTitle("临时加时")
                Text(
                    "只增加今日总时长上限，其他规则不变。过了今天自动失效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { minutes ->
                        OutlinedButton(
                            onClick = { onCreateGrant("total_add", null, null, minutes, 12 * 60) },
                            enabled = !busy,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        ) { Text("+$minutes 分钟") }
                    }
                }

                Spacer(Modifier.height(18.dp))

                /* ---------- 单应用放行 ---------- */
                SectionTitle("单独放行某个应用")
                Text(
                    "让该应用不受黑白名单与它自己的时长/时段规则限制，"
                        + "但仍然算在今日总时长里。想给它无限使用，请同时加时。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (pickingApp) {
                    AppPicker(
                        apps = installedApps,
                        onPick = { app ->
                            pickingApp = false
                            onCreateGrant("app_allow", app.packageName, app.appLabel, null, 30)
                        },
                        onDismiss = { pickingApp = false },
                    )
                } else {
                    OutlinedButton(onClick = { pickingApp = true }, enabled = !busy) {
                        Text("选择应用并放行 30 分钟")
                    }
                }

                Spacer(Modifier.height(18.dp))

                /* ---------- 临时总解封 ---------- */
                SectionTitle("临时解除全部限制")
                Text(
                    "在选定时间内完全关闭管控（含名单、时长、时段）。"
                        + "这是最强的一档，建议只在特殊情况下短暂使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60).forEach { minutes ->
                        OutlinedButton(
                            onClick = { onCreateGrant("unlock", null, null, null, minutes) },
                            enabled = !busy,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        ) { Text("解除 $minutes 分钟") }
                    }
                }

                Spacer(Modifier.height(18.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                /* ---------- 桌面图标隐藏 ---------- */
                SectionTitle("桌面图标")
                Text(
                    text = if (iconHidden) {
                        "图标当前已隐藏（重启、离线都不会失效）。孩子看不到入口；" +
                            "已开启密码门禁时，从应用列表打开也要先输家长密码。"
                    } else {
                        "隐藏孩子的桌面图标，防止孩子自行打开本应用的设置。" +
                            "管控与拦截不受影响，你随时可以从这里恢复。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onToggleIcon(!iconHidden) },
                    enabled = !busy,
                ) {
                    Text(if (iconHidden) "恢复桌面图标" else "隐藏桌面图标")
                }

                Spacer(Modifier.height(18.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                /* ---------- 生效中的授权 ---------- */
                SectionTitle("生效中的授权")
                val active = grants.filter { it.active }
                if (active.isEmpty()) {
                    Text(
                        "当前没有临时授权",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    active.forEach { grant ->
                        GrantRow(grant = grant, busy = busy, onRevoke = { onRevokeGrant(grant.id) })
                    }
                }

                Spacer(Modifier.height(18.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                /* ---------- 指令执行状态 ---------- */
                SectionTitle("下发的指令")
                Text(
                    "离线设备会在上线后自动补执行；超过有效期的指令显示为已失效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (commands.isEmpty()) {
                    Text(
                        "暂无指令记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    commands.take(8).forEach { command -> CommandRow(command) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh, enabled = !busy) { Text("刷新") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun GrantRow(grant: GrantDto, busy: Boolean, onRevoke: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(grantLabel(grant), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    grant.expireAt?.let { append("剩余 ").append(remainingMinutes(it)).append(" 分钟") }
                        ?: append("长期有效")
                    if (grant.source == "request") append(" · 由孩子申请")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRevoke, enabled = !busy) { Text("撤销") }
    }
}

@Composable
private fun CommandRow(command: CommandDto) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(commandLabel(command.type), style = MaterialTheme.typography.bodySmall)
            if (command.status == "failed") {
                Text(
                    "失败：${commandFailureText(command)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Text(
            text = when (command.status) {
                "done" -> "已执行"
                "failed" -> "执行失败"
                "expired" -> "已失效"
                "sent" -> "已下发"
                else -> "待下发"
            },
            style = MaterialTheme.typography.labelSmall,
            color = when (command.status) {
                "done" -> MaterialTheme.colorScheme.secondary
                "failed", "expired" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 指令失败原因是 JSON，取其中的 detail 字段即可；取不到就显示原文 */
private fun commandFailureText(command: CommandDto): String {
    val raw = command.result?.toString().orEmpty()
    val match = Regex("\"detail\"\\s*:\\s*\"([^\"]*)\"").find(raw)
    return match?.groupValues?.get(1) ?: "未知原因"
}

private fun commandLabel(type: String): String = when (type) {
    "immediate_lock" -> "立即锁定"
    "clear_lock" -> "解除锁定"
    "request_installed_apps" -> "重新获取应用清单"
    "screenshot" -> "查看屏幕"
    else -> type
}

private fun grantLabel(grant: GrantDto): String = when (grant.scope) {
    "total_add" -> "加时 ${grant.extraMinutes ?: 0} 分钟"
    "app_allow" -> "放行「${grant.appLabel ?: grant.packageName ?: "应用"}」"
    "unlock" -> "临时解除全部限制"
    else -> "临时授权"
}

/**
 * 应用选择器。
 *
 * 用弹窗而不是内嵌列表：工具面板本身已经很长，再塞一百个应用进去
 * 会让家长找不到自己要的东西。
 */
@Composable
private fun AppPicker(
    apps: List<InstalledAppDto>,
    onPick: (InstalledAppDto) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    val filtered = remember(apps, keyword) {
        if (keyword.isBlank()) {
            apps
        } else {
            apps.filter {
                it.packageName.contains(keyword, ignoreCase = true) ||
                    (it.appLabel ?: "").contains(keyword, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要放行的应用") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text("搜索") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                if (apps.isEmpty()) {
                    Text(
                        "还没有收到设备的应用清单。请确认被控端在线，然后在「应用管控」里点「重新获取」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }

                LazyColumn {
                    items(filtered, key = { it.packageName }) { app ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable { onPick(app) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    app.appLabel ?: app.packageName,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/* ---------------- 供同一包内其他面板复用的小工具 ---------------- */

internal fun remainingMinutes(expireAt: Long): Long =
    ((expireAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
