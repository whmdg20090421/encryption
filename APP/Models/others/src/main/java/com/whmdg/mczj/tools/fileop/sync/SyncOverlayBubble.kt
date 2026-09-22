package com.whmdg.mczj.tools.fileop.sync

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 云盘同步的系统级悬浮球（`TYPE_APPLICATION_OVERLAY`）。
 *
 * 仅当用户授予"显示在其他应用上层"（[Settings.canDrawOverlays]）时创建；否则调用方
 * 退回应用内悬浮球。球在所有应用之上显示，并让本进程被系统视为前台，为后台上传
 * 提供额外保活。
 *
 * 交互：
 * - 拖动：位移 ≥ [CLICK_SLOP_DP]；
 * - 点击：位移 < 阈值 → 拉回本应用并展开进度弹窗（[onClick]）；
 * - 自动贴边：球贴近某条边、拖动松手后 [SNAP_DELAY_MS] 内无新拖动时，就近贴边
 *   （优先左右、其次上下），只留半圆在屏内；
 * - 贴边态：忽略拖动，触摸穿透到下层应用；点击一次仅解除贴边（球回到屏内完整显示），
 *   再点击才拉回应用。
 *
 * 一个进程内同时至多一个球（上传/下载互斥）。所有 UI 操作切主线程执行。
 */
object SyncOverlayBubble {

    private const val CLICK_SLOP_DP = 8f
    private const val BUBBLE_SIZE_DP = 50f
    /** 拖动松手后多久无操作触发自动贴边。 */
    private const val SNAP_DELAY_MS = 5000L
    /** 判定"贴近边缘"的距离阈值（dp）。 */
    private const val NEAR_EDGE_DP = 24f
    /** 长按多久切换锁定状态。 */
    private const val LONG_PRESS_MS = 5000L

    /** 贴边方向。 */
    private enum class Edge { NONE, LEFT, RIGHT, TOP, BOTTOM }

    private var windowManager: WindowManager? = null
    private var bubbleView: FrameLayout? = null
    private var labelView: TextView? = null
    private var lockView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var onBubbleClick: (() -> Unit)? = null

    /** 当前是否处于贴边态。 */
    private var snapped = false
    /** 当前贴边方向（NONE 表示未贴边）。 */
    private var snapEdge = Edge.NONE

    /** 是否处于锁定态（锁定后禁用贴边，右下角显示锁图标）。 */
    private var locked = false

    /** 本次触摸是否已因长按而消费（避免 ACTION_UP 再触发点击）。 */
    private var longPressConsumed = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapRunnable = Runnable { performSnap() }
    private val longPressRunnable = Runnable { onLongPress() }

    /** 原始进度文本（未按贴边方向排版）。 */
    private var rawLabelText: String = "0.00%"

    /** 是否已授予悬浮窗权限。 */
    fun canShow(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * 显示悬浮球。[onClick] 在"可点击"状态下触发（贴边态下第一次点击用于解除贴边）。
     * 重复调用只更新点击回调，不重复添加视图。
     */
    fun show(context: Context, onClick: () -> Unit) {
        mainHandler.post {
            onBubbleClick = onClick
            if (bubbleView != null) return@post
            if (!canShow(context)) return@post

            val appContext = context.applicationContext
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            val density = appContext.resources.displayMetrics.density
            val size = (BUBBLE_SIZE_DP * density).toInt()

            val container = FrameLayout(appContext)
            val label = TextView(appContext).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#3B82F6"))
                textSize = 13f
                text = "0.00%"
            }
            container.addView(label, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            // 右下角小锁：仅锁定态可见
            val lock = TextView(appContext).apply {
                text = "🔒"
                textSize = 10f
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
            val lockSize = (16 * density).toInt()
            container.addView(lock, FrameLayout.LayoutParams(lockSize, lockSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            })
            container.background = circleDrawable(density)

            val lp = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val metrics = appContext.resources.displayMetrics
                x = metrics.widthPixels - size - (20 * density).toInt()
                y = metrics.heightPixels - size - (24 * density).toInt()
            }

            setupTouch(appContext, container, lp, density)

            try {
                wm.addView(container, lp)
            } catch (_: Exception) {
                return@post
            }
            windowManager = wm
            bubbleView = container
            labelView = label
            lockView = lock
            layoutParams = lp
            snapped = false
            snapEdge = Edge.NONE
            locked = false
        }
    }

    /** 刷新球上的进度文本（两位小数）。会按当前贴边方向重新排版。 */
    fun update(percentText: String) {
        mainHandler.post {
            rawLabelText = percentText
            renderLabel()
        }
    }

    /** 移除悬浮球。任务结束/取消/异常都必须调用。 */
    fun dismiss() {
        mainHandler.post {
            mainHandler.removeCallbacks(snapRunnable)
            mainHandler.removeCallbacks(longPressRunnable)
            try {
                bubbleView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {
            }
            bubbleView = null
            labelView = null
            lockView = null
            layoutParams = null
            onBubbleClick = null
            snapped = false
            snapEdge = Edge.NONE
            locked = false
        }
    }

    // ── 触摸：拖动 / 点击 / 贴边解除 ──

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch(
        context: Context,
        view: FrameLayout,
        lp: WindowManager.LayoutParams,
        density: Float
    ) {
        val slop = CLICK_SLOP_DP * density
        var initialX = 0
        var initialY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 贴边态不接收拖动：点击一次仅解除贴边（不参与长按锁定）
                    if (snapped) {
                        unsnap(context, view, lp, density)
                        return@setOnTouchListener true
                    }
                    initialX = lp.x
                    initialY = lp.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    longPressConsumed = false
                    mainHandler.removeCallbacks(longPressRunnable)
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        dragging = true
                        // 开始拖动即取消长按
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        lp.x = (initialX + dx).toInt()
                        lp.y = (initialY + dy).toInt()
                        try {
                            windowManager?.updateViewLayout(view, lp)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    when {
                        longPressConsumed -> true
                        dragging -> {
                            clampToScreen(context, lp)
                            scheduleSnapIfNearEdge(context, density)
                            true
                        }
                        else -> {
                            onBubbleClick?.invoke()
                            true
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    false
                }
                else -> false
            }
        }
    }

    /** 长按 5 秒：切换锁定状态。锁定后禁用贴边并显示右下角小锁。 */
    private fun onLongPress() {
        longPressConsumed = true
        locked = !locked
        if (locked) {
            // 锁定立即取消待执行的贴边，并解除当前贴边（恢复完整显示）
            mainHandler.removeCallbacks(snapRunnable)
            val ctx = bubbleView?.context
            val view = bubbleView
            val lp = layoutParams
            if (snapped && ctx != null && view != null && lp != null) {
                unsnap(ctx, view, lp, ctx.resources.displayMetrics.density)
            }
        }
        updateLockBadge()
    }

    /** 同步锁图标可见性。 */
    private fun updateLockBadge() {
        lockView?.visibility = if (locked) View.VISIBLE else View.GONE
    }

    /** 解除贴边：恢复完整显示、可拖动，并把文字恢复水平居中、重新挂上箭头由调用方刷新。 */
    private fun unsnap(
        context: Context,
        view: FrameLayout,
        lp: WindowManager.LayoutParams,
        density: Float
    ) {
        mainHandler.removeCallbacks(snapRunnable)
        snapped = false
        snapEdge = Edge.NONE
        val metrics = context.resources.displayMetrics
        // 贴回屏内：让球完整可见
        lp.x = lp.x.coerceIn(0, metrics.widthPixels - lp.width)
        lp.y = lp.y.coerceIn(0, metrics.heightPixels - lp.height)
        try {
            windowManager?.updateViewLayout(view, lp)
        } catch (_: Exception) {
        }
        resetLabelLayout()
    }

    /** 松手后若球靠近边缘，则启动贴边倒计时（锁定态禁用贴边）。 */
    private fun scheduleSnapIfNearEdge(context: Context, density: Float) {
        if (locked) return
        val lp = layoutParams ?: return
        if (nearestEdge(context, lp, density) == Edge.NONE) return
        mainHandler.removeCallbacks(snapRunnable)
        mainHandler.postDelayed(snapRunnable, SNAP_DELAY_MS)
    }

    /** 判断球当前贴近哪条边（优先左右、其次上下）；不近任何边返回 NONE。 */
    private fun nearestEdge(context: Context, lp: WindowManager.LayoutParams, density: Float): Edge {
        val metrics = context.resources.displayMetrics
        val near = NEAR_EDGE_DP * density
        val distLeft = lp.x.toFloat()
        val distRight = (metrics.widthPixels - (lp.x + lp.width)).toFloat()
        val distTop = lp.y.toFloat()
        val distBottom = (metrics.heightPixels - (lp.y + lp.height)).toFloat()

        val nearLeft = distLeft <= near
        val nearRight = distRight <= near
        val nearTop = distTop <= near
        val nearBottom = distBottom <= near

        // 优先左右：只要接触左边或右边就左右贴边（四角时也优先左右）
        if (nearLeft && nearRight) return if (distLeft <= distRight) Edge.LEFT else Edge.RIGHT
        if (nearLeft) return Edge.LEFT
        if (nearRight) return Edge.RIGHT
        if (nearTop && nearBottom) return if (distTop <= distBottom) Edge.TOP else Edge.BOTTOM
        if (nearTop) return Edge.TOP
        if (nearBottom) return Edge.BOTTOM
        return Edge.NONE
    }

    /** 执行自动贴边：球心贴到最近边缘，使半径移出屏幕外，并切换为竖排/横排文本。 */
    private fun performSnap() {
        val context = bubbleView?.context ?: return
        val view = bubbleView ?: return
        val lp = layoutParams ?: return
        val density = context.resources.displayMetrics.density
        val edge = nearestEdge(context, lp, density)
        if (edge == Edge.NONE) return

        val metrics = context.resources.displayMetrics
        val half = lp.width / 2
        when (edge) {
            Edge.LEFT -> lp.x = -half
            Edge.RIGHT -> lp.x = metrics.widthPixels - lp.width + half
            Edge.TOP -> lp.y = -half
            Edge.BOTTOM -> lp.y = metrics.heightPixels - lp.height + half
            Edge.NONE -> return
        }
        snapped = true
        snapEdge = edge
        try {
            windowManager?.updateViewLayout(view, lp)
        } catch (_: Exception) {
        }
        applySnappedLabelLayout(edge)
    }

    /** 贴边态文本布局：左右贴边竖排、上下贴边横排，均仅显示进度数字。 */
    private fun applySnappedLabelLayout(edge: Edge) {
        labelView?.textSize = when (edge) {
            Edge.LEFT, Edge.RIGHT -> 10f
            Edge.TOP, Edge.BOTTOM -> 12f
            Edge.NONE -> 13f
        }
        renderLabel()
    }

    /** 按当前贴边方向渲染文本：左右竖排（每字符一行），其余横排单行。 */
    private fun renderLabel() {
        val label = labelView ?: return
        label.gravity = Gravity.CENTER
        label.text = when (snapEdge) {
            Edge.LEFT, Edge.RIGHT -> rawLabelText.toCharArray().joinToString("\n")
            else -> rawLabelText
        }
    }

    /** 恢复未贴边时的正常文本（单行）。 */
    private fun resetLabelLayout() {
        labelView?.textSize = 13f
        renderLabel()
    }

    /** 拖动松手后把球约束回屏幕内，避免被拖出可视区。 */
    private fun clampToScreen(context: Context, lp: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        lp.x = lp.x.coerceIn(0, metrics.widthPixels - lp.width)
        lp.y = lp.y.coerceIn(0, metrics.heightPixels - lp.height)
        try {
            bubbleView?.let { windowManager?.updateViewLayout(it, lp) }
        } catch (_: Exception) {
        }
    }

    private fun circleDrawable(density: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke((2 * density).toInt(), Color.parseColor("#3B82F6"))
        }
}
