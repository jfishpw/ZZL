package com.zzl.guardian.child.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zzl.guardian.child.di.guardGraph
import com.zzl.guardian.child.keepalive.GuardKeepaliveAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 保活闹钟的落点。
 *
 * 一次性精确闹钟不会自动重复，因此每次醒来后必须重新安排下一次 ——
 * 忘了这一步的后果很隐蔽：设备第一次被清理后能拉回来，
 * 但之后再被清理就再也没有唤醒了（因为闹钟已经用掉且没有续上）。
 *
 * 用 Receiver 而不是直接让闹钟指向 Service：
 * PendingIntent 的目标越轻量，在 Doze 模式下被系统延迟的概率越低。
 */
class GuardAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                // 关键：先确认本机仍在管控中，再续期闹钟。
                //
                // 少了这一步会留下两个后患：家长用超级密码退出管控后，
                // 闹钟依然每次醒来都把自己续上，形成永不停歇的空转；
                // 而 GuardKeepaliveAlarm.cancel() 一旦在某个路径上被漏掉，
                // 这个空转还会在进程重启后复活。
                val session = runCatching {
                    guardGraph(appContext).settingsStore().childSessionOnce()
                }.getOrNull()

                if (session == null) {
                    Log.i(TAG, "已退出管控，停止保活闹钟")
                    GuardKeepaliveAlarm.cancel(appContext)
                    return@launch
                }

                Log.i(TAG, "保活闹钟触发，重新拉起管控服务")
                GuardKeepaliveAlarm.rescheduleAfterWake(appContext)
            } catch (e: Throwable) {
                Log.w(TAG, "保活唤醒失败", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "GuardAlarm"
    }
}
