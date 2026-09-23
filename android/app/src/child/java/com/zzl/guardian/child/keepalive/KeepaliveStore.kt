package com.zzl.guardian.child.keepalive

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.keepaliveStore: DataStore<Preferences> by preferencesDataStore(name = "zzl_keepalive")

/**
 * 保活相关的本地配置。
 *
 * 只有一个字段需要持久化：**厂商白名单是否已设置**。
 *
 * 为什么需要家长手动确认：厂商的自启动/后台白名单**没有公开 API 可查**，
 * 系统也不让 App 自己加白名单（这正是它们防后台常驻的设计）。
 * 因此只能"引导家长去设置 → 请他勾选已设置 → 记下来"。
 *
 * 不这么做的话，健康度永远显示"不健康"，家长会逐渐无视这个提示 ——
 * 一个永远报警的指标等于没有指标。
 */
@Singleton
class KeepaliveStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val vendorWhitelistConfirmed = booleanPreferencesKey("vendor_whitelist_confirmed")
        val confirmedVendor = stringPreferencesKey("confirmed_vendor")
    }

    /**
     * 是否已确认设置厂商白名单。
     *
     * 带厂商校验：换了机型（或刷机后 Build.MANUFACTURER 变了）时自动失效，
     * 否则家长换设备后会看到一个"已确认"但其实从未设置过的白名单。
     */
    suspend fun vendorWhitelistConfirmed(): Boolean {
        val prefs = context.keepaliveStore.data.first()
        if (prefs[Keys.vendorWhitelistConfirmed] != true) return false
        return prefs[Keys.confirmedVendor] == KeepaliveManager.detectVendor().displayName
    }

    suspend fun setVendorWhitelistConfirmed(confirmed: Boolean) {
        context.keepaliveStore.edit { prefs ->
            if (confirmed) {
                prefs[Keys.vendorWhitelistConfirmed] = true
                prefs[Keys.confirmedVendor] = KeepaliveManager.detectVendor().displayName
            } else {
                prefs.remove(Keys.vendorWhitelistConfirmed)
                prefs.remove(Keys.confirmedVendor)
            }
        }
    }

    suspend fun clear() {
        context.keepaliveStore.edit { it.clear() }
    }
}
