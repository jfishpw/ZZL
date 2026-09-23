package com.zzl.guardian.data

import com.zzl.guardian.BuildConfig

/**
 * 服务器连接配置。
 *
 * 设计要点：**主机与端口是两个独立可配置项**，代码中不存在任何写死的地址或端口。
 * 优先级（高 → 低）：
 *   1. 配对页 / 设置页手动填写（写入 DataStore）
 *   2. DataStore 中已有的运行期值
 *   3. BuildConfig 中的编译期默认值（源自 gradle.properties）
 */
data class ServerConfig(
    val host: String,
    val port: Int,
    val scheme: String = SCHEME_HTTP,
    val trustSelfSigned: Boolean = false,
) {
    /** 形如 http://203.0.113.10:8111/ —— 结尾斜杠是 Retrofit 的硬性要求 */
    val baseUrl: String get() = "$scheme://$host:$port/"

    val apiBaseUrl: String get() = "${baseUrl}api/"

    /** WebSocket 协议由 scheme 推导，不单独配置 */
    val wsUrl: String get() = "${if (scheme == SCHEME_HTTPS) "wss" else "ws"}://$host:$port/ws"

    /** 供界面展示，例如 http://203.0.113.10:8111 */
    val display: String get() = "$scheme://$host:$port"

    companion object {
        const val SCHEME_HTTP = "http"
        const val SCHEME_HTTPS = "https"

        /**
         * 未显式填写端口时的兜底值（例如家长只输入一个 IP）。
         *
         * **不写死数字**：直接取自编译期配置 `gradle.properties → BuildConfig`，
         * 这样它与服务端 `deploy/.env` 的 `APP_PORT` 保持一致。
         * 写死会在改默认端口时留下一处静默漂移 —— 输入框里显示 8111，
         * 而只填 IP 时却连到 8080。
         */
        val DEFAULT_PORT: Int get() = BuildConfig.DEFAULT_SERVER_PORT

        private val HOST_REGEX = Regex("^[A-Za-z0-9._:\\-]+\$")

        /** 编译期默认值（gradle.properties → BuildConfig），优先级最低 */
        fun default(): ServerConfig = ServerConfig(
            host = BuildConfig.DEFAULT_SERVER_HOST,
            port = BuildConfig.DEFAULT_SERVER_PORT,
            scheme = BuildConfig.DEFAULT_SERVER_SCHEME,
        )

        fun isValidPort(port: Int): Boolean = port in 1..65535

        /**
         * 容错解析单行输入，支持这些写法（未写端口时取编译期默认端口）：
         *   192.168.1.10                  → http://192.168.1.10:8111
         *   192.168.1.10:9000             → http://192.168.1.10:9000
         *   http://203.0.113.10:8111       → 原样
         *   https://example.com           → https 443
         *   example.com/path              → 忽略路径部分
         * 无法解析时返回 null。
         */
        fun parse(raw: String): ServerConfig? {
            var text = raw.trim()
            if (text.isEmpty()) return null

            var scheme = SCHEME_HTTP
            val schemeIndex = text.indexOf("://")
            if (schemeIndex > 0) {
                scheme = text.substring(0, schemeIndex).lowercase()
                if (scheme != SCHEME_HTTP && scheme != SCHEME_HTTPS) return null
                text = text.substring(schemeIndex + 3)
            }

            text = text.substringBefore('/').substringBefore('?').substringBefore('#')
            if (text.isEmpty()) return null

            var hostPart = text
            var port = if (scheme == SCHEME_HTTPS) 443 else DEFAULT_PORT
            var portExplicit = false

            if (hostPart.startsWith("[")) {
                // IPv6 字面量，如 [fe80::1]:8111
                val end = hostPart.indexOf(']')
                if (end < 0) return null
                val after = hostPart.substring(end + 1)
                hostPart = hostPart.substring(1, end)
                if (after.startsWith(":")) {
                    port = after.substring(1).toIntOrNull() ?: return null
                    portExplicit = true
                }
            } else {
                val colon = hostPart.lastIndexOf(':')
                if (colon >= 0) {
                    port = hostPart.substring(colon + 1).toIntOrNull() ?: return null
                    hostPart = hostPart.substring(0, colon)
                    portExplicit = true
                }
            }

            hostPart = hostPart.trim()
            if (hostPart.isEmpty() || !HOST_REGEX.matches(hostPart)) return null
            if (!isValidPort(port)) return null
            if (!portExplicit && scheme == SCHEME_HTTPS) port = 443

            return ServerConfig(host = hostPart, port = port, scheme = scheme)
        }

        /** 供「主机」「端口」两个独立输入框使用 */
        fun of(hostInput: String, portInput: String): Result<ServerConfig> {
            val host = hostInput.trim()
            if (host.isEmpty()) {
                return Result.failure(IllegalArgumentException("请填写服务器地址"))
            }
            if (host.contains("://") || host.contains("/")) {
                // 用户把整串粘进了主机框，走容错解析
                val parsed = parse(hostInput)
                    ?: return Result.failure(IllegalArgumentException("服务器地址格式不正确"))
                return Result.success(parsed)
            }
            if (!HOST_REGEX.matches(host)) {
                return Result.failure(IllegalArgumentException("服务器地址只能包含字母、数字、. - : _"))
            }

            val port = portInput.trim().toIntOrNull()
                ?: return Result.failure(IllegalArgumentException("端口必须是数字"))
            if (!isValidPort(port)) {
                return Result.failure(IllegalArgumentException("端口需在 1 - 65535 之间"))
            }

            return Result.success(ServerConfig(host = host, port = port))
        }
    }
}
