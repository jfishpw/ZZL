package com.zzl.guardian.child.local

import android.content.Context
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 配对信息提供者（被控端界面用）：
 * 设备码 / 一次性令牌 / 局域网地址。令牌在服务运行期间有效（10 分钟），
 * 显示时按需生成，配对成功后作废。
 */
class LocalPairingInfo(context: Context) {

    private val identity = LocalIdentity(context)

    val deviceCode: String get() = identity.deviceCode

    /** 取当前令牌（无有效令牌则生成一个新的） */
    fun token(): String = identity.ensurePairingToken()

    /** 剩余有效秒数（界面提示用） */
    fun tokenRemainingSeconds(): Long = identity.tokenRemainingSeconds()

    fun ip(): String = localIpv4() ?: "未获取到"

    val port: Int get() = LocalHttpServer.PORT

    companion object {
        /** 局域网 IPv4（排除回环与 IPv6） */
        fun localIpv4(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull {
                    it is InetAddress && !it.isLoopbackAddress && it.hostAddress?.contains(':') == false
                }
                ?.hostAddress
        }.getOrNull()
    }
}
