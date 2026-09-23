package com.zzl.guardian.ui.serversettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzl.guardian.data.ServerConfig
import com.zzl.guardian.data.SettingsStore
import com.zzl.guardian.data.api.HealthResponse
import com.zzl.guardian.di.ServerConfigHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 服务器设置的共享逻辑（控制端与被控端复用）。
 *
 * 关键行为：
 *   - 主机与端口是两个独立字段，各自校验
 *   - 保存前先对候选地址做一次 /api/health 连通性探测，连不上不允许保存
 *   - 保存成功后清空全部凭据，强制重新登录 / 重新配对
 */
@HiltViewModel
class ServerSettingsViewModel @Inject constructor(
    private val holder: ServerConfigHolder,
    private val settingsStore: SettingsStore,
    private val client: OkHttpClient,
    private val json: Json,
) : ViewModel() {

    sealed interface ProbeState {
        data object Idle : ProbeState
        data object Probing : ProbeState
        data class Ok(val serverTime: Long) : ProbeState
        data class Failed(val reason: String) : ProbeState
    }

    data class UiState(
        val host: String = "",
        val port: String = "",
        val scheme: String = ServerConfig.SCHEME_HTTP,
        val probe: ProbeState = ProbeState.Idle,
        val fieldError: String? = null,
        val notice: String? = null,
        val saved: Boolean = false,
    ) {
        val default: ServerConfig get() = ServerConfig.default()
        val isDefault: Boolean
            get() = host.trim() == default.host && port.trim() == default.port.toString() && scheme == default.scheme
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * 探测专用客户端：必须剥掉 BaseUrlInterceptor。
     *
     * 共享客户端上的拦截器会把每个请求的 scheme/host/port 重写为「当前」服务器，
     * 拿着它去测候选地址，测的其实还是旧地址 —— 结果就是无论填什么都提示"连接成功"。
     * newBuilder 复用连接池与线程池，只是去掉拦截器，代价很低。
     */
    private val probeClient: OkHttpClient = client.newBuilder()
        .apply { interceptors().clear() }
        // 探测要给出快速反馈：地址填错时不该让用户等 30 秒
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        // WebSocket 保活间隔对一次性请求没有意义，去掉以免复用连接时行为怪异
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val config = settingsStore.serverConfigOnce()
            _state.update {
                it.copy(
                    host = config.host,
                    port = config.port.toString(),
                    scheme = config.scheme,
                    probe = ProbeState.Idle,
                    fieldError = null,
                    notice = null,
                    saved = false,
                )
            }
        }
    }

    fun onHostChange(value: String) = _state.update { it.copy(host = value, probe = ProbeState.Idle, fieldError = null) }

    fun onPortChange(value: String) = _state.update { it.copy(port = value.filter(Char::isDigit), probe = ProbeState.Idle, fieldError = null) }

    fun onSchemeChange(value: String) = _state.update { it.copy(scheme = value, probe = ProbeState.Idle, fieldError = null) }

    /** 探测候选地址是否可达，成功则保存并清空凭据 */
    fun probeAndSave() {
        val current = _state.value
        val parsed = ServerConfig.of(current.host, current.port)
        if (parsed.isFailure) {
            _state.update { it.copy(fieldError = parsed.exceptionOrNull()?.message, probe = ProbeState.Idle) }
            return
        }
        val candidate = parsed.getOrThrow()

        viewModelScope.launch {
            _state.update { it.copy(probe = ProbeState.Probing, fieldError = null, notice = null) }
            val result = probeHealth(candidate)

            result.fold(
                onSuccess = { serverTime ->
                    holder.update(candidate)
                    // 换了服务器，旧凭据一律作废
                    settingsStore.clearAllSessions()
                    _state.update {
                        it.copy(
                            probe = ProbeState.Ok(serverTime),
                            notice = "已切换到 ${candidate.display}，请重新登录或重新配对",
                            saved = true,
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(probe = ProbeState.Failed(error.message ?: "连接失败"), fieldError = null)
                    }
                },
            )
        }
    }

    fun resetToDefault() {
        viewModelScope.launch {
            holder.resetToDefault()
            settingsStore.clearAllSessions()
            val config = ServerConfig.default()
            _state.update {
                it.copy(
                    host = config.host,
                    port = config.port.toString(),
                    scheme = config.scheme,
                    probe = ProbeState.Idle,
                    fieldError = null,
                    notice = "已恢复默认地址 ${config.display}",
                    saved = true,
                )
            }
        }
    }

    fun consumeSaved() = _state.update { it.copy(saved = false) }

    /**
     * 直接用显式 URL 探测，不走 Retrofit —— 因为拦截器用的是「当前」配置，
     * 而这里要测的是「候选」配置。
     */
    private suspend fun probeHealth(config: ServerConfig): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${config.baseUrl}api/health")
                .build()

            probeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("服务返回 HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) error("响应为空，可能不是本服务")
                val health = json.decodeFromString<HealthResponse>(body)
                if (!health.ok) error("服务健康检查未通过")
                health.serverTime
            }
        }.recoverCatching { error ->
            throw IllegalStateException(describe(error), error)
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is UnknownHostException -> "无法解析该地址，请检查是否填错"
        is SocketTimeoutException -> "连接超时，请检查端口是否放行（阿里云安全组）"
        is ConnectException -> "拒绝连接，请确认服务已启动且端口正确"
        else -> error.message ?: "连接失败"
    }
}
