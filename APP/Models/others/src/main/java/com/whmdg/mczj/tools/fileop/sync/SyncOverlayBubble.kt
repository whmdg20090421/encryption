package com.whmdg.mczj.tools.fileop.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AlignmentSpan
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.petterp.floatingx.core.FloatingX
import com.petterp.floatingx.core.FxControl
import com.petterp.floatingx.core.FxListener
import com.petterp.floatingx.core.FxState
import com.petterp.floatingx.core.config.FxContent
import com.petterp.floatingx.core.gesture.FxDrag
import com.petterp.floatingx.core.layout.FxAdsorb
import com.petterp.floatingx.core.layout.FxAnchor
import com.petterp.floatingx.core.layout.FxEdge
import com.petterp.floatingx.core.layout.FxGravity
import com.petterp.floatingx.core.update
import com.petterp.floatingx.system.permission.FxPermissionStrategy
import com.petterp.floatingx.system.systemHost
import com.whmdg.mczj.tools.others.R

/**
 * 云盘同步的系统级悬浮球，基于 FloatingX `SystemHost`（`TYPE_APPLICATION_OVERLAY`）。
 *
 * 仅当用户授予"显示在其他应用上层"（[Settings.canDrawOverlays]）时创建；否则调用方退回应用内
 * 悬浮球。球在所有应用之上显示，并让本进程被系统视为前台，为后台上传提供额外保活。
 *
 * 交互（[FxListener] 驱动；FloatingX 只负责拖动与长按/点击判定）：
 * - 拖动：FloatingX 内置，位移超过系统 slop 才判定为拖动；松手后由本对象手动 clamp 回屏内；
 * - 点击：未贴边 → 拉回本应用并展开进度弹窗；贴边 → 仅取消贴边（球恢复完整显示）；
 * - 长按：系统默认长按超时（`longPressTimeout = 0`）切换锁定；锁定后禁用拖动，右下角显示 🔒；
 *   长按后同一次手势不再触发点击；
 * - 自动贴边：**不用 FloatingX 的即时吸附**。仅当用户手动把球拖到贴近屏幕左/右边缘
 *   （距离 < [EDGE_TRIGGER_DP] dp）、松手后静置 [SNAP_DELAY_MS]（5 秒）无操作，且处于未锁定状态时，
 *   才自动半隐贴边（隐藏一半，文字落在露出的半边）；
 * - 贴边态长按无效：贴边时 [FxListener.onLongClick] 直接忽略，只有未贴边时才可锁定/解锁。
 *
 * 一个进程内同时至多一个球（上传/下载互斥）。FloatingX 的所有 API 必须在主线程调用，
 * 本对象对外方法内部统一切主线程执行。
 */
object SyncOverlayBubble {

    /** FloatingX 注册表 tag，同 tag 重复 install 会先 cancel 旧的。 */
    private const val TAG = "sync-overlay-bubble"

    /** 判定"已移到边缘"的距离阈值（dp）：球边缘距屏幕左/右边界小于此值才允许贴边。 */
    private const val EDGE_TRIGGER_DP = 20f

    /** 拖到边缘后静置多久自动贴边。 */
    private const val SNAP_DELAY_MS = 5000L

    /** 贴边半隐比例：隐藏一半，留一半在屏内。 */
    private const val HALF_HIDE = 0.5f

    /** 贴边态数字字号（sp）：逐位竖排时缩小，避免单个数字溢出半圆可见区。 */
    private const val SNAPPED_TEXT_SIZE = 10f

    /** 未贴边态进度文本字号（sp）。 */
    private const val UN_SNAPPED_TEXT_SIZE = 13f

    /** 上传箭头单次穿越动画的时长（毫秒），循环播放。 */
    private const val ARROW_ANIM_DURATION_MS = 1400L

    /** 箭头垂直行程相对自身高度的倍数：0.5× 在上方、-0.5× 在下方，使箭头完整穿出球体。 */
    private const val ARROW_TRAVEL_FACTOR = 3f

    /** 箭头峰值透明度（叠加 view 自身 alpha），保持淡雅不压过进度数字。 */
    private const val ARROW_MAX_ALPHA = 0.5f

    /** 渐显/渐隐各占动画进度的比例，其余中段保持峰值。 */
    private const val ARROW_FADE_IN_FRACTION = 0.25f
    private const val ARROW_FADE_OUT_FRACTION = 0.3f

    private val mainHandler = Handler(Looper.getMainLooper())

    private var control: FxControl? = null

    private var onBubbleClick: (() -> Unit)? = null

    /** 球内上传箭头的循环动画；随悬浮球 show/dismiss 启停。 */
    private var arrowAnimator: android.animation.ValueAnimator? = null

    /** 是否处于锁定态（锁定后禁用拖动与贴边，右下角显示锁图标）。 */
    private var locked = false

    /** 原始进度文本（未按贴边方向排版）。 */
    private var rawLabelText: String = "0.00"

    /** 当前贴边方向：null 表示未贴边。 */
    private var snapEdge: FxEdge? = null

    /** 本次手势是否已被长按消费（避免松手后再触发点击）。 */
    private var longPressConsumed = false

    /** 拖动结束后启动的贴边倒计时。 */
    private val snapRunnable = Runnable { performSnap() }

    /** 是否已授予悬浮窗权限。 */
    fun canShow(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context.applicationContext)

    /**
     * 显示悬浮球。[onClick] 在"可点击状态下触发（贴边态下第一次点击用于解除贴边）。
     * 重复调用只更新点击回调与文本，不重复安装。
     */
    fun show(context: Context, onClick: () -> Unit) {
        mainHandler.post {
            onBubbleClick = onClick
            if (!canShow(context)) return@post

            // 已有实例：FloatingX 同 tag 复用，或本对象的控制权仍在
            val existing = control ?: FloatingX.controlOrNull(TAG)
            if (existing != null && existing.state != FxState.CANCELLED) {
                control = existing
                renderLabel()
                if (!existing.isShowing) existing.show()
                startArrowAnimation()
                return@post
            }

            val appContext = context.applicationContext
            val c = FloatingX.install(TAG) {
                content(FxContent.layout(R.layout.fx_sync_bubble))
                anchor(FxGravity.BOTTOM_END, dx = 0f, dy = 0f)
                safeArea = true
                // 关闭 FloatingX 的即时吸附：贴边时机完全由本对象控制
                adsorb(FxAdsorb.None)
                // 放开左右边界，贴边时才能把球移到屏幕外做半隐
                overflow(left = true, right = true)
                gesture {
                    click = true
                    longPress = true
                    drag = FxDrag.IMMEDIATE
                    longPressTimeout = 0L
                }
                persist(null)
                systemHost(appContext) {
                    // 调用方已在 canShow 校验权限，这里跳过申请（避免后台弹页失败）
                    permission(FxPermissionStrategy.Skip)
                }
            }
            c.addListener(listener)
            locked = false
            snapEdge = null
            longPressConsumed = false
            control = c
            c.show()
            renderLabel()
            startArrowAnimation()
        }
    }

    /** 刷新球上的进度文本（两位小数）。会按当前贴边方向重新排版。 */
    fun update(percentText: String) {
        rawLabelText = percentText
        if (Looper.myLooper() == Looper.getMainLooper()) {
            renderLabel()
        } else {
            mainHandler.post { renderLabel() }
        }
    }

    /** 移除悬浮球。任务结束/取消/异常都必须调用。 */
    fun dismiss() {
        mainHandler.post {
            mainHandler.removeCallbacks(snapRunnable)
            stopArrowAnimation()
            control = null
            onBubbleClick = null
            locked = false
            snapEdge = null
            longPressConsumed = false
            FloatingX.uninstall(TAG)
        }
    }

    // ── FloatingX 事件 ──

    private val listener = object : FxListener {
        override fun onDragStart(control: FxControl) {
            cancelSnapCountdown()
            longPressConsumed = false
            // 从贴边态开始拖动：立即恢复正常横排显示并恢复箭头动画
            if (snapEdge != null) {
                snapEdge = null
                renderLabel()
                startArrowAnimation()
            }
        }

        override fun onDragEnd(control: FxControl, x: Float, y: Float) {
            // 松手后先把球拉回屏内（拖动期间可能被拖出左右边界）
            clampIntoBounds(control)
            // 未锁定 + 停在贴近边缘处 → 5 秒后自动贴边
            if (!locked && nearHorizontalEdge(control)) scheduleSnap()
        }

        override fun onClick(control: FxControl, view: View) {
            // 长按已消费本次手势：松手时不再当作点击
            if (longPressConsumed) {
                longPressConsumed = false
                return
            }
            if (snapEdge != null) {
                unsnap(control)
                return
            }
            onBubbleClick?.invoke()
        }

        override fun onLongClick(control: FxControl, view: View) {
            // 贴边态长按无效：需先点击取消贴边，再长按锁定
            if (snapEdge != null) return
            longPressConsumed = true
            cancelSnapCountdown()
            locked = !locked
            applyLockState(control)
        }
    }

    /** 应用锁定状态：锁定 → 禁用拖动并取消倒计时；解锁 → 恢复拖动。 */
    private fun applyLockState(c: FxControl) {
        if (locked) {
            cancelSnapCountdown()
            c.update { gesture { drag = FxDrag.DISABLED } }
        } else {
            c.update { gesture { drag = FxDrag.IMMEDIATE } }
        }
        updateLockBadge()
    }

    /** 同步锁图标可见性。 */
    private fun updateLockBadge() {
        labelViewById(R.id.fx_bubble_lock)?.visibility = if (locked) View.VISIBLE else View.GONE
    }

    /**
     * 实时从当前 control 的内容视图查找子 view。
     *
     * 不能缓存 view 引用：FloatingX 在窗口（重）建时会替换 content view，
     * 旧引用会 detached 失效，导致后续 setText 不生效（表现为进度卡死）。
     */
    private fun labelViewById(id: Int): TextView? =
        control?.contentView?.findViewById(id)

    // ── 上传箭头动画 ──

    /**
     * 启动球内上传箭头的循环动画：箭头从球体下方进入（渐显），沿垂直轴向上平移穿过球心，
     * 到球体上方时渐隐，随后回到起点循环。alpha 与位移由同一个 [android.animation.ValueAnimator]
     * 的 0..1 进度驱动。
     *
     * 与标签同样不缓存 view 引用：FloatingX 重建窗口会替换 content view，
     * 因此动画的每帧回调都从当前 [control] 重新查找箭头 view。
     */
    private fun startArrowAnimation() {
        stopArrowAnimation()
        setArrowVisible(true)
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ARROW_ANIM_DURATION_MS
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                val arrow = control?.contentView?.findViewById<android.widget.ImageView>(R.id.fx_bubble_arrow)
                    ?: return@addUpdateListener
                val fraction = anim.animatedValue as Float
                // 从球心下方 (+0.5×行程) 平移到上方 (-0.5×行程)，穿过球体
                arrow.translationY = (0.5f - fraction) * ARROW_TRAVEL_FACTOR * arrow.height
                // 进入/穿出两端渐显渐隐，中段保持峰值，避免生硬闪现
                arrow.alpha = ARROW_MAX_ALPHA * arrowAlphaFactor(fraction)
            }
        }
        arrowAnimator = animator
        animator.start()
    }

    /** 停止并释放上传箭头动画，并隐藏箭头（贴边态不显示箭头）。 */
    private fun stopArrowAnimation() {
        arrowAnimator?.cancel()
        arrowAnimator = null
        setArrowVisible(false)
    }

    /**
     * 设置箭头可见性。贴边时箭头必须隐藏：`ValueAnimator.cancel()` 会冻结在当前帧，
     * 若只 cancel 不隐藏，箭头会以半透明残留在半隐后的可见半边（表现为"卡住的半截图标"）。
     */
    private fun setArrowVisible(visible: Boolean) {
        control?.contentView
            ?.findViewById<android.widget.ImageView>(R.id.fx_bubble_arrow)
            ?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * 进度对应的透明度因子（0..1）：前 [ARROW_FADE_IN_FRACTION] 从 0 渐显，
     * 后 [ARROW_FADE_OUT_FRACTION] 渐隐，中间保持 1。
     */
    private fun arrowAlphaFactor(fraction: Float): Float = when {
        fraction <= 0f -> 0f
        fraction >= 1f -> 0f
        fraction < ARROW_FADE_IN_FRACTION -> fraction / ARROW_FADE_IN_FRACTION
        fraction > 1f - ARROW_FADE_OUT_FRACTION -> (1f - fraction) / ARROW_FADE_OUT_FRACTION
        else -> 1f
    }

    // ── 贴边 ──

    /** 启动 5 秒贴边倒计时。 */
    private fun scheduleSnap() {
        mainHandler.removeCallbacks(snapRunnable)
        mainHandler.postDelayed(snapRunnable, SNAP_DELAY_MS)
    }

    /** 取消贴边倒计时。拖动/点击/长按/移除时调用。 */
    private fun cancelSnapCountdown() {
        mainHandler.removeCallbacks(snapRunnable)
    }

    /** 倒计时到期：把球半隐贴到最近的左/右边缘。 */
    private fun performSnap() {
        val c = control ?: return
        if (locked) return
        val size = contentSize(c) ?: return
        val area = c.host.bounds().rect
        if (area.width <= 0f) return

        val cur = c.position
        val distLeft = cur.x - area.left
        val distRight = area.right - (cur.x + size.first)
        val edge = if (distLeft <= distRight) FxEdge.START else FxEdge.END

        val x = when (edge) {
            FxEdge.START -> area.left - size.first * HALF_HIDE
            FxEdge.END -> area.right - size.first + size.first * HALF_HIDE
            else -> return
        }
        snapEdge = edge
        stopArrowAnimation()
        renderLabel()
        c.moveTo(x, cur.y, animate = true)
    }

    /** 取消贴边：把球移动回屏内完整可见的位置，并恢复箭头动画。 */
    private fun unsnap(c: FxControl) {
        snapEdge = null
        renderLabel()
        startArrowAnimation()
        clampIntoBounds(c, animate = true)
    }

    /** 把球限制回屏幕可用区内（完整可见）。 */
    private fun clampIntoBounds(c: FxControl, animate: Boolean = false) {
        val size = contentSize(c) ?: return
        val area = c.host.bounds().rect
        if (area.width <= 0f || area.height <= 0f) return
        val cur = c.position
        val x = cur.x.coerceIn(area.left, area.right - size.first)
        val y = cur.y.coerceIn(area.top, area.bottom - size.second)
        if (x == cur.x && y == cur.y) return
        c.moveTo(x, y, animate)
    }

    /** 球是否贴近屏幕左/右边缘（用于触发贴边）。 */
    private fun nearHorizontalEdge(c: FxControl): Boolean {
        val size = contentSize(c) ?: return false
        val area = c.host.bounds().rect
        if (area.width <= 0f) return false
        val threshold = EDGE_TRIGGER_DP * c.host.context.resources.displayMetrics.density
        val cur = c.position
        val distLeft = cur.x - area.left
        val distRight = area.right - (cur.x + size.first)
        return distLeft <= threshold || distRight <= threshold
    }

    // ── 文字排版 ──

    /** 内容 view 的实际宽高（未测量时返回 null）。 */
    private fun contentSize(c: FxControl): Pair<Float, Float>? {
        val v = c.contentView ?: return null
        val w = v.width.toFloat()
        val h = v.height.toFloat()
        return if (w > 0f && h > 0f) w to h else null
    }

    /**
     * 按当前贴边方向渲染文本。
     *
     * 关键点：`label` 是 match_parent（占满整个球），半隐贴边后屏幕只露出靠内的半边。
     * 若仅用 gravity 靠边对齐，竖排数字会贴到整球的边缘、落到被裁掉的屏外半边。
     * 因此贴边时统一用 [Gravity.CENTER] 让竖排数字列在每个字符行内居中，再通过
     * [TextView.setTranslationX] 把整列从"整球中心"平移到"露出半球的中心"：
     * - 露出右半（贴左 START，球左半移出屏）→ 向右平移 1/4 球宽；
     * - 露出左半（贴右 END，球右半移出屏）→ 向左平移 1/4 球宽。
     * 垂直方向始终保持整球居中，使数字列关于圆心上下对称。
     *
     * 未贴边时复位平移，横排居中显示完整进度文本（如 `63.73`）。
     */
    private fun renderLabel() {
        val label = labelViewById(R.id.fx_bubble_label) ?: return
        label.gravity = Gravity.CENTER
        when (snapEdge) {
            FxEdge.START, FxEdge.END -> {
                label.textSize = SNAPPED_TEXT_SIZE
                label.text = verticalLabelText()
                // 整球中心 → 露出半球中心的水平位移 = 半个球宽的一半 = 球宽的 1/4
                val width = label.width.takeIf { it > 0 }?.toFloat() ?: rootWidth()
                val quarter = width * 0.25f
                label.translationX =
                    if (snapEdge == FxEdge.START) quarter else -quarter
            }
            else -> {
                label.textSize = UN_SNAPPED_TEXT_SIZE
                label.text = rawLabelText
                label.translationX = 0f
            }
        }
    }

    /** 球内容根的宽度（用于 label 尚未测量时估算半宽位移）。 */
    private fun rootWidth(): Float =
        control?.contentView?.width?.toFloat() ?: 0f

    /**
     * 贴边竖排文本：只取进度整数位，逐位数字各占一行、每行水平居中，形成对齐的数字列。
     * 例：`56.73` → `5` / `6`；`100` → `1` / `0` / `0`；`7` → `7`。
     * 非数字（扫描阶段文件数本身为整数，同样逐位竖排）。
     */
    private fun verticalLabelText(): CharSequence {
        val intPart = rawLabelText.substringBefore('.').filter { it.isDigit() }
            .ifEmpty { "0" }
        if (intPart.length <= 1) return intPart
        val spannable = SpannableString(intPart.toCharArray().joinToString("\n"))
        var start = 0
        for (ch in intPart) {
            spannable.setSpan(
                AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                start, start + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            start += 2
        }
        return spannable
    }
}
