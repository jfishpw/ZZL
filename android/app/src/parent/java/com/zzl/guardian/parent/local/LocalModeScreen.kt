package com.zzl.guardian.parent.local

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 本地模式一期界面（docs/本地模式设计.md §5/§8）：
 *  - 配对：输入被控端通知里的「设备码 + 令牌 + IP:端口」
 *  - 连接后：实时状态（前台应用/已用/上限/锁定态）+ 锁定/解锁 + 总时长顶层策略编辑
 *  - 报告/截屏/审计/加时/逐应用规则 → 二期
 *
 * 自包含组件：不触碰 ParentViewModel 与服务器模式的任何代码路径。
 */
@Composable
fun LocalModeScreen(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { LocalParentStore(context) }
    val transport = remember { LocalTransport(store) }

    var paired by remember { mutableStateOf(store.paired() != null) }
    var deviceCode by remember { mutableStateOf(store.paired()?.deviceCode ?: "") }
    var token by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf(store.paired()?.ip ?: "") }
    var port by remember { mutableStateOf((store.paired()?.port ?: 9527).toString()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    var foreground by remember { mutableStateOf<String?>(null) }
    var usedTodayMs by remember { mutableStateOf(0L) }
    var limitMs by remember { mutableStateOf(0L) }
    var locked by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }

    var weekdayMin by remember { mutableStateOf("") }
    var weekendMin by remember { mutableStateOf("") }
    var resetHour by remember { mutableStateOf("") }
    var policyEnabled by remember { mutableStateOf(true) }

    suspend fun refreshState() {
        val text = transport.getState()
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
        foreground = obj["foreground"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> !p.isString || p.content != "null" }?.content
        }
        usedTodayMs = obj["usedTodayMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        limitMs = obj["limitMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        locked = obj["locked"]?.jsonPrimitive?.content == "true"
        connected = true
    }

    suspend fun refreshPolicy() {
        val bundle = transport.getPolicy()
        weekdayMin = bundle.weekdayTotalMin.toString()
        weekendMin = bundle.weekendTotalMin.toString()
        resetHour = bundle.resetHour.toString()
        policyEnabled = bundle.enabled
    }

    // 连接后轮询实时状态（5 秒）
    LaunchedEffect(paired) {
        if (!paired) return@LaunchedEffect
        while (true) {
            runCatching { refreshState() }
                .onFailure { connected = false; message = "连接失败：${it.message}" }
            delay(5000)
        }
    }

    fun fmt(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60} 小时 ${m % 60} 分" else "$m 分钟"
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("本地模式", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("返回服务器模式") }
            }
            Text(
                "局域网直连被控端，无需服务器；两台设备需在同一 WiFi",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (!paired) {
                Text("① 配对：在平板上打开掌中灵被控端，从其通知里抄下这三项", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = deviceCode,
                    onValueChange = { deviceCode = it },
                    label = { Text("设备码（如 a1b2c3d4）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("配对令牌（6 位，单次有效）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        label = { Text("平板 IP") },
                        modifier = Modifier.weight(2f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("端口") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        busy = true; message = null
                        kotlinx.coroutines.MainScope().launch {
                            transport.pair(ip, port.toIntOrNull() ?: 9527, token, deviceCode)
                                .onSuccess {
                                    paired = true
                                    runCatching { refreshState(); refreshPolicy() }
                                }
                                .onFailure { message = "配对失败：${it.message}" }
                            busy = false
                        }
                    },
                    enabled = !busy && deviceCode.isNotBlank() && token.isNotBlank() && ip.isNotBlank(),
                ) { Text(if (busy) "配对中…" else "配对") }
            } else {
                val device = store.paired()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已连接：${device?.deviceCode ?: ""}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(if (connected) "● 在线" else "○ 离线", fontSize = 13.sp)
                }
                Text("地址 ${device?.ip}:${device?.port}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))

                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("实时状态", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text("当前前台：${foreground ?: "桌面 / 无"}")
                        Text("今日已用：${fmt(usedTodayMs)} / 上限 ${fmt(limitMs)}")
                        Text("设备锁定：${if (locked) "是" else "否"}")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { busy = true; message = null
                            kotlinx.coroutines.MainScope().launch {
                                runCatching { transport.command("lock") }
                                    .onSuccess { locked = true }
                                    .onFailure { message = "失败：${it.message}" }
                                busy = false
                            }
                        },
                        enabled = !busy,
                    ) { Text("立即锁定") }
                    OutlinedButton(
                        onClick = { busy = true; message = null
                            kotlinx.coroutines.MainScope().launch {
                                runCatching { transport.command("unlock") }
                                    .onSuccess { locked = false }
                                    .onFailure { message = "失败：${it.message}" }
                                busy = false
                            }
                        },
                        enabled = !busy,
                    ) { Text("解除锁定") }
                }
                Spacer(Modifier.height(16.dp))

                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("每日总时长（分钟）", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = weekdayMin, onValueChange = { weekdayMin = it },
                                label = { Text("工作日") }, modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            OutlinedTextField(
                                value = weekendMin, onValueChange = { weekendMin = it },
                                label = { Text("周末") }, modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            OutlinedTextField(
                                value = resetHour, onValueChange = { resetHour = it },
                                label = { Text("归零点(时)") }, modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = policyEnabled, onCheckedChange = { policyEnabled = it })
                            Spacer(Modifier.width(8.dp))
                            Text("启用管控总开关")
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                busy = true; message = null
                                kotlinx.coroutines.MainScope().launch {
                                    runCatching {
                                        val bundle = transport.getPolicy()
                                        transport.putPolicy(
                                            bundle.copy(
                                                weekdayTotalMin = weekdayMin.toIntOrNull() ?: bundle.weekdayTotalMin,
                                                weekendTotalMin = weekendMin.toIntOrNull() ?: bundle.weekendTotalMin,
                                                resetHour = resetHour.toIntOrNull() ?: bundle.resetHour,
                                                enabled = policyEnabled,
                                                version = bundle.version + 1,
                                                updatedAt = System.currentTimeMillis(),
                                            ),
                                        )
                                        message = "策略已下发并生效"
                                    }.onFailure { message = "下发失败：${it.message}" }
                                    busy = false
                                }
                            },
                            enabled = !busy,
                        ) { Text("下发策略") }
                        Text(
                            "逐应用规则 / 时段 / 名单编辑将在后续版本提供（当前请用服务器模式或直接下发顶层策略）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = {
                    store.clear(); paired = false; connected = false
                }) { Text("解除配对") }
            }

            message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }
    }
}
