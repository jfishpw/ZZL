package com.zzl.guardian.child.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
/** 策略的服务端副本。被控端必须能离线执行管控，所以策略要落本地。 */
@Entity(tableName = "policy_cache")
data class PolicyEntity(
    @PrimaryKey val deviceId: Long,
    val weekdayTotalMin: Int,
    val weekendTotalMin: Int,
    val resetHour: Int,
    val listMode: String,
    val allowTimeRequest: Boolean,
    val enabled: Boolean,
    val version: Int,
    val updatedAt: Long,
    val syncedAt: Long,
)

/**
 * 一次应用使用会话。
 *
 * 采用「先开行、再更新时长、最后收尾」的写法：
 *   - 打开应用：插入 endTs = null 的行
 *   - 每 15 秒：刷新 durationMs（进程意外被杀也最多丢 15 秒）
 *   - 切走应用 / 熄屏：写入 endTs 与最终 durationMs
 *   - 应用启动时：把遗留的未收尾行按已记录的 durationMs 补齐收尾
 */
@Entity(
    tableName = "usage_sessions",
    indices = [Index("dayKey"), Index("packageName"), Index("uploaded")],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 上报去重键，服务端靠它做幂等 */
    val clientKey: String,
    val packageName: String,
    val startTs: Long,
    val endTs: Long? = null,
    val durationMs: Long = 0,
    val dayKey: String,
    val uploaded: Boolean = false,
)

/** 拦截记录，家长侧用于了解"孩子何时因何原因被拦下" */
@Entity(tableName = "block_logs", indices = [Index("ts"), Index("uploaded")])
data class BlockLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /**
     * 上报去重键。必须显式生成而不是让服务端自增 ——
     * 因为「上报成功但响应丢失」时被控端会重传同一批数据，
     * 服务端只能靠这个键认出它们是同一条记录。
     */
    val clientKey: String,
    val packageName: String,
    /** total_exhausted | app_exhausted | out_of_window | blacklist | not_in_whitelist | lock */
    val reason: String,
    val ts: Long,
    val uploaded: Boolean = false,
)

/** 临时授权的范围取值，与服务端 grants.scope 一一对应 */
object GrantScope {
    /** 加时：只增加当日总时长上限 */
    const val TOTAL_ADD = "total_add"

    /** 单应用放行：不受黑白名单与逐应用规则限制，但仍受当日总时长约束 */
    const val APP_ALLOW = "app_allow"

    /** 临时总解封：有效期内完全关闭管控 */
    const val UNLOCK = "unlock"
}

/**
 * 一条临时授权（服务端 grants 的本地副本）。
 *
 * 之所以要落本地：授权决定「现在能不能用」，必须断网也生效 ——
 * 否则孩子只要断网，家长的放行就没了，或者反过来说断网就等于绕过管控。
 */
@Entity(tableName = "grants", indices = [Index("deviceId"), Index("expireAt")])
data class GrantEntity(
    @PrimaryKey val grantId: Long,
    val deviceId: Long,
    val scope: String,
    val packageName: String?,
    val appLabel: String?,
    val extraMinutes: Int?,
    /** 加时绑定的「额度日」，跨过归日点后不再叠加 */
    val dayKey: String?,
    val expireAt: Long?,
    val source: String,
    val createdAt: Long,
)

/**
 * 设备级状态。
 *
 * locked 是家长「立即锁定」的持久状态 —— 它必须能在进程被杀、设备重启后恢复，
 * 否则孩子重启一次平板就解锁了。
 */
@Entity(tableName = "device_state")
data class DeviceStateEntity(
    @PrimaryKey val deviceId: Long,
    val locked: Boolean,
    /** 服务端的状态版本号，用于识别乱序到达的旧状态 */
    val stateVersion: Int,
    val syncedAt: Long,
    /**
     * 家长在本机用离线密码加的时长（毫秒），以及它所属的额度日。
     *
     * 与云端授权分开存：云端授权会被对账整份覆盖，
     * 混在一起的话一次对账就会把家长刚在设备上加的时间冲掉。
     * 带上 dayKey 是为了跨过归日点后自动失效 —— 昨天的加时不能留到今天。
     */
    val localExtraMs: Long = 0,
    val localExtraDayKey: String? = null,
)

/**
 * 已执行指令台账。
 *
 * 指令可能被重复投递（长连接一次 + 补发拉取一次），执行动作必须幂等：
 * 「立即锁定」重复执行无害，但「加时」重复执行就会把额度叠两次。
 */
@Entity(tableName = "executed_commands", indices = [Index("executedAt")])
data class CommandLogEntity(
    @PrimaryKey val commandId: String,
    val type: String,
    val result: String,
    val executedAt: Long,
)

/**
 * 离线密码尝试记录。
 *
 * 与拦截记录同一套思路：本地先落库，联网后批量上报并按 clientKey 幂等去重。
 * 家长正是靠它知道"孩子试过密码" —— 断网期间发生的尝试也必须留下痕迹。
 *
 * **成功与失败都记**：只记失败的话，"孩子试对了密码"这件事反而是不可见的，
 * 而那恰恰是家长最需要知道的。
 */
@Entity(tableName = "pin_attempts", indices = [Index("ts"), Index("uploaded")])
data class PinAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientKey: String,
    val level: Int,
    val success: Boolean,
    /** overlay（拦截页密码框）；corner / dialer 为旧版隐藏入口的历史数据 */
    val source: String,
    val ts: Long,
    val uploaded: Boolean = false,
)

/**
 * 本地审计事件。
 *
 * 记录"家长应该知道、但只在设备本地发生"的事情：权限被关、密码被试、
 * 设备管理器被取消激活、图标被孩子用暗码唤回……
 *
 * 断网期间先落本地库，联网后批量补传（按 clientKey 幂等去重）。
 * 这类事件的时效性远低于「加时申请」，晚几小时上报完全可以接受；
 * 而**丢一条**却可能让家长错过"孩子正在绕过管控"这一关键信号。
 *
 * 因此取舍很明确：**宁可晚报，不可漏报**。
 */
@Entity(tableName = "audit_events", indices = [Index("ts"), Index("uploaded")])
data class AuditEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientKey: String,
    /** 与服务端 audit.js 的 ACTION_TEXT 键保持一致 */
    val action: String,
    /** JSON 字符串，内容因事件而异 */
    val detail: String?,
    /** info | notice | warn */
    val level: String,
    val ts: Long,
    val uploaded: Boolean = false,
)

/** 黑白名单项（本地副本） */
@Entity(
    tableName = "policy_list_cache",
    primaryKeys = ["deviceId", "packageName"],
    indices = [Index("deviceId")],
)
data class PolicyListEntity(
    val deviceId: Long,
    val packageName: String,
    val appLabel: String?,
)

/** 逐应用规则（本地副本） */
@Entity(
    tableName = "app_rules_cache",
    primaryKeys = ["deviceId", "packageName"],
    indices = [Index("deviceId")],
)
data class AppRuleEntity(
    val deviceId: Long,
    val packageName: String,
    val appLabel: String?,
    /** 0 表示不单独限制 */
    val dailyLimitMin: Int,
    /** JSON 数组，形如 [{"start":"19:00","end":"20:00"}] */
    val timeWindowsJson: String?,
    /** 生效星期位掩码：bit0 = 周一 … bit6 = 周日 */
    val weekdaysMask: Int,
    val enabled: Boolean,
    /**
     * 该应用的用时不计入当日总时长。
     *
     * 只豁免「总时长」这一条：单日上限、允许时段、黑白名单照旧生效。
     * 关掉规则开关（[enabled]）或不在生效星期内时，本项一并失效 ——
     * 与其余规则项保持同一套生效条件，否则家长会看到"规则没生效但豁免还在"。
     */
    val exemptTotal: Boolean = false,
)

/** 一个允许使用的时段。endMin <= startMin 表示跨零点。 */
data class TimeWindow(val startMin: Int, val endMin: Int) {
    val crossesMidnight: Boolean get() = endMin <= startMin

    fun contains(minuteOfDay: Int): Boolean =
        if (crossesMidnight) {
            minuteOfDay >= startMin || minuteOfDay < endMin
        } else {
            minuteOfDay >= startMin && minuteOfDay < endMin
        }

    /**
     * 距时段结束还剩多少分钟（含跨零点：结束时间在次日 [endMin]）。
     * 只在 [contains] 为真的分钟数上调用。
     */
    fun minutesUntilEnd(minuteOfDay: Int): Int =
        if (crossesMidnight && minuteOfDay >= startMin) {
            (24 * 60 - minuteOfDay) + endMin
        } else {
            endMin - minuteOfDay
        }
}

/** 引擎用的规则形态：JSON 已解析、结构已归一，判定时零解析开销 */
data class GuardAppRule(
    val packageName: String,
    val dailyLimitMin: Int,
    val windows: List<TimeWindow>,
    val weekdaysMask: Int,
    val enabled: Boolean,
    /**
     * 用时不计入当日总时长。
     *
     * 只豁免「总时长」这一条：单日上限、允许时段、黑白名单照旧生效 ——
     * 所以它不会变成"无限使用"，家长想限制时长仍可单独设 [dailyLimitMin]。
     */
    val exemptTotal: Boolean = false,
) {
    fun isActiveOn(weekdayBit: Int): Boolean = enabled && (weekdaysMask shr weekdayBit) and 1 == 1

    /**
     * 该应用此刻是否免于当日总时长的管控。
     *
     * 必须同时满足「开关打开」与「规则今天生效」。少了后半条会出现家长看不懂的
     * 中间态：界面上规则是关的（或今天不生效），孩子却依然不受总时长约束。
     */
    fun isExemptTotalOn(weekdayBit: Int): Boolean = exemptTotal && isActiveOn(weekdayBit)
}

/**
 * 引擎持有的完整本地策略副本。
 *
 * 组合成一个不可变对象，是为了让规则判定完全不碰数据库 ——
 * 每次前台切换、每秒巡检都只做纯内存计算。
 */
data class GuardPolicy(
    val policy: PolicyEntity,
    val listMode: String,
    val listedPackages: Set<String>,
    val rules: Map<String, GuardAppRule>,
) {
    /**
     * 该应用此刻是否不计入当日总时长。
     *
     * 规则判定与用量统计共用这一个入口 —— 两处各判一次的话，
     * 迟早会因为漏改其中一处而出现"判定放行、用量却在扣"的错位。
     */
    fun isExemptFromTotal(packageName: String, at: Long): Boolean =
        rules[packageName]?.isExemptTotalOn(DayKeys.weekdayBit(at)) == true

    /**
     * 当日全部不计入总时长的应用。
     *
     * 供引擎统计"今日已用"时把它们的会话整段剔除。只扣在途时长是不够的：
     * 已经收尾的历史会话同样不能计入，否则孩子一整天都在用豁免应用，
     * 额度照样会被扣光。
     */
    fun exemptPackagesAt(at: Long): Set<String> {
        val bit = DayKeys.weekdayBit(at)
        return rules.values
            .filter { it.isExemptTotalOn(bit) }
            .mapTo(mutableSetOf()) { it.packageName }
    }

    companion object {
        const val MODE_BLACKLIST = "blacklist"
        const val MODE_WHITELIST = "whitelist"
    }
}

/**
 * 叠加在策略之上的运行时覆盖项，由「设备状态 + 生效授权」推导而来。
 *
 * 与 [GuardPolicy] 分开的原因：策略是家长配置的静态规则，
 * 覆盖项是随时间失效的动态例外。混在一起会让"这次拦截到底是哪条规则触发的"
 * 变得难以回答 —— 而家长最想知道的就是这个。
 *
 * 做成不可变对象同样是为了让 [com.zzl.guardian.child.engine.RuleJudge]
 * 保持纯函数，从而能被 JVM 单元测试完整覆盖。
 */
data class GuardOverrides(
    /** 家长点了「立即锁定」：压过一切，连管控总开关都不例外 */
    val forcedLocked: Boolean = false,
    /** 临时总解封的有效期上限（毫秒时间戳）；null 表示没有解封授权 */
    val unlockedUntil: Long? = null,
    /** 当日常规额度之外的加时总量（毫秒） */
    val extraTotalMs: Long = 0,
    /** 被单独放行的应用 */
    val allowedApps: Set<String> = emptySet(),
    /**
     * 家长在设备上用离线密码加的时长（毫秒）。
     *
     * 与云端下发的 `extraTotalMs` 分开存放，因为它的生命周期不同：
     * 云端授权会被对账整份覆盖，而本地加时必须活到当天结束 ——
     * 混在一起的话，一次对账就会把家长刚在设备上加的时间冲掉。
     */
    val localExtraMs: Long = 0,
) {
    companion object {
        val NONE = GuardOverrides()

        /**
         * 从本地授权列表推导覆盖项。
         *
         * @param dayKey 当前「额度日」，用于剔除属于其他日期的加时
         * @param localExtraMs 家长在本机用离线密码加的时长（已按额度日校验过）
         */
        fun of(
            locked: Boolean,
            grants: List<GrantEntity>,
            dayKey: String,
            at: Long,
            localExtraMs: Long = 0,
        ): GuardOverrides {
            var extraMs = 0L
            var unlockedUntil: Long? = null
            val allowed = mutableSetOf<String>()

            for (grant in grants) {
                val expire = grant.expireAt
                if (expire != null && expire <= at) continue

                when (grant.scope) {
                    GrantScope.TOTAL_ADD -> {
                        // 加时只在它所属的额度日里叠加。
                        // 少了这一层判断，昨天批准的加时会一直留在今天。
                        if (grant.dayKey == null || grant.dayKey == dayKey) {
                            extraMs += (grant.extraMinutes ?: 0).coerceAtLeast(0) * 60_000L
                        }
                    }

                    GrantScope.APP_ALLOW -> grant.packageName?.let { allowed += it }

                    GrantScope.UNLOCK -> {
                        val until = expire ?: Long.MAX_VALUE
                        if (unlockedUntil == null || until > unlockedUntil) unlockedUntil = until
                    }
                }
            }

            return GuardOverrides(
                forcedLocked = locked,
                unlockedUntil = unlockedUntil,
                extraTotalMs = extraMs + localExtraMs.coerceAtLeast(0),
                allowedApps = allowed,
                localExtraMs = localExtraMs.coerceAtLeast(0),
            )
        }
    }

    /** 该时刻是否处于「临时总解封」有效期内 */
    fun isUnlocked(at: Long): Boolean = unlockedUntil != null && at < unlockedUntil

    /** 当日总时长上限 = 策略额度 + 加时 */
    fun limitMsFor(baseLimitMs: Long): Long = baseLimitMs + extraTotalMs
}

/**
 * 「额度日」与「星期」的计算。
 * 归日点不是固定的 00:00，而是策略里的 resetHour —— 例如设成 4 点，
 * 那么凌晨 2 点的使用量仍算作「昨天」。
 */
object DayKeys {

    fun of(timestamp: Long, resetHour: Int): String {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = timestamp
            add(Calendar.HOUR_OF_DAY, -resetHour.coerceIn(0, 23))
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
    }

    /** 取值日额度时用：判断该时刻属于工作日还是周末 */
    fun isWeekendAt(timestamp: Long): Boolean {
        val dayOfWeek = Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.DAY_OF_WEEK)
        return dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
    }

    /** 当天的第几分钟（0 - 1439），用于时段判定 */
    fun minuteOfDay(timestamp: Long): Int {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    /** 生效星期位掩码的下标：bit0 = 周一 … bit6 = 周日 */
    fun weekdayBit(timestamp: Long): Int {
        val dayOfWeek = Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.DAY_OF_WEEK)
        return when (dayOfWeek) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            else -> 6 // Calendar.SUNDAY
        }
    }

    /** 把 "HH:MM" 解析成当天的第几分钟；非法输入返回 null */
    fun parseHhMm(value: String): Int? {
        val parts = value.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }
}



/**
 * 逐应用「单日上限」的生效基线。
 *
 * 家长把某应用设成「单日上限 2 分钟」时，孩子今天往往**已经**用了远不止 2 分钟 ——
 * 如果直接拿全天已用去比上限，规则一生效应用就被封死，家长会觉得
 * "我设了 2 分钟怎么一打开就没了"。
 *
 * 基线的语义：限额**第一次生效**（或被改成新值）的那一刻，把孩子当天的已有用量
 * 记下来；之后判定一律用「已用 − 基线」。于是：
 *   - 设 2 分钟 = 从现在起还能用 2 分钟；
 *   - 改成 30 分钟 = 从现在起还能用 30 分钟（等价于一次加时）；
 *   - 跨天基线自动归零，新的一天重新按全天用量计；
 *   - 基线落库持久化，孩子强杀应用、重启平板都刷不掉它。
 */
@Entity(tableName = "app_limit_baseline")
data class AppLimitBaselineEntity(
    @PrimaryKey val packageName: String,
    val dayKey: String,
    /** 基线对应的限额值（分钟）：限额被修改时基线要跟着重记 */
    val limitMin: Int,
    val baselineMs: Long,
)
