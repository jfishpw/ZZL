package com.zzl.guardian.child.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Base64
import android.util.Log
import com.zzl.guardian.data.api.PolicyBundleDto
import com.zzl.guardian.child.engine.GuardEngine
import com.zzl.guardian.child.data.PolicyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 本地模式一期（docs/本地模式设计.md §8）：
 *  - 前台服务内监听 TCP 9527，提供 /local/v1/{pair,state,policy,command}
 *  - mDNS 注册 `_zzl._tcp`（服务名 = 设备码），网络切换自动重注册
 *  - 配对：被控端通知展示「设备码 + 令牌 + IP:端口」，家长端提交后完成 ECDH 绑定
 *  - 鉴权：除 pair 外全部要求 X-ZZL-Auth = Base64(HMAC(M, path|ts))，ts 窗口 ±120s
 *
 * 服务器模式不受影响：本服务只在「已存在配对家长端」时启动。
 */
class LocalHttpServer(
    private val context: Context,
    private val identity: LocalIdentity,
    private val policyRepository: PolicyRepository,
    private val engine: GuardEngine,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var masterKey: ByteArray? = null
    private val usedNonces = HashSet<String>()
    private val random = SecureRandom()
    private var pairingToken: String? = null
    private var pairingTokenIssuedAt: Long = 0L

    @Volatile
    private var running = false

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun start() {
        if (running) return
        running = true
        pairingToken = String.format("%06d", random.nextInt(1000000))
        pairingTokenIssuedAt = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            runCatching {
                serverSocket = ServerSocket(PORT)
                acceptThread = Thread({ acceptLoop() }, "zzl-local").apply { isDaemon = true; start() }
                registerNsd()
                showPairingNotice()
                Log.i(TAG, "本地服务已启动，端口=$PORT")
            }.onFailure { Log.e(TAG, "本地服务启动失败", it); running = false }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        unregisterNsd()
        Log.i(TAG, "本地服务已停止")
    }

    private fun acceptLoop() {
        while (running) {
            val socket = runCatching { serverSocket?.accept() }.getOrNull() ?: break
            scope.launch(Dispatchers.IO) { handle(socket) }
        }
    }

    private fun handle(socket: java.net.Socket) {
        runCatching {
            socket.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            var contentLength = 0
            generateSequence { reader.readLine() }.takeWhile { it.isNotBlank() }.forEach { header ->
                val m = Regex("(?i)content-length:\\s*(\\d+)").find(header)
                if (m != null) contentLength = m.groupValues[1].toInt()
            }
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            val (status, payload) = route(method, path, body)
            val resp = "HTTP/1.1 $status\r\nContent-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${payload.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n$payload"
            socket.getOutputStream().write(resp.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
        }.onFailure { Log.d(TAG, "请求处理失败: ${it.message}") }
        runCatching { socket.close() }
    }

    private fun route(method: String, path: String, body: String): Pair<String, String> {
        return when {
            path == "/local/v1/pair" && method == "POST" -> handlePair(body)
            path == "/local/v1/ping" && method == "GET" -> "200" to "{\"ok\":true,\"paired\":${identity.hasPairedParent()}}"
            !identity.hasPairedParent() -> "403" to "{\"error\":\"not_paired\"}"
            !verifySignature(path, body) -> "401" to "{\"error\":\"bad_signature\"}"
            path == "/local/v1/state" && method == "GET" -> handleState()
            path == "/local/v1/policy" && method == "GET" -> handleGetPolicy()
            path == "/local/v1/policy" && method == "PUT" -> handlePutPolicy(body)
            path == "/local/v1/command" && method == "POST" -> handleCommand(body)
            else -> "404" to "{\"error\":\"not_found\"}"
        }
    }

    /* ---------------- 配对 ---------------- */

    private fun handlePair(body: String): Pair<String, String> {
        val token = pairingToken
            ?: return "403" to "{\"error\":\"no_token\"}"
        val obj = runCatching { json.decodeFromString(PairRequest.serializer(), body) }.getOrNull()
            ?: return "400" to "{\"error\":\"bad_json\"}"
        val fresh = pairingTokenIssuedAt.let { System.currentTimeMillis() - it < TOKEN_TTL_MS }
        if (!fresh || obj.token.trim() != token) return "403" to "{\"error\":\"bad_token\"}"
        pairingToken = null // 单次有效

        val master = identity.deriveMasterKey(obj.pub)
        masterKey = master
        identity.rememberParent(obj.pub)
        // 回执：用 M 对固定串签名，家长端校验后确认密钥一致
        val proof = Base64.encodeToString(identity.sign(master, "zzl-pair-proof".toByteArray()), Base64.NO_WRAP)
        return "200" to "{\"ok\":true,\"proof\":\"$proof\"}"
    }

    private fun verifySignature(path: String, body: String): Boolean {
        val master = masterKey ?: return false
        // 从 body 里带 ts 与 sign（避免依赖无法自定义的头解析差异）
        val obj = runCatching { json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject }.getOrNull()
            ?: return false
        val ts = obj["ts"]?.toString()?.toLongOrNull() ?: return false
        val sign = obj["sign"] ?: return false
        if (kotlin.math.abs(System.currentTimeMillis() - ts) > 120_000) return false
        val expected = Base64.encodeToString(
            identity.sign(master, "$path|$ts".toByteArray()), Base64.NO_WRAP,
        )
        if (expected != sign.toString().trim('"')) return false
        // 防重放：同签名只接受一次
        synchronized(usedNonces) {
            if (!usedNonces.add(expected)) return false
            if (usedNonces.size > 512) usedNonces.clear() // 简化清理
        }
        return true
    }


    /* ---------------- 业务端点 ---------------- */

    private fun handleState(): Pair<String, String> {
        val s = engine.state.value
        val payload = "{\"ts\":${System.currentTimeMillis()}," +
            "\"foreground\":${jsonStr(s.foregroundPackage)}," +
            "\"locked\":${s.locked || engine.localLocked}," +
            "\"usedTodayMs\":${s.usedTodayMs}," +
            "\"limitMs\":${s.limitMs}," +
            "\"blocked\":${jsonStr(s.blockedPackage)}}"
        return "200" to payload
    }

    private fun jsonStr(v: String?): String =
        if (v == null) "null" else "\"" + v.replace("\"", "\\\"") + "\""

    private fun handleGetPolicy(): Pair<String, String> {
        val bundle = buildBundle()
            ?: return "404" to "{\"error\":\"no_policy\"}"
        return "200" to json.encodeToString(PolicyBundleDto.serializer(), bundle)
    }

    private fun handlePutPolicy(body: String): Pair<String, String> {
        val bundle = runCatching { json.decodeFromString(PolicyBundleDto.serializer(), body) }.getOrNull()
            ?: return "400" to "{\"error\":\"bad_json\"}"
        scope.launch {
            runCatching {
                policyRepository.saveBundle(engine.currentPolicyDeviceId(), bundle)
                engine.reevaluate()
                Log.i(TAG, "本地策略已更新（版本 ${bundle.version}）")
            }
        }
        return "200" to "{\"ok\":true}"
    }

    private fun handleCommand(body: String): Pair<String, String> {
        val obj = runCatching { json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject }.getOrNull()
            ?: return "400" to "{\"error\":\"bad_json\"}"
        val action = obj["action"]?.toString()?.trim('"')
        when (action) {
            "lock" -> engine.localLocked = true
            "unlock" -> engine.localLocked = false
            else -> return "400" to "{\"error\":\"unknown_action\"}"
        }
        scope.launch { runCatching { engine.reevaluate() } }
        return "200" to "{\"ok\":true,\"action\":\"$action\"}"
    }

    /** 从引擎已初始化的策略组装完整策略包（服务器 DTO 同构） */
    private fun buildBundle(): PolicyBundleDto? {
        val guard = engine.guardPolicySnapshot() ?: return null
        val p = guard.policy
        return PolicyBundleDto(
            deviceId = guard.policy.deviceId,
            weekdayTotalMin = p.weekdayTotalMin,
            weekendTotalMin = p.weekendTotalMin,
            resetHour = p.resetHour,
            listMode = guard.listMode,
            allowTimeRequest = p.allowTimeRequest,
            enabled = p.enabled,
            timingMode = p.timingMode,
            version = p.version,
            updatedAt = p.updatedAt,
            listItems = guard.listedPackages.map { com.zzl.guardian.data.api.PolicyListItemDto(it, null) },
            appRules = guard.rules.values.map { rule ->
                com.zzl.guardian.data.api.AppRuleDto(
                    packageName = rule.packageName,
                    dailyLimitMin = rule.dailyLimitMin,
                    weekdaysMask = rule.weekdaysMask,
                    enabled = rule.enabled,
                    keepTimingOnIdle = rule.keepTimingOnIdle,
                    exemptTotal = rule.exemptTotal,
                )
            },
        )
    }

    /* ---------------- 发现（mDNS）与配对通知 ---------------- */

    private fun registerNsd() {
        val nsd = context.getSystemService(Service.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = nsd
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS 已注册: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "mDNS 注册失败: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registrationListener = listener
        val info = NsdServiceInfo().apply {
            serviceName = "zzl-${identity.deviceCode}"
            serviceType = "_zzl._tcp"
            port = PORT
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun unregisterNsd() {
        val nsd = nsdManager ?: return
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        registrationListener = null
    }

    /** 配对信息通过独立通知展示（被控端界面可能被图标隐藏挡住） */
    private fun showPairingNotice() {
        val nm = context.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "本地连接", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val ip = localIpv4() ?: "未获取到 IP"
        val text = "设备码 ${identity.deviceCode} · 配对令牌 $pairingToken · 地址 $ip:$PORT"
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("掌中灵本地连接")
                .setContentText("在家长端输入以下信息完成配对")
                .setStyle(Notification.BigTextStyle().bigText("在家长端「本地模式 → 添加设备」中输入：\n$text\n（令牌单次有效）"))
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("掌中灵本地连接：$text")
                .setOngoing(true)
                .build()
        }
        runCatching { nm.notify(NOTIFY_ID, notification) }
    }

    private fun localIpv4(): String? =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is InetAddress && !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress

    companion object {
        private const val TAG = "LocalServer"
        const val PORT = 9527
        private const val CHANNEL = "zzl_local_pairing"
        private const val NOTIFY_ID = 9527
        private const val TOKEN_TTL_MS = 10 * 60_000L
    }

    /** 配对请求体 */
    @kotlinx.serialization.Serializable
    data class PairRequest(val token: String, val pub: String)
}
