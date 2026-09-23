package com.zzl.guardian.child.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        PolicyEntity::class,
        PolicyListEntity::class,
        AppRuleEntity::class,
        SessionEntity::class,
        BlockLogEntity::class,
        GrantEntity::class,
        DeviceStateEntity::class,
        CommandLogEntity::class,
        PinAttemptEntity::class,
        AuditEventEntity::class,
        AppLimitBaselineEntity::class,
    ],
    // v7：app_limit_baseline 新增（逐应用限额的生效基线，实现「设 N 分钟 = 从现在起还能用 N 分钟」）。
    // 沿用破坏式迁移：本地库存的都是可从服务端重建的缓存与日志，
    // 为它维护迁移脚本的收益远低于脚本写错导致崩溃的风险。
    version = 7,
    exportSchema = false,
)
abstract class GuardDatabase : RoomDatabase() {
    abstract fun policyDao(): PolicyDao
    abstract fun policyListDao(): PolicyListDao
    abstract fun appRuleDao(): AppRuleDao
    abstract fun sessionDao(): SessionDao
    abstract fun blockLogDao(): BlockLogDao
    abstract fun grantDao(): GrantDao
    abstract fun deviceStateDao(): DeviceStateDao
    abstract fun commandLogDao(): CommandLogDao
    abstract fun pinAttemptDao(): PinAttemptDao
    abstract fun auditEventDao(): AuditEventDao
    abstract fun appLimitBaselineDao(): AppLimitBaselineDao
}

/**
 * 本地库只存「可重建的缓存与日志」，丢了也不影响正确性
 * （策略与授权会从服务端重新拉取），因此采用破坏式迁移，避免为缓存数据维护迁移脚本。
 *
 * 注意：这一定性只在**服务端仍保有真相**时成立。设备状态（锁定）与授权
 * 都属于服务端真相，所以破坏式迁移最多让管控短暂少几条例外，
 * 下一次对账即恢复 —— 不会出现"永久绕过"。
 */
@Module
@InstallIn(SingletonComponent::class)
object GuardDatabaseModule {

    @Provides
    @Singleton
    fun provideGuardDatabase(@ApplicationContext context: Context): GuardDatabase =
        Room.databaseBuilder(context, GuardDatabase::class.java, "zzl_guard.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun providePolicyDao(database: GuardDatabase): PolicyDao = database.policyDao()

    @Provides
    fun providePolicyListDao(database: GuardDatabase): PolicyListDao = database.policyListDao()

    @Provides
    fun provideAppRuleDao(database: GuardDatabase): AppRuleDao = database.appRuleDao()

    @Provides
    fun provideSessionDao(database: GuardDatabase): SessionDao = database.sessionDao()

    @Provides
    fun provideBlockLogDao(database: GuardDatabase): BlockLogDao = database.blockLogDao()

    @Provides
    fun provideGrantDao(database: GuardDatabase): GrantDao = database.grantDao()

    @Provides
    fun provideDeviceStateDao(database: GuardDatabase): DeviceStateDao = database.deviceStateDao()

    @Provides
    fun provideCommandLogDao(database: GuardDatabase): CommandLogDao = database.commandLogDao()

    @Provides
    fun providePinAttemptDao(database: GuardDatabase): PinAttemptDao = database.pinAttemptDao()

    @Provides
    fun provideAuditEventDao(database: GuardDatabase): AuditEventDao = database.auditEventDao()

    @Provides
    fun provideAppLimitBaselineDao(database: GuardDatabase): AppLimitBaselineDao =
        database.appLimitBaselineDao()
}
