package com.whmdg.mczj.tools.ui.accounting

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.whmdg.mczj.tools.security.MyAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OCR 悬浮窗：气泡 + 展开菜单 + 识别结果。
 *
 * 状态机：BUBBLE → MENU → LOADING → RESULT → BUBBLE
 */
object OcrFloatingWindow {

    private enum class State { BUBBLE, MENU, LOADING, RESULT }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var menuView: View? = null
    private var animationView: View? = null
    private var scope: CoroutineScope? = null
    private var state = State.BUBBLE

    private const val BUBBLE_SIZE_DP = 40
    private const val MENU_WIDTH_DP = 200

    /** 显示悬浮气泡 */
    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (bubbleView != null) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        scope?.cancel()
        scope = null
        val wm = windowManager ?: return
        // 移除所有视图
        animationView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        menuView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        bubbleView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        animationView = null
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
            State.RESULT -> hideMenu()
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
            startRecognition(context, bubble, density)
        }
        menu.addView(btn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, buttonHeight
        ))

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

    private fun startRecognition(
        context: Context,
        bubble: FrameLayout,
        density: Float
    ) {
        state = State.LOADING

        val service = MyAccessibilityService.instance
        if (service == null) {
            showResult(context, bubble, density, null, "无障碍服务未运行", null)
            return
        }

        scope?.launch {
            // 1. 截图 + 转换为软件位图（在 IO 线程执行）
            val screenshot = withContext(Dispatchers.IO) {
                service.takeScreenshot()?.let {
                    with(service) { it.toSoftwareBitmap() }
                }
            }
            if (screenshot == null) {
                showResult(context, bubble, density, null, "截图失败", null)
                return@launch
            }

            // 2. 在截图后立即获取包名并检测（动画前）
            val topPkg = service.topPackage ?: service.getTopPackageFromWindow()
            var pkg = topPkg
            var searchMode = false
            var croppedScreenshot: Bitmap? = null

            if (pkg == null || pkg !in BillOcrConfig.supportedApps) {
                // 包名不在支持列表中，搜索所有窗口找支持的应用
                val found = service.findSupportedAppWindow(BillOcrConfig.supportedApps)
                if (found != null) {
                    val (foundPkg, bounds) = found
                    // 局部截图：裁剪到目标窗口区域
                    val safeLeft = bounds.left.coerceIn(0, screenshot.width)
                    val safeTop = bounds.top.coerceIn(0, screenshot.height)
                    val safeRight = bounds.right.coerceIn(0, screenshot.width)
                    val safeBottom = bounds.bottom.coerceIn(0, screenshot.height)
                    val cropW = safeRight - safeLeft
                    val cropH = safeBottom - safeTop
                    if (cropW > 0 && cropH > 0) {
                        croppedScreenshot = Bitmap.createBitmap(screenshot, safeLeft, safeTop, cropW, cropH)
                        pkg = foundPkg
                        searchMode = true
                    }
                }
            }

            if (pkg == null || pkg !in BillOcrConfig.supportedApps) {
                screenshot.recycle()
                croppedScreenshot?.recycle()
                val errorMsg = if (pkg == null) "未检测到前台应用"
                               else "当前应用不支持：${BillOcrConfig.getAppName(pkg)}"
                showResult(context, bubble, density, null, errorMsg, null)
                return@launch
            }

            // 3. 最终使用的截图（搜索模式用裁剪图，普通模式用全屏截图）
            val ocrBitmap = croppedScreenshot ?: screenshot

            // 4. 包名有效，播放动画
            showScreenshotAnimation(context, bubble, density, ocrBitmap) {
                // 5. 动画结束后显示转圈
                val progress = bubble.getTag() as? ProgressBar
                progress?.visibility = View.VISIBLE

                // 6. OCR 识别（try-finally 确保 bitmap 回收）
                scope?.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            BillOcrEngine.recognizeFromBitmap(ocrBitmap, pkg!!)
                        }
                        val debugText = if (result.bill == null) result.debugText else null
                        showResult(context, bubble, density, result.bill, result.error, debugText, searchMode)
                    } finally {
                        ocrBitmap.recycle()
                        // 搜索模式下原始截图也需要回收
                        if (searchMode) screenshot.recycle()
                        progress?.visibility = View.GONE
                    }
                }
            }
        }
    }

    /**
     * 截图动画：截图从原始位置缩小飞入悬浮窗
     */
    private fun showScreenshotAnimation(
        context: Context,
        bubble: FrameLayout,
        density: Float,
        bitmap: Bitmap,
        onAnimationEnd: () -> Unit
    ) {
        val wm = windowManager ?: return
        val bubbleLp = bubble.layoutParams as? WindowManager.LayoutParams ?: return

        // 计算悬浮窗屏幕位置
        val bubbleScreenX = bubbleLp.x
        val bubbleScreenY = bubbleLp.y
        val bubbleSize = (BUBBLE_SIZE_DP * density).toInt()

        // 创建全屏透明窗口用于动画
        val overlay = FrameLayout(context)

        // 截图 ImageView
        val imageView = ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        // 初始大小（屏幕的 80%）
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val initialWidth = (screenWidth * 0.8f).toInt()
        val initialHeight = (screenHeight * 0.8f).toInt()
        val initialX = (screenWidth - initialWidth) / 2
        val initialY = (screenHeight - initialHeight) / 2

        val imageParams = FrameLayout.LayoutParams(initialWidth, initialHeight).apply {
            leftMargin = initialX
            topMargin = initialY
        }
        overlay.addView(imageView, imageParams)

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            wm.addView(overlay, overlayParams)
            animationView = overlay
        } catch (_: Exception) {
            bitmap.recycle()
            // 不手动设置 state，让 showResult 内部处理
            showResult(context, bubble, density, null, "悬浮窗创建失败", null)
            return
        }

        // 动画：缩小 + 移动到悬浮窗位置
        val targetScale = bubbleSize.toFloat() / initialWidth

        val scaleX = ObjectAnimator.ofFloat(imageView, "scaleX", 1f, targetScale)
        val scaleY = ObjectAnimator.ofFloat(imageView, "scaleY", 1f, targetScale)
        val translationX = ObjectAnimator.ofFloat(
            imageView, "translationX",
            0f, (bubbleScreenX - initialX).toFloat()
        )
        val translationY = ObjectAnimator.ofFloat(
            imageView, "translationY",
            0f, (bubbleScreenY - initialY).toFloat()
        )
        val alpha = ObjectAnimator.ofFloat(imageView, "alpha", 1f, 0.7f)

        val animatorSet = AnimatorSet().apply {
            playTogether(scaleX, scaleY, translationX, translationY, alpha)
            duration = 500
            interpolator = AccelerateInterpolator()
        }

        animatorSet.addListener(object : android.animation.AnimatorListenerAdapter() {
            var cancelled = false

            override fun onAnimationCancel(animation: android.animation.Animator) {
                cancelled = true
                imageView.setImageDrawable(null)
                try { wm.removeView(overlay) } catch (_: Exception) {}
                animationView = null
                bitmap.recycle()
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (cancelled) return  // 取消触发的 end，跳过
                imageView.setImageDrawable(null)
                try { wm.removeView(overlay) } catch (_: Exception) {}
                animationView = null
                onAnimationEnd()
            }
        })

        animatorSet.start()
    }

    // ── 结果展示 ──

    private fun showResult(
        context: Context,
        bubble: FrameLayout,
        density: Float,
        result: OcrBillResult?,
        error: String?,
        debugText: String?,
        searchMode: Boolean = false
    ) {
        val menuWidth = (MENU_WIDTH_DP * density).toInt()
        val padding = (12 * density).toInt()
        val lineSpacing = (4 * density).toInt()

        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(0xFF2D2D2D.toInt())
            }
            setPadding(padding, padding, padding, padding)
        }

        // 搜索模式标识
        if (searchMode) {
            menu.addView(createText(context, "搜索模式", 0xFFFFAB40.toInt(), 12f))
        }

        if (error != null) {
            menu.addView(createText(context, error, 0xFFFF5252.toInt(), 14f).apply {
                if (searchMode) setPadding(0, (6 * density).toInt(), 0, 0)
            })
            // 显示调试信息
            if (debugText != null && debugText.isNotBlank()) {
                menu.addView(createText(context, "提取到的文字:", 0xFF888888.toInt(), 11f).apply {
                    setPadding(0, (8 * density).toInt(), 0, 0)
                })
                menu.addView(createText(context, debugText, 0xFFAAAAAA.toInt(), 11f))
            }
        } else if (result != null) {
            menu.addView(createText(context, "识别成功", 0xFF4CAF50.toInt(), 12f).apply {
                if (searchMode) setPadding(0, (6 * density).toInt(), 0, 0)
            })

            menu.addView(createText(context,
                "${result.type}  ¥${String.format("%.2f", result.amount)}",
                0xFFFFFFFF.toInt(), 16f).apply {
                setPadding(0, (6 * density).toInt(), 0, 0)
            })

            menu.addView(createText(context,
                "商户: ${result.merchant}",
                0xFFBBBBBB.toInt(), 13f).apply {
                setPadding(0, lineSpacing, 0, 0)
            })

            menu.addView(createText(context,
                "来源: ${BillOcrConfig.getAppName(result.sourceApp)}",
                0xFF999999.toInt(), 12f).apply {
                setPadding(0, lineSpacing, 0, 0)
            })
        } else {
            menu.addView(createText(context, "未识别到账单信息", 0xFFFF9800.toInt(), 14f).apply {
                if (searchMode) setPadding(0, (6 * density).toInt(), 0, 0)
            })
        }

        // 关闭按钮
        val closeBtn = createButton(context, "关闭", density) { hideMenu() }
        menu.addView(closeBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (36 * density).toInt()
        ).apply { topMargin = (8 * density).toInt() })

        // 获取气泡位置
        val bubbleLp = bubble.layoutParams as? WindowManager.LayoutParams

        val menuParams = WindowManager.LayoutParams(
            menuWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleLp?.x ?: 0) + (BUBBLE_SIZE_DP * density).toInt() + (8 * density).toInt()
            y = bubbleLp?.y ?: 0
            // 确保不超出屏幕右边界
            val screenWidth = context.resources.displayMetrics.widthPixels
            if (x + menuWidth > screenWidth) {
                x = (bubbleLp?.x ?: 0) - menuWidth - (8 * density).toInt()
            }
        }

        try {
            windowManager?.addView(menu, menuParams)
            menuView = menu
            state = State.RESULT
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
