package com.zzl.guardian.child

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzl.guardian.child.gate.ChildGateViewModel
import com.zzl.guardian.child.gate.PinGateScreen
import com.zzl.guardian.child.keepalive.KeepaliveManager
import com.zzl.guardian.ui.serversettings.ServerSettingsDialog

@Composable
fun ChildHome(viewModel: ChildViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    /* ---------------- 密码门禁 ---------------- */
    // 家长在控制端设置过离线密码后，打开被控端界面需要先验证；
    // 未绑定（配对页）不设门禁 —— 此时还没有任何需要保护的东西，
    // 也保证家长忘记密码后仍能走重装/重新配对这条兜底路径。
    var gateChecked by remember { mutableStateOf(false) }
    var gateUnlocked by rememberSaveable { mutableStateOf(false) }
    val gateViewModel: ChildGateViewModel = hiltViewModel()
    LaunchedEffect(state.session) {
        gateUnlocked = state.session == null || !gateViewModel.requiresPin()
        gateChecked = true
    }
    if (!gateChecked) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (!gateUnlocked) {
        PinGateScreen(viewModel = gateViewModel, onUnlocked = { gateUnlocked = true })
        return
    }

    var showServerSettings by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Android 13+ 前台服务的常驻通知需要运行时授权；
    // 没授权服务照样跑，但孩子看不到"今日剩余多少分钟"，家长也会以为管控没生效
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    var notificationAsked by remember { mutableStateOf(false) }
    LaunchedEffect(state.session) {
        if (state.session == null || notificationAsked) return@LaunchedEffect
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect

        notificationAsked = true
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

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
                // 切换服务器会作废设备令牌，必须重新读会话状态
                viewModel.reloadSession()
                viewModel.refreshHealth()
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ChildTopBar(
                state = state,
                onServerSettings = { showServerSettings = true },
                onRefresh = { viewModel.refreshUsage(); viewModel.refreshHealth() },
                onUnpair = { viewModel.unpair() },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.booting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.session == null -> PairScreen(
                    busy = state.busy,
                    serverDisplay = state.serverDisplay,
                    onPair = viewModel::pair,
                    onServerSettings = { showServerSettings = true },
                )

                else -> BoundScreen(
                    state = state,
                    onOpenAccessibility = { PermissionGuide.openAccessibility(context) },
                    onOpenUsageAccess = { PermissionGuide.openUsageAccess(context) },
                    onOpenOverlay = { PermissionGuide.openOverlay(context) },
                    onOpenAppDetails = { PermissionGuide.openAppDetails(context) },
                    onOpenBattery = { PermissionGuide.openBatteryOptimization(context) },
                    onActivateAdmin = { viewModel.activateDeviceAdmin() },
                    onOpenAutoStart = { KeepaliveManager.openAutoStartSettings(context) },
                    onOpenExactAlarm = { KeepaliveManager.openExactAlarmSettings(context) },
                    onConfirmVendorWhitelist = viewModel::confirmVendorWhitelist,
                    onRefresh = { viewModel.refreshHealth() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChildTopBar(
    state: ChildViewModel.UiState,
    onServerSettings: () -> Unit,
    onRefresh: () -> Unit,
    onUnpair: () -> Unit,
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.session == null) "掌中灵 · 待配对" else "掌中灵 · 已受管控")
                if (state.session != null && state.guard.ready) {
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
            if (state.session != null) {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
                TextButton(onClick = onUnpair) { Text("解除绑定") }
            }
        },
    )
}

/* ---------------- 配对 ---------------- */

@Composable
private fun PairScreen(
    busy: Boolean,
    serverDisplay: String,
    onPair: (String) -> Unit,
    onServerSettings: () -> Unit,
) {
    var code by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("绑定到家长账号", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            "请在家长手机上打开「掌中灵 · 控制端」，点击右下角按钮生成 6 位配对码",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text("配对码") },
            placeholder = { Text("6 位数字") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onPair(code) },
            enabled = !busy && code.length == 6,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("开始绑定")
            }
        }

        Spacer(Modifier.height(24.dp))

        TextButton(onClick = onServerSettings, modifier = Modifier.fillMaxWidth()) {
            Text("服务器设置 · $serverDisplay", fontSize = 13.sp)
        }
    }
}

/* ---------------- 已绑定 ---------------- */

@Composable
private fun BoundScreen(
    state: ChildViewModel.UiState,
    onOpenAccessibility: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onOpenBattery: () -> Unit,
    onActivateAdmin: () -> Unit,
    onOpenAutoStart: () -> Unit,
    onOpenExactAlarm: () -> Unit,
    onConfirmVendorWhitelist: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    val session = state.session ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // 设备与管控状态
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(session.deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "设备编号 #${session.deviceId}" +
                        if (state.guard.policyVersion > 0) " · 策略 v${state.guard.policyVersion}" else " · 策略待同步",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        !state.guard.ready -> "管控引擎未就绪"
                        !state.guard.enforcementEnabled -> "家长当前已暂停管控"
                        state.fullyArmed -> "管控生效中"
                        else -> "管控未完全生效，还有 ${state.missingCount} 项权限待开启"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        state.fullyArmed -> MaterialTheme.colorScheme.secondary
                        state.guard.ready && !state.guard.enforcementEnabled ->
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error
                    },
                )

                if (state.guard.policyVersion > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            append("规则版本 v").append(state.guard.policyVersion)
                            append(" · 名单 ").append(state.guard.listedAppCount).append(" 个")
                            append(" · 逐应用规则 ").append(state.guard.ruleCount).append(" 条")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 今日使用情况
        TodayUsageCard(state)

        Spacer(Modifier.height(20.dp))

        Text("管控权限检查", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (state.missingCount == 0) "三项关键权限均已开启" else "还有 ${state.missingCount} 项未开启，管控无法生效",
            style = MaterialTheme.typography.bodySmall,
            color = if (state.missingCount == 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))

        PermissionRow(
            title = "无障碍服务",
            description = "识别当前打开的应用，并在时间用尽时强制返回桌面",
            granted = state.health.accessibility,
            onOpen = onOpenAccessibility,
        )
        PermissionRow(
            title = "使用情况访问",
            description = "统计各应用的使用时长与历史记录",
            granted = state.health.usageAccess,
            onOpen = onOpenUsageAccess,
        )
        PermissionRow(
            title = "悬浮窗权限",
            description = "时间用尽时弹出全屏拦截页",
            granted = state.health.overlay,
            onOpen = onOpenOverlay,
        )

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRefresh) { Text("重新检测") }

        Spacer(Modifier.height(16.dp))

        // 加固与保活：这两块决定"管控能不能被绕过、会不会被系统清掉"，
        // 都是设备本地的既成事实，只能在这里核对
        HardeningCard(state = state, onActivateAdmin = onActivateAdmin)
        Spacer(Modifier.height(12.dp))
        KeepaliveCard(
            state = state,
            onOpenAutoStart = onOpenAutoStart,
            onOpenExactAlarm = onOpenExactAlarm,
            onOpenBattery = onOpenBattery,
            onOpenAppDetails = onOpenAppDetails,
            onConfirm = onConfirmVendorWhitelist,
        )

        Spacer(Modifier.height(12.dp))

        // 装机必读
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("装着卡住了？看这里", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. 无障碍开关是灰的、点不动\n" +
                        "   Android 13 起，浏览器安装的应用默认禁止开启无障碍。\n" +
                        "   正确路径：应用详情页 → 右上角菜单 → 允许受限设置 → 再开启无障碍。\n\n" +
                        "2. 设置里找不到应用\n" +
                        "   可直接从下面的按钮跳到应用详情页。\n\n" +
                        "3. 用一会儿就失效了\n" +
                        "   小米 / 华为 / 荣耀 / OPPO / vivo 需要在系统设置里允许「自启动」\n" +
                        "   并把省电策略改成「无限制」，否则后台服务会被系统清理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenAppDetails) { Text("打开应用详情页") }
                    OutlinedButton(onClick = onOpenBattery) { Text("电池优化白名单") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "当前服务器：${state.serverDisplay}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 加固状态卡片。
 *
 * 家长在设备上最该核对的两件事：
 *   1. 管控模式有多硬（能不能被卸载）
 *   2. 离线密码有没有设（断网时能不能临时放行）
 *
 * 刻意把"可被卸载"写得很直白：这是最容易被忽略、后果最严重的缺口 ——
 * 家长以为装好了，孩子几分钟就卸掉了。
 */
@Composable
private fun HardeningCard(state: ChildViewModel.UiState, onActivateAdmin: () -> Unit) {
    val isOwner = state.adminMode == "device_owner"
    val isAdmin = state.adminMode == "device_admin"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isOwner -> MaterialTheme.colorScheme.secondaryContainer
                isAdmin -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("防卸载加固", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(state.modeText, style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    isOwner ->
                        "卸载入口已被系统移除，无法在设置里取消激活。这是最强的一档。"
                    isAdmin ->
                        "已阻止直接卸载，但进入「设置 → 安全 → 设备管理应用」取消激活后仍可卸载。" +
                            "取消激活时家长会立刻收到告警。"
                    else ->
                        "当前没有任何防卸载保护，本应用可被直接卸载，卸载后管控立即失效。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("阻止卸载：")
                    append(if (state.uninstallBlocked) "已生效" else "未生效")
                    append(" · 离线密码：")
                    append(if (state.pinReady) "已设置" else "未设置")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.failedPinAttempts > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "密码输入失败 ${state.failedPinAttempts} 次（已记录并上报家长）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (!isAdmin && !isOwner) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onActivateAdmin) { Text("激活设备管理器") }
                Spacer(Modifier.height(6.dp))
                Text(
                    // ColorOS 13+ 等系统会拦截第三方设备管理器激活：
                    // 在系统激活页点「激活」后被拒，页面抖一下就是典型表现。
                    // 此时唯一出路是 ADB 设备所有者（也更强），必须提前讲清楚。
                    text = "若系统页面点「激活」后抖动/无效，说明该系统限制了第三方设备管理器。" +
                        "请改用电脑 ADB 激活「设备所有者」模式（更彻底，方法见部署指南）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!state.pinReady) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "还没有设置离线密码。请在控制端的「设备加固」里设置 —— " +
                        "否则断网时家长无法在设备上临时放行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 保活状态卡片。
 *
 * 保活是"看起来没问题、过几天才暴露"的隐患：前台服务被厂商 ROM 清掉后，
 * 管控会静默失效，家长要等到发现"孩子怎么还在玩"才知道。
 * 因此这里把每一项都列出来，并给出对应厂商的设置路径。
 */
@Composable
private fun KeepaliveCard(
    state: ChildViewModel.UiState,
    onOpenAutoStart: () -> Unit,
    onOpenExactAlarm: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    val keepalive = state.keepalive

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("后台存活", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))

            if (keepalive == null) {
                Text(
                    "正在检测…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            val missing = state.keepaliveMissing
            if (missing.isEmpty()) {
                Text(
                    "保活设置均已到位，后台被系统清理的风险很低。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else {
                Text(
                    "以下设置缺失时，管控服务会被系统清掉，设备看起来「不管用了」：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                missing.forEach { item ->
                    Text(
                        "· $item",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            keepalive.vendorHint?.let { hint ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "${keepalive.vendor} 的设置路径：\n$hint",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onOpenAutoStart,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { Text("自启动管理", style = MaterialTheme.typography.labelSmall) }
                OutlinedButton(
                    onClick = onOpenBattery,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { Text("电池优化", style = MaterialTheme.typography.labelSmall) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onOpenExactAlarm,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { Text("精确闹钟", style = MaterialTheme.typography.labelSmall) }
                OutlinedButton(
                    onClick = onOpenAppDetails,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { Text("应用详情页", style = MaterialTheme.typography.labelSmall) }
            }

            // 厂商白名单无法程序化检测，只能由家长确认后勾选。
            // 不这么做的话健康度永远显示"不健康"，家长会逐渐无视这个提示。
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.vendorWhitelistConfirmed,
                    onCheckedChange = onConfirm,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "我已在系统设置里允许掌中灵后台常驻",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TodayUsageCard(state: ChildViewModel.UiState) {
    val limit = state.guard.limitMs
    val used = state.guard.usedTodayMs
    val fraction = if (limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("今日使用情况", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))

            // 家长主动锁定 / 临时解封都会显著改变孩子能做的事，放在最前面直说
            if (state.guard.locked) {
                Text(
                    "设备已被家长锁定，暂时无法使用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }

            if (state.guard.unlocked) {
                Text(
                    "家长已临时解除限制，本次解除到期后会自动恢复",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (!state.guard.enforcementEnabled) {
                Text(
                    "家长尚未启用管控，或策略还未同步下来",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("已用 ${state.usedText}", style = MaterialTheme.typography.bodyMedium)
                Text("上限 ${state.limitText}", style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            Text(
                text = if (state.guard.exhausted) "今日额度已用完" else "今日还剩 ${state.remainingText}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.guard.exhausted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )

            // 加时让"上限"看起来和策略里写的不一样，必须解释清来源，否则家长会以为设错了
            if (state.guard.extraMs > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "其中包含家长临时增加的 ${state.guard.extraMs / 60_000} 分钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.grantSummary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "家长当前放行：${state.grantSummary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.guard.timeRequestStatus?.let { status ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        status == "pending" -> "加时申请已发送，等待家长处理"
                        status == "approved" -> "家长已批准加时"
                        status == "rejected" -> "家长未批准本次申请"
                        status.startsWith("failed:") -> status.removePrefix("failed:")
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (state.guard.foregroundPackage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "当前前台：${state.guard.foregroundPackage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (granted) {
            Text(
                "已开启",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        } else {
            TextButton(onClick = onOpen, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                Text("去开启")
            }
        }
    }
}
