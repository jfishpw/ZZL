package com.zzl.guardian.child.di

import android.content.Context
import com.zzl.guardian.child.data.DeviceStateRepository
import com.zzl.guardian.child.data.InstalledAppsReporter
import com.zzl.guardian.child.data.PolicyRepository
import com.zzl.guardian.child.data.UsageRepository
import com.zzl.guardian.child.engine.CommandRunner
import com.zzl.guardian.child.engine.GuardEngine
import com.zzl.guardian.child.icon.IconController
import com.zzl.guardian.child.keepalive.KeepaliveStore
import com.zzl.guardian.child.pin.PinStore
import com.zzl.guardian.child.screenshot.ScreenshotCapturer
import com.zzl.guardian.data.SettingsStore
import com.zzl.guardian.data.WsClient
import com.zzl.guardian.data.api.ApiService
import com.zzl.guardian.di.ServerConfigHolder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json

/**
 * 无障碍服务与前台服务由系统实例化，无法使用 `@AndroidEntryPoint` 注入，
 * 因此通过 EntryPoint 从应用级组件图里取依赖。这是 Hilt 官方推荐的做法。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface GuardEntryPoint {
    fun guardEngine(): GuardEngine
    fun commandRunner(): CommandRunner
    fun policyRepository(): PolicyRepository
    fun deviceStateRepository(): DeviceStateRepository
    fun usageRepository(): UsageRepository
    fun installedAppsReporter(): InstalledAppsReporter
    fun pinStore(): PinStore
    fun keepaliveStore(): KeepaliveStore
    fun screenshotCapturer(): ScreenshotCapturer
    fun iconController(): IconController
    fun settingsStore(): SettingsStore
    fun wsClient(): WsClient
    fun apiService(): ApiService
    fun serverConfigHolder(): ServerConfigHolder
    fun json(): Json
}

fun guardGraph(context: Context): GuardEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, GuardEntryPoint::class.java)
