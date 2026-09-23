package com.zzl.guardian.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzl.guardian.data.api.DeviceView
import com.zzl.guardian.data.api.PolicyBundleDto
import com.zzl.guardian.ui.serversettings.ServerSettingsDialog

@Composable
fun ParentHome(viewModel: ParentViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showServerSettings by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val message = state.error ?: state.notice
    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessages()
        }
    }

    if (showServerSettings) {
        ServerSettingsDialog(
            onDismiss = {
                showServerSettings = false
                // 切换服务器会作废凭据，必须重新读会话状态
                viewModel.reloadSession()
                viewModel.refreshDevices()
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ParentTopBar(
                title = if (state.session == null) "掌中灵 · 控制端" else (state.session?.username ?: ""),
                online = state.wsConnected,
                canLogout = state.session != null,
                pendingRequests = state.pendingRequestCount,
                onServerSettings = { showServerSettings = true },
                onRefresh = {
                    viewModel.refreshDevices()
                    viewModel.loadTimeRequests()
                },
                onTimeRequests = { viewModel.openTimeRequests() },
                onLogout = { viewModel.logout() },
            )
        },
        floatingActionButton = {
            if (state.session != null) {
                FloatingActionButton(onClick = { viewModel.requestPairCode() }) {
                    Icon(Icons.Filled.Add, contentDescription = "添加设备")
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.booting -> CenterProgress()
                state.session == null -> LoginScreen(
                    busy = state.busy,
                    serverDisplay = state.serverDisplay,
                    onLogin = viewModel::login,
                    onRegister = viewModel::register,
                    onServerSettings = { showServerSettings = true },
                )

                else -> DeviceList(
                    devices = state.devices,
                    serverDisplay = state.serverDisplay,
                    pendingRequestCount = state.pendingRequestCount,
                    weakDeviceCount = state.weakDevices.size,
                    onAdd = { viewModel.requestPairCode() },
                    onOpenPolicy = { viewModel.openPolicyEditor(it) },
                    onOpenAppControl = { viewModel.openAppControl(it) },
                    onOpenReport = { viewModel.openReport(it) },
                    onOpenTools = { viewModel.openTools(it) },
                    onOpenHardening = { viewModel.openHardening(it) },
                    onOpenScreenshots = { viewModel.openScreenshots(it) },
                    onOpenAudit = { viewModel.openAudit(it) },
                    onOpenTimeRequests = { viewModel.openTimeRequests() },
                    onDeleteDevice = { viewModel.requestDeleteDevice(it) },
                )
            }
        }
    }

    state.pairCode?.let { code ->
        PairCodeDialog(
            code = code.code,
            ttlSeconds = code.ttlSeconds,
            onDismiss = viewModel::dismissPairCode,
        )
    }

    if (state.editingDeviceId != null) {
        PolicyDialog(
            deviceName = state.editingDevice?.name ?: "该设备",
            policy = state.policy,
            busy = state.policyBusy,
            onSave = { weekday, weekend, resetHour, enabled, allowTimeRequest ->
                viewModel.savePolicy(
                    deviceId = state.editingDeviceId!!,
                    weekdayTotalMin = weekday,
                    weekendTotalMin = weekend,
                    resetHour = resetHour,
                    enabled = enabled,
                    allowTimeRequest = allowTimeRequest,
                )
            },
            onDismiss = viewModel::closePolicyEditor,
        )
    }

    if (state.appControlDeviceId != null) {
        AppControlDialog(
            deviceName = state.appControlDevice?.name ?: "该设备",
            bundle = state.bundle,
            installedApps = state.installedApps,
            busy = state.appControlBusy,
            onSave = { listMode, listed, rules ->
                viewModel.saveAppControl(state.appControlDeviceId!!, listMode, listed, rules)
            },
            onRefreshApps = { viewModel.requestInstalledAppsRefresh(state.appControlDeviceId!!) },
            onDismiss = viewModel::closeAppControl,
        )
    }

    if (state.toolsDeviceId != null) {
        DeviceToolsDialog(
            deviceName = state.toolsDevice?.name ?: "该设备",
            deviceLocked = state.toolsDevice?.locked == true,
            iconHidden = state.toolsDevice?.iconHidden == true,
            grants = state.grants,
            commands = state.commands,
            installedApps = state.installedApps,
            busy = state.toolsBusy,
            onLock = { viewModel.lockNow(state.toolsDeviceId!!) },
            onUnlock = { viewModel.unlock(state.toolsDeviceId!!) },
            onCreateGrant = { scope, packageName, appLabel, extraMinutes, ttlMinutes ->
                viewModel.createGrant(
                    deviceId = state.toolsDeviceId!!,
                    scope = scope,
                    packageName = packageName,
                    appLabel = appLabel,
                    extraMinutes = extraMinutes,
                    ttlMinutes = ttlMinutes,
                )
            },
            onRevokeGrant = { viewModel.revokeGrant(it, state.toolsDeviceId!!) },
            onToggleIcon = { hidden -> viewModel.setDeviceIcon(state.toolsDeviceId!!, hidden) },
            onRefresh = { viewModel.loadTools(state.toolsDeviceId!!) },
            onDismiss = viewModel::closeTools,
        )
    }

    if (state.reportDeviceId != null) {
        UsageReportDialog(
            deviceName = state.reportDevice?.name ?: "该设备",
            days = state.reportDays,
            overview = state.overview,
            trend = state.trend,
            ranking = state.ranking,
            sessions = state.sessions,
            blocks = state.blocks,
            busy = state.reportBusy,
            onDaysChange = viewModel::setReportDays,
            onRefresh = { viewModel.loadReport(state.reportDeviceId!!) },
            onDismiss = viewModel::closeReport,
        )
    }

    if (state.showTimeRequests) {
        TimeRequestsDialog(
            requests = state.timeRequests,
            decidingId = state.decidingId,
            onDecide = { id, approve, decidedMin -> viewModel.decideTimeRequest(id, approve, decidedMin) },
            onRefresh = { viewModel.loadTimeRequests() },
            onDismiss = viewModel::closeTimeRequests,
        )
    }

    if (state.hardeningDeviceId != null) {
        DeviceHardeningDialog(
            deviceName = state.hardeningDevice?.name ?: "该设备",
            hardening = state.hardening,
            pins = state.pins,
            attempts = state.pinAttempts,
            failedTotal = state.pinFailedTotal,
            suspicious = state.pinSuspicious,
            busy = state.hardeningBusy,
            onClearPin = { level -> viewModel.clearPin(state.hardeningDeviceId!!, level) },
            onSetPin = { level, pin, hint -> viewModel.setPin(state.hardeningDeviceId!!, level, pin, hint) },
            onRefresh = { viewModel.loadHardening(state.hardeningDeviceId!!) },
            onDismiss = viewModel::closeHardening,
        )
    }

    if (state.screenshotDeviceId != null) {
        val deviceId = state.screenshotDeviceId!!
        ScreenshotDialog(
            deviceName = state.screenshotDevice?.name ?: "该设备",
            screenshots = state.screenshots,
            busy = state.screenshotsBusy,
            capturePending = state.capturePending,
            captureNotice = state.captureNotice,
            // 图片字节带着家长令牌加载 —— 界面只管拿 Bitmap，不关心鉴权细节
            loadImage = { path -> viewModel.loadImageByPath(path) },
            onCapture = { viewModel.requestScreenshot(deviceId) },
            onDelete = { shotId -> viewModel.deleteScreenshot(deviceId, shotId) },
            onRefresh = { viewModel.loadScreenshots(deviceId) },
            onDismiss = viewModel::closeScreenshots,
        )
    }

    if (state.showAudit) {
        AuditDialog(
            deviceName = state.auditDevice?.name,
            logs = state.auditLogs,
            summary = state.auditSummary,
            warnOnly = state.auditWarnOnly,
            busy = state.auditBusy,
            onToggleWarnOnly = viewModel::toggleAuditWarnOnly,
            onRefresh = viewModel::loadAudit,
            onDismiss = viewModel::closeAudit,
        )
    }

    state.deletingDevice?.let { device ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteDevice,
            title = { Text("删除「${device.name}」？") },
            text = {
                Text(
                    "将删除这条设备记录，包括它的策略、使用记录与截屏，且无法恢复。\n\n" +
                        "适合在被控端已解除绑定后清理列表；若设备仍在管控中，删除后它将连不上服务器。\n" +
                        "设备以后重新配对：记录已删除就新增一条，还没删除就沿用原记录。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmDeleteDevice,
                    enabled = !state.deleteBusy,
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteDevice) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentTopBar(
    title: String,
    online: Boolean,
    canLogout: Boolean,
    pendingRequests: Int,
    onServerSettings: () -> Unit,
    onRefresh: () -> Unit,
    onTimeRequests: () -> Unit,
    onLogout: () -> Unit,
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title)
                if (online) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.secondary, CircleShape),
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onServerSettings) {
                Icon(Icons.Filled.Dns, contentDescription = "服务器设置")
            }
            if (canLogout) {
                // 待审批申请带角标：孩子提交后家长可能正在别的界面
                BadgedBox(
                    badge = {
                        if (pendingRequests > 0) {
                            Badge { Text(pendingRequests.toString()) }
                        }
                    },
                ) {
                    IconButton(onClick = onTimeRequests) {
                        Icon(Icons.Filled.Notifications, contentDescription = "加时申请")
                    }
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
                IconButton(onClick = onLogout) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "退出登录")
                }
            }
        },
    )
}

@Composable
private fun CenterProgress() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/* ---------------- 登录 ---------------- */

@Composable
private fun LoginScreen(
    busy: Boolean,
    serverDisplay: String,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String) -> Unit,
    onServerSettings: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("家长账号", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            "登录后即可管控已绑定的儿童平板",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onLogin(username, password) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("登录")
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { onRegister(username, password) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("注册新账号")
        }

        Spacer(Modifier.height(24.dp))

        TextButton(onClick = onServerSettings, modifier = Modifier.fillMaxWidth()) {
            Text("服务器设置 · $serverDisplay", fontSize = 13.sp)
        }
    }
}

/* ---------------- 设备列表 ---------------- */

@Composable
private fun DeviceList(
    devices: List<DeviceView>,
    serverDisplay: String,
    pendingRequestCount: Int,
    weakDeviceCount: Int,
    onAdd: () -> Unit,
    onOpenPolicy: (Long) -> Unit,
    onOpenAppControl: (Long) -> Unit,
    onOpenReport: (Long) -> Unit,
    onOpenTools: (Long) -> Unit,
    onOpenHardening: (Long) -> Unit,
    onOpenScreenshots: (Long) -> Unit,
    onOpenAudit: (Long) -> Unit,
    onOpenTimeRequests: () -> Unit,
    onDeleteDevice: (Long) -> Unit,
) {
    if (devices.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("还没有绑定设备", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "在被控端安装「掌中灵」，点击右下角按钮生成配对码完成绑定",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAdd) { Text("生成配对码") }
            Spacer(Modifier.height(28.dp))
            Text("当前服务器：$serverDisplay", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 孩子的加时申请是会自己过期的东西，放在最上面，家长一打开就看到
        if (pendingRequestCount > 0) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenTimeRequests),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "有 $pendingRequestCount 条加时申请待处理",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "超过审批时限会自动作废，请尽快处理",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "去处理",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // 管控强度不足是"看起来装好了、其实能被绕过"的隐患，值得单独提醒
        if (weakDeviceCount > 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "有 $weakDeviceCount 台设备的防护不完整",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "可能是未激活设备管理器（可被直接卸载），或未设置离线密码"
                                + "（断网时无法临时放行）。点设备卡片上的「设置离线密码 / 设备加固」处理。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        items(devices, key = { it.id }) { device ->
            DeviceCard(
                device = device,
                onOpenPolicy = { onOpenPolicy(device.id) },
                onOpenAppControl = { onOpenAppControl(device.id) },
                onOpenReport = { onOpenReport(device.id) },
                onOpenTools = { onOpenTools(device.id) },
                onOpenHardening = { onOpenHardening(device.id) },
                onOpenScreenshots = { onOpenScreenshots(device.id) },
                onOpenAudit = { onOpenAudit(device.id) },
                onDeleteDevice = { onDeleteDevice(device.id) },
            )
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                "当前服务器：$serverDisplay",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceView,
    onOpenPolicy: () -> Unit,
    onOpenAppControl: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenHardening: () -> Unit,
    onOpenScreenshots: () -> Unit,
    onOpenAudit: () -> Unit,
    onDeleteDevice: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 整卡点击 = 进时长规则；"应用管控"按钮进名单与逐应用规则
            .clickable(onClick = onOpenPolicy),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        if (device.online) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    if (device.locked) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "已锁定",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(if (device.online) "在线" else "离线")
                        device.model?.let { append(" · ").append(it) }
                        device.androidVersion?.let { append(" · Android ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                // 管控强度是家长最该一眼看到的信息：装了 App 不等于拦得住
                Text(
                    text = buildString {
                        append(device.capabilities?.label ?: "未激活管控模式")
                        if (!device.uninstallBlocked) append(" · 可被卸载")
                        if (!device.pinReady) append(" · 无离线密码")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        device.effectiveAdminMode == "device_owner" -> MaterialTheme.colorScheme.secondary
                        device.effectiveAdminMode == "device_admin" -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = device.foregroundPackage?.let { "当前应用：$it" } ?: "暂无前台应用信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (device.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "最后活跃：${relativeTime(device.lastSeen)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onOpenPolicy,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    ) { Text("时长规则") }
                    OutlinedButton(
                        onClick = onOpenAppControl,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    ) { Text("应用管控") }
                    OutlinedButton(
                        onClick = onOpenReport,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    ) { Text("使用报告") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onOpenTools,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    ) { Text("限制工具") }
                    OutlinedButton(
                        onClick = onOpenScreenshots,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    ) { Text("查看屏幕") }
                    OutlinedButton(
                        onClick = onOpenAudit,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    ) { Text("操作记录") }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onOpenHardening,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        colors = if (device.effectiveAdminMode == "none" || !device.pinReady) {
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                        // 没设密码时直接把入口说破：家长找的是"设密码"，
                        // 不会知道它藏在「设备加固」里
                    ) { Text(if (device.pinReady) "设备加固" else "设置离线密码") }

                    OutlinedButton(
                        onClick = onDeleteDevice,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text("删除") }

                    // 图标隐藏状态是"看不见的"——不显式标出来，家长会忘了自己藏过，
                    // 然后因为"找不到这个 App"而困惑
                    if (device.iconHidden) {
                        Text(
                            text = "图标已隐藏",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/* ---------------- 策略编辑 ---------------- */

@Composable
private fun PolicyDialog(
    deviceName: String,
    policy: PolicyBundleDto?,
    busy: Boolean,
    onSave: (weekday: Int, weekend: Int, resetHour: Int, enabled: Boolean, allowTimeRequest: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // 输入框的初值来自服务端策略，加载完成后一次性同步
    var weekday by remember(policy) { mutableStateOf(policy?.weekdayTotalMin?.toString() ?: "") }
    var weekend by remember(policy) { mutableStateOf(policy?.weekendTotalMin?.toString() ?: "") }
    var resetHour by remember(policy) { mutableStateOf(policy?.resetHour?.toString() ?: "0") }
    var enabled by remember(policy) { mutableStateOf(policy?.enabled ?: true) }
    var allowTimeRequest by remember(policy) { mutableStateOf(policy?.allowTimeRequest ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管控规则 · $deviceName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (policy == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在读取策略…", style = MaterialTheme.typography.bodySmall)
                    }
                    return@Column
                }

                Text(
                    "策略版本 v${policy.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = weekday,
                    onValueChange = { weekday = it.filter(Char::isDigit).take(4) },
                    label = { Text("上学日每日总时长（分钟）") },
                    supportingText = { Text("周一至周五。0 表示当天完全不允许使用") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = weekend,
                    onValueChange = { weekend = it.filter(Char::isDigit).take(4) },
                    label = { Text("周末每日总时长（分钟）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = resetHour,
                    onValueChange = { resetHour = it.filter(Char::isDigit).take(2) },
                    label = { Text("额度重置时间（0 - 23 点）") },
                    supportingText = { Text("例如设为 4，则凌晨 3 点的使用量仍算前一天") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("启用管控", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "关闭后设备不再限制使用时长，但仍会记录使用情况",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = allowTimeRequest,
                        onCheckedChange = { allowTimeRequest = it },
                        enabled = enabled,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("允许孩子申请加时", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "受拦时孩子可向你申请，你可以少批一点或拒绝。"
                                + "关闭后孩子只能找你当面输入密码。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    "逐应用时段与黑白名单在设备卡片上的「应用管控」里设置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        weekday.toIntOrNull() ?: return@TextButton,
                        weekend.toIntOrNull() ?: return@TextButton,
                        resetHour.toIntOrNull() ?: 0,
                        enabled,
                        allowTimeRequest,
                    )
                },
                enabled = !busy && policy != null,
            ) {
                Text(if (busy) "保存中…" else "保存并下发")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/* ---------------- 配对码 ---------------- */

@Composable
private fun PairCodeDialog(code: String, ttlSeconds: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配对码") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = code,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 8.sp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "在儿童平板的被控端输入该码完成绑定\n有效期约 ${ttlSeconds / 60} 分钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

/* ---------------- 工具 ---------------- */

private fun relativeTime(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0) return "从未"
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        else -> "${diff / 86_400_000} 天前"
    }
}
