package com.zzl.guardian.child.pin

import com.zzl.guardian.data.pin.PinPolicy
import com.zzl.guardian.data.pin.PinThrottle
import com.zzl.guardian.data.pin.PinStrength
import com.zzl.guardian.data.pin.PinLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密码强度校验与限流状态机。
 *
 * 这两件事在真机上极难验证：强度校验要试各种弱口令，
 * 限流要真的等 10 分钟锁定期结束。抽成纯逻辑后可以在 JVM 上完整覆盖。
 */
class PinPolicyTest {

    /* ==================== 强度校验 ==================== */

    @Test
    fun rejectsTooShort() {
        val result = PinPolicy.validate("12345")
        assertFalse(result.ok)
        assertEquals(PinStrength.WEAK, result.strength)
        assertTrue(result.reason!!.contains("至少"))
    }

    @Test
    fun acceptsSixDigitMinimum() {
        // 6 位是下限：4 位数字只有 1 万种，配合限流也不够安全
        assertTrue(PinPolicy.validate("829314").ok)
    }

    @Test
    fun rejectsTooLong() {
        assertFalse(PinPolicy.validate("1".repeat(33)).ok)
    }

    @Test
    fun rejectsWhitespace() {
        assertFalse(PinPolicy.validate("123 456").ok)
    }

    @Test
    fun rejectsBannedCommonPins() {
        for (pin in listOf("123456", "000000", "888888", "qwerty", "password")) {
            val result = PinPolicy.validate(pin)
            assertFalse("$pin 应被拒绝", result.ok)
        }
    }

    @Test
    fun policyIsSharedBetweenEnds() {
        /**
         * 控制端（家长手机）与被控端（平板）用的是**同一份** `PinPolicy`。
         *
         * 这条测试固化一个真实的回归：控制端早期只校验"长度 ≥ 6"，
         * 于是家长可以在控制端远程设一个 `123456` —— 设备端的强度策略被整个绕过。
         * 下面这些密码**长度都合格**，只有真正跑策略才会被拦下。
         */
        val lengthOkButWeak = listOf(
            "123456",   // 最常见弱口令
            "654321",   // 逆序，同样在禁用表内
            "111111",   // 重复字符
            "456789",   // 连续递增
            "987654",   // 连续递减
            "20110315", // 生日
        )
        for (pin in lengthOkButWeak) {
            assertTrue("$pin 长度合格，前置条件不成立", pin.length >= PinPolicy.MIN_LENGTH)
            assertFalse("$pin 必须被策略拒绝，否则两端行为不一致", PinPolicy.validate(pin).ok)
        }
    }

    @Test
    fun rejectsRepeatedCharacters() {
        val result = PinPolicy.validate("7777777")
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("重复"))
    }

    @Test
    fun rejectsSequentialDigits() {
        assertFalse("连续递增应被拒绝", PinPolicy.validate("456789").ok)
        assertFalse("连续递减应被拒绝", PinPolicy.validate("987654").ok)
    }

    @Test
    fun rejectsNonSequentialButAscendingLookingPins() {
        // 123580 不是连续序列，不该被这条规则拦下（否则会误伤大量正常密码）
        assertTrue(PinPolicy.validate("123580").ok)
    }

    @Test
    fun rejectsDateLikePins() {
        val result = PinPolicy.validate("20110315")
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("日期"))
    }

    @Test
    fun allowsYearPrefixThatIsNotAValidDate() {
        // 20269999 看起来像日期但月日非法，不该被日期规则拦下
        assertTrue(PinPolicy.validate("20269999").ok)
    }

    @Test
    fun rejectsSameAsAnotherLevel() {
        // 三级用同一个密码等于只有一级，分级形同虚设
        val result = PinPolicy.validate("829314", others = listOf("829314"))
        assertFalse(result.ok)
        assertTrue(result.reason!!.contains("相同"))
    }

    @Test
    fun strongerPinsGetHigherRating() {
        val simple = PinPolicy.validate("829314")
        val long = PinPolicy.validate("8293abcd")
        val mixed = PinPolicy.validate("Kx7#mQ2p9z")

        assertEquals(PinStrength.ACCEPTABLE, simple.strength)
        assertTrue(long.strength.level >= simple.strength.level)
        assertEquals(PinStrength.STRONG, mixed.strength)
    }

    @Test
    fun givesAdviceForShortButAcceptablePins() {
        val result = PinPolicy.validate("829314")
        assertTrue(result.ok)
        assertNotNull("6-7 位应给出加长建议", result.advice)
    }

    @Test
    fun noAdviceForStrongPins() {
        assertNull(PinPolicy.validate("Kx7#mQ2p9z").advice)
    }

    /* ==================== 限流状态机 ==================== */

    private val t0 = 1_800_000_000_000L

    @Test
    fun freshStateIsNotLocked() {
        val state = PinThrottle.initial()
        assertFalse(state.isLocked(t0))
        assertEquals(PinThrottle.FIRST_THRESHOLD, state.attemptsLeft(t0))
    }

    @Test
    fun locksAfterFiveFailures() {
        var state = PinThrottle.initial()
        repeat(PinThrottle.FIRST_THRESHOLD - 1) { state = PinThrottle.onFailure(state, t0) }
        assertFalse("第 4 次失败还不该锁", state.isLocked(t0))
        assertEquals(1, state.attemptsLeft(t0))

        state = PinThrottle.onFailure(state, t0)
        assertTrue("第 5 次失败应触发锁定", state.isLocked(t0))
        assertEquals(PinThrottle.FIRST_LOCK_MS, state.remainingLockMs(t0))
    }

    @Test
    fun lockExpiresNaturally() {
        var state = PinThrottle.initial()
        repeat(PinThrottle.FIRST_THRESHOLD) { state = PinThrottle.onFailure(state, t0) }

        val duringLock = t0 + PinThrottle.FIRST_LOCK_MS - 1
        assertTrue("锁定期内仍锁定", state.isLocked(duringLock))

        val afterLock = t0 + PinThrottle.FIRST_LOCK_MS
        assertFalse("到点即解锁（边界时刻算已解锁）", state.isLocked(afterLock))
    }

    @Test
    fun escalatesToLongLockAtTenFailures() {
        var state = PinThrottle.initial()
        repeat(PinThrottle.SECOND_THRESHOLD) { state = PinThrottle.onFailure(state, t0) }
        assertEquals(
            "第 10 次失败应升级为 1 小时锁定",
            PinThrottle.SECOND_LOCK_MS,
            state.remainingLockMs(t0),
        )
    }

    @Test
    fun failureCountResetsAfterQuietWindow() {
        var state = PinThrottle.initial()
        repeat(4) { state = PinThrottle.onFailure(state, t0) }

        // 隔了很久再错一次：旧的 4 次计数不该继续累积
        val muchLater = t0 + PinThrottle.RESET_WINDOW_MS + 1
        state = PinThrottle.onFailure(state, muchLater)

        assertEquals(1, state.failedCount)
        assertFalse("不该因为三天前的 4 次失败而被锁", state.isLocked(muchLater))
    }

    @Test
    fun lockSurvivesProcessRestart() {
        // 状态完全由「最近失败时间 + 累计次数 + 锁定截止时间」推导，
        // 因此进程被杀、设备重启都不会让限流失效
        var state = PinThrottle.initial()
        repeat(PinThrottle.FIRST_THRESHOLD) { state = PinThrottle.onFailure(state, t0) }

        val restored = PinThrottle.State(
            failedCount = state.failedCount,
            lastFailedAt = state.lastFailedAt,
            lockedUntil = state.lockedUntil,
        )
        assertTrue(restored.isLocked(t0 + 1000))
    }

    @Test
    fun successClearsEverything() {
        var state = PinThrottle.initial()
        repeat(3) { state = PinThrottle.onFailure(state, t0) }

        state = PinThrottle.onSuccess()
        assertEquals(0, state.failedCount)
        assertEquals(PinThrottle.FIRST_THRESHOLD, state.attemptsLeft(t0))
        assertFalse(state.isLocked(t0))
    }

    @Test
    fun attemptsLeftIsZeroWhileLocked() {
        var state = PinThrottle.initial()
        repeat(PinThrottle.FIRST_THRESHOLD) { state = PinThrottle.onFailure(state, t0) }
        assertEquals(0, state.attemptsLeft(t0 + 1000))
    }

    /* ==================== 级别定义 ==================== */

    @Test
    fun onlySuperLevelCanExitGuard() {
        assertFalse(PinLevel.DAILY.canExitGuard)
        assertFalse(PinLevel.ADMIN.canExitGuard)
        assertTrue("只有超级密码能退出管控", PinLevel.SUPER.canExitGuard)
    }

    @Test
    fun levelLookupById() {
        assertEquals(PinLevel.DAILY, PinLevel.of(1))
        assertEquals(PinLevel.ADMIN, PinLevel.of(2))
        assertEquals(PinLevel.SUPER, PinLevel.of(3))
        assertNull(PinLevel.of(4))
        assertNull(PinLevel.of(0))
    }
}
