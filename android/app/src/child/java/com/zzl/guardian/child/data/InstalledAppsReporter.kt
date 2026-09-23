package com.zzl.guardian.child.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.zzl.guardian.data.api.ApiService
import com.zzl.guardian.data.api.InstalledAppDto
import com.zzl.guardian.data.api.InstalledAppsRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 已安装应用清单采集与上报。
 *
 * 只采集「有启动图标」的应用 —— 这正是家长需要管控的范围。
 * 把系统服务、输入法、后台组件一并上报只会让名单变成一团噪声。
 */
@Singleton
class InstalledAppsReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService,
) {

    fun collect(limit: Int = 300): List<InstalledAppDto> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    launcherIntent,
                    PackageManager.ResolveInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(launcherIntent, 0)
            }
        }.getOrDefault(emptyList())

        return resolved
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            // 自己不需要被管控
            .filter { it != context.packageName }
            .map { packageName ->
                InstalledAppDto(
                    packageName = packageName,
                    appLabel = labelOf(packageManager, packageName),
                    isSystem = isSystemApp(packageManager, packageName),
                )
            }
            .sortedBy { it.appLabel ?: it.packageName }
            .take(limit)
    }

    /** 整份上报，返回服务端受理的条数；失败返回 0，下次同步会重试 */
    suspend fun upload(token: String, deviceId: Long): Int = withContext(Dispatchers.IO) {
        val apps = collect()
        if (apps.isEmpty()) return@withContext 0

        runCatching {
            api.reportInstalledApps(
                authorization = "Bearer $token",
                deviceId = deviceId,
                body = InstalledAppsRequest(apps),
            )
        }.getOrNull()?.accepted ?: 0
    }

    private fun labelOf(packageManager: PackageManager, packageName: String): String =
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)

    private fun isSystemApp(packageManager: PackageManager, packageName: String): Boolean =
        runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrDefault(false)
}
