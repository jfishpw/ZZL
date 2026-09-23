package com.zzl.guardian.child.gate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzl.guardian.child.pin.PinStore
import com.zzl.guardian.child.pin.PinVerifyResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 被控端 App 的密码门禁。
 *
 * 家长在控制端设置了任一级离线密码后，打开被控端界面就需要先输密码 ——
 * 否则孩子（或拿到平板的人）可以直接进设置页解除绑定、关权限。
 *
 * 门禁只挡 UI，不挡管控：前台服务、无障碍、拦截遮罩都在 Activity 之外运行，
 * 输不进密码不影响「该拦的照拦、该锁的照锁」。
 *
 * 验证规则：**任一级密码都能进门**。三级密码的差别在通过后能做什么
 * （拦截页上的加时/放行/退出），不在"能不能打开这个界面"。
 * 逐级尝试由 [PinStore.verify] 内置的限流兜底：错误次数累积到各级自己的锁定期。
 */
@HiltViewModel
class ChildGateViewModel @Inject constructor(
    private val pinStore: PinStore,
) : ViewModel() {

    /** 是否需要门禁：家长在控制端设置过任意一级离线密码 */
    suspend fun requiresPin(): Boolean = pinStore.hasAny()

    /**
     * 门禁验证。逐级尝试已设置的密码（1 → 3），任一通过即放行。
     *
     * @param onResult null 表示通过；否则为错误文案
     */
    fun verify(pin: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            var lockedMs: Long? = null
            for (level in 1..3) {
                when (val result = pinStore.verify(level, pin)) {
                    is PinVerifyResult.Ok -> {
                        onResult(null)
                        return@launch
                    }

                    is PinVerifyResult.Locked -> {
                        val remaining = result.remainingMs
                        if (lockedMs == null || remaining > lockedMs) lockedMs = remaining
                    }

                    // NotSet：该级没设密码，跳过；Wrong：记到下一轮统一提示
                    else -> Unit
                }
            }

            onResult(
                lockedMs?.let { "尝试次数过多，请 ${it / 60_000 + 1} 分钟后再试" }
                    ?: "密码不正确",
            )
        }
    }
}

@Composable
fun PinGateScreen(
    viewModel: ChildGateViewModel,
    onUnlocked: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("掌中灵", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "本应用已开启密码保护\n请输入任一级家长密码进入",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))

                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                    label = { Text("家长密码") },
                    singleLine = true,
                    // 星号回显：旁边站着孩子时明文输入等于把密码念出来
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (pin.isEmpty() || busy) return@Button
                        busy = true
                        error = null
                        viewModel.verify(pin) { message ->
                            busy = false
                            if (message == null) {
                                onUnlocked()
                            } else {
                                error = message
                                pin = ""
                            }
                        }
                    },
                    enabled = !busy && pin.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "验证中…" else "进入")
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { pin = ""; error = null },
                    enabled = !busy,
                ) { Text("清空重输") }
            }
        }
    }
}
