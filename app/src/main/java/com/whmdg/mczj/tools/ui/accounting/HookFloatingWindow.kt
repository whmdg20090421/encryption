package com.whmdg.mczj.tools.ui.accounting

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hook 模式悬浮窗：气泡 + 结果列表。
 *
 * 状态机：BUBBLE ↔ RESULT_LIST
 *
 * 与 OcrFloatingWindow 不同，Hook 模式不需要手动触发识别，
 * 数据通过 HookResultReceiver 自动到达。
 */
object HookFloatingWindow {

    private enum class State { BUBBLE, RESULT_LIST }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var listView: View? = null
    private var state = State.BUBBLE
    private var unreadCount = 0

    private const val BUBBLE_SIZE_DP = 40
    private const val LIST_WIDTH_DP = 280
    private const val MAX_DISPLAY = 5

    /** 显示悬浮气泡 */
    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (bubbleView != null) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val density = context.resources.displayMetrics.density
        val bubbleSize = (BUBBLE_SIZE_DP * density).toInt()

        // 创建气泡
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

            // 注册新数据回调
            HookResultReceiver.onNewResult = { result ->
                unreadCount++
                updateBadge(context)
            }
        } catch (_: Exception) {}
    }

    /** 移除悬浮窗 */
    fun dismiss() {
        HookResultReceiver.onNewResult = null
        val wm = windowManager ?: return
        listView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubbleView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        listView = null
        bubbleView = null
        state = State.BUBBLE
        unreadCount = 0
    }

    // ── 气泡视图 ──

    private fun createBubbleView(context: Context, size: Int, density: Float): FrameLayout {
        val container = FrameLayout(context)

        // 圆形背景（绿色，区别于 OCR 的蓝色）
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF4CAF50.toInt())
        }
        container.background = bg

        // "H" 文字标识
        val label = TextView(context).apply {
            text = "H"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            paint.isFakeBoldText = true
        }
        container.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 角标（未读数）
        val badge = TextView(context).apply {
            visibility = View.GONE
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 9f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFF5252.toInt())
            }
        }
        val badgeSize = (16 * density).toInt()
        val badgeLp = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = (-2 * density).toInt()
            rightMargin = (-2 * density).toInt()
        }
        container.addView(badge, badgeLp)

        // 存储 badge 引用
        container.setTag(badge)

        return container
    }

    private fun updateBadge(context: Context) {
        val bubble = bubbleView as? FrameLayout ?: return
        val badge = bubble.getTag() as? TextView ?: return
        if (unreadCount > 0) {
            badge.text = if (unreadCount > 9) "9+" else unreadCount.toString()
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }
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
                    try { windowManager?.updateViewLayout(bubble, params) } catch (_: Exception) {}
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

    private fun onBubbleClicked(
        context: Context,
        bubble: FrameLayout,
        bubbleParams: WindowManager.LayoutParams,
        density: Float
    ) {
        when (state) {
            State.BUBBLE -> showResultList(context, bubble, bubbleParams, density)
            State.RESULT_LIST -> hideResultList()
        }
    }

    // ── 结果列表 ──

    private fun showResultList(
        context: Context,
        bubble: FrameLayout,
        bubbleParams: WindowManager.LayoutParams,
        density: Float
    ) {
        if (listView != null) return

        unreadCount = 0
        updateBadge(context)

        val listWidth = (LIST_WIDTH_DP * density).toInt()
        val padding = (12 * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(0xFF2D2D2D.toInt())
            }
            setPadding(padding, padding, padding, padding)
        }

        // 标题
        val title = TextView(context).apply {
            text = "Hook 捕获"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            paint.isFakeBoldText = true
        }
        container.addView(title)

        val results = HookResultReceiver.getRecent(MAX_DISPLAY)

        if (results.isEmpty()) {
            val empty = TextView(context).apply {
                text = "等待微信账单数据..."
                setTextColor(0xFF999999.toInt())
                textSize = 12f
                setPadding(0, (8 * density).toInt(), 0, 0)
            }
            container.addView(empty)
        } else {
            val divider = View(context).apply {
                setBackgroundColor(0xFF444444.toInt())
            }
            container.addView(divider, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply { topMargin = (8 * density).toInt(); bottomMargin = (8 * density).toInt() })

            for (result in results.reversed()) {
                val item = createResultItem(context, result, density)
                container.addView(item, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() })
            }
        }

        // 操作按钮
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * density).toInt(), 0, 0)
        }

        val clearBtn = createSmallButton(context, "清空", 0xFF888888.toInt(), density) {
            HookResultReceiver.clear()
            hideResultList()
        }
        btnRow.addView(clearBtn, LinearLayout.LayoutParams(0, (32 * density).toInt(), 1f))

        container.addView(btnRow)

        // 定位：气泡右侧
        val bubbleLp = bubble.layoutParams as? WindowManager.LayoutParams
        val listParams = WindowManager.LayoutParams(
            listWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleLp?.x ?: 0) + (BUBBLE_SIZE_DP * density).toInt() + (8 * density).toInt()
            y = bubbleLp?.y ?: 0
            // 防止超出右边界
            val screenWidth = context.resources.displayMetrics.widthPixels
            if (x + listWidth > screenWidth) {
                x = (bubbleLp?.x ?: 0) - listWidth - (8 * density).toInt()
            }
        }

        try {
            windowManager?.addView(container, listParams)
            listView = container
            state = State.RESULT_LIST
        } catch (_: Exception) {}
    }

    private fun hideResultList() {
        listView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        listView = null
        state = State.BUBBLE
    }

    private fun createResultItem(context: Context, result: OcrBillResult, density: Float): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 第一行：类型 + 金额
        val topRow = TextView(context).apply {
            val typeIcon = if (result.type == "收入") "↑" else "↓"
            text = "$typeIcon ${result.type}  ¥${String.format("%.2f", result.amount)}"
            setTextColor(if (result.type == "收入") 0xFF4CAF50.toInt() else 0xFFFF5252.toInt())
            textSize = 14f
        }
        row.addView(topRow)

        // 第二行：商户 + 时间
        val bottomRow = TextView(context).apply {
            val timeStr = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(result.time))
            text = "${result.merchant}    $timeStr"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 11f
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        row.addView(bottomRow)

        return row
    }

    private fun createSmallButton(
        context: Context,
        text: String,
        color: Int,
        density: Float,
        onClick: () -> Unit
    ): FrameLayout {
        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(0xFF3D3D3D.toInt())
            }
            setOnClickListener { onClick() }
        }
        val tv = TextView(context).apply {
            this.text = text
            setTextColor(color)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        container.addView(tv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        return container
    }
}
