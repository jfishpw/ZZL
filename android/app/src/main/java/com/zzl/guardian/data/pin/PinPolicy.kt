package com.zzl.guardian.data.pin

/**
 * 密码强度校验与限流状态机。
 *
 * 全是纯逻辑，因此可以在 JVM 上完整测 —— 这两件事在真机上极难复现：
 * 强度校验要试各种弱口令，限流要等到锁定期结束。
 */

/* ---------------- 密码强度 ---------------- */

enum class PinStrength(val level: Int, val label: String) {
    /** 太弱，直接拒绝 */
    WEAK(0, "太弱"),
    ACCEPTABLE(1, "可用"),
    STRONG(2, "较强"),
}

data class PinValidation(
    val ok: Boolean,
    val strength: PinStrength,
    val reason: String? = null,
    /** 可展示给家长的改进建议；通过时也可能非空（例如"建议加长到 8 位"） */
    val advice: String? = null,
)

object PinPolicy {

    /** 6 位是下限：4 位数字只有 1 万种，配合 10 万次迭代也只需几分钟即可枚举完 */
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 32

    /**
     * 常见的弱口令与模式。
     *
     * 这里刻意不做"密码字典"式的穷举，只挡住最典型的几类 ——
     * 真正的防线是限流，强度校验的作用是避免家长随手设一个 123456。
     */
    private val BANNED_EXACT = setOf(
        "123456", "1234567", "12345678", "123456789", "1234567890",
        "000000", "111111", "222222", "333333", "444444", "555555",
        "666666", "777777", "888888", "999999", "112233", "121212",
        "abcdef", "qwerty", "asdfgh", "zxcvbn", "password", "admin123",
        "654321", "88888888", "66668888", "168168", "520520",
    )

    /** 连续递增/递减的序列 */
    private fun isSequential(pin: String): Boolean {
        if (pin.length < 4) return false
        var ascending = true
        var descending = true
        for (index in 1 until pin.length) {
            val delta = pin[index] - pin[index - 1]
            if (delta != 1) ascending = false
            if (delta != -1) descending = false
        }
        return ascending || descending
    }

    /** 全部字符相同 */
    private fun isRepeated(pin: String): Boolean =
        pin.length > 1 && pin.all { it == pin[0] }

    /** 是否像日期：8 位且能拆成 19xx/20xx + 合法月日 */
    private fun looksLikeDate(pin: String): Boolean {
        if (pin.length != 8) return false
        val year = pin.take(4).toIntOrNull() ?: return false
        if (year !in 1900..2100) return false
        val month = pin.substring(4, 6).toIntOrNull() ?: return false
        val day = pin.substring(6, 8).toIntOrNull() ?: return false
        return month in 1..12 && day in 1..31
    }

    /**
     * 校验单级密码。
     *
     * @param pin        待校验的密码
     * @param others     其他级别的已有密码。**不允许与其他级别相同** ——
     *                   三级用同一个密码等于只有一级，分级形同虚设。
     */
    fun validate(pin: String, others: List<String> = emptyList()): PinValidation {
        val value = pin.trim()

        if (value.length < MIN_LENGTH) {
            return PinValidation(false, PinStrength.WEAK, "至少 $MIN_LENGTH 位")
        }
        if (value.length > MAX_LENGTH) {
            return PinValidation(false, PinStrength.WEAK, "最多 $MAX_LENGTH 位")
        }
        if (value.any { it.isWhitespace() }) {
            return PinValidation(false, PinStrength.WEAK, "不能包含空格")
        }
        if (value.lowercase() in BANNED_EXACT) {
            return PinValidation(false, PinStrength.WEAK, "这是最常见的弱密码，请换一个")
        }
        if (isRepeated(value)) {
            return PinValidation(false, PinStrength.WEAK, "不能是重复的同一个字符")
        }
        if (isSequential(value)) {
            return PinValidation(false, PinStrength.WEAK, "不能是连续递增或递减的数字")
        }
        if (looksLikeDate(value)) {
            return PinValidation(false, PinStrength.WEAK, "不要使用生日等日期")
        }
        if (others.any { it == value }) {
            return PinValidation(false, PinStrength.WEAK, "不能与其他级别的密码相同")
        }

        // 通过基础校验后，给出强度分级与改进建议
        val kinds = buildSet {
            if (value.any { it.isDigit() }) add("digit")
            if (value.any { it.isLetter() }) add("letter")
            if (value.any { !it.isLetterOrDigit() }) add("symbol")
        }.size

        val strength = when {
            value.length >= 10 || (value.length >= 8 && kinds >= 2) -> PinStrength.STRONG
            value.length >= 8 || kinds >= 2 -> PinStrength.ACCEPTABLE
            else -> PinStrength.ACCEPTABLE
        }

        val advice = when {
            value.length < 8 -> "建议加长到 8 位以上，安全性明显提升"
            kinds == 1 -> "加入字母或符号会更强"
            else -> null
        }

        return PinValidation(true, strength, null, advice)
    }
}

/* ---------------- 错误限流 ---------------- */

/**
 * 错误限流状态机。
 *
 * 设计取舍：**锁定期随时间自然解除，不需要持久化"解锁时间点"** ——
 * 只要记下最近一次失败的时间与累计次数，就能推导出当前是否处于锁定中。
 * 这样进程被杀、设备重启都不会让限流失效，也不必维护定时器。
 *
 * 与需求文档一致：5 次锁 10 分钟、10 次锁 1 小时。
 */
object PinThrottle {

    /** 达到该次数后进入第一次锁定 */
    const val FIRST_THRESHOLD = 5
    const val FIRST_LOCK_MS = 10 * 60 * 1000L

    /** 达到该次数后进入长锁定 */
    const val SECOND_THRESHOLD = 10
    const val SECOND_LOCK_MS = 60 * 60 * 1000L

    /** 距上次失败超过该时长，失败计数清零（避免"三天里错 5 次"就被锁） */
    const val RESET_WINDOW_MS = 30 * 60 * 1000L

    data class State(
        val failedCount: Int = 0,
        val lastFailedAt: Long = 0,
        val lockedUntil: Long = 0,
    ) {
        fun isLocked(at: Long): Boolean = at < lockedUntil

        fun remainingLockMs(at: Long): Long = (lockedUntil - at).coerceAtLeast(0)

        /** 还可尝试几次才触发锁定 */
        fun attemptsLeft(at: Long): Int {
            if (isLocked(at)) return 0
            val effective = effectiveCount(at)
            return when {
                effective < FIRST_THRESHOLD -> FIRST_THRESHOLD - effective
                else -> SECOND_THRESHOLD - effective
            }.coerceAtLeast(0)
        }

        /** 把超过重置窗口的陈旧计数视为 0 */
        fun effectiveCount(at: Long): Int =
            if (lastFailedAt > 0 && at - lastFailedAt > RESET_WINDOW_MS) 0 else failedCount
    }

    fun initial(): State = State()

    /**
     * 记录一次失败，返回新状态。
     *
     * 达到阈值时设置 `lockedUntil`，但**不清零计数** ——
     * 清零的话"锁定期结束后再错一次就又被锁 10 分钟"，
     * 而孩子的实际体验应该是"锁定期结束，重新获得 5 次机会"。
     * 这里保留计数，让第 10 次错误直接跳到 1 小时锁定。
     */
    fun onFailure(state: State, at: Long): State {
        val base = if (state.lastFailedAt > 0 && at - state.lastFailedAt > RESET_WINDOW_MS) {
            State()
        } else {
            state
        }

        val count = base.failedCount + 1
        val lockMs = when {
            count >= SECOND_THRESHOLD -> SECOND_LOCK_MS
            count >= FIRST_THRESHOLD -> FIRST_LOCK_MS
            else -> 0L
        }

        return State(
            failedCount = count,
            lastFailedAt = at,
            lockedUntil = if (lockMs > 0) at + lockMs else base.lockedUntil,
        )
    }

    /** 记录一次成功：计数清零 */
    fun onSuccess(): State = State()
}

/* ---------------- 级别能力 ---------------- */

/** 各级密码可执行的操作。与服务端 `LEVEL_CAPABILITIES` 必须保持一致。 */
enum class PinLevel(val level: Int, val displayName: String, val canExitGuard: Boolean) {
    DAILY(1, "日常密码", false),
    ADMIN(2, "管理密码", false),
    SUPER(3, "超级密码", true);

    companion object {
        fun of(level: Int): PinLevel? = entries.firstOrNull { it.level == level }
    }
}
