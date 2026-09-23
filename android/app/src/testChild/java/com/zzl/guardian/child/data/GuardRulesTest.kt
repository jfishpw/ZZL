package com.zzl.guardian.child.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 规则判定的核心逻辑全部是纯函数，可以在 JVM 上直接验证，
 * 不需要真机、不需要模拟器。
 *
 * 重点覆盖三类最容易写错、且真机上极难复现的分支：
 *   - 跨零点时段（22:00 - 07:00）的边界
 *   - 额度归日点（resetHour 不是 0 时，凌晨算前一天）
 *   - 生效星期位掩码的位序（bit0 = 周一）
 */
class GuardRulesTest {

    companion object {
        /**
         * DayKeys 内部用 Calendar.getInstance()，依赖进程默认时区。
         * 固定成 Asia/Shanghai，断言结果才与运行机器无关。
         */
        @JvmStatic
        @BeforeClass
        fun pinTimeZone() {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        }
    }

    /** 2026-09-14 周一 · 09-18 周五 · 09-19 周六 · 09-20 周日 */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, 0)
        return calendar.timeInMillis
    }

    private fun policy(
        weekdayTotalMin: Int,
        weekendTotalMin: Int,
        resetHour: Int = 0,
        enabled: Boolean = true,
    ) = PolicyEntity(
        deviceId = 1L,
        weekdayTotalMin = weekdayTotalMin,
        weekendTotalMin = weekendTotalMin,
        resetHour = resetHour,
        listMode = "blacklist",
        allowTimeRequest = true,
        enabled = enabled,
        version = 1,
        updatedAt = 0L,
        syncedAt = 0L,
    )

    /* ==================== 额度归日点 ==================== */

    @Test
    fun dayKey_naturalDay_whenResetHourIsZero() {
        assertEquals("2026-09-18", DayKeys.of(at(2026, 9, 18, 0, 0), 0))
        assertEquals("2026-09-18", DayKeys.of(at(2026, 9, 18, 12, 0), 0))
        assertEquals("2026-09-18", DayKeys.of(at(2026, 9, 18, 23, 59), 0))
        assertEquals("2026-09-19", DayKeys.of(at(2026, 9, 19, 0, 0), 0))
    }

    @Test
    fun dayKey_earlyMorningBelongsToPreviousDay_whenResetHourIs4() {
        // 最容易被算错的分支：凌晨 2 点的用量应归到"昨天"
        assertEquals("2026-09-17", DayKeys.of(at(2026, 9, 18, 0, 0), 4))
        assertEquals("2026-09-17", DayKeys.of(at(2026, 9, 18, 2, 0), 4))
        assertEquals("2026-09-17", DayKeys.of(at(2026, 9, 18, 3, 59), 4))
        // 到点即翻篇
        assertEquals("2026-09-18", DayKeys.of(at(2026, 9, 18, 4, 0), 4))
        assertEquals("2026-09-18", DayKeys.of(at(2026, 9, 18, 23, 0), 4))
    }

    /* ==================== 工作日 / 周末 ==================== */

    @Test
    fun isWeekendAt_matchesRealWeekdays() {
        assertFalse(DayKeys.isWeekendAt(at(2026, 9, 18, 12, 0))) // 周五
        assertTrue(DayKeys.isWeekendAt(at(2026, 9, 19, 12, 0))) // 周六
        assertTrue(DayKeys.isWeekendAt(at(2026, 9, 20, 12, 0))) // 周日
        assertFalse(DayKeys.isWeekendAt(at(2026, 9, 14, 12, 0))) // 周一
    }

    /* ==================== 星期位掩码 ==================== */

    @Test
    fun weekdayBit_startsFromMondayAsBitZero() {
        assertEquals(0, DayKeys.weekdayBit(at(2026, 9, 14, 12, 0))) // 周一
        assertEquals(1, DayKeys.weekdayBit(at(2026, 9, 15, 12, 0)))
        assertEquals(2, DayKeys.weekdayBit(at(2026, 9, 16, 12, 0)))
        assertEquals(3, DayKeys.weekdayBit(at(2026, 9, 17, 12, 0)))
        assertEquals(4, DayKeys.weekdayBit(at(2026, 9, 18, 12, 0))) // 周五
        assertEquals(5, DayKeys.weekdayBit(at(2026, 9, 19, 12, 0))) // 周六
        assertEquals(6, DayKeys.weekdayBit(at(2026, 9, 20, 12, 0))) // 周日
    }

    @Test
    fun guardAppRule_respectsWeekdayMaskAndEnabledFlag() {
        val mondayOnly = GuardAppRule("pkg", 0, emptyList(), weekdaysMask = 0b0000001, enabled = true)
        assertTrue(mondayOnly.isActiveOn(0))
        assertFalse(mondayOnly.isActiveOn(1))
        assertFalse(mondayOnly.isActiveOn(6))

        val allWeek = GuardAppRule("pkg", 0, emptyList(), weekdaysMask = 0b1111111, enabled = true)
        (0..6).forEach { assertTrue("bit $it 应生效", allWeek.isActiveOn(it)) }

        val none = GuardAppRule("pkg", 0, emptyList(), weekdaysMask = 0, enabled = true)
        (0..6).forEach { assertFalse(none.isActiveOn(it)) }

        // 关掉开关后，掩码设得再全也不生效
        val disabled = GuardAppRule("pkg", 0, emptyList(), weekdaysMask = 0b1111111, enabled = false)
        (0..6).forEach { assertFalse(disabled.isActiveOn(it)) }
    }

    /* ==================== 时间解析 ==================== */

    @Test
    fun parseHhMm_acceptsValidAndRejectsInvalid() {
        assertEquals(0, DayKeys.parseHhMm("00:00"))
        assertEquals(1140, DayKeys.parseHhMm("19:00"))
        assertEquals(1439, DayKeys.parseHhMm("23:59"))

        assertNull(DayKeys.parseHhMm("24:00"))
        assertNull(DayKeys.parseHhMm("19:60"))
        assertNull(DayKeys.parseHhMm("-1:00"))
        assertNull(DayKeys.parseHhMm("1900"))
        assertNull(DayKeys.parseHhMm("19"))
        assertNull(DayKeys.parseHhMm(""))
        assertNull(DayKeys.parseHhMm("aa:bb"))
        assertNull(DayKeys.parseHhMm("19:00:00"))
    }

    @Test
    fun minuteOfDay_reflectsHourAndMinute() {
        assertEquals(0, DayKeys.minuteOfDay(at(2026, 9, 18, 0, 0)))
        assertEquals(19 * 60, DayKeys.minuteOfDay(at(2026, 9, 18, 19, 0)))
        assertEquals(19 * 60 + 59, DayKeys.minuteOfDay(at(2026, 9, 18, 19, 59)))
        assertEquals(23 * 60 + 59, DayKeys.minuteOfDay(at(2026, 9, 18, 23, 59)))
    }

    /* ==================== 时段判定 ==================== */

    @Test
    fun timeWindow_normalRange_endsExclusively() {
        val window = TimeWindow(startMin = 19 * 60, endMin = 20 * 60) // 19:00 - 20:00

        assertFalse("18:59 不在时段内", window.contains(19 * 60 - 1))
        assertTrue("19:00 是起点，应包含", window.contains(19 * 60))
        assertTrue("19:59 应包含", window.contains(19 * 60 + 59))
        assertFalse("20:00 是终点，应为开区间", window.contains(20 * 60))
        assertFalse("20:01 应排除", window.contains(20 * 60 + 1))

        assertFalse(window.crossesMidnight)
    }

    @Test
    fun timeWindow_crossingMidnight_handlesBothSides() {
        // 22:00 - 07:00
        val window = TimeWindow(startMin = 22 * 60, endMin = 7 * 60)

        assertTrue("跨零点应被识别", window.crossesMidnight)

        assertFalse("21:59 不在时段内", window.contains(22 * 60 - 1))
        assertTrue("22:00 是起点，应包含", window.contains(22 * 60))
        assertTrue("23:59 应包含", window.contains(23 * 60 + 59))
        assertTrue("00:00 应包含（零点之后）", window.contains(0))
        assertTrue("06:59 应包含", window.contains(7 * 60 - 1))
        assertFalse("07:00 是终点，应为开区间", window.contains(7 * 60))
        assertFalse("中午 12:00 应排除", window.contains(12 * 60))
    }

    @Test
    fun timeWindow_fullDayRange_behavesAsExpected() {
        // 00:00 - 23:59，几乎全天
        val window = TimeWindow(startMin = 0, endMin = 23 * 60 + 59)
        assertFalse(window.crossesMidnight)
        assertTrue(window.contains(0))
        assertTrue(window.contains(12 * 60))
        assertFalse("23:59 是终点，应为开区间", window.contains(23 * 60 + 59))
    }

    /* ==================== 时段剩余时间（锁定预警用） ==================== */

    @Test
    fun timeWindow_minutesUntilEnd_normalRange() {
        val window = TimeWindow(startMin = 19 * 60, endMin = 20 * 60) // 19:00 - 20:00

        assertEquals(60, window.minutesUntilEnd(19 * 60))
        assertEquals(5, window.minutesUntilEnd(19 * 60 + 55))
        assertEquals(1, window.minutesUntilEnd(19 * 60 + 59))
        assertEquals("到达终点应为 0", 0, window.minutesUntilEnd(20 * 60).toLong())
    }

    @Test
    fun timeWindow_minutesUntilEnd_crossingMidnight_countsIntoNextDay() {
        // 22:00 - 07:00
        val window = TimeWindow(startMin = 22 * 60, endMin = 7 * 60)

        // 23:00 → 次日 07:00 还有 8 小时
        assertEquals(8 * 60, window.minutesUntilEnd(23 * 60))
        // 23:59 → 次日 07:00 还有 7 小时 1 分
        assertEquals(7 * 60 + 1, window.minutesUntilEnd(23 * 60 + 59))
        // 零点之后按普通差值算
        assertEquals(7 * 60, window.minutesUntilEnd(0))
        assertEquals(5, window.minutesUntilEnd(7 * 60 - 5))
        assertEquals(0, window.minutesUntilEnd(7 * 60))
    }

    /* ==================== 每日额度取值 ==================== */

    @Test
    fun dailyLimitMs_picksWeekdayOrWeekendBudget() {
        val target = policy(weekdayTotalMin = 60, weekendTotalMin = 120)

        assertEquals(60 * 60_000L, PolicyRules.dailyLimitMs(target, at(2026, 9, 18, 12, 0))) // 周五
        assertEquals(120 * 60_000L, PolicyRules.dailyLimitMs(target, at(2026, 9, 19, 12, 0))) // 周六
        assertEquals(120 * 60_000L, PolicyRules.dailyLimitMs(target, at(2026, 9, 20, 12, 0))) // 周日
    }

    @Test
    fun dailyLimitMs_zeroMeansNoUsageAllowed() {
        val target = policy(weekdayTotalMin = 0, weekendTotalMin = 0)
        assertEquals(0L, PolicyRules.dailyLimitMs(target, at(2026, 9, 18, 12, 0)))
    }

    @Test
    fun dailyLimitMs_ignoresEnabledFlag() {
        // 额度取值只看数值，是否生效由引擎单独判断，
        // 避免"关掉管控后剩余时长显示为 0"这种误导性界面
        val disabled = policy(weekdayTotalMin = 90, weekendTotalMin = 90, enabled = false)
        assertEquals(90 * 60_000L, PolicyRules.dailyLimitMs(disabled, at(2026, 9, 18, 12, 0)))
    }

    @Test
    fun policyRules_dayKey_usesPolicyResetHour() {
        val target = policy(60, 120, resetHour = 4)
        assertEquals("2026-09-17", PolicyRules.dayKey(target, at(2026, 9, 18, 2, 0)))
        assertEquals("2026-09-18", PolicyRules.dayKey(target, at(2026, 9, 18, 5, 0)))
    }

    /* ==================== 不计入当日总时长 ==================== */

    @Test
    fun guardAppRule_exemptFlagIsGatedByEnabledAndWeekday() {
        // 豁免与上限、时段共用同一套"今天到底生不生效"的条件。
        // 分开判断会留下家长看不懂的中间态：规则明明关着，孩子却不受总时长约束。
        assertTrue(
            GuardAppRule("p", 0, emptyList(), weekdaysMask = 0b1111111, enabled = true, exemptTotal = true)
                .isExemptTotalOn(0),
        )
        assertFalse(
            GuardAppRule("p", 0, emptyList(), weekdaysMask = 0b1111111, enabled = false, exemptTotal = true)
                .isExemptTotalOn(0),
        )
        assertFalse(
            GuardAppRule("p", 0, emptyList(), weekdaysMask = 0b1111111, enabled = true, exemptTotal = false)
                .isExemptTotalOn(0),
        )
        assertFalse(
            "非生效星期内豁免一并失效",
            GuardAppRule("p", 0, emptyList(), weekdaysMask = 0b0000001, enabled = true, exemptTotal = true)
                .isExemptTotalOn(4),
        )
    }

    @Test
    fun guardPolicy_exposesExemptPackagesForTheDay() {
        val mondayOnly = GuardAppRule(
            "com.monday", 0, emptyList(), weekdaysMask = 0b0000001, enabled = true, exemptTotal = true,
        )
        val always = GuardAppRule(
            "com.always", 0, emptyList(), weekdaysMask = 0b1111111, enabled = true, exemptTotal = true,
        )
        val switchedOff = GuardAppRule(
            "com.off", 0, emptyList(), weekdaysMask = 0b1111111, enabled = false, exemptTotal = true,
        )
        val plain = GuardAppRule(
            "com.plain", 0, emptyList(), weekdaysMask = 0b1111111, enabled = true, exemptTotal = false,
        )

        val guard = GuardPolicy(
            policy = policy(60, 120),
            listMode = GuardPolicy.MODE_BLACKLIST,
            listedPackages = emptySet(),
            rules = listOf(mondayOnly, always, switchedOff, plain).associateBy { it.packageName },
        )

        val friday = at(2026, 9, 18, 12, 0)
        assertEquals("周五只有『全天生效』的那条算豁免", setOf("com.always"), guard.exemptPackagesAt(friday))
        assertTrue(guard.isExemptFromTotal("com.always", friday))
        assertFalse("规则今天不生效，豁免也不生效", guard.isExemptFromTotal("com.monday", friday))
        assertFalse("规则被关掉，豁免一并失效", guard.isExemptFromTotal("com.off", friday))
        assertFalse(guard.isExemptFromTotal("com.plain", friday))
        assertFalse("没有规则的应用当然不算豁免", guard.isExemptFromTotal("com.unknown", friday))

        // 周一：只在周一生效的那条也进来了
        assertEquals(
            setOf("com.always", "com.monday"),
            guard.exemptPackagesAt(at(2026, 9, 14, 12, 0)),
        )
    }
}
