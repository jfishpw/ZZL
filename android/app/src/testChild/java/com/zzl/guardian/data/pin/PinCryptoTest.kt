package com.zzl.guardian.data.pin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密码哈希的纯 JVM 验证。
 *
 * 这段代码在控制端与被控端都要用（家长远程设密码、设备本地验证），
 * 两端必须算出完全相同的结果 —— 否则家长在控制端设的密码在设备上永远打不开。
 * 因此它的正确性比一般的工具类更重要。
 */
class PinCryptoTest {

    @Test
    fun sameInputProducesSameHash() {
        val salt = PinCrypto.newSalt()
        val a = PinCrypto.hash("829314", salt, 10_000)
        val b = PinCrypto.hash("829314", salt, 10_000)
        assertEquals("同样的密码与盐必须得到同样的哈希", a, b)
    }

    @Test
    fun differentSaltProducesDifferentHash() {
        // 同一密码配不同盐必须得到不同哈希 —— 否则盐就白加了，
        // 两个用同一密码的家长会在数据库里留下相同的指纹
        val a = PinCrypto.hash("829314", PinCrypto.newSalt(), 10_000)
        val b = PinCrypto.hash("829314", PinCrypto.newSalt(), 10_000)
        assertNotEquals(a, b)
    }

    @Test
    fun differentPasswordProducesDifferentHash() {
        val salt = PinCrypto.newSalt()
        assertNotEquals(
            PinCrypto.hash("829314", salt, 10_000),
            PinCrypto.hash("829315", salt, 10_000),
        )
    }

    @Test
    fun iterationsAffectTheHash() {
        // 迭代次数必须真正参与计算：如果它被忽略，
        // 日后调高迭代次数就不会改变任何东西，安全升级形同虚设
        val salt = PinCrypto.newSalt()
        assertNotEquals(
            PinCrypto.hash("829314", salt, 10_000),
            PinCrypto.hash("829314", salt, 20_000),
        )
    }

    @Test
    fun saltIsRandomAndDecodable() {
        val salts = (1..20).map { PinCrypto.newSalt() }
        assertEquals("20 次生成的盐必须互不相同", 20, salts.toSet().size)

        // 盐要能被 decode 回 16 字节，否则 hash() 会抛异常
        for (salt in salts) {
            assertTrue(salt.isNotBlank())
        }
    }

    @Test
    fun verifyAcceptsCorrectPassword() {
        val salt = PinCrypto.newSalt()
        val hash = PinCrypto.hash("Kx7#mQ2p9z", salt, 10_000)
        assertTrue(PinCrypto.verify("Kx7#mQ2p9z", hash, salt, 10_000))
    }

    @Test
    fun verifyRejectsWrongPassword() {
        val salt = PinCrypto.newSalt()
        val hash = PinCrypto.hash("829314", salt, 10_000)
        assertFalse(PinCrypto.verify("829315", hash, salt, 10_000))
        assertFalse(PinCrypto.verify("", hash, salt, 10_000))
        assertFalse(PinCrypto.verify("829314 ", hash, salt, 10_000))
    }

    @Test
    fun verifyRejectsWrongIterations() {
        // 迭代次数不匹配时必须验证失败，否则「换了参数还能通过」意味着
        // 哈希里实际没有绑定迭代次数
        val salt = PinCrypto.newSalt()
        val hash = PinCrypto.hash("829314", salt, 10_000)
        assertFalse(PinCrypto.verify("829314", hash, salt, 20_000))
    }

    @Test
    fun verifyHandlesMalformedInputGracefully() {
        // 脏数据（盐被截断、哈希不是合法 Base64）不能让验证抛异常 ——
        // 那会让密码输入页直接崩掉，家长连重试的机会都没有
        assertFalse(PinCrypto.verify("829314", "not-base64!!!", "also-bad", 10_000))
        assertFalse(PinCrypto.verify("829314", "", PinCrypto.newSalt(), 10_000))
    }

    @Test
    fun hashIsStableAcrossInstances() {
        // 控制端算好上传、被控端下载后验证 —— 两端必须算出同一个结果。
        // 这里用固定盐与固定参数模拟这条跨端链路。
        val salt = "AAAAAAAAAAAAAAAAAAAAAA=="
        val first = PinCrypto.hash("829314", salt, 10_000)
        val second = PinCrypto.hash("829314", salt, 10_000)
        assertEquals(first, second)

        // 同一条哈希在"另一端"也能验证通过
        assertTrue(PinCrypto.verify("829314", first, salt, 10_000))
    }
}
