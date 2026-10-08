package com.zzl.guardian.parent.local

import android.content.Context
import android.util.Base64
import android.util.Log
import com.zzl.guardian.data.api.PolicyBundleDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 本地模式家长端通道（docs/本地模式设计.md §5，一期）。
 *
 * - 家长端同样持有一对 EC P-256 静态密钥；配对时与被控端公钥做 ECDH，
 *   两侧各自派生同一把主密钥 M（KDF 与被控端 LocalIdentity 完全一致）
 * - 之后每个请求体携带 {ts, sign=HMAC(M, path|ts), ...}，被控端验签 + 防重放
 * - 只与「已配对的设备码」通信；服务器模式完全不经过这里
 */
class LocalParentStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class PairedDevice(
        val deviceCode: String,
        val childPub: String,
        val masterKey: ByteArray,
        val ip: String,
        val port: Int,
    )

    fun paired(): PairedDevice? {
        val code = prefs.getString(KEY_CODE, null) ?: return null
        val childPub = prefs.getString(KEY_CHILD_PUB, null) ?: return null
        val master = prefs.getString(KEY_MASTER, null) ?: return null
        return PairedDevice(
            deviceCode = code,
            childPub = childPub,
            masterKey = Base64.decode(master, Base64.NO_WRAP),
            ip = prefs.getString(KEY_IP, "") ?: "",
            port = prefs.getInt(KEY_PORT, 9527),
        )
    }

    fun savePaired(device: PairedDevice) {
        prefs.edit()
            .putString(KEY_CODE, device.deviceCode)
            .putString(KEY_CHILD_PUB, device.childPub)
            .putString(KEY_MASTER, Base64.encodeToString(device.masterKey, Base64.NO_WRAP))
            .putString(KEY_IP, device.ip)
            .putInt(KEY_PORT, device.port)
            .apply()
    }

    fun saveAddress(ip: String, port: Int) {
        prefs.edit().putString(KEY_IP, ip).putInt(KEY_PORT, port).apply()
    }

    fun clear() = prefs.edit().clear().apply()

    /** 家长端身份密钥（本地生成一次） */
    fun parentPrivateKey(): PrivateKey {
        prefs.getString(KEY_PRIV, null) ?: generate()
        val encoded = prefs.getString(KEY_PRIV, null)!!
        return KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(encoded, Base64.NO_WRAP)))
    }

    fun parentPublicKey(): String {
        prefs.getString(KEY_PUB, null) ?: generate()
        return prefs.getString(KEY_PUB, null)!!
    }

    private fun generate(): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val pair = gen.generateKeyPair()
        prefs.edit()
            .putString(KEY_PUB, Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
            .putString(KEY_PRIV, Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            .apply()
        return pair
    }

    companion object {
        private const val PREFS = "zzl_local_parent"
        private const val KEY_CODE = "code"
        private const val KEY_CHILD_PUB = "child_pub"
        private const val KEY_MASTER = "master"
        private const val KEY_IP = "ip"
        private const val KEY_PORT = "port"
        private const val KEY_PUB = "pub"
        private const val KEY_PRIV = "priv"
    }
}

/** 局域网请求与签名（与被控端 LocalHttpServer 的验签协议一一对应） */
class LocalTransport(
    private val store: LocalParentStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun post(url: String, body: String): String {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        try {
            val text = response.body?.string() ?: error("空响应")
            if (!response.isSuccessful) error("HTTP " + response.code + ": " + text)
            return text
        } finally {
            response.close()
        }
    }

    /** 配对：提交令牌与家长公钥，校验回执后派生并保存主密钥 */
    suspend fun pair(ip: String, port: Int, token: String, deviceCode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = """{"token":"${token.trim()}","pub":"${store.parentPublicKey()}"}"""
                val response = post("http://$ip:$port/local/v1/pair", body)
                val obj = json.parseToJsonElement(response).jsonObject
                require(obj["ok"]?.jsonPrimitive?.content == "true") { "配对被拒绝（令牌错误或已使用）" }
                val childPub = obj["childPub"]?.jsonPrimitive?.content
                    ?: error("被控端未返回公钥（请升级被控端到 1.0.13+）")
                val proof = obj["proof"]?.jsonPrimitive?.content ?: error("缺少回执签名")

                // 与被控端同口径派生主密钥：ECDH(parentPriv, childPub) → KDF
                val peer = KeyFactory.getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(Base64.decode(childPub, Base64.NO_WRAP)))
                val agreement = KeyAgreement.getInstance("ECDH")
                agreement.init(store.parentPrivateKey())
                agreement.doPhase(peer, true)
                val master = kdf(agreement.generateSecret(), "zzl-local-master-v1")

                // 校验回执：证明对端持有同一把 M
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(master, "HmacSHA256"))
                val expected = Base64.encodeToString(
                    mac.doFinal("zzl-pair-proof".toByteArray()), Base64.NO_WRAP,
                )
                require(expected == proof) { "密钥校验失败" }

                store.savePaired(
                    LocalParentStore.PairedDevice(
                        deviceCode = deviceCode.trim(),
                        childPub = childPub,
                        masterKey = master,
                        ip = ip.trim(),
                        port = port,
                    ),
                )
            }
        }

    /** 已签名请求：返回响应体文本；失败抛异常 */
    suspend fun request(path: String, body: String = ""): String = withContext(Dispatchers.IO) {
        val device = store.paired() ?: error("尚未配对")
        val ts = System.currentTimeMillis()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(device.masterKey, "HmacSHA256"))
        val sign = Base64.encodeToString(
            mac.doFinal("$path|$ts".toByteArray()), Base64.NO_WRAP,
        )
        val payload = if (body.isBlank()) """{"ts":$ts,"sign":"$sign"}"""
        else {
            val obj = json.parseToJsonElement(body).jsonObject.toMutableMap()
            obj["ts"] = kotlinx.serialization.json.JsonPrimitive(ts)
            obj["sign"] = kotlinx.serialization.json.JsonPrimitive(sign)
            kotlinx.serialization.json.JsonObject(obj).toString()
        }
        val request = Request.Builder()
            .url("http://${device.ip}:${device.port}$path")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        try {
            val text = response.body?.string() ?: error("空响应")
            if (!response.isSuccessful) error("HTTP " + response.code + ": " + text)
            text
        } finally {
            response.close()
        }
    }

    suspend fun getState(): String = request("/local/v1/state")
    suspend fun getPolicy(): PolicyBundleDto =
        json.decodeFromString(PolicyBundleDto.serializer(), request("/local/v1/policy"))

    suspend fun putPolicy(bundle: PolicyBundleDto) {
        val text = json.encodeToString(PolicyBundleDto.serializer(), bundle)
        request("/local/v1/policy", text)
    }

    suspend fun command(action: String) {
        request("/local/v1/command", """{"action":"$action"}""")
    }

    /** 与被控端 LocalIdentity.kdf 完全同口径 */
    private fun kdf(secret: ByteArray, info: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val prk = mac.doFinal("zzl-hkdf-salt-v1".toByteArray())
        val mac2 = Mac.getInstance("HmacSHA256")
        mac2.init(SecretKeySpec(prk, "HmacSHA256"))
        return mac2.doFinal(info.toByteArray())
    }

    companion object {
        private const val TAG = "LocalTransport"
    }
}
