package com.zzl.guardian.di

import com.zzl.guardian.data.ServerConfig
import com.zzl.guardian.data.SettingsStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务器配置的内存缓存。
 *
 * 为什么需要它：OkHttp 拦截器运行在非挂起上下文，无法直接读 DataStore。
 * 这里用一个 volatile 字段缓存当前配置，拦截器只做同步读取，零阻塞。
 * 任何修改服务器配置的入口都必须经过 [update] / [resetToDefault]，保证缓存与持久层一致。
 */
@Singleton
class ServerConfigHolder @Inject constructor(private val settingsStore: SettingsStore) {

    @Volatile
    private var cached: ServerConfig = ServerConfig.default()

    val current: ServerConfig get() = cached

    /** 进程启动时调用，把持久化的值载入缓存 */
    suspend fun warmUp() {
        cached = settingsStore.serverConfig.first()
    }

    suspend fun update(config: ServerConfig) {
        cached = config
        settingsStore.setServerConfig(config)
    }

    suspend fun resetToDefault() {
        settingsStore.resetServerConfig()
        cached = ServerConfig.default()
    }
}
