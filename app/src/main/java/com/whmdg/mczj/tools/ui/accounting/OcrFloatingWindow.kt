package com.whmdg.mczj.tools.ui.accounting

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.whmdg.mczj.tools.security.MyAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OCR 悬浮窗：气泡 + 展开菜单 + 识别结果。
 *
 * 状态机：BUBBLE → MENU → LOADING → CONFIRM → BUBBLE
 */
object OcrFloatingWindow {

    private enum class State { BUBBLE, MENU, LOADING, CONFIRM }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var menuView: View? = null
    private var state = State.BUBBLE

    private const val BUBBLE_SIZE_DP = 40
    private const val MENU_WIDTH_DP = 200

    /** 显示悬浮气泡 */
    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (bubbleView != null) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val density = context.resources.displayMetrics.density
        val bubbleSize = (BUBBLE_SIZE_DP * density).toInt()

        // 创建气泡视图
        val bubble = createBubbleView(context, bubbleSize, density)

        val params = WindowManager.LayoutParams(
            bubbleSize, bubbleSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (context.resources.displayMetrics.heightPixels * 0.4f).toInt()
        }

        setupDragAndClick(bubble, params, context, density)

        try {
            wm.addView(bubble, params)
            bubbleView = bubble
            state = State.BUBBLE
        } catch (_: Exception) {
            // 悬浮窗权限可能被撤销
        }
    }

    /** 悬浮窗是否正在显示 */
    fun isVisible(): Boolean = bubbleView != null

    /** 移除悬浮窗 */
    fun dismiss() {
        val wm = windowManager ?: return
        menuView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        bubbleView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        menuView = null
        bubbleView = null
        state = State.BUBBLE
    }

    /** 开关状态变化时调用 */
    fun onToggleChanged(context: Context, enabled: Boolean) {
        if (enabled) show(context) else dismiss()
    }

    // ── 气泡视图 ──

    private fun createBubbleView(context: Context, size: Int, density: Float): FrameLayout {
        val container = FrameLayout(context)

        // 圆形背景
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF1A73E8.toInt()) // 蓝色
        }
        container.background = bg

        // 应用图标（使用系统默认图标）
        val icon = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setColorFilter(0xFFFFFFFF.toInt())
            val padding = (8 * density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        container.addView(icon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 转圈进度条（LOADING 状态时显示）
        val progress = ProgressBar(context).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        val progressSize = (16 * density).toInt()
        val progressLp = FrameLayout.LayoutParams(progressSize, progressSize).apply {
            gravity = Gravity.BOTTOM or Gravity.END
        }
        container.addView(progress, progressLp)

        // 存储引用
        container.setTag(progress)

        return container
    }

    // ── 拖动 + 点击 ──

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragAndClick(
        bubble: FrameLayout,
        params: WindowManager.LayoutParams,
        context: Context,
        density: Float
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (kotlin.math.abs(dx) > 5 || kotlin.math.abs(dy) > 5) {
                        isDragging = true
                    }
                    params.x = (initialX + dx).toInt()
                    params.y = (initialY + dy).toInt()
                    try {
                        windowManager?.updateViewLayout(bubble, params)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onBubbleClicked(context, bubble, params, density)
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ── 气泡点击 → 展开菜单 ──

    private fun onBubbleClicked(
        context: Context,
        bubble: FrameLayout,
        bubbleParams: WindowManager.LayoutParams,
        density: Float
    ) {
        when (state) {
            State.BUBBLE -> showMenu(context, bubble, bubbleParams, density)
            State.MENU -> hideMenu()
            State.CONFIRM -> {} // 弹窗自行处理
            State.LOADING -> {} // 忽略
        }
    }

    // ── 菜单 ──

    private fun showMenu(
        context: Context,
        bubble: FrameLayout,
        bubbleParams: WindowManager.LayoutParams,
        density: Float
    ) {
        if (menuView != null) return

        val menuWidth = (MENU_WIDTH_DP * density).toInt()
        val padding = (12 * density).toInt()
        val buttonHeight = (40 * density).toInt()

        // 菜单容器
        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(0xFF2D2D2D.toInt())
            }
            setPadding(padding, padding, padding, padding)
        }

        // 识别按钮
        val btn = createButton(context, "识别", density) {
            hideMenu()
            startRecognition(context)
        }
        menu.addView(btn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, buttonHeight
        ))

        // UI展示按钮（待实现）
        val uiBtn = createButton(context, "UI展示", density) {
            hideMenu()
        }
        menu.addView(uiBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, buttonHeight
        ).apply { topMargin = (6 * density).toInt() })

        // 计算菜单位置（气泡下方，自适应屏幕边界）
        val screenHeight = context.resources.displayMetrics.heightPixels
        val menuY = bubbleParams.y + bubbleParams.height + (8 * density).toInt()
        val menuGravity = if (menuY + 200 * density > screenHeight) {
            // 空间不足，显示在气泡上方
            Gravity.TOP or Gravity.END
        } else {
            Gravity.TOP or Gravity.START
        }

        val menuParams = WindowManager.LayoutParams(
            menuWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = menuGravity
            x = bubbleParams.x
            y = if (menuGravity == (Gravity.TOP or Gravity.END)) {
                bubbleParams.y - (8 * density).toInt() - 200
            } else {
                menuY
            }
        }

        try {
            windowManager?.addView(menu, menuParams)
            menuView = menu
            state = State.MENU
        } catch (_: Exception) {}
    }

    private fun hideMenu() {
        menuView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        menuView = null
        state = State.BUBBLE
    }

    // ── 识别流程 ──

    private fun startRecognition(context: Context) {
        state = State.LOADING
        // 显示气泡上的进度条
        bubbleView?.getTag()?.let { (it as? ProgressBar)?.visibility = View.VISIBLE }

        Handler(Looper.getMainLooper()).post {
            try {
                val service = MyAccessibilityService.instance
                if (service == null) {
                    state = State.BUBBLE
                    bubbleView?.getTag()?.let { (it as? ProgressBar)?.visibility = View.GONE }
                    showErrorPopup(context, "无障碍服务未运行", emptyList())
                    return@post
                }
                val result = BillOcrEngine.recognizeNow(service)
                bubbleView?.getTag()?.let { (it as? ProgressBar)?.visibility = View.GONE }
                if (result != null) {
                    state = State.CONFIRM
                    showConfirmPopup(context, result)
                } else {
                    state = State.BUBBLE
                    // 提取调试信息
                    val debugTexts = service.extractAllTexts().take(5)
                    showErrorPopup(context, "未识别到账单信息", debugTexts)
                }
            } catch (e: Exception) {
                state = State.BUBBLE
                bubbleView?.getTag()?.let { (it as? ProgressBar)?.visibility = View.GONE }
                showErrorPopup(context, "识别出错: ${e.message}", emptyList())
            }
        }
    }

    // ── 确认弹窗 ──

    /** 自动识别结果展示（由无障碍事件触发） */
    fun showAutoResult(context: Context, result: OcrBillInfo) {
        Handler(Looper.getMainLooper()).post {
            state = State.CONFIRM
            showConfirmPopup(context, result)
        }
    }

    private fun showConfirmPopup(context: Context, info: OcrBillInfo) {
        val wm = windowManager ?: return

        val density = context.resources.displayMetrics.density

        // 全屏半透明背景
        val overlay = FrameLayout(context).apply {
            setBackgroundColor(0x80000000.toInt())
        }

        // 底部白色卡片
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(0xFFFFFFFF.toInt())
            }
            setPadding((20 * density).toInt(), (16 * density).toInt(),
                       (20 * density).toInt(), (20 * density).toInt())
        }

        // 类型标签
        val typeLabel = when {
            info.transfer -> "转账"
            info.income -> "收入"
            else -> "支出"
        }
        val typeColor = when {
            info.transfer -> 0xFF1A73E8.toInt()
            info.income -> 0xFF34A853.toInt()
            else -> 0xFFEA4335.toInt()
        }
        card.addView(TextView(context).apply {
            text = typeLabel
            setTextColor(typeColor)
            textSize = 16f
        })

        // 金额
        val amountText = if (info.number.isNotEmpty()) "¥${info.number}" else "未知"
        card.addView(TextView(context).apply {
            text = amountText
            setTextColor(0xFF000000.toInt())
            textSize = 28f
            setPadding(0, (8 * density).toInt(), 0, 0)
        })

        // 分隔线
        card.addView(View(context).apply {
            setBackgroundColor(0xFFE0E0E0.toInt())
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
        ).apply { topMargin = (12 * density).toInt(); bottomMargin = (12 * density).toInt() })

        // 详情字段
        val fields = mutableListOf<Pair<String, String>>()
        if (info.remark.isNotEmpty()) fields.add("备注" to info.remark)
        if (info.shopName.isNotEmpty()) fields.add("商家" to info.shopName)
        fields.add("来源" to info.origin)
        if (info.time > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            fields.add("时间" to sdf.format(Date(info.time)))
        }
        if (info.asset.isNotEmpty()) fields.add("支付方式" to info.asset)
        if (info.transfer) {
            if (info.fromAsset.isNotEmpty()) fields.add("转出" to info.fromAsset)
            if (info.toAsset.isNotEmpty()) fields.add("转入" to info.toAsset)
        }
        if (info.discount.isNotEmpty()) fields.add("优惠" to info.discount)

        for ((label, value) in fields) {
            card.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(context).apply {
                    text = label
                    setTextColor(0xFF999999.toInt())
                    textSize = 14f
                    minWidth = (60 * density).toInt()
                })
                addView(TextView(context).apply {
                    text = value
                    setTextColor(0xFF333333.toInt())
                    textSize = 14f
                })
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() })
        }

        // 按钮区域
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (16 * density).toInt(), 0, 0)
        }

        // 保存按钮（灰色不可用）
        val saveBtn = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(0xFF999999.toInt())
            }
            isClickable = false
            isFocusable = false
        }
        saveBtn.addView(TextView(context).apply {
            text = "保存记账（开发中）"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        buttonRow.addView(saveBtn, LinearLayout.LayoutParams(0, (44 * density).toInt(), 1f))

        // 关闭按钮
        val closeBtn = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(0xFF1A73E8.toInt())
            }
            setOnClickListener {
                try { wm.removeView(overlay) } catch (_: Exception) {}
                state = State.BUBBLE
            }
        }
        closeBtn.addView(TextView(context).apply {
            text = "关闭"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        buttonRow.addView(closeBtn, LinearLayout.LayoutParams(0, (44 * density).toInt(), 1f).apply {
            marginStart = (12 * density).toInt()
        })

        card.addView(buttonRow)

        // 卡片放在底部
        val cardLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            leftMargin = (16 * density).toInt()
            rightMargin = (16 * density).toInt()
            bottomMargin = (24 * density).toInt()
        }
        overlay.addView(card, cardLp)

        // 点击背景关闭
        overlay.setOnClickListener { v ->
            if (v == overlay) {
                try { wm.removeView(overlay) } catch (_: Exception) {}
                state = State.BUBBLE
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        try {
            wm.addView(overlay, params)
            // 从底部弹出动画
            card.translationY = 300 * density
            card.alpha = 0f
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 500
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val fraction = anim.animatedValue as Float
                    card.translationY = 300 * density * (1f - fraction)
                    card.alpha = fraction
                }
                start()
            }
        } catch (_: Exception) {}
    }

    // ── 错误弹窗 ──

    private fun showErrorPopup(context: Context, message: String, debugTexts: List<String>) {
        val builder = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("识别失败")
            .setMessage(buildString {
                append(message)
                if (debugTexts.isNotEmpty()) {
                    append("\n\n调试信息（前5个文本）：")
                    for ((i, t) in debugTexts.withIndex()) {
                        append("\n${i + 1}. $t")
                    }
                }
            })
            .setPositiveButton("关闭", null)
        try {
            builder.show()
        } catch (_: Exception) {}
    }

    // ── 工具方法 ──

    private fun createText(context: Context, text: String, color: Int, sizeSp: Float): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(color)
            textSize = sizeSp
        }
    }

    private fun createButton(context: Context, text: String, density: Float, onClick: () -> Unit): View {
        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(0xFF1A73E8.toInt())
            }
            setOnClickListener { onClick() }
        }
        val tv = TextView(context).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        }
        container.addView(tv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        return container
    }
}
