package com.zzl.guardian.child.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.zzl.guardian.child.di.guardGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机自启。
 * 只在「本机已完成配对」时才拉起常驻服务，避免未配对设备白白占一个前台服务。
 */
class GuardBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val session = runCatching {
                    guardGraph(appContext).settingsStore().childSessionOnce()
                }.getOrNull()

                if (session == null) {
                    Log.i(TAG, "尚未配对，跳过自启")
                    return@launch
                }

                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, GuardForegroundService::class.java),
                )
                Log.i(TAG, "已随开机启动管控服务")
            } catch (e: Throwable) {
                Log.w(TAG, "自启失败", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "GuardBootReceiver"
    }
}
