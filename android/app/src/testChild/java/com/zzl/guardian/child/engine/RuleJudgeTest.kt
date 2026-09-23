package com.zzl.guardian.child.engine

import com.zzl.guardian.child.data.GuardAppRule
import com.zzl.guardian.child.data.GuardOverrides
import com.zzl.guardian.child.data.GuardPolicy
import com.zzl.guardian.child.data.PolicyEntity
import com.zzl.guardian.child.data.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 五条判定的顺序与优先级验证。
 *
 * 这是全项目最值得反复跑的一组测试：判定顺序一旦被改动，
 * 真机上表现为"该拦的没拦"或"黑名单应用还能用"，而这类问题极难复现。
 */
class RuleJudgeTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun pinTimeZone() {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        }
    }

    private val fridayNoon: Long = at(2026, 9, 18, 12, 0)
    private val saturdayNoon: Long = at(2026, 9, 19, 12, 0)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, 0)
        return calendar.timeInMillis
    }

    private fun policy(
        weekdayTotalMin: Int = 60,
        weekendTotalMin: Int = 120,
        resetHour: Int = 0,
        listMode: String = GuardPolicy.MODE_BLACKLIST,
        enabled: Boolean = true,
        listedPackages: Set<String> = emptySet(),
        rules: Map<String, GuardAppRule> = emptyMap(),
    ) = GuardPolicy(
        policy = PolicyEntity(
            deviceId = 1L,
            weekdayTotalMin = weekdayTotalMin,
            weekendTotalMin = weekendTotalMin,
            resetHour = resetHour,
            listMode = listMode,
            allowTimeRequest = true,
            enabled = enabled,
            version = 1,
            updatedAt = 0L,
            syncedAt = 0L,
        ),
        listMode = listMode,
        listedPackages = listedPackages,
        rules = rules,
    )

    private fun rule(
        packageName: String,
        dailyLimitMin: Int = 0,
        windows: List<TimeWindow> = emptyList(),
        weekdaysMask: Int = 0b1111111,
        enabled: Boolean = true,
        exemptTotal: Boolean = false,
    ) = GuardAppRule(packageName, dailyLimitMin, windows, weekdaysMask, enabled, exemptTotal)

    private fun decide(
        policy: GuardPolicy,
        packageName: String = "com.demo.app",
        at: Long = fridayNoon,
        totalUsedMs: Long = 0,
        appUsedMs: Long = 0,
        overrides: GuardOverrides = GuardOverrides.NONE,
    ) = RuleJudge.decide(policy, overrides, packageName, at, totalUsedMs, appUsedMs)

    /* ==================== 基础放行 ==================== */

    @Test
    fun allowsEverything_whenEnforcementDisabled() {
        val target = policy(
            enabled = false,
            weekdayTotalMin = 0,
            listedPackages = setOf("com.demo.app"),
            rules = mapOf("com.demo.app" to rule("com.demo.app", dailyLimitMin = 1)),
        )
        assertNull("关掉管控后一切放行", decide(target, totalUsedMs = 999_999_999))
    }

    @Test
    fun allows_whenNothingMatches() {
        assertNull(decide(policy()))
    }

    /* ==================== 1 / 2 黑白名单 ==================== */

    @Test
    fun blacklist_blocksListedApp() {
        val target = policy(listedPackages = setOf("com.bad.app"))
        assertEquals(BlockReason.BLACKLIST, decide(target, packageName = "com.bad.app"))
        assertNull("不在黑名单里的应用应放行", decide(target, packageName = "com.fine.app"))
    }

    @Test
    fun whitelist_blocksUnlistedApp() {
        val target = policy(
            listMode = GuardPolicy.MODE_WHITELIST,
            listedPackages = setOf("com.allowed.app"),
        )
        assertEquals(BlockReason.NOT_IN_WHITELIST, decide(target, packageName = "com.other.app"))
        assertNull("白名单内的应用应放行", decide(target, packageName = "com.allowed.app"))
    }

    @Test
    fun whitelist_emptyBlocksEverything() {
        val target = policy(listMode = GuardPolicy.MODE_WHITELIST, listedPackages = emptySet())
        assertEquals(BlockReason.NOT_IN_WHITELIST, decide(target, packageName = "com.any.app"))
    }

    @Test
    fun blacklistListTakesPrecedenceOverTotalBudget() {
        // 关键优先级：名单命中必须压过"今天还有额度"
        val target = policy(
            weekdayTotalMin = 60,
            listedPackages = setOf("com.bad.app"),
        )
        assertEquals(
            "名单判定必须排在总时长之前",
            BlockReason.BLACKLIST,
            decide(target, packageName = "com.bad.app", totalUsedMs = 0),
        )
    }

    /* ==================== 3 当日总时长 ==================== */

    @Test
    fun blocksWhenTotalBudgetExhausted() {
        val target = policy(weekdayTotalMin = 60) // = 3,600,000 ms
        val limit = 60 * 60_000L

        assertNull("差 1 毫秒未耗尽，应放行", decide(target, totalUsedMs = limit - 1))
        assertEquals(
            BlockReason.TOTAL_EXHAUSTED,
            decide(target, totalUsedMs = limit),
        )
        assertEquals(
            BlockReason.TOTAL_EXHAUSTED,
            decide(target, totalUsedMs = limit + 60_000),
        )
    }

    @Test
    fun zeroBudgetBlocksImmediately() {
        val target = policy(weekdayTotalMin = 0)
        assertEquals(BlockReason.TOTAL_EXHAUSTED, decide(target, totalUsedMs = 0))
    }

    @Test
    fun weekendUsesWeekendBudget() {
        val target = policy(weekdayTotalMin = 10, weekendTotalMin = 120)
        val weekdayLimit = 10 * 60_000L

        // 同样的已用时长：工作日已耗尽，周末还很宽裕
        assertEquals(
            BlockReason.TOTAL_EXHAUSTED,
            decide(target, at = fridayNoon, totalUsedMs = weekdayLimit),
        )
        assertNull(
            decide(target, at = saturdayNoon, totalUsedMs = weekdayLimit),
        )
    }

    /* ==================== 4 单应用单日上限 ==================== */

    @Test
    fun blocksWhenAppBudgetExhausted() {
        val target = policy(
            weekdayTotalMin = 600, // 总时长充足，隔离出单应用判定的影响
            rules = mapOf("com.demo.app" to rule("com.demo.app", dailyLimitMin = 40)),
        )
        val appLimit = 40 * 60_000L

        assertNull(decide(target, appUsedMs = appLimit - 1))
        assertEquals(BlockReason.APP_EXHAUSTED, decide(target, appUsedMs = appLimit))
    }

    @Test
    fun zeroAppLimitMeansUnlimited() {
        val target = policy(
            weekdayTotalMin = 600,
            rules = mapOf("com.demo.app" to rule("com.demo.app", dailyLimitMin = 0)),
        )
        assertNull("单日上限为 0 表示不单独限制", decide(target, appUsedMs = 999 * 60_000L))
    }

    @Test
    fun appRuleOnlyAffectsItsOwnPackage() {
        val target = policy(
            weekdayTotalMin = 600,
            rules = mapOf("com.demo.app" to rule("com.demo.app", dailyLimitMin = 1)),
        )
        assertEquals(
            BlockReason.APP_EXHAUSTED,
            decide(target, packageName = "com.demo.app", appUsedMs = 60_000),
        )
        assertNull(
            "别的应用不该受这条规则影响",
            decide(target, packageName = "com.other.app", appUsedMs = 999 * 60_000L),
        )
    }

    /* ==================== 5 允许时段 ==================== */

    @Test
    fun blocksOutsideAllowedWindows() {
        val target = policy(
            weekdayTotalMin = 600,
            rules = mapOf(
                "com.demo.app" to rule(
                    "com.demo.app",
                    windows = listOf(TimeWindow(19 * 60, 20 * 60)),
                ),
            ),
        )

        assertNull("19:30 在时段内", decide(target, at = at(2026, 9, 18, 19, 30)))
        assertEquals(
            BlockReason.OUT_OF_WINDOW,
            decide(target, at = at(2026, 9, 18, 12, 0)),
        )
    }

    @Test
    fun emptyWindowsMeanNoTimeRestriction() {
        val target = policy(
            weekdayTotalMin = 600,
            rules = mapOf("com.demo.app" to rule("com.demo.app", windows = emptyList())),
        )
        assertNull(decide(target, at = at(2026, 9, 18, 3, 0)))
    }

    @Test
    fun crossMidnightWindowAllowsEarlyMorning() {
        val target = policy(
            weekdayTotalMin = 600,
            rules = mapOf(
                "com.demo.app" to rule(
                    "com.demo.app",
                    windows = listOf(TimeWindow(22 * 60, 7 * 60)),
                ),
            ),
        )

        assertNull("23:00 应放行", decide(target, at = at(2026, 9, 18, 23, 0)))
        assertNull("凌晨 3:00 应放行", decide(target, at = at(2026, 9, 18, 3, 0)))
        assertEquals(
            BlockReason.OUT_OF_WINDOW,
            decide(target, at = at(2026, 9, 18, 12, 0)),
        )
    }

    @Test
    fun appBudgetTakesPrecedenceOverTimeWindow() {
        // 两个条件同时命中时，先判单日上限
        val target = policy(
            weekdayTotalMin = 600,
            rules = mapOf(
                "com.demo.app" to rule(
                    "com.demo.app",
                    dailyLimitMin = 10,
                    windows = listOf(TimeWindow(0, 1)), // 几乎全天禁止
                ),
            ),
        )
        assertEquals(
            BlockReason.APP_EXHAUSTED,
            decide(target, appUsedMs = 10 * 60_000L),
        )
    }

    /* ==================== 生效星期 ==================== */

    @Test
    fun appRuleSkippedOnInactiveWeekday() {
        // 规则只在周一生效
        val target = policy(
            weekdayTotalMin = 600,
            rules = mapOf(
                "com.demo.app" to rule(
                    "com.demo.app",
                    dailyLimitMin = 1,
                    weekdaysMask = 0b0000001,
                ),
            ),
        )

        // 周五：规则不参与判定，即便已用很久也放行
        assertNull(
            "非生效星期内规则应完全跳过",
            decide(target, at = fridayNoon, appUsedMs = 999 * 60_000L),
        )

        // 周一 2026-09-14：规则生效
        assertEquals(
            BlockReason.APP_EXHAUSTED,
            decide(target, at = at(2026, 9, 14, 12, 0), appUsedMs = 60_000),
        )
    }

    @Test
    fun disabledAppRuleIsSkipped() {
        val target = policy(
            weekdayTotalMin = 600,
            rules = mapOf(
                "com.demo.app" to rule("com.demo.app", dailyLimitMin = 1, enabled = false),
            ),
        )
        assertNull(decide(target, appUsedMs = 999 * 60_000L))
    }

    @Test
    fun totalBudgetStillAppliesWhenAppRuleIsInactive() {
        // 逐应用规则不生效时，总时长仍然兜底 —— 这是最容易被漏掉的一层
        val target = policy(
            weekdayTotalMin = 30,
            rules = mapOf(
                "com.demo.app" to rule(
                    "com.demo.app",
                    dailyLimitMin = 5,
                    weekdaysMask = 0b0000001, // 只在周一
                ),
            ),
        )
        assertEquals(
            BlockReason.TOTAL_EXHAUSTED,
            decide(target, at = fridayNoon, totalUsedMs = 30 * 60_000L),
        )
    }

    /* ==================== 覆盖项：家长立即锁定 ==================== */

    @Test
    fun forcedLockOverridesEverything() {
        // 关键优先级：家长按下「立即锁定」要的是立刻生效，
        // 此时「管控已暂停」不该成为例外
        val disabled = policy(enabled = false, weekdayTotalMin = 600)
        assertEquals(
            BlockReason.LOCKED,
            decide(disabled, overrides = GuardOverrides(forcedLocked = true)),
        )

        val unlocked = GuardOverrides(unlockedUntil = fridayNoon + 60_000)
        assertEquals(
            "锁定必须压过临时解封",
            BlockReason.LOCKED,
            decide(policy(), overrides = unlocked.copy(forcedLocked = true)),
        )
    }

    /* ==================== 覆盖项：临时总解封 ==================== */

    @Test
    fun temporaryUnlockAllowsEverythingWhileValid() {
        val target = policy(
            weekdayTotalMin = 0,
            listedPackages = setOf("com.demo.app"),
        )
        val overrides = GuardOverrides(unlockedUntil = fridayNoon + 30 * 60_000)

        assertNull("解封有效期内应完全放行", decide(target, overrides = overrides))
    }

    @Test
    fun temporaryUnlockStopsAtItsDeadline() {
        val target = policy(weekdayTotalMin = 0, listedPackages = setOf("com.demo.app"))
        val expired = GuardOverrides(unlockedUntil = fridayNoon)

        assertEquals(
            "到期即恢复管控（边界时刻不再算有效）",
            BlockReason.BLACKLIST,
            decide(target, at = fridayNoon, overrides = expired),
        )
    }

    /* ==================== 覆盖项：单应用放行 ==================== */

    @Test
    fun appAllowBypassesListsAndAppRules() {
        val target = policy(
            listMode = GuardPolicy.MODE_WHITELIST,
            listedPackages = setOf("com.allowed.app"),
            weekdayTotalMin = 600,
            rules = mapOf(
                "com.demo.app" to rule(
                    "com.demo.app",
                    dailyLimitMin = 1,
                    windows = listOf(TimeWindow(0, 1)),
                ),
            ),
        )
        val overrides = GuardOverrides(allowedApps = setOf("com.demo.app"))

        assertNull(
            "放行后既不受白名单限制，也不受该应用自己的时长与时段限制",
            decide(target, overrides = overrides, appUsedMs = 999 * 60_000L),
        )
        assertEquals(
            "没被放行的应用照旧被拦",
            BlockReason.NOT_IN_WHITELIST,
            decide(target, packageName = "com.other.app", overrides = overrides),
        )
    }

    @Test
    fun appAllowStillCountsAgainstTotalBudget() {
        // 设计取舍：放行一个应用不等于给孩子无限额度。
        // 否则家长批一个"放行微信"，本意是让它能用，结果变成全天随便玩。
        val target = policy(weekdayTotalMin = 30)
        val overrides = GuardOverrides(allowedApps = setOf("com.demo.app"))

        assertEquals(
            BlockReason.TOTAL_EXHAUSTED,
            decide(target, overrides = overrides, totalUsedMs = 30 * 60_000L),
        )
    }

    /* ==================== 覆盖项：临时加时 ==================== */

    @Test
    fun extraTotalMinutesRaisesTheDailyLimit() {
        val target = policy(weekdayTotalMin = 30) // 30 分钟
        val overrides = GuardOverrides(extraTotalMs = 15 * 60_000L) // 家长再加 15 分钟

        assertNull(
            "加时后 30 分钟不再是上限",
            decide(target, overrides = overrides, totalUsedMs = 30 * 60_000L),
        )
        assertNull(
            "差 1 毫秒到新上限，仍应放行",
            decide(target, overrides = overrides, totalUsedMs = 45 * 60_000L - 1),
        )
        assertEquals(
            BlockReason.TOTAL_EXHAUSTED,
            decide(target, overrides = overrides, totalUsedMs = 45 * 60_000L),
        )
    }

    @Test
    fun extraTotalMinutesDoesNotBypassLists() {
        // 加时只放宽时长，不该顺带把黑名单也放开了
        val target = policy(weekdayTotalMin = 30, listedPackages = setOf("com.demo.app"))
        val overrides = GuardOverrides(extraTotalMs = 60 * 60_000L)

        assertEquals(BlockReason.BLACKLIST, decide(target, overrides = overrides))
    }

    @Test
    fun limitMsIncludesExtraMinutes() {
        val target = policy(weekdayTotalMin = 60)
        val overrides = GuardOverrides(extraTotalMs = 30 * 60_000L)

        assertEquals(
            90 * 60_000L,
            RuleJudge.limitMs(target, overrides, fridayNoon),
        )
    }

    /* ==================== 名单快速预判（零等待拦截通道） ==================== */

    private fun listedBlockReason(
        policy: GuardPolicy,
        packageName: String = "com.bad.app",
        at: Long = fridayNoon,
        overrides: GuardOverrides = GuardOverrides.NONE,
    ) = RuleJudge.listedBlockReason(policy, overrides, packageName, at)

    @Test
    fun listedBlockReason_blacklistHitAndMiss() {
        val target = policy(listedPackages = setOf("com.bad.app"))

        assertEquals(BlockReason.BLACKLIST, listedBlockReason(target))
        assertNull("不在黑名单里不触发快速通道（是否超额交给常规路径）", listedBlockReason(target, packageName = "com.fine.app"))
    }

    @Test
    fun listedBlockReason_whitelistOutside() {
        val target = policy(
            listMode = GuardPolicy.MODE_WHITELIST,
            listedPackages = setOf("com.allowed.app"),
        )

        assertEquals(BlockReason.NOT_IN_WHITELIST, listedBlockReason(target, packageName = "com.other.app"))
        assertNull("白名单内不触发", listedBlockReason(target, packageName = "com.allowed.app"))
    }

    @Test
    fun listedBlockReason_skippedWhenAppAllowed() {
        // 单应用放行跳过名单 —— 与 decide 的第 3/4 条语义一致，
        // 否则家长批了「放行微信」，快速通道还会把它拦下
        val target = policy(listedPackages = setOf("com.bad.app"))
        val overrides = GuardOverrides(allowedApps = setOf("com.bad.app"))

        assertNull(listedBlockReason(target, overrides = overrides))
    }

    @Test
    fun listedBlockReason_skippedWhenUnlockedOrDisabledOrLocked() {
        val target = policy(listedPackages = setOf("com.bad.app"))

        assertNull(
            "临时总解封期间名单不生效",
            listedBlockReason(target, overrides = GuardOverrides(unlockedUntil = fridayNoon + 60_000)),
        )
        assertNull(
            "管控总开关关闭时不预判",
            listedBlockReason(target(policyEnabled = false)),
        )
        assertNull(
            "家长主动锁定时遮罩常驻，无需快速通道",
            listedBlockReason(target, overrides = GuardOverrides(forcedLocked = true)),
        )
    }

    private fun target(policyEnabled: Boolean = true): GuardPolicy =
        policy(enabled = policyEnabled, listedPackages = setOf("com.bad.app"))
}
