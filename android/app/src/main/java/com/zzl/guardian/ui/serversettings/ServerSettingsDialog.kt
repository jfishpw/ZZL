package com.zzl.guardian.ui.serversettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzl.guardian.data.ServerConfig

/**
 * 服务器设置弹窗（控制端与被控端共用）。
 * 主机与端口是两个独立可配置项，保存前会先做连通性探测。
 */
@Composable
fun ServerSettingsDialog(
    onDismiss: () -> Unit,
    viewModel: ServerSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务器设置") },
        text = {
            Column {
                Text(
                    text = "主机与端口可自由配置，支持 IP 或域名。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ServerConfig.SCHEME_HTTP to "http", ServerConfig.SCHEME_HTTPS to "https").forEach { (value, label) ->
                        FilterChip(
                            selected = state.scheme == value,
                            onClick = { viewModel.onSchemeChange(value) },
                            label = { Text(label) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.host,
                    onValueChange = viewModel::onHostChange,
                    label = { Text("主机（IP 或域名）") },
                    placeholder = { Text("例如 203.0.113.10") },
                    singleLine = true,
                    isError = state.fieldError != null,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.port,
                    onValueChange = viewModel::onPortChange,
                    label = { Text("端口") },
                    placeholder = { Text("例如 8111") },
                    singleLine = true,
                    isError = state.fieldError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                state.fieldError?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(16.dp))

                ProbeRow(state.probe)

                state.notice?.let { notice ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(Modifier.height(4.dp))

                TextButton(onClick = viewModel::resetToDefault) {
                    Text("恢复默认（${state.default.display}）")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = viewModel::probeAndSave,
                enabled = state.probe !is ServerSettingsViewModel.ProbeState.Probing,
            ) {
                Text(if (state.probe is ServerSettingsViewModel.ProbeState.Probing) "连接中…" else "测试并保存")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (state.saved) viewModel.consumeSaved()
                    onDismiss()
                },
            ) { Text("关闭") }
        },
    )
}

@Composable
private fun ProbeRow(probe: ServerSettingsViewModel.ProbeState) {
    when (probe) {
        is ServerSettingsViewModel.ProbeState.Idle -> {
            Text(
                text = "保存前会先测试连通性",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is ServerSettingsViewModel.ProbeState.Probing -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在测试连接…", style = MaterialTheme.typography.bodySmall)
            }
        }

        is ServerSettingsViewModel.ProbeState.Ok -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.width(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("连接成功，已保存", style = MaterialTheme.typography.bodySmall)
            }
        }

        is ServerSettingsViewModel.ProbeState.Failed -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.width(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = probe.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
