package com.zzl.guardian.child.engine

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 时间用尽时的全屏遮盖层。
 *
 * 用 [WindowManager] 直接挂 `TYPE_APPLICATION_OVERLAY` 而非启动 Activity：
 *  - 不需要在任务栈里留记录，孩子无法用「最近任务」把它划掉
 *  - 不受 Activity 生命周期影响，切换应用时依然覆盖全屏
 *  - 屏蔽返回键；离开靠系统全面屏手势（遮罩不拦截系统级手势）
 *
 * 离线密码**直接内联在拦截页上**（输入框 + 验证按钮），验证通过后展开
 * 家长操作按钮。历史上这里是三个隐藏入口（连点标题 / 长按角落 / 拨号暗码）
 * 再弹出独立 Activity —— 真机反馈两条都走不通：入口难发现，Activity 又被
 * 本遮罩压在下面（应用窗口永远低于 TYPE_APPLICATION_OVERLAY）。
 *
 * [show] 是**幂等**的：已显示时只更新文案与按钮状态，不重新挂窗口。
 * 引擎每秒巡检都会调用它，重建窗口会让界面闪烁，也会让孩子的点击落空。
 */
@Singleton
class BlockOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private var rootView: FrameLayout? = null
    private var titleView: TextView? = null
    private var subtitleView: TextView? = null
    private var detailView: TextView? = null
    private var requestButton: TextView? = null
    private var hintView: TextView? = null

    /* ---------------- 内联密码区 ---------------- */
    private var pinRow: LinearLayout? = null
    private var pinInput: EditText? = null
    private var pinVerifyButton: TextView? = null
    private var pinFeedback: TextView? = null
    private var pinActions: LinearLayout? = null

    /** 当前尝试的级别；NotSet 时由服务端回调降级 */
    private var pinLevel = 1

    /** 验证通过后的级别；非空时展示家长操作按钮 */
    private var verifiedLevel: Int? = null

    /** 操作按钮构建时的锁定状态；与 [overlayLocked] 不一致时 refresh 会重建按钮 */
    private var actionsBuiltLocked: Boolean? = null

    /** 防止连点重复提交 */
    private var pinBusy = false

    /** 提交代号：每次提交自增，用于丢弃迟到回调与超时兜底 */
    private var pinSubmitGeneration = 0

    /** 「退出管控」的两段式确认：第一次点击武装，5 秒内再点才执行 */
    private var exitArmedAt = 0L

    /**
     * 当前遮罩是否处于「家长主动锁定」状态。
     * 锁定期间 forcedLocked 压过一切授权 —— 加时与放行点了也不会生效，
     * 与其让孩子点了没反应，不如直接不显示并说明原因。
     */
    private var overlayLocked = false

    val isShowing: Boolean get() = rootView != null

    /**
     * @param canRequestTime 是否展示「申请加时」按钮。名单类拦截与家长主动锁定不展示 ——
     *                       那两种情况不是"额度不够"，给孩子申请入口只会变成讨价还价的噪音。
     * @param requestHint    申请结果的反馈文案（已发送 / 已批准 / 未批准 / 网络失败）
     * @param onRequestTime  点击申请时的回调；null 表示不展示按钮
     * @param onPinSubmit    内联密码验证：pin + 当前级别 → (是否通过, 生效级别, 反馈文案)
     * @param onPinAction    验证通过后的家长动作：action → 反馈文案（null 表示成功且无需提示）
     * @param onGoHome       回桌面回调（「回到桌面」按钮已移除，参数保留备用）
     * @param deviceLocked   设备是否被家长主动锁定（锁定态下验证后只提供「退出管控」）
     */
    fun show(
        appLabel: String,
        reasonText: String,
        detailText: String,
        canRequestTime: Boolean,
        requestHint: String?,
        onRequestTime: (() -> Unit)?,
        onPinSubmit: ((pin: String, level: Int, onResult: (Boolean, Int, String?) -> Unit) -> Unit)?,
        onPinAction: ((action: String, onResult: (String?) -> Unit) -> Unit)?,
        onGoHome: () -> Unit,
        deviceLocked: Boolean = false,
    ) {
        overlayLocked = deviceLocked
        mainHandler.post {
            if (rootView != null) {
                refresh(
                    appLabel, reasonText, detailText,
                    canRequestTime, requestHint, onRequestTime,
                    onPinSubmit, onPinAction,
                )
                return@post
            }
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "缺少悬浮窗权限，无法显示拦截页")
                return@post
            }

            runCatching {
                attach(
                    appLabel, reasonText, detailText,
                    canRequestTime, requestHint, onRequestTime,
                    onPinSubmit, onPinAction, onGoHome,
                )
            }.onFailure { Log.e(TAG, "显示拦截页失败", it) }
        }
    }

    fun hide() {
        mainHandler.post {
            val view = rootView ?: return@post
            rootView = null
            titleView = null
            subtitleView = null
            detailView = null
            requestButton = null
            hintView = null
            pinRow = null
            pinInput = null
            pinVerifyButton = null
            pinFeedback = null
            pinActions = null
            pinLevel = 1
            verifiedLevel = null
            actionsBuiltLocked = null
            pinBusy = false
            pinSubmitGeneration++
            exitArmedAt = 0L
            runCatching { windowManager.removeView(view) }
                .onFailure { Log.w(TAG, "移除拦截页失败", it) }
        }
    }

    /* ---------------- 已显示时只更新内容 ---------------- */

    private fun refresh(
        appLabel: String,
        reasonText: String,
        detailText: String,
        canRequestTime: Boolean,
        requestHint: String?,
        onRequestTime: (() -> Unit)?,
        onPinSubmit: ((pin: String, level: Int, onResult: (Boolean, Int, String?) -> Unit) -> Unit)?,
        onPinAction: ((action: String, onResult: (String?) -> Unit) -> Unit)?,
    ) {
        subtitleView?.text = "「$appLabel」$reasonText"
        detailView?.text = detailText

        val button = requestButton
        if (button != null) {
            val pending = requestHint?.startsWith("已发送") == true
            button.visibility = if (canRequestTime && onRequestTime != null) View.VISIBLE else View.GONE
            button.isEnabled = !pending
            button.alpha = if (pending) 0.5f else 1f
            button.text = if (pending) "申请已发送" else "申请加时"
            // 重新绑定点击：onRequestTime 是每次传入的闭包，不更新会点到旧的
            button.setOnClickListener { if (button.isEnabled) onRequestTime?.invoke() }
        }

        hintView?.text = requestHint ?: DEFAULT_HINT

        // 密码区的回调是转发引擎的 volatile 字段，闭包本身无状态，
        // 但验证中/已通过的状态要保持 —— 不能在每秒一次的 refresh 里重置 UI
        bindPinCallbacks(onPinSubmit, onPinAction)

        // 锁定状态在遮罩挂着期间可能变化（家长远程解锁/上锁）：
        // 已验证展开的操作按钮必须跟着重建，否则按钮集合与实际可执行的动作不一致
        val verified = verifiedLevel
        if (verified != null && actionsBuiltLocked != overlayLocked) {
            buildActionButtons(verified)
        }
    }

    /* ---------------- 构建界面 ---------------- */

    private fun attach(
        appLabel: String,
        reasonText: String,
        detailText: String,
        canRequestTime: Boolean,
        requestHint: String?,
        onRequestTime: (() -> Unit)?,
        onPinSubmit: ((pin: String, level: Int, onResult: (Boolean, Int, String?) -> Unit) -> Unit)?,
        onPinAction: ((action: String, onResult: (String?) -> Unit) -> Unit)?,
        onGoHome: () -> Unit,
    ) {
        val root = FrameLayout(context).apply {
            setBackgroundColor(BACKGROUND)
            // 吃掉返回键：遮罩内没有退出按钮，系统手势（侧滑回桌面）不受影响
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        titleView = text("使用受限", 26f, Color.WHITE, Typeface.BOLD)
        column.addView(titleView)
        column.addView(space(dp(16)))

        subtitleView = text("「$appLabel」$reasonText", 16f, ACCENT)
        column.addView(subtitleView)

        column.addView(space(dp(8)))
        detailView = text(detailText, 14f, MUTED)
        column.addView(detailView)

        column.addView(space(dp(32)))

        /**
         * 「申请加时」按钮**总是**建出来，只控制可见性。
         *
         * 不按需创建的原因：遮罩是复用的（引擎每秒都在调用 [show]），
         * 孩子可能从"黑名单拦截"切到"额度用尽"—— 那时拦截页已经挂着，
         * 走的是只更新内容的 [refresh] 分支，按需创建就永远长不出这个按钮。
         */
        requestButton = button("申请加时") { }.apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(SECONDARY)
            }
        }
        column.addView(requestButton)
        column.addView(space(dp(14)))

        // 「回到桌面」按钮已按家长反馈移除：全面屏手势（侧滑）可以直接离开，
        // 按钮反而成了孩子反复进出的入口。onGoHome 参数保留备用。
        column.addView(space(dp(16)))
        hintView = text(requestHint ?: DEFAULT_HINT, 13f, MUTED)
        column.addView(hintView)

        /* ---------- 家长密码区（内联，直接输入） ---------- */
        column.addView(space(dp(20)))

        pinFeedback = text("", 13f, ACCENT).apply { visibility = View.GONE }
        buildPinRow()
        column.addView(pinRow)
        column.addView(space(dp(8)))
        column.addView(pinFeedback)

        pinActions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        column.addView(pinActions)

        root.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // 密码框聚焦时让输入法正常弹出
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        windowManager.addView(root, params)
        rootView = root
        root.requestFocus()

        // 首次显示也要按真实参数校准一次按钮状态与文案
        refresh(
            appLabel, reasonText, detailText,
            canRequestTime, requestHint, onRequestTime,
            onPinSubmit, onPinAction,
        )
    }

    private fun buildPinRow() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            // 显式加变换遮蔽：部分 ROM 的数字键盘输入法不理会
            // TYPE_NUMBER_VARIATION_PASSWORD，表现为明文回显（真机反馈）。
            // transformationMethod 是最终兜底，与 inputType 谁生效都以它为准。
            transformationMethod = PasswordTransformationMethod.getInstance()
            // 密码是纯数字：用 DigitsKeyListener 锁死键盘字符集，
            // 避免 ROM 定制输入法在 password 变体上切出字母面板
            keyListener = DigitsKeyListener.getInstance()
            setTextColor(Color.WHITE)
            setHintTextColor(0x66FFFFFF)
            hint = "家长密码"
            textSize = 16f
            gravity = Gravity.CENTER
            // 注意：不要在这里调 setSingleLine —— inputType 含 password 变体时
            // 已经是单行，而 setSingleLine 会把 transformationMethod 重置成
            // SingleLineTransformationMethod，星号遮蔽直接失效变明文（真机反馈）。
            // 悬浮窗在部分 ROM 上不自动拉输入法，聚焦时显式拉一次
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    runCatching {
                        val ime = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        ime.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
        }
        input.setOnEditorActionListener { _, _, _ ->
            submitPin()
            true
        }
        pinInput = input

        row.addView(
            input,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            },
        )

        val verifyButton = TextView(context).apply {
            text = "验证"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(ACCENT)
            }
            isClickable = true
            setOnClickListener { submitPin() }
        }
        pinVerifyButton = verifyButton
        row.addView(
            verifyButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        pinRow = row
    }

    private fun submitPin() {
        val input = pinInput ?: return
        if (pinBusy || verifiedLevel != null) return
        val value = input.text?.toString().orEmpty().trim()
        if (value.isEmpty()) {
            showPinFeedback("请输入密码")
            return
        }
        if (pinSubmitHandler == null) {
            showPinFeedback("暂时无法验证，请稍后再试")
            return
        }

        pinBusy = true
        pinVerifyButton?.isEnabled = false
        pinVerifyButton?.alpha = 0.5f
        pinVerifyButton?.text = "验证中…"
        showPinFeedback(null)

        // 提交代号：迟到/重复的回调（例如超时与真回调赛跑）一律丢弃
        val generation = ++pinSubmitGeneration
        pinSubmitHandler?.invoke(value, pinLevel) { verified, level, message ->
            mainHandler.post {
                if (generation != pinSubmitGeneration) return@post
                handlePinResult(verified, level, message)
            }
        }

        // 兜底：回调链任何一环断了（ROM 停掉前台服务的窗口期、协程异常）
        // 都不能把按钮永远卡在「验证中…」—— 超时后恢复可重试
        mainHandler.postDelayed({
            if (generation == pinSubmitGeneration && pinBusy) {
                pinSubmitGeneration++
                resetPinButton("验证超时，请重试")
            }
        }, PIN_VERIFY_TIMEOUT_MS)
    }

    private fun handlePinResult(verified: Boolean, level: Int, message: String?) {
        pinBusy = false
        pinVerifyButton?.isEnabled = true
        pinVerifyButton?.alpha = 1f
        pinVerifyButton?.text = "验证"
        if (verified) {
            verifiedLevel = level
            pinLevel = level
            pinInput?.setText("")
            pinRow?.visibility = View.GONE
            showPinFeedback("已通过验证（${levelName(level)}），请选择操作")
            buildActionButtons(level)
        } else {
            if (level != pinLevel) pinLevel = level
            showPinFeedback(message ?: "验证未通过")
        }
    }

    private fun resetPinButton(message: String?) {
        pinBusy = false
        pinVerifyButton?.isEnabled = true
        pinVerifyButton?.alpha = 1f
        pinVerifyButton?.text = "验证"
        showPinFeedback(message ?: "验证未通过")
    }

    private fun buildActionButtons(level: Int) {
        val container = pinActions ?: return
        container.removeAllViews()
        container.visibility = View.VISIBLE
        actionsBuiltLocked = overlayLocked

        fun actionButton(label: String, color: Int, onClick: () -> Unit) =
            TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(12), dp(28), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(22).toFloat()
                    setColor(color)
                }
                isClickable = true
                setOnClickListener { onClick() }
            }

        if (overlayLocked) {
            // 家长主动锁定期间 forcedLocked 压过一切授权：
            // 加时与放行执行了也不会解锁，显示出来只会让孩子反复点"没反应"（真机反馈）。
            // 唯一能离开锁定的是 L3 退出管控，或家长在控制端解除锁定。
            if (level >= 1) {
                container.addView(
                    text("设备已被锁定：加时与放行在锁定期间不可用", 13f, MUTED),
                )
                container.addView(space(dp(10)))
            }
            if (level >= 3) {
                container.addView(buildExitButton())
            } else {
                container.addView(
                    text("退出管控需要输入超级密码（第 3 级）", 13f, MUTED),
                )
            }
            return
        }

        // L1+：加时（四个固定档位直接铺开，不用再弹选择框）
        if (level >= 1) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            listOf(15, 30, 60, 120).forEachIndexed { index, minutes ->
                if (index > 0) row.addView(space(dp(6)))
                row.addView(
                    actionButton("+$minutes 分钟", SECONDARY) { runAction("add_time:$minutes") },
                )
            }
            container.addView(row)
            container.addView(space(dp(10)))
        }

        // L2+：放行当前被拦的应用
        if (level >= 2) {
            container.addView(
                actionButton("放行当前应用 30 分钟", SECONDARY) { runAction(ACTION_ALLOW_APP) },
            )
            container.addView(space(dp(10)))
        }

        // L3：退出管控
        if (level >= 3) {
            container.addView(buildExitButton())
        }
    }

    /** 「退出管控」两段式确认按钮：第一次点变成"再点一次确认"，5 秒内再点才执行 */
    private fun buildExitButton(): TextView {
        lateinit var exitButton: TextView
        exitButton = TextView(context).apply {
            text = "退出管控"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(12), dp(28), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(EXIT_COLOR)
            }
            isClickable = true
            setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - exitArmedAt <= EXIT_ARM_WINDOW_MS) {
                    exitArmedAt = 0L
                    runAction(ACTION_EXIT_GUARD)
                } else {
                    exitArmedAt = now
                    exitButton.text = "再点一次确认退出"
                    mainHandler.postDelayed({
                        if (System.currentTimeMillis() - exitArmedAt >= EXIT_ARM_WINDOW_MS) {
                            exitArmedAt = 0L
                            exitButton.text = "退出管控"
                        }
                    }, EXIT_ARM_WINDOW_MS + 100)
                }
            }
        }
        return exitButton
    }

    private fun runAction(action: String) {
        val handler = pinActionHandler ?: run {
            showPinFeedback("暂时无法执行，请稍后再试")
            return
        }
        if (pinBusy) return
        pinBusy = true
        handler(action) { message ->
            mainHandler.post {
                pinBusy = false
                // 成功的加时/放行会让引擎重新评估并自动撤掉遮罩；
                // 遮罩还在（比如额度仍然不够）时把结果告诉家长
                if (message != null || rootView != null) {
                    showPinFeedback(message ?: "已执行")
                }
            }
        }
    }

    private fun showPinFeedback(message: String?) {
        val view = pinFeedback ?: return
        if (message.isNullOrBlank()) {
            view.visibility = View.GONE
            view.text = ""
        } else {
            view.visibility = View.VISIBLE
            view.text = message
        }
    }

    /** 每次刷新都重绑：回调转发的是引擎 volatile 字段，重绑保证拿到最新注册 */
    private fun bindPinCallbacks(
        onPinSubmit: ((pin: String, level: Int, onResult: (Boolean, Int, String?) -> Unit) -> Unit)?,
        onPinAction: ((action: String, onResult: (String?) -> Unit) -> Unit)?,
    ) {
        pinActionHandler = onPinAction
        pinSubmitHandler = onPinSubmit
    }

    /** 由 [bindPinCallbacks] 持有；[pinInput] 的提交走这里而不是闭包捕获，避免拿到旧回调 */
    private var pinActionHandler: ((action: String, onResult: (String?) -> Unit) -> Unit)? = null
    private var pinSubmitHandler: ((pin: String, level: Int, onResult: (Boolean, Int, String?) -> Unit) -> Unit)? = null

    private fun levelName(level: Int) = when (level) {
        3 -> "超级密码"
        2 -> "管理密码"
        else -> "日常密码"
    }

    private fun text(value: String, sizeSp: Float, color: Int, style: Int = Typeface.NORMAL) =
        TextView(context).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = Typeface.create(Typeface.DEFAULT, style)
            gravity = Gravity.CENTER
        }

    private fun button(label: String, onClick: () -> Unit) =
        TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(16), dp(48), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(ACCENT)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun space(height: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, height)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    private companion object {
        const val TAG = "BlockOverlay"
        const val BACKGROUND = 0xFF141318.toInt()
        const val ACCENT = 0xFF7F77DD.toInt()
        const val SECONDARY = 0xFF3E7C6A.toInt()
        const val MUTED = 0xFFB4B2A9.toInt()
        const val DEFAULT_HINT = "家长可在下方输入离线密码临时加时或放行"

        const val ACTION_ALLOW_APP = "allow_app"
        const val ACTION_EXIT_GUARD = "exit_guard"

        /** 「退出管控」按钮底色 */
        const val EXIT_COLOR = 0xFF8A4A4A.toInt()

        /** 「退出管控」两段式确认的有效窗口 */
        const val EXIT_ARM_WINDOW_MS = 5_000L

        /** 密码验证的超时兜底：回调链断掉时恢复按钮可重试 */
        const val PIN_VERIFY_TIMEOUT_MS = 8_000L
    }
}

/**
 * 密码尝试来源标识（写入审计 pin_source）。
 *
 * 现在只剩拦截页内联密码框一种入口；corner / dialer 是旧版隐藏入口，
 * 常量保留只为正确描述历史审计记录。
 */
object PinEntrySource {

    /** 拦截页内联密码框 */
    const val OVERLAY = "overlay"

    /** 旧版：拦截页右下角长按（已停用） */
    const val CORNER = "corner"

    /** 旧版：拨号盘暗码（已停用） */
    const val DIALER = "dialer"

    fun describe(source: String?): String = when (source) {
        OVERLAY -> "拦截页密码框"
        CORNER -> "拦截页长按角落（旧版入口）"
        DIALER -> "拨号盘暗码（旧版入口）"
        else -> "未知入口"
    }
}
