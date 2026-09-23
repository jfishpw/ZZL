package com.zzl.guardian.child.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
@Dao
interface PolicyDao {

    @Query("SELECT * FROM policy_cache WHERE deviceId = :deviceId LIMIT 1")
    fun observe(deviceId: Long): Flow<PolicyEntity?>

    @Query("SELECT * FROM policy_cache WHERE deviceId = :deviceId LIMIT 1")
    suspend fun get(deviceId: Long): PolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(policy: PolicyEntity)

    @Query("DELETE FROM policy_cache")
    suspend fun clear()
}

@Dao
interface SessionDao {

    @Insert
    suspend fun open(session: SessionEntity): Long

    /** 当前尚未收尾的会话（正常情况下最多一条） */
    @Query("SELECT * FROM usage_sessions WHERE endTs IS NULL ORDER BY startTs DESC LIMIT 1")
    suspend fun currentOpen(): SessionEntity?

    @Query("UPDATE usage_sessions SET durationMs = :durationMs WHERE id = :id")
    suspend fun refreshDuration(id: Long, durationMs: Long)

    @Query("UPDATE usage_sessions SET endTs = :endTs, durationMs = :durationMs WHERE id = :id")
    suspend fun close(id: Long, endTs: Long, durationMs: Long)

    /** 进程被杀等异常场景下的兜底收尾 */
    @Query("UPDATE usage_sessions SET endTs = startTs + durationMs WHERE endTs IS NULL")
    suspend fun closeDangling()

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM usage_sessions WHERE dayKey = :dayKey")
    suspend fun totalForDay(dayKey: String): Long

    /**
     * 当日**计入总时长**的用量：剔除被标记为不计入的应用。
     *
     * 空列表不能走这里 —— SQLite 的 `IN ()` 不是合法语法，
     * 调用方（[UsageRepository.usedTodayMs]）必须先判断集合是否为空。
     */
    @Query(
        "SELECT COALESCE(SUM(durationMs), 0) FROM usage_sessions " +
            "WHERE dayKey = :dayKey AND packageName NOT IN (:excluded)",
    )
    suspend fun totalForDayExcluding(dayKey: String, excluded: List<String>): Long

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM usage_sessions WHERE dayKey = :dayKey AND packageName = :packageName")
    suspend fun totalForApp(dayKey: String, packageName: String): Long

    @Query("SELECT * FROM usage_sessions WHERE dayKey = :dayKey ORDER BY startTs DESC")
    suspend fun sessionsForDay(dayKey: String): List<SessionEntity>

    @Query("SELECT * FROM usage_sessions WHERE uploaded = 0 AND endTs IS NOT NULL ORDER BY startTs ASC LIMIT :limit")
    suspend fun pendingUpload(limit: Int): List<SessionEntity>

    @Query("UPDATE usage_sessions SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    @Query("DELETE FROM usage_sessions WHERE uploaded = 1 AND startTs < :before")
    suspend fun pruneUploaded(before: Long)
}

@Dao
interface PolicyListDao {

    @Query("SELECT * FROM policy_list_cache WHERE deviceId = :deviceId")
    fun observe(deviceId: Long): Flow<List<PolicyListEntity>>

    @Query("DELETE FROM policy_list_cache WHERE deviceId = :deviceId")
    suspend fun clearForDevice(deviceId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PolicyListEntity>)

    @Query("DELETE FROM policy_list_cache")
    suspend fun clearAll()
}

@Dao
interface AppRuleDao {

    @Query("SELECT * FROM app_rules_cache WHERE deviceId = :deviceId")
    fun observe(deviceId: Long): Flow<List<AppRuleEntity>>

    @Query("DELETE FROM app_rules_cache WHERE deviceId = :deviceId")
    suspend fun clearForDevice(deviceId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<AppRuleEntity>)

    @Query("DELETE FROM app_rules_cache")
    suspend fun clearAll()
}

@Dao
interface AppLimitBaselineDao {

    @Query("SELECT * FROM app_limit_baseline WHERE packageName = :packageName LIMIT 1")
    suspend fun get(packageName: String): AppLimitBaselineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(baseline: AppLimitBaselineEntity)

    @Query("DELETE FROM app_limit_baseline WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface BlockLogDao {

    @Insert
    suspend fun insert(log: BlockLogEntity)

    @Query("SELECT * FROM block_logs ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BlockLogEntity>>

    @Query("SELECT * FROM block_logs WHERE uploaded = 0 ORDER BY ts ASC LIMIT :limit")
    suspend fun pendingUpload(limit: Int): List<BlockLogEntity>

    @Query("UPDATE block_logs SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    /** 拦截记录只保留最近一段时间的，避免本地库无限增长 */
    @Query("DELETE FROM block_logs WHERE ts < :before")
    suspend fun pruneOlderThan(before: Long)
}

/* ---------------- 临时授权与设备状态（M4） ---------------- */

@Dao
interface GrantDao {

    @Query("SELECT * FROM grants WHERE deviceId = :deviceId ORDER BY grantId ASC")
    fun observe(deviceId: Long): Flow<List<GrantEntity>>

    @Query("SELECT * FROM grants WHERE deviceId = :deviceId ORDER BY grantId ASC")
    suspend fun getAll(deviceId: Long): List<GrantEntity>

    @Query("SELECT * FROM grants WHERE deviceId = :deviceId AND grantId = :grantId LIMIT 1")
    suspend fun get(deviceId: Long, grantId: Long): GrantEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(grants: List<GrantEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(grant: GrantEntity)

    @Query("DELETE FROM grants WHERE deviceId = :deviceId")
    suspend fun clearForDevice(deviceId: Long)

    @Query("DELETE FROM grants")
    suspend fun clearAll()

    @Query("DELETE FROM grants WHERE deviceId = :deviceId AND grantId = :grantId")
    suspend fun delete(deviceId: Long, grantId: Long)

    /** 清理早已失效的授权，避免本地表无限增长 */
    @Query("DELETE FROM grants WHERE expireAt IS NOT NULL AND expireAt < :before")
    suspend fun pruneExpired(before: Long)
}

@Dao
interface DeviceStateDao {

    @Query("SELECT * FROM device_state WHERE deviceId = :deviceId LIMIT 1")
    fun observe(deviceId: Long): Flow<DeviceStateEntity?>

    @Query("SELECT * FROM device_state WHERE deviceId = :deviceId LIMIT 1")
    suspend fun get(deviceId: Long): DeviceStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: DeviceStateEntity)

    @Query("DELETE FROM device_state")
    suspend fun clear()
}

@Dao
interface CommandLogDao {

    @Query("SELECT COUNT(*) FROM executed_commands WHERE commandId = :commandId")
    suspend fun count(commandId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: CommandLogEntity)

    @Query("SELECT * FROM executed_commands ORDER BY executedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<CommandLogEntity>

    @Query("DELETE FROM executed_commands WHERE executedAt < :before")
    suspend fun pruneOlderThan(before: Long)
}

/* ---------------- 离线密码尝试（M5） ---------------- */

@Dao
interface PinAttemptDao {

    @Insert
    suspend fun insert(attempt: PinAttemptEntity)

    @Query("SELECT * FROM pin_attempts WHERE uploaded = 0 ORDER BY ts ASC LIMIT :limit")
    suspend fun pendingUpload(limit: Int): List<PinAttemptEntity>

    @Query("UPDATE pin_attempts SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM pin_attempts WHERE success = 0")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT * FROM pin_attempts ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PinAttemptEntity>>

    @Query("DELETE FROM pin_attempts WHERE ts < :before")
    suspend fun pruneOlderThan(before: Long)
}

/* ---------------- 本地审计事件（M6） ---------------- */

@Dao
interface AuditEventDao {

    @Insert
    suspend fun insert(event: AuditEventEntity)

    @Query("SELECT * FROM audit_events WHERE uploaded = 0 ORDER BY ts ASC LIMIT :limit")
    suspend fun pendingUpload(limit: Int): List<AuditEventEntity>

    @Query("UPDATE audit_events SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    @Query("SELECT * FROM audit_events ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditEventEntity>>

    @Query("SELECT COUNT(*) FROM audit_events WHERE uploaded = 0")
    fun observePendingCount(): Flow<Int>

    @Query("DELETE FROM audit_events WHERE ts < :before")
    suspend fun pruneOlderThan(before: Long)
}
