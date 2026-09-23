package com.whmdg.mczj.tools.fileop.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import com.petterp.floatingx.system.SystemHost
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

    private val mainHandler = Handler(Looper.getMainLooper())

    private var control: FxControl? = null
    private var labelView: TextView? = null
    private var lockView: TextView? = null

    private var onBubbleClick: (() -> Unit)? = null

    /** 是否处于锁定态（锁定后禁用拖动与贴边，右下角显示锁图标）。 */
    private var locked = false

    /** 原始进度文本（未按贴边方向排版）。 */
    private var rawLabelText: String = "0.00%"

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
                bindContent(existing)
                renderLabel()
                if (!existing.isShowing) existing.show()
                return@post
            }

            val appContext = context.applicationContext
            val c = FloatingX.install(TAG) {
                content(FxContent.layout(R.layout.fx_sync_bubble))
                anchor(FxGravity.TOP_END, dx = 0f, dy = 0f)
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
            bindContent(c)
            locked = false
            snapEdge = null
            longPressConsumed = false
            control = c
            renderLabel()
            c.show()
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
            control = null
            labelView = null
            lockView = null
            onBubbleClick = null
            locked = false
            snapEdge = null
            longPressConsumed = false
            FloatingX.uninstall(TAG)
        }
    }

    /** 绑定内容里的 TextView 引用。 */
    private fun bindContent(c: FxControl) {
        c.updateContent { holder ->
            labelView = holder.getViewOrNull(R.id.fx_bubble_label)
            lockView = holder.getViewOrNull(R.id.fx_bubble_lock)
        }
        updateLockBadge()
    }

    // ── FloatingX 事件 ──

    private val listener = object : FxListener {
        override fun onDragStart(control: FxControl) {
            cancelSnapCountdown()
            longPressConsumed = false
            // 从贴边态开始拖动：立即恢复正常横排显示
            if (snapEdge != null) {
                snapEdge = null
                renderLabel()
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
        lockView?.visibility = if (locked) View.VISIBLE else View.GONE
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
        renderLabel()
        c.moveTo(x, cur.y, animate = true)
    }

    /** 取消贴边：把球移动回屏内完整可见的位置。 */
    private fun unsnap(c: FxControl) {
        snapEdge = null
        renderLabel()
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
     * 按当前贴边方向渲染文本：
     * - 贴左（START 边，整个球被推到屏幕左侧外）：文字靠右对齐，落在露出的右半边；
     * - 贴右（END 边）：文字靠左对齐，落在露出的左半边；
     * - 未贴边：横排居中，显示完整进度文本（如 `63.73%`）。
     *
     * 贴边时可见区域只有半条约 25dp 宽，横排放不下完整文本；改用竖排三行，以小数点为界分成
     * 整数部分 / 小数点 / 小数部分，例如 `63.73%` → `63` · `·` · `73`。
     */
    private fun renderLabel() {
        val label = labelView ?: return
        when (snapEdge) {
            FxEdge.START -> {
                label.gravity = Gravity.CENTER_VERTICAL or Gravity.END
                label.textSize = 11f
                label.text = verticalLabelText()
            }
            FxEdge.END -> {
                label.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                label.textSize = 11f
                label.text = verticalLabelText()
            }
            else -> {
                label.gravity = Gravity.CENTER
                label.textSize = 13f
                label.text = rawLabelText
            }
        }
    }

    /**
     * 竖排文本：以小数点为界，整数部分 / 小数点 / 小数部分各占一行。
     * 纯整数（无小数点，如扫描阶段的文件数）则原样逐字竖排。
     * 例：`63.73%` → `63`、`·`、`73`；`128` → `1`、`2`、`8`。
     */
    private fun verticalLabelText(): String {
        val text = rawLabelText
        val dot = text.indexOf('.')
        if (dot < 0) return text.toCharArray().joinToString("\n")
        val intPart = text.substring(0, dot).takeIf { it.isNotEmpty() } ?: "0"
        val decimalPart = text.substring(dot + 1).takeWhile { it.isDigit() }
        return if (decimalPart.isEmpty()) {
            intPart
        } else {
            "$intPart\n·\n$decimalPart"
        }
    }
}
