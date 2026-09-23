package com.zzl.guardian.child.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.zzl.guardian.child.di.guardGraph
import com.zzl.guardian.child.engine.GuardEngine
import com.zzl.guardian.child.screenshot.ScreenshotCapturer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 前台应用感知的核心通道。
 *
 * 只做三件事，其余判定全部交给 [GuardEngine]：
 *  1. 把「当前前台是哪个应用」实时喂给引擎
 *  2. 向引擎提供「强制返回桌面」的能力
 *  3. 服务连接时补一次当前前台应用，避免错过服务启动前已经打开的应用
 */
class GuardAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val engine: GuardEngine by lazy { guardGraph(this).guardEngine() }

    /**
     * 截屏器。
     *
     * 用 `by lazy` 而不是在 onCreate 里取：无障碍服务的生命周期由系统控制，
     * 可能在应用组件图尚未就绪时就被创建。延迟到真正用到时再取更安全。
     */
    private val capturer: ScreenshotCapturer by lazy { guardGraph(this).screenshotCapturer() }

    /**
     * 输入法包名集合。
     *
     * 输入法弹出/收起会发出 TYPE_WINDOW_STATE_CHANGED 事件，且事件包名是**输入法自己**
     * 而不是被遮罩的应用 —— 引擎会把这当成「用户切走了」，把拦截遮罩撤掉。
     * 真机表现就是：孩子一点拦截页上的密码输入框，遮罩消失。
     * 因此输入法事件必须在进入引擎之前整个丢弃（注意不是归一化成 null，
     * 那样会走「无前台应用 → 撤遮罩」分支，效果一样是遮罩消失）。
     */
    private val imePackages: Set<String> by lazy { resolveImePackages() }

    /**
     * 安装器与运行时权限弹窗的包名（AOSP 与 Google 套件两套并存）。
     * 它们的窗口事件永远不代表「孩子切到了新应用」。
     */
    private val transientDialogPackages: Set<String> = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    /** 上一次「回桌面兜底链」的发起时间，避免引擎每秒触发时兜底动作叠加 */
    private val lastHomeChainAt = AtomicLong(0L)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "无障碍服务已连接")

        // 把「回到桌面」的能力交给引擎，拦截时由引擎触发。
        // 实测（OnePlus/ColorOS 13+）：performGlobalAction 可能**返回 true 但不执行**，
        // 而后台 startActivity 又受 ROM「后台弹出界面」限制 —— 单一路径都会哑火。
        // 所以这里是三级兜底：全局动作 → 核实没动再补 HOME Intent → 还没动再上滑手势。
        engine.performHome = { goHomeWithFallback() }

        // 把「截屏」的能力交给截屏器。
        // 只有无障碍服务持有 takeScreenshot 能力，而它由系统实例化，
        // 因此必须在这里主动登记 —— 这是 M6 截屏唯一的入口。
        capturer.attach(this)

        bootstrap()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val current = event ?: return
        if (current.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            current.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        val packageName = current.packageName?.toString()
        if (packageName.isNullOrBlank()) return

        // 关键：拦截遮罩是我们自己的窗口，它的出现会触发无障碍事件。
        // 如果把自身包名上报给引擎，引擎会理解成"切到了不受管控的应用"，
        // 从而把刚弹出来的遮罩立刻收掉。所以这里直接丢弃自身事件。
        if (packageName == this.packageName) return

        // 输入法窗口事件整个丢弃：它既不代表用户切走了，也不能触发「撤遮罩」。
        // 漏掉这条，拦截页上的密码输入框一点、输入法一弹，遮罩就没了。
        if (packageName in imePackages) return

        // SystemUI 的窗口事件（音量条、通知栏、截屏浮层、媒体控制等）必须丢弃。
        // 引擎把「null 前台」理解为"用户回到了桌面/熄屏"而关闭计时会话；
        // 这些事件恰恰在孩子停留在应用内时大量出现 —— 会话被关掉后，
        // 由于应用窗口没有再变化，不会再有事件把计时恢复，
        // 表现为"使用报告远低于实际、限额迟迟不触发、时间线全是 0 分钟碎片"（真机反馈）。
        // 回到桌面由桌面启动器（launcher）自己的事件上报，不依赖 SystemUI。
        if (packageName == SYSTEM_UI_PACKAGE) return

        // 安装器/权限弹窗：它们是"应用内流程的一部分"，不是孩子主动使用的应用。
        // 上报给引擎会把调用方应用的会话切碎（点一次授权就多一条 0 秒记录）。
        // 与输入法同一性质 —— 整个丢弃，时间归属调用方应用。
        if (packageName in transientDialogPackages) return

        engine.onForegroundChanged(packageName, System.currentTimeMillis())
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        engine.performHome = null
        engine.foregroundLookup = null
        // 摘掉截屏能力：服务即将销毁，留着弱引用也只会指向一个死对象，
        // 而 isReady() 会因此误报"可用"，让家长收到一个莫名其妙的超时
        capturer.detach()
        return super.onUnbind(intent)
    }

    /** 服务是系统代管的，初始化解绑到应用作用域，避免随服务销毁而中断 */
    private fun bootstrap() {
        scope.launch {
            val store = guardGraph(this@GuardAccessibilityService).settingsStore()
            val session = runCatching { store.childSessionOnce() }.getOrNull()
            if (session == null) {
                Log.i(TAG, "尚未配对，跳过引擎初始化")
                return@launch
            }

            runCatching {
                engine.initialize(session.deviceId)
                // 前台核实回调：引擎提交切换前用它确认「前台真的变了」，
                // 防瞬态窗口（权限弹窗/侧边栏等）把计时会话切碎
                engine.foregroundLookup = { currentForegroundPackage() }
                engine.onForegroundChanged(currentForegroundPackage(), System.currentTimeMillis())
            }.onFailure { Log.w(TAG, "初始化管控引擎失败", it) }
        }
    }

    /**
     * 读取当前前台应用。
     * 无障碍事件只在切换时触发，服务刚启动时如果已有应用在前台，
     * 必须主动查一次，否则这段时间不会被计入使用时长。
     */
    private fun currentForegroundPackage(): String? = runCatching {
        val applicationWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val active = applicationWindows.firstOrNull { it.isActive } ?: applicationWindows.firstOrNull()
        val pkg = active?.root?.packageName?.toString()
        pkg?.takeIf {
            it != packageName && it !in imePackages &&
                it != SYSTEM_UI_PACKAGE && it !in transientDialogPackages
        }
    }.getOrNull()

    /* ---------------- 回桌面：三级兜底 ---------------- */

    private fun goHomeWithFallback() {
        val now = SystemClock.uptimeMillis()
        // 引擎被拦期间每秒都会触发一次；兜底链自带延迟核实，
        // 不设冷却的话会出现多条链同时在跑、手势连发的叠加
        val chainAllowed = now - lastHomeChainAt.get() >= HOME_CHAIN_COOLDOWN_MS
        lastHomeChainAt.set(now)

        val before = currentForegroundPackage()
        val viaGlobalAction = runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
            .getOrDefault(false)
        if (!viaGlobalAction) {
            // 返回 false 说明这条路明确不通，直接走 HOME Intent
            launchHomeIntent()
            return
        }
        if (!chainAllowed) return

        // 部分 ROM 返回 true 但实际没动 —— 延迟核实：前台没变就逐级补发
        mainHandler.postDelayed({
            if (currentForegroundPackage() != before) return@postDelayed
            Log.w(TAG, "GLOBAL_ACTION_HOME 假成功，补发 HOME Intent")
            launchHomeIntent()
            mainHandler.postDelayed({
                if (currentForegroundPackage() != before) return@postDelayed
                Log.w(TAG, "HOME Intent 未生效，重试全局动作")
                runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
                mainHandler.postDelayed({
                    if (currentForegroundPackage() != before) return@postDelayed
                    Log.w(TAG, "仍在前台，尝试上滑手势回桌面")
                    swipeUpHome()
                }, HOME_VERIFY_DELAY_MS)
            }, HOME_VERIFY_DELAY_MS)
        }, HOME_VERIFY_DELAY_MS)
    }

    private fun launchHomeIntent() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "HOME Intent 启动失败（请检查 ROM 的「后台弹出界面」权限）", it) }
    }

    /**
     * 上滑手势模拟「回到桌面」（手势导航机型）。
     * 这是无障碍自带能力，不依赖任何后台启动豁免；三键导航机型上无效但无害。
     */
    private fun swipeUpHome() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val metrics = resources.displayMetrics
            val centerX = metrics.widthPixels / 2f
            val bottom = metrics.heightPixels - 20f
            val path = Path().apply {
                moveTo(centerX, bottom)
                lineTo(centerX, metrics.heightPixels * 0.3f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 180L))
                .build()
            // dispatchGesture 在无障碍配置未声明 canPerformGestures 时静默返回 false
            val dispatched = dispatchGesture(gesture, null, null)
            if (!dispatched) {
                Log.w(TAG, "dispatchGesture 被系统拒绝（升级后需重开一次无障碍开关）")
            }
        }.onFailure { Log.w(TAG, "上滑手势执行失败", it) }
    }

    /* ---------------- 输入法识别 ---------------- */

    /**
     * 收集输入法包名：枚举所有注册了 InputMethod 服务的包 +
     * 当前默认输入法（双保险，防个别 ROM 枚举不全）。
     * 应用持有 QUERY_ALL_PACKAGES，这个查询不受包可见性过滤影响。
     */
    private fun resolveImePackages(): Set<String> = runCatching {
        buildSet {
            packageManager
                .queryIntentServices(Intent("android.view.InputMethod"), 0)
                .forEach { it.serviceInfo?.packageName?.let(::add) }
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }
    }.getOrDefault(emptySet())

    private companion object {
        const val TAG = "GuardAccessibility"

        /** SystemUI 的窗口事件不代表前台应用切换（详见 onAccessibilityEvent 内注释） */
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /** 兜底链每一步之间的核实等待 */
        const val HOME_VERIFY_DELAY_MS = 500L

        /** 兜底链冷却：与引擎的回桌面节流（3 秒）对齐，保证同一时刻只有一条链在跑 */
        const val HOME_CHAIN_COOLDOWN_MS = 3_000L
    }
}
