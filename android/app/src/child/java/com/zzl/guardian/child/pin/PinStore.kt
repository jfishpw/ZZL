package com.zzl.guardian.child.pin

import com.zzl.guardian.data.pin.PinThrottle
import com.zzl.guardian.data.pin.PinCrypto
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zzl.guardian.data.api.PinLevelDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pinStore: DataStore<Preferences> by preferencesDataStore(name = "zzl_pins")

/** 一级密码的本地形态：只存哈希、盐与迭代次数，绝不存明文 */
data class StoredPin(
    val level: Int,
    val hash: String,
    val salt: String,
    val iterations: Int,
    val hint: String?,
)

/**
 * 离线密码的本地存储。
 *
 * 三条约束：
 *
 *  1. **只存哈希。** 明文只在设置时短暂存在于内存里，用完即弃。
 *  2. **每次改密码都要换新盐。** 复用旧盐会让"改了密码"在哈希层面毫无变化。
 *  3. **本地与服务端双写。** 本地保证断网可用，云端保证换机可恢复 ——
 *     两者缺一不可，所以写入时两边一起更新。
 *
 * 版本号用于解决冲突：服务端下发的密码备份带 version，本地也记一份。
 * 对账时只有服务端版本**更高**才覆盖本地 —— 否则家长刚在设备上改的密码
 * 会被一份陈旧的云端备份冲掉。
 */
@Singleton
class PinStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        fun hash(level: Int) = stringPreferencesKey("pin_${level}_hash")
        fun salt(level: Int) = stringPreferencesKey("pin_${level}_salt")
        fun iters(level: Int) = intPreferencesKey("pin_${level}_iters")
        fun hint(level: Int) = stringPreferencesKey("pin_${level}_hint")

        val levelCount = intPreferencesKey("pin_level_count")
        val version = intPreferencesKey("pin_version")

        /* 限流状态：记下"最近一次失败时间 + 累计次数"即可推导出是否处于锁定中，
           不需要持久化解锁时间点，也就不必维护定时器 */
        fun failedCount(level: Int) = intPreferencesKey("pin_${level}_failed")
        fun lastFailedAt(level: Int) = longPreferencesKey("pin_${level}_last_failed")
        fun lockedUntil(level: Int) = longPreferencesKey("pin_${level}_locked_until")
    }

    suspend fun levelCount(): Int = context.pinStore.data.first()[Keys.levelCount] ?: DEFAULT_LEVELS

    suspend fun version(): Int = context.pinStore.data.first()[Keys.version] ?: 0

    suspend fun stored(level: Int): StoredPin? {
        val prefs = context.pinStore.data.first()
        val hash = prefs[Keys.hash(level)] ?: return null
        val salt = prefs[Keys.salt(level)] ?: return null
        val iters = prefs[Keys.iters(level)] ?: PinCrypto.DEFAULT_ITERATIONS
        return StoredPin(level, hash, salt, iters, prefs[Keys.hint(level)])
    }

    /** 所有已设置的级别，按级别升序 */
    suspend fun allStored(): List<StoredPin> =
        (1..3).mapNotNull { stored(it) }.sortedBy { it.level }

    suspend fun hasAny(): Boolean = allStored().isNotEmpty()

    /**
     * 设置或清除某一级。
     *
     * @param pin null 表示清除该级
     */
    suspend fun save(level: Int, pin: String?, hint: String?, levelCountValue: Int? = null) {
        val entry = pin?.let {
            // 每次都用新盐：复用旧盐会让"改了密码"在哈希层面毫无变化
            val salt = PinCrypto.newSalt()
            StoredPin(
                level = level,
                hash = PinCrypto.hash(it, salt),
                salt = salt,
                iterations = PinCrypto.DEFAULT_ITERATIONS,
                hint = hint,
            )
        }

        context.pinStore.edit { prefs ->
            if (entry == null) {
                prefs.remove(Keys.hash(level))
                prefs.remove(Keys.salt(level))
                prefs.remove(Keys.iters(level))
                prefs.remove(Keys.hint(level))
            } else {
                prefs[Keys.hash(level)] = entry.hash
                prefs[Keys.salt(level)] = entry.salt
                prefs[Keys.iters(level)] = entry.iterations
                if (entry.hint.isNullOrBlank()) {
                    prefs.remove(Keys.hint(level))
                } else {
                    prefs[Keys.hint(level)] = entry.hint
                }
            }
            levelCountValue?.let { prefs[Keys.levelCount] = it }
        }
    }

    /**
     * 用服务端下发的备份覆盖本地。
     *
     * 只在**服务端版本更高**时应用。这个判断不可省略：
     * 家长刚在设备上改了密码（本地 version 已前进），
     * 一份陈旧的云端备份会把新密码冲掉，家长下次就进不去了。
     */
    suspend fun applyRemote(remote: List<PinLevelDto>, remoteVersion: Int, levelCountValue: Int) {
        if (remoteVersion <= version()) return

        context.pinStore.edit { prefs ->
            for (level in 1..3) {
                val dto = remote.firstOrNull { it.level == level }
                if (dto == null || dto.hash.isNullOrBlank() || dto.salt.isNullOrBlank()) {
                    prefs.remove(Keys.hash(level))
                    prefs.remove(Keys.salt(level))
                    prefs.remove(Keys.iters(level))
                    prefs.remove(Keys.hint(level))
                    continue
                }
                prefs[Keys.hash(level)] = dto.hash
                prefs[Keys.salt(level)] = dto.salt
                prefs[Keys.iters(level)] = dto.iterations ?: PinCrypto.DEFAULT_ITERATIONS
                if (dto.hint.isNullOrBlank()) {
                    prefs.remove(Keys.hint(level))
                } else {
                    prefs[Keys.hint(level)] = dto.hint
                }
            }
            prefs[Keys.levelCount] = levelCountValue
            prefs[Keys.version] = remoteVersion
        }
    }

    /** 本地改动后推进版本号，让对账时能判断"本地比云端新" */
    suspend fun bumpVersion() {
        val next = version() + 1
        context.pinStore.edit { it[Keys.version] = next }
    }

    /* ---------------- 限流状态 ---------------- */

    suspend fun throttleState(level: Int, at: Long = System.currentTimeMillis()): PinThrottle.State {
        val prefs = context.pinStore.data.first()
        return PinThrottle.State(
            failedCount = prefs[Keys.failedCount(level)] ?: 0,
            lastFailedAt = prefs[Keys.lastFailedAt(level)] ?: 0,
            lockedUntil = prefs[Keys.lockedUntil(level)] ?: 0,
        )
    }

    suspend fun recordFailure(level: Int, at: Long = System.currentTimeMillis()): PinThrottle.State {
        val next = PinThrottle.onFailure(throttleState(level, at), at)
        context.pinStore.edit { prefs ->
            prefs[Keys.failedCount(level)] = next.failedCount
            prefs[Keys.lastFailedAt(level)] = next.lastFailedAt
            prefs[Keys.lockedUntil(level)] = next.lockedUntil
        }
        return next
    }

    suspend fun recordSuccess(level: Int) {
        context.pinStore.edit { prefs ->
            prefs.remove(Keys.failedCount(level))
            prefs.remove(Keys.lastFailedAt(level))
            prefs.remove(Keys.lockedUntil(level))
        }
    }

    /** 校验密码；同时负责限流判定与计数更新 */
    suspend fun verify(level: Int, pin: String, at: Long = System.currentTimeMillis()): PinVerifyResult {
        val state = throttleState(level, at)
        if (state.isLocked(at)) {
            return PinVerifyResult.Locked(state.remainingLockMs(at))
        }

        val stored = stored(level) ?: return PinVerifyResult.NotSet

        val ok = PinCrypto.verify(pin, stored.hash, stored.salt, stored.iterations)
        if (ok) {
            recordSuccess(level)
            return PinVerifyResult.Ok
        }

        val after = recordFailure(level, at)
        return if (after.isLocked(at)) {
            PinVerifyResult.Locked(after.remainingLockMs(at))
        } else {
            PinVerifyResult.Wrong(after.attemptsLeft(at))
        }
    }

    /** 退出管控时用：清掉全部密码（孩子不再受管控，留着密码没有意义） */
    suspend fun clearAll() {
        context.pinStore.edit { prefs ->
            for (level in 1..3) {
                prefs.remove(Keys.hash(level))
                prefs.remove(Keys.salt(level))
                prefs.remove(Keys.iters(level))
                prefs.remove(Keys.hint(level))
                prefs.remove(Keys.failedCount(level))
                prefs.remove(Keys.lastFailedAt(level))
                prefs.remove(Keys.lockedUntil(level))
            }
            prefs.remove(Keys.version)
        }
    }

    private companion object {
        const val DEFAULT_LEVELS = 3
    }
}

sealed interface PinVerifyResult {
    /** 验证通过 */
    data object Ok : PinVerifyResult

    /** 该级别还没设置密码 */
    data object NotSet : PinVerifyResult

    /** 密码错误，还剩几次机会 */
    data class Wrong(val attemptsLeft: Int) : PinVerifyResult

    /** 已锁定，剩余毫秒 */
    data class Locked(val remainingMs: Long) : PinVerifyResult
}
