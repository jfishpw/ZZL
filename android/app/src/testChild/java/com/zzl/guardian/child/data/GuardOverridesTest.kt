package com.zzl.guardian.child.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 授权 → 覆盖项的推导验证。
 *
 * 这段逻辑决定「家长给的加时/放行到底有没有生效、什么时候失效」，
 * 是纯计算，因此可以在 JVM 上完整覆盖。
 *
 * 最容易错的两条：
 *   1. 加时没有绑定额度日 → 昨天批的加时会一直留在今天
 *   2. 已过期的授权仍被算进去 → 管控形同虚设
 */
class GuardOverridesTest {

    private val now = 1_800_000_000_000L

    private fun grant(
        id: Long = 1,
        scope: String,
        packageName: String? = null,
        extraMinutes: Int? = null,
        dayKey: String? = null,
        expireAt: Long? = null,
    ) = GrantEntity(
        grantId = id,
        deviceId = 1,
        scope = scope,
        packageName = packageName,
        appLabel = null,
        extraMinutes = extraMinutes,
        dayKey = dayKey,
        expireAt = expireAt,
        source = "manual",
        createdAt = now - 1000,
    )

    @Test
    fun noGrantsMeansNoOverrides() {
        val overrides = GuardOverrides.of(locked = false, grants = emptyList(), dayKey = "2026-09-18", at = now)

        assertFalse(overrides.forcedLocked)
        assertNull(overrides.unlockedUntil)
        assertEquals(0L, overrides.extraTotalMs)
        assertTrue(overrides.allowedApps.isEmpty())
    }

    @Test
    fun lockedFlagIsCarriedThrough() {
        val overrides = GuardOverrides.of(true, emptyList(), "2026-09-18", now)
        assertTrue(overrides.forcedLocked)
    }

    @Test
    fun totalAddSumsExtraMinutes() {
        val grants = listOf(
            grant(1, GrantScope.TOTAL_ADD, extraMinutes = 30, dayKey = "2026-09-18", expireAt = now + 60_000),
            grant(2, GrantScope.TOTAL_ADD, extraMinutes = 15, dayKey = "2026-09-18", expireAt = now + 60_000),
        )
        val overrides = GuardOverrides.of(false, grants, "2026-09-18", now)
        assertEquals(45 * 60_000L, overrides.extraTotalMs)
    }

    @Test
    fun totalAddIsIgnoredOnAnotherDayKey() {
        // 关键：昨天批准的加时不能泄漏到今天
        val grants = listOf(
            grant(1, GrantScope.TOTAL_ADD, extraMinutes = 30, dayKey = "2026-09-17", expireAt = now + 3600_000),
        )
        val overrides = GuardOverrides.of(false, grants, "2026-09-18", now)
        assertEquals(0L, overrides.extraTotalMs)
    }

    @Test
    fun totalAddWithNullDayKeyStillCounts() {
        // 服务端早期数据或异常情况下 day_key 可能为空，此时按"仍然有效"处理更安全
        val grants = listOf(
            grant(1, GrantScope.TOTAL_ADD, extraMinutes = 20, dayKey = null, expireAt = now + 60_000),
        )
        assertEquals(20 * 60_000L, GuardOverrides.of(false, grants, "2026-09-18", now).extraTotalMs)
    }

    @Test
    fun expiredGrantsAreIgnored() {
        val grants = listOf(
            grant(1, GrantScope.TOTAL_ADD, extraMinutes = 30, dayKey = "2026-09-18", expireAt = now - 1),
            grant(2, GrantScope.APP_ALLOW, packageName = "com.a", expireAt = now - 1),
            grant(3, GrantScope.UNLOCK, expireAt = now - 1),
        )
        val overrides = GuardOverrides.of(false, grants, "2026-09-18", now)

        assertEquals(0L, overrides.extraTotalMs)
        assertTrue(overrides.allowedApps.isEmpty())
        assertNull(overrides.unlockedUntil)
        assertFalse("已过期的解封不该生效", overrides.isUnlocked(now))
    }

    @Test
    fun expiryBoundaryIsExclusive() {
        // expire_at == now 视为已过期：与服务端 activeGrants 的 `expire_at > at` 一致
        val grants = listOf(grant(1, GrantScope.UNLOCK, expireAt = now))
        assertNull(GuardOverrides.of(false, grants, "2026-09-18", now).unlockedUntil)
    }

    @Test
    fun appAllowCollectsPackagesAndIgnoresBlank() {
        val grants = listOf(
            grant(1, GrantScope.APP_ALLOW, packageName = "com.a", expireAt = now + 60_000),
            grant(2, GrantScope.APP_ALLOW, packageName = "com.b", expireAt = now + 60_000),
            grant(3, GrantScope.APP_ALLOW, packageName = null, expireAt = now + 60_000),
        )
        val overrides = GuardOverrides.of(false, grants, "2026-09-18", now)
        assertEquals(setOf("com.a", "com.b"), overrides.allowedApps)
    }

    @Test
    fun unlockUsesTheFurthestExpiry() {
        val grants = listOf(
            grant(1, GrantScope.UNLOCK, expireAt = now + 10 * 60_000),
            grant(2, GrantScope.UNLOCK, expireAt = now + 40 * 60_000),
        )
        val overrides = GuardOverrides.of(false, grants, "2026-09-18", now)

        assertEquals(now + 40 * 60_000, overrides.unlockedUntil)
        assertTrue(overrides.isUnlocked(now + 30 * 60_000))
    }

    @Test
    fun unlockWithoutExpiryMeansUnlimited() {
        val grants = listOf(grant(1, GrantScope.UNLOCK, expireAt = null))
        val overrides = GuardOverrides.of(false, grants, "2026-09-18", now)

        assertEquals(Long.MAX_VALUE, overrides.unlockedUntil)
        assertTrue("没有到期时间就一直是解封状态", overrides.isUnlocked(now + 10L * 365 * 24 * 3600 * 1000))
    }

    @Test
    fun limitIncludesExtra() {
        val overrides = GuardOverrides(extraTotalMs = 15 * 60_000L)
        assertEquals(75 * 60_000L, overrides.limitMsFor(60 * 60_000L))
    }

    @Test
    fun negativeExtraMinutesAreClamped() {
        // 脏数据不该把上限算成负数，那会导致"永远超额"、应用彻底打不开
        val grants = listOf(
            grant(1, GrantScope.TOTAL_ADD, extraMinutes = -100, dayKey = "2026-09-18", expireAt = now + 60_000),
        )
        assertEquals(0L, GuardOverrides.of(false, grants, "2026-09-18", now).extraTotalMs)
    }

    @Test
    fun unknownScopeIsIgnored() {
        val grants = listOf(grant(1, "future_scope", expireAt = now + 60_000))
        val overrides = GuardOverrides.of(false, grants, "2026-09-18", now)

        assertEquals(0L, overrides.extraTotalMs)
        assertTrue(overrides.allowedApps.isEmpty())
        assertNull(overrides.unlockedUntil)
    }
}
