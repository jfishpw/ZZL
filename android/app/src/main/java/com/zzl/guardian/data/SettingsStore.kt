package com.zzl.guardian.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zzl_settings")

data class ParentSession(val token: String, val userId: Long, val username: String)

data class ChildSession(val token: String, val deviceId: Long, val deviceName: String)

/**
 * 本地配置与凭据存储。服务器地址的「运行期覆盖」就落在这里。
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val host = stringPreferencesKey("server_host")
        val port = intPreferencesKey("server_port")
        val scheme = stringPreferencesKey("server_scheme")
        val trustSelfSigned = booleanPreferencesKey("server_trust_self_signed")

        val parentToken = stringPreferencesKey("parent_token")
        val parentUserId = longPreferencesKey("parent_user_id")
        val parentUsername = stringPreferencesKey("parent_username")

        val childToken = stringPreferencesKey("child_token")
        val childDeviceId = longPreferencesKey("child_device_id")
        val childDeviceName = stringPreferencesKey("child_device_name")

        /** 设备唯一标识：换服务器、重新配对都保持不变，避免产生僵尸设备记录 */
        val childUuid = stringPreferencesKey("child_uuid")
    }

    /* ---------------- 服务器配置 ---------------- */

    val serverConfig: Flow<ServerConfig> = context.dataStore.data.map { prefs ->
        val defaults = ServerConfig.default()
        ServerConfig(
            host = prefs[Keys.host]?.takeIf { it.isNotBlank() } ?: defaults.host,
            port = prefs[Keys.port]?.takeIf { ServerConfig.isValidPort(it) } ?: defaults.port,
            scheme = prefs[Keys.scheme]?.takeIf { it.isNotBlank() } ?: defaults.scheme,
            trustSelfSigned = prefs[Keys.trustSelfSigned] ?: false,
        )
    }

    suspend fun serverConfigOnce(): ServerConfig = serverConfig.first()

    suspend fun setServerConfig(config: ServerConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.host] = config.host
            prefs[Keys.port] = config.port
            prefs[Keys.scheme] = config.scheme
            prefs[Keys.trustSelfSigned] = config.trustSelfSigned
        }
    }

    /** 恢复为编译期默认值 */
    suspend fun resetServerConfig() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.host)
            prefs.remove(Keys.port)
            prefs.remove(Keys.scheme)
            prefs.remove(Keys.trustSelfSigned)
        }
    }

    /* ---------------- 设备身份 ---------------- */

    /** 首次调用时生成并持久化，之后恒定不变 */
    suspend fun childUuid(): String {
        val existing = context.dataStore.data.first()[Keys.childUuid]
        if (!existing.isNullOrBlank()) return existing

        // 首次生成：优先从 ANDROID_ID 派生，而不是随机数。
        // ANDROID_ID 对同一设备上的同一签名应用是稳定的 —— 卸载重装后不变，
        // 重新配对就会得到同一个 UUID，服务端按 childUuid 复用原设备记录，
        // 不会出现「同一台平板在控制端变成两条」。恢复出厂会换 ANDROID_ID，那本来就该当新设备。
        // 拿不到 ANDROID_ID（部分设备/工作资料限制）时才退回随机值。
        val generated = deriveUuid(
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            ),
        )
        context.dataStore.edit { it[Keys.childUuid] = generated }
        return generated
    }

    companion object {
        /**
         * 由 ANDROID_ID 派生稳定的设备 UUID（纯函数，可单测）。
         *
         * 加固定前缀做域分隔，避免同一 ANDROID_ID 在其它体系里的派生值撞车；
         * 排除 `9774d56d682e549c` —— 部分老机型所有应用都返回这个值，等于没有标识。
         */
        fun deriveUuid(androidId: String?): String {
            val seed = androidId?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
            return if (seed != null) {
                java.util.UUID.nameUUIDFromBytes("zzl-child-identity:$seed".toByteArray()).toString()
            } else {
                java.util.UUID.randomUUID().toString()
            }
        }
    }

    /* ---------------- 控制端会话 ---------------- */

    val parentSession: Flow<ParentSession?> = context.dataStore.data.map { prefs ->
        val token = prefs[Keys.parentToken]
        val userId = prefs[Keys.parentUserId]
        val username = prefs[Keys.parentUsername]
        if (token.isNullOrBlank() || userId == null || username.isNullOrBlank()) {
            null
        } else {
            ParentSession(token, userId, username)
        }
    }

    suspend fun parentSessionOnce(): ParentSession? = parentSession.first()

    suspend fun saveParentSession(session: ParentSession) {
        context.dataStore.edit { prefs ->
            prefs[Keys.parentToken] = session.token
            prefs[Keys.parentUserId] = session.userId
            prefs[Keys.parentUsername] = session.username
        }
    }

    suspend fun clearParentSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.parentToken)
            prefs.remove(Keys.parentUserId)
            prefs.remove(Keys.parentUsername)
        }
    }

    /* ---------------- 被控端会话 ---------------- */

    val childSession: Flow<ChildSession?> = context.dataStore.data.map { prefs ->
        val token = prefs[Keys.childToken]
        val deviceId = prefs[Keys.childDeviceId]
        val name = prefs[Keys.childDeviceName]
        if (token.isNullOrBlank() || deviceId == null || name.isNullOrBlank()) {
            null
        } else {
            ChildSession(token, deviceId, name)
        }
    }

    suspend fun childSessionOnce(): ChildSession? = childSession.first()

    suspend fun saveChildSession(session: ChildSession) {
        context.dataStore.edit { prefs ->
            prefs[Keys.childToken] = session.token
            prefs[Keys.childDeviceId] = session.deviceId
            prefs[Keys.childDeviceName] = session.deviceName
        }
    }

    suspend fun clearChildSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.childToken)
            prefs.remove(Keys.childDeviceId)
            prefs.remove(Keys.childDeviceName)
        }
    }

    /**
     * 切换服务器时调用：凭据全部作废，强制重新登录 / 重新配对。
     * 注意 childUuid 不清理——它是设备身份，不是会话凭据。
     */
    suspend fun clearAllSessions() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.parentToken)
            prefs.remove(Keys.parentUserId)
            prefs.remove(Keys.parentUsername)
            prefs.remove(Keys.childToken)
            prefs.remove(Keys.childDeviceId)
            prefs.remove(Keys.childDeviceName)
        }
    }
}
