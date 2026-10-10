package com.zzl.guardian.child.local

import android.content.Context
import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 本地模式身份与密钥材料（设计文档 docs/本地模式设计.md §3）。
 *
 * 设计要点：
 *  - 设备身份用 **EC P-256 静态密钥对**：公钥即身份（指纹），私钥永不外传，不需要任何注册服务；
 *  - 与家长端各持一把静态密钥，做一次 ECDH 得到共享秘密 S，双方各自派生同一把主密钥 M
 *    （KDF(M) 相同即证明"我就是要连接的那个家长端"，无需第三个证书机构）；
 *  - 挑战应答 = HMAC(M, nonce‖ts)，防重放与冒名。
 *
 * 为什么不用 X25519：Android 上 XDH 的 KeyAgreement 在部分 ROM/版本上不可用，
 * EC P-256 从 API 23 起全平台可用，工程上更稳。
 */
class LocalIdentity(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    /** 设备公钥（Base64 X.509），即身份 */
    val devicePublicKey: String
        get() = prefs.getString(KEY_PUB, null) ?: synchronized(this) {
            val existing = prefs.getString(KEY_PUB, null)
            if (existing != null) {
                existing
            } else {
                val pair = generate()
                persist(pair)
                Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
            }
        }

    /** 设备公钥的未压缩点形式（0x04||X||Y，Base64）——配对时上送，跨 ROM 更稳 */
    val devicePublicKeyRaw: String
        get() = Base64.encodeToString(toRawPoint(publicKeyOf(devicePublicKey)), Base64.NO_WRAP)

    /** 设备 ID 前 8 位，配对码用（孩子可读） */
    val deviceCode: String
        get() = devicePublicKey.take(8)

    private var cachedPrivate: PrivateKey? = null

    private fun generate(): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"), random)
        return gen.generateKeyPair()
    }

    private fun persist(pair: KeyPair) {
        prefs.edit()
            .putString(KEY_PUB, Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
            .putString(KEY_PRIV, Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            .apply()
    }

    private fun privateKey(): PrivateKey {
        cachedPrivate?.let { return it }
        val encoded = prefs.getString(KEY_PRIV, null) ?: error("本地密钥尚未生成")
        val key = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(encoded, Base64.NO_WRAP)))
        cachedPrivate = key
        return key
    }

    /** 由对端公钥派生主密钥 M（两侧算法相同 → 结果一致） */
    fun deriveMasterKey(peerPublicKeyBase64: String): ByteArray {
        val peer = parsePeerPublicKey(peerPublicKeyBase64)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey())
        agreement.doPhase(peer, true)
        return kdf(agreement.generateSecret(), "zzl-local-master-v1")
    }

    /** 挑战应答签名 */
    fun sign(masterKey: ByteArray, payload: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(masterKey, "HmacSHA256"))
        return mac.doFinal(payload)
    }

    /** 会话密钥：每次挑战用不同 nonce 派生，避免密钥重用 */
    fun sessionKey(masterKey: ByteArray, nonce: String): ByteArray =
        kdf(masterKey, "zzl-local-session-$nonce")

    /* ---------------- 配对码 ---------------- */

    /** 生成 6 位一次性令牌（60 秒有效） */
    fun newPairingToken(): String {
        // 双参 nextInt(from, to) 在 Android 上要 API 34+，这里用单参 + 偏移
        val token = (100000 + random.nextInt(900000)).toString()
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_TOKEN_AT, System.currentTimeMillis())
            .apply()
        return token
    }

    /**
     * 校验令牌（**不消费**）。
     *
     * 消费与校验分离是必须的：配对流程在完成密钥协商之前失败（弱网/权限/编码问题）
     * 时若已作废令牌，家长第二次尝试就会得到 no_token —— 真机上踩过。
     */
    fun checkPairingToken(input: String): Boolean {
        val token = prefs.getString(KEY_TOKEN, null) ?: return false
        val at = prefs.getLong(KEY_TOKEN_AT, 0L)
        val fresh = System.currentTimeMillis() - at < TOKEN_TTL_MS
        return fresh && input.trim() == token
    }

    /** 配对成功后才作废令牌 */
    fun invalidatePairingToken() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_TOKEN_AT).apply()
    }

    /** 取当前有效令牌；没有或已过期就生成一个新的（被控端界面展示用） */
    fun ensurePairingToken(): String {
        val token = prefs.getString(KEY_TOKEN, null)
        val at = prefs.getLong(KEY_TOKEN_AT, 0L)
        val fresh = token != null && System.currentTimeMillis() - at < TOKEN_TTL_MS
        return if (fresh) token!! else newPairingToken()
    }

    fun tokenRemainingSeconds(): Long {
        val at = prefs.getLong(KEY_TOKEN_AT, 0L)
        if (at == 0L) return 0
        val left = TOKEN_TTL_MS - (System.currentTimeMillis() - at)
        return if (left > 0) left / 1000 else 0
    }

    /** 记住已配对的家长端（多端白名单） */
    fun rememberParent(publicKeyBase64: String) {
        val set = prefs.getStringSet(KEY_PARENTS, emptySet())!!.toMutableSet()
        set.add(publicKeyBase64)
        prefs.edit().putStringSet(KEY_PARENTS, set).apply()
        Log.i(TAG, "已记录配对家长端，当前数量=${set.size}")
    }

    fun isKnownParent(publicKeyBase64: String): Boolean =
        prefs.getStringSet(KEY_PARENTS, emptySet())?.contains(publicKeyBase64) == true

    fun hasPairedParent(): Boolean = (prefs.getStringSet(KEY_PARENTS, emptySet())?.size ?: 0) > 0

    companion object {
        private const val TAG = "LocalIdentity"
        private const val PREFS = "zzl_local_identity"
        private const val KEY_PUB = "pub"
        private const val KEY_PRIV = "priv"
        private const val KEY_TOKEN = "token"
        private const val KEY_TOKEN_AT = "token_at"
        private const val KEY_PARENTS = "parents"
        private const val TOKEN_TTL_MS = 10 * 60_000L

        /** 未压缩点 → PublicKey（显式给定曲线参数，不经过证书/SPKI 解析路径） */
        fun publicFromRawPoint(bytes: ByteArray): PublicKey {
            val params = java.security.AlgorithmParameters.getInstance("EC").apply {
                init(java.security.spec.ECGenParameterSpec("secp256r1"))
            }
            val spec = params.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
            val x = java.math.BigInteger(1, bytes.copyOfRange(1, 33))
            val y = java.math.BigInteger(1, bytes.copyOfRange(33, 65))
            return KeyFactory.getInstance("EC").generatePublic(
                java.security.spec.ECPublicKeySpec(java.security.spec.ECPoint(x, y), spec),
            )
        }

        /** PublicKey → 未压缩点（65 字节，左右各补齐 32 字节） */
        fun toRawPoint(key: PublicKey): ByteArray {
            val ec = key as java.security.interfaces.ECPublicKey
            fun pad(v: java.math.BigInteger): ByteArray {
                val b = v.toByteArray()
                return when {
                    b.size == 32 -> b
                    b.size == 33 && b[0].toInt() == 0 -> b.copyOfRange(1, 33)
                    b.size < 32 -> ByteArray(32 - b.size) + b
                    else -> b.copyOfRange(b.size - 32, b.size)
                }
            }
            return byteArrayOf(0x04) + pad(ec.w.affineX) + pad(ec.w.affineY)
        }

        /** 容忍两种编码：65 字节未压缩点（新）或 X.509 SPKI（旧/其它端） */
        fun parsePeerPublicKey(base64: String): PublicKey {
            val bytes = Base64.decode(base64.trim(), Base64.DEFAULT)
            if (bytes.size == 65 && bytes[0].toInt() == 0x04) return publicFromRawPoint(bytes)
            return runCatching {
                KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
            }.getOrElse {
                error(
                    "对端公钥无法解析（长度 " + bytes.size + "，首字节 " +
                        (bytes.firstOrNull()?.toInt() ?: -1) + "）",
                )
            }
        }

        private fun publicKeyOf(base64: String): PublicKey = runCatching {
            KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.decode(base64.trim(), Base64.DEFAULT)),
            )
        }.getOrElse { error("本地公钥无法解析") }

        /** HKDF-SHA256（简化版：extract 后再 expand 一次，32 字节输出足够本协议使用） */
        fun kdf(secret: ByteArray, info: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            val prk = mac.doFinal(("zzl-hkdf-salt-v1").toByteArray())
            val mac2 = Mac.getInstance("HmacSHA256")
            mac2.init(SecretKeySpec(prk, "HmacSHA256"))
            return mac2.doFinal(info.toByteArray())
        }
    }
}
