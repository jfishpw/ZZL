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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zzl.guardian.data.api.HardeningDto
import com.zzl.guardian.data.api.PinAttemptDto
import com.zzl.guardian.data.pin.PinPolicy
import com.zzl.guardian.data.api.PinBackupDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备加固面板。
 *
 * 家长在这个面板上要回答三个问题：
 *   1. 这台设备的管控有多"硬"？（当前模式 + 能做什么/做不到什么）
 *   2. 我忘了密码怎么办？（离线密码的管理与远程重置）
 *   3. 有没有人在试密码？（尝试记录）
 *
 * 刻意把"能力矩阵"完整展示而不是只写一句"已加固"：
 * 两种模式的强度差距是数量级的，家长必须知道自己站在哪一档，
 * 否则会误以为"装了 App 就万事大吉"，而实际孩子可能几分钟就绕过了。
 */
@Composable
internal fun DeviceHardeningDialog(
    deviceName: String,
    hardening: HardeningDto?,
    pins: PinBackupDto?,
    attempts: List<PinAttemptDto>,
    failedTotal: Int,
    suspicious: Boolean,
    busy: Boolean,
    onClearPin: (level: Int) -> Unit,
    onSetPin: (level: Int, pin: String, hint: String?) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var settingLevel by remember { mutableStateOf<Int?>(null) }
    var clearingLevel by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设备加固 · $deviceName") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (hardening == null) {
                    Text(
                        if (busy) "正在读取…" else "暂无数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    /* ---------- 当前管控强度 ---------- */
                    ModeCard(hardening)

                    Spacer(Modifier.height(16.dp))

                    /* ---------- 保活健康度 ---------- */
                    KeepaliveCard(hardening)

                    Spacer(Modifier.height(16.dp))

                    /* ---------- 离线密码 ---------- */
                    SectionHeader("离线密码")
                    Text(
                        "断网或家长不在身边时，可在设备上输入密码临时加时、放行应用。"
                            + "三级密码权限递增：日常只能加时，管理可放行应用，超级还能退出管控。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    val levels = pins?.levels.orEmpty()
                    if (levels.isEmpty()) {
                        WarnRow("尚未设置离线密码。一旦断网，家长将无法在设备上临时放行，只能等网络恢复。")
                        Spacer(Modifier.height(4.dp))
                    }

                    // 三个级别**固定渲染**：已设置的显示提示语与「重置/清除」，
                    // 未设置的也显示「设置」按钮。之前只在 levels 非空时才渲染行——
                    // 一个密码都没设过的设备整块没有任何按钮，家长被
                    // 「未设置」的警告困住却无路可走（真机反馈 2026-09-20）。
                    (1..3).forEach { lv ->
                        val level = levels.firstOrNull { it.level == lv }
                        val capability = pins?.capabilities?.get(lv.toString())
                        PinLevelRow(
                            level = lv,
                            name = level?.name?.takeIf { it.isNotBlank() }
                                ?: capability?.name?.takeIf { it.isNotBlank() }
                                ?: "第 $lv 级",
                            actions = level?.actions?.takeIf { it.isNotEmpty() }
                                ?: capability?.actions.orEmpty(),
                            hint = level?.hint,
                            set = level != null,
                            busy = busy,
                            onSet = { settingLevel = lv },
                            onClear = level?.let { { clearingLevel = lv } },
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    /* ---------- 密码尝试记录 ---------- */
                    SectionHeader("密码尝试记录")
                    if (suspicious) {
                        WarnRow(
                            "有 $failedTotal 次密码输入失败。如果这不是你操作的，" +
                                "说明孩子可能在尝试猜密码 —— 建议立即重置全部级别。",
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (attempts.isEmpty()) {
                        Text(
                            "还没有任何密码输入记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        attempts.take(10).forEach { attempt -> AttemptRow(attempt) }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    /* ---------- Device Owner 激活指引 ---------- */
                    if (!hardening.deviceOwner) {
                        SectionHeader("升级到最强防护")
                        Text(
                            "当前模式下，孩子仍可在系统设置里取消激活后卸载。"
                                + "升级为「设备所有者」后，卸载入口会被系统彻底移除。"
                                + "需要一台电脑，用数据线连接设备后执行：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        hardening.activationHint?.deviceOwner?.forEach { cmd ->
                            CommandBox(cmd)
                        }
                        hardening.activationHint?.notes?.forEach { note ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "· $note",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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

    settingLevel?.let { level ->
        SetPinDialog(
            level = level,
            busy = busy,
            onConfirm = { pin, hint ->
                settingLevel = null
                onSetPin(level, pin, hint)
            },
            onDismiss = { settingLevel = null },
        )
    }

    clearingLevel?.let { level ->
        AlertDialog(
            onDismissRequest = { clearingLevel = null },
            title = { Text("清除第 $level 级密码？") },
            text = {
                Text(
                    "清除后这一级的能力将无法在设备上使用。\n\n"
                        + "如果你自己也忘了密码，清除后需要重新设置一个新密码并告知家人。\n"
                        + "设备离线时清除会在其上线后生效，期间旧密码仍然可用。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clearingLevel = null
                    onClearPin(level)
                }) { Text("确认清除") }
            },
            dismissButton = {
                TextButton(onClick = { clearingLevel = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ModeCard(hardening: HardeningDto) {
    val cap = hardening.capabilities
    val isStrong = hardening.effectiveAdminMode == "device_owner"
    val isNone = hardening.effectiveAdminMode == "none"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isStrong -> MaterialTheme.colorScheme.secondaryContainer
                isNone -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cap?.label ?: "未知模式",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "防护强度：${cap?.strength ?: "—"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("可以做到", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            cap?.can?.forEach { item ->
                Text(
                    "· $item",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            cap?.limits?.takeIf { it.isNotEmpty() }?.let { limits ->
                Spacer(Modifier.height(8.dp))
                Text("做不到", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                limits.forEach { item ->
                    Text(
                        "· $item",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            cap?.risks?.takeIf { it.isNotEmpty() }?.let { risks ->
                Spacer(Modifier.height(8.dp))
                Text("需要注意", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                risks.forEach { item ->
                    Text(
                        "· $item",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append("阻止卸载：")
                    append(if (hardening.uninstallBlocked) "已生效" else "未生效")
                    append(" · 离线密码：")
                    append(if (hardening.pinReady) "已设置" else "未设置")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeepaliveCard(hardening: HardeningDto) {
    val keepalive = hardening.keepalive
    if (keepalive == null) return

    val items = buildList {
        if (!keepalive.notificationEnabled) add("通知权限未开启")
        if (!keepalive.batteryOptimized) add("未加入电池优化白名单")
        if (!keepalive.exactAlarmAllowed) add("精确闹钟权限未开启")
        if (!keepalive.foregroundService) add("管控服务未在运行")
        if (!keepalive.vendorWhitelistConfirmed) add("未确认厂商后台白名单（${keepalive.vendor}）")
    }

    SectionHeader("后台存活")
    if (items.isEmpty()) {
        Text(
            "五项保活设置均已到位，后台被系统清理的风险很低。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    } else {
        Text(
            "以下设置缺失会让管控服务被系统清掉，设备看起来「不管用了」：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        items.forEach { item ->
            Text(
                "· $item",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        keepalive.vendorHint?.let { hint ->
            Spacer(Modifier.height(6.dp))
            Text(
                "${keepalive.vendor} 的设置路径：\n$hint",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PinLevelRow(
    level: Int,
    name: String,
    actions: List<String>,
    hint: String?,
    /** 该级是否已设置。未设置时按钮文案是「设置」且不显示「清除」 */
    set: Boolean,
    busy: Boolean,
    onSet: () -> Unit,
    onClear: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (set) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text("$name（L$level）", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (!set) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "未设置",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                "可执行：${actions.joinToString("、")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        hint?.takeIf { it.isNotBlank() }?.let {
            Text(
                "提示语：$it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onSet,
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                colors = if (!set) {
                    ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                } else {
                    ButtonDefaults.outlinedButtonColors()
                },
            ) { Text(if (set) "重置" else "设置", style = MaterialTheme.typography.labelSmall) }
            if (set) {
                OutlinedButton(
                    onClick = { onClear?.invoke() },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("清除", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

/**
 * 控制端设定密码。
 *
 * 这里**没有"显示密码"开关**是刻意的：家长在手机上设密码时旁边往往就是孩子，
 * 明文回显会让整个过程失去意义。提示语是更好的选择。
 */
@Composable
private fun SetPinDialog(
    level: Int,
    busy: Boolean,
    onConfirm: (pin: String, hint: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }

    /**
     * 用与设备端**完全相同**的 `PinPolicy` 校验，而不是只数位数。
     *
     * 只校验长度的话，家长可以在这里远程设一个 `123456` ——
     * 设备端的强度策略就被整个绕过了。策略必须两端一致，
     * 这与 `PinCrypto` 必须两端一致是同一个理由。
     */
    val validation = remember(pin) {
        if (pin.isEmpty()) null else PinPolicy.validate(pin)
    }
    val mismatch = confirm.isNotEmpty() && pin != confirm
    val canSubmit = validation?.ok == true && pin == confirm && !busy

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置第 $level 级密码") },
        text = {
            Column {
                Text(
                    "请把新密码告知家人。设备离线时，只有这个密码能在设备上临时放行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> !c.isWhitespace() }.take(32) },
                    label = { Text("新密码（至少 ${PinPolicy.MIN_LENGTH} 位）") },
                    singleLine = true,
                    // 密码输入必须星号回显：设置时旁边往往就是孩子（明文会泄露）
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = validation?.ok == false,
                    supportingText = {
                        val v = validation
                        when {
                            v == null -> Text("不要用生日、连续数字或与其他级别相同的密码")
                            !v.ok -> Text(v.reason ?: "密码太简单，请换一个")
                            else -> Text(
                                buildString {
                                    append("强度：${v.strength.label}")
                                    v.advice?.let { append(" · ").append(it) }
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter { c -> !c.isWhitespace() }.take(32) },
                    label = { Text("再输一次") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = mismatch,
                    supportingText = { if (mismatch) Text("两次输入不一致") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = hint,
                    onValueChange = { hint = it.take(40) },
                    label = { Text("提示语（可选）") },
                    supportingText = { Text("会显示在设备的密码输入页，帮助家长回忆") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pin, hint.takeIf { it.isNotBlank() }) },
                enabled = canSubmit,
            ) { Text(if (busy) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AttemptRow(attempt: PinAttemptDto) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "第 ${attempt.level} 级 · ${PinEntrySourceLabel.of(attempt.source)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                formatDateTime(attempt.ts),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (attempt.success) "通过" else "失败",
            style = MaterialTheme.typography.labelSmall,
            color = if (attempt.success) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun WarnRow(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(10.dp),
        )
    }
}

@Composable
private fun CommandBox(command: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            command,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(10.dp),
        )
    }
}

/** 密码入口来源的展示文案，与被控端 `PinEntrySource` 保持一致 */
internal object PinEntrySourceLabel {
    fun of(source: String?): String = when (source) {
        "overlay" -> "拦截页连点标题"
        "corner" -> "拦截页长按角落"
        "dialer" -> "拨号盘暗码"
        else -> "未知入口"
    }
}

private fun formatDateTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

@Suppress("unused")
private val unusedColor: Color = Color.Unspecified
