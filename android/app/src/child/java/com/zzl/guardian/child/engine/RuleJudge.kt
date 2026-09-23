package com.zzl.guardian.child.engine

import com.zzl.guardian.child.data.DayKeys
import com.zzl.guardian.child.data.GuardOverrides
import com.zzl.guardian.child.data.GuardPolicy
import com.zzl.guardian.child.data.PolicyRules

/**
 * 纯函数形式的规则判定器。
 *
 * 刻意从 [GuardEngine] 里抽出来：它是整个管控逻辑里最需要被反复验证的部分，
 * 而抽成纯函数后就能在 JVM 上直接跑单元测试，不必依赖真机或模拟器。
 * 引擎只负责"什么时候调用它"，不负责"它怎么判断"。
 *
 * 判定顺序（任一命中即拦截），顺序不可随意调整：
 *
 *   —— 覆盖项（家长刚刚做的临时决定，应当立刻压过所有静态规则）——
 *   0. 家长点了「立即锁定」            → lock
 *   1. 处于「临时总解封」有效期内      → 放行
 *
 *   —— 策略（家长配置的静态规则）——
 *   2. 管控总开关关闭                  → 放行
 *   3. 黑名单模式且应用在名单内        → blacklist
 *   4. 白名单模式且应用不在名单内      → not_in_whitelist
 *   5. 当日总时长已耗尽                → total_exhausted
 *   6. 该应用单日时长已超额            → app_exhausted
 *   7. 当前不在该应用允许时段内        → out_of_window
 *
 * 两个容易做错、因此单独说明的顺序决定：
 *
 *  - **黑白名单排在总时长之前**：因为"这个应用根本不许用"应当压过"今天还有额度"。
 *  - **「立即锁定」排在管控总开关之前**：家长按下锁定时要的是立刻生效，
 *    此时"管控已暂停"不应成为例外。
 *
 * 另外，「单应用放行」（app_allow 授权）只跳过第 3、4、6、7 条 ——
 * 它让这个应用不受名单与逐应用规则约束，但**仍然受当日总时长约束**。
 * 否则家长批一个"放行微信"，等于给孩子开了无限额度，显然不是他的本意。
 *
 * 与它正好互补的是逐应用规则里的「用时不计入当日总时长」（exemptTotal）：
 *
 * | 机制 | 第 3/4 条·名单 | 第 5 条·总时长 | 第 6/7 条·本应用上限与时段 |
 * |---|---|---|---|
 * | 单应用放行（临时） | 跳过 | **受约束** | 跳过 |
 * | 不计入总时长（常设） | 受约束 | **跳过** | 受约束 |
 *
 * 两者各只放宽一部分：一个管"现在能不能用"，一个管"用了算不算数"，
 * 都不等于把管控整个关掉 —— 家长要的正是这种"精确开口子"。
 */
object RuleJudge {

    /**
     * @param totalUsedMs 当日已用总时长（含进行中的会话）
     * @param appUsedMs   该应用当日已用时长（含进行中的会话）
     * @return 命中原因；null 表示放行
     */
    fun decide(
        policy: GuardPolicy,
        overrides: GuardOverrides,
        packageName: String,
        at: Long,
        totalUsedMs: Long,
        appUsedMs: Long,
    ): String? {
        // 0. 立即锁定：压过一切，连管控总开关都不例外
        if (overrides.forcedLocked) return BlockReason.LOCKED

        // 1. 临时总解封：有效期内完全关闭管控
        if (overrides.isUnlocked(at)) return null

        // 2. 管控总开关
        if (!policy.policy.enabled) return null

        // 该应用是否被单独放行（跳过名单与逐应用规则，但仍受总时长约束）
        val appAllowed = packageName in overrides.allowedApps

        // 3 / 4. 黑白名单
        if (!appAllowed) {
            when (policy.listMode) {
                GuardPolicy.MODE_BLACKLIST ->
                    if (packageName in policy.listedPackages) return BlockReason.BLACKLIST

                GuardPolicy.MODE_WHITELIST ->
                    if (packageName !in policy.listedPackages) return BlockReason.NOT_IN_WHITELIST
            }
        }

        // 该应用的规则提前取出来：第 5 条要用到豁免开关，第 6/7 条要用到上限与时段。
        // 提前取是为了让"这条规则今天到底生不生效"只判一次，避免两处判断慢慢走偏。
        val weekdayBit = DayKeys.weekdayBit(at)
        val rule = policy.rules[packageName]
        val ruleActiveToday = rule != null && rule.isActiveOn(weekdayBit)
        val exemptFromTotal = rule != null && rule.isExemptTotalOn(weekdayBit)

        // 5. 当日总时长（额度 = 策略额度 + 加时授权）
        //
        // 标了「不计入总时长」的应用跳过这一条。它有两层含义，缺一不可：
        //   - 引擎统计「今日已用」时本就把它的时长剔除（见 GuardEngine.todayTotal）；
        //   - 这里不能再拿那个总数去拦它，否则孩子把额度用光后连学习应用都打不开，
        //     而这恰恰是该开关要解决的问题。
        // 注意豁免的**只有**这一条：黑白名单（第 3/4 条）与它自己的上限、时段
        // （第 6/7 条）照旧生效，所以它不会退化成"无限使用"。
        if (!exemptFromTotal) {
            val limitMs = overrides.limitMsFor(PolicyRules.dailyLimitMs(policy.policy, at))
            if (totalUsedMs >= limitMs) return BlockReason.TOTAL_EXHAUSTED
        }

        // 6 / 7. 逐应用规则，只在配置的生效星期内起作用
        if (appAllowed) return null
        if (rule == null || !ruleActiveToday) return null

        if (rule.dailyLimitMin > 0 && appUsedMs >= rule.dailyLimitMin * 60_000L) {
            return BlockReason.APP_EXHAUSTED
        }

        if (rule.windows.isNotEmpty() &&
            rule.windows.none { it.contains(DayKeys.minuteOfDay(at)) }
        ) {
            return BlockReason.OUT_OF_WINDOW
        }

        return null
    }

    /** 当日总时长上限（含加时），供界面与通知展示 */
    fun limitMs(policy: GuardPolicy, overrides: GuardOverrides, at: Long): Long =
        overrides.limitMsFor(PolicyRules.dailyLimitMs(policy.policy, at))

    /**
     * 名单类「确定必拦」的快速预判 —— 第 3/4 条的独立入口。
     *
     * 与 [decide] 的判定顺序保持同源（锁定 → 总解封 → 总开关 → 单应用放行 → 名单），
     * 只做不依赖任何用量的名单检查，因此可以零成本预判。
     * 引擎用它为黑名单/白名单外的应用打开提供零等待拦截（快速通道）；
     * 总时长、单应用上限、时段等依赖用量的判定不在此列，仍走常规核实路径。
     *
     * @return 命中的拦截原因（blacklist / not_in_whitelist）；null 表示无法确定必拦
     */
    fun listedBlockReason(
        policy: GuardPolicy,
        overrides: GuardOverrides,
        packageName: String,
        at: Long,
    ): String? {
        // 锁定态遮罩常驻，无需快速通道
        if (overrides.forcedLocked) return null
        // 临时总解封：有效期内名单不生效
        if (overrides.isUnlocked(at)) return null
        if (!policy.policy.enabled) return null
        // 单应用放行跳过名单（与第 3/4 条的 appAllowed 语义一致）
        if (packageName in overrides.allowedApps) return null
        return when (policy.listMode) {
            GuardPolicy.MODE_BLACKLIST ->
                if (packageName in policy.listedPackages) BlockReason.BLACKLIST else null

            GuardPolicy.MODE_WHITELIST ->
                if (packageName !in policy.listedPackages) BlockReason.NOT_IN_WHITELIST else null

            else -> null
        }
    }
}
