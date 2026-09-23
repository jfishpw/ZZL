package com.zzl.guardian.data.pin

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 离线密码的哈希计算。
 *
 * 刻意做成**纯 JVM 代码**（不碰 Android 的 Context / SharedPreferences）：
 * 密码强度校验与限流状态机是这类产品里最容易出错、又最难在真机上复现的部分，
 * 抽成纯函数后就能用 JVM 单元测试完整覆盖。
 *
 * 算法选择 PBKDF2-HMAC-SHA256：Java 标准库自带（`SecretKeyFactory`），
 * 不需要引入任何第三方库，也不需要原生依赖 —— 与后端"零原生依赖"的取向一致。
 *
 * ⚠️ Base64 必须用 `java.util.Base64` 而不是 `android.util.Base64`：
 * 后者在 JVM 单元测试里是空桩，一调用就抛 RuntimeException，
 * 会让这个最需要被测试的类反而完全测不了。minSdk 26 起 java.util.Base64 可用。
 */
object PinCrypto {

    /**
     * 默认迭代次数。
     *
     * 10 万次在主流机型上约 100-200ms，用户感知为"轻微停顿"，
     * 而暴力枚举 6 位数字（100 万种）需要约 30 小时/单核 —— 配合限流足够。
     */
    const val DEFAULT_ITERATIONS = 120_000

    /** 每级独立随机盐的长度（字节） */
    private const val SALT_BYTES = 16

    private const val KEY_BITS = 256

    private val random = SecureRandom()

    fun newSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * 计算密码哈希。
     *
     * 输入是**明文密码**，输出是 Base64 编码的派生密钥。
     * 服务端只接收输出，从不接触明文。
     */
    fun hash(password: String, salt: String, iterations: Int = DEFAULT_ITERATIONS): String {
        val saltBytes = Base64.getDecoder().decode(salt)
        val spec = PBEKeySpec(password.toCharArray(), saltBytes, iterations, KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            Base64.getEncoder().encodeToString(factory.generateSecret(spec).encoded)
        } finally {
            // 及时清掉内存里的明文副本
            spec.clearPassword()
        }
    }

    /** 常量时间比较，避免通过响应时间逐位猜出哈希 */
    fun verify(password: String, expectedHash: String, salt: String, iterations: Int): Boolean {
        val actual = runCatching { hash(password, salt, iterations) }.getOrNull() ?: return false
        return constantTimeEquals(actual, expectedHash)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (index in a.indices) {
            diff = diff or (a[index].code xor b[index].code)
        }
        return diff == 0
    }
}
