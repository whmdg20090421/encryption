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
import com.petterp.floatingx.core.layout.FxHalfHide
import com.petterp.floatingx.core.update
import com.petterp.floatingx.system.SystemHost
import com.petterp.floatingx.system.permission.FxPermissionStrategy
import com.whmdg.mczj.tools.others.R

/**
 * 云盘同步的系统级悬浮球，基于 FloatingX `SystemHost`（`TYPE_APPLICATION_OVERLAY`）。
 *
 * 仅当用户授予"显示在其他应用上层"（[Settings.canDrawOverlays]）时创建；否则调用方退回应用内
 * 悬浮球。球在所有应用之上显示，并让本进程被系统视为前台，为后台上传提供额外保活。
 *
 * 交互（全部由 FloatingX 的 [FxListener] 驱动，不再自研触摸/贴边逻辑）：
 * - 拖动：FloatingX 内置，位移超过系统 slop 才判定为拖动；
 * - 点击：未贴边 → 拉回本应用并展开进度弹窗；贴边 → 仅取消贴边（球恢复完整显示）；
 * - 长按：使用系统默认长按超时（`longPressTimeout = 0`），切换锁定状态；锁定后禁用拖动与吸附，
 *   右下角显示 🔒；
 * - 自动贴边：松手后就近吸附到左/右边缘，[FxHalfHide] 隐藏一半（50%），文字落在露出的半边
 *   （贴左→靠右对齐，贴右→靠左对齐），并按方向切换竖排/横排；
 * - 贴边态长按无效：贴边时 [FxListener.onLongClick] 直接忽略，只有未贴边时才可锁定/解锁。
 *
 * 一个进程内同时至多一个球（上传/下载互斥）。FloatingX 的所有 API 必须在主线程调用，
 * 本对象对外方法内部统一切主线程执行。
 */
object SyncOverlayBubble {

    /** FloatingX 注册表 tag，同 tag 重复 install 会先 cancel 旧的。 */
    private const val TAG = "sync-overlay-bubble"

    /** 贴边判定容差（px）：内容边缘超出屏幕边界超过此值即视为贴边。 */
    private const val SNAPPED_TOLERANCE_PX = 2f

    private val mainHandler = Handler(Looper.getMainLooper())

    private var control: FxControl? = null
    private var labelView: TextView? = null
    private var lockView: TextView? = null

    private var onBubbleClick: (() -> Unit)? = null

    /** 是否处于锁定态（锁定后禁用拖动与吸附，右下角显示锁图标）。 */
    private var locked = false

    /** 原始进度文本（未按贴边方向排版）。 */
    private var rawLabelText: String = "0.00%"

    /** 当前贴边方向：null 表示未贴边。 */
    private var snapEdge: FxEdge? = null

    /** 是否已授予悬浮窗权限。 */
    fun canShow(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context.applicationContext)

    /**
     * 显示悬浮球。[onClick] 在"可点击"状态下触发（贴边态下第一次点击用于解除贴边）。
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
                adsorb(FxAdsorb.Edges(setOf(FxEdge.START, FxEdge.END), halfHide = FxHalfHide(0.5f)))
                gesture {
                    click = true
                    longPress = true
                    drag = FxDrag.IMMEDIATE
                    longPressTimeout = 0L
                }
                storage(null)
                systemHost(appContext) {
                    // 调用方已在 canShow 校验权限，这里跳过申请（避免后台弹页失败）
                    permission(FxPermissionStrategy.Skip)
                }
            }
            c.addListener(listener)
            bindContent(c)
            locked = false
            snapEdge = null
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
            control = null
            labelView = null
            lockView = null
            onBubbleClick = null
            locked = false
            snapEdge = null
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
        override fun onClick(control: FxControl, view: View) {
            if (isSnapped(control)) {
                unsnap(control)
                return
            }
            onBubbleClick?.invoke()
        }

        override fun onLongClick(control: FxControl, view: View) {
            // 贴边态长按无效：需先点击取消贴边，再长按锁定
            if (isSnapped(control)) return
            locked = !locked
            applyLockState(control)
        }

        override fun onPositionChanged(control: FxControl, anchor: FxAnchor) {
            val edge = snappedEdge(control)
            if (edge != snapEdge) {
                snapEdge = edge
                renderLabel()
            }
        }
    }

    /** 应用锁定状态：锁定 → 禁用拖动、取消吸附并解除当前贴边；解锁 → 恢复拖动与吸附。 */
    private fun applyLockState(c: FxControl) {
        if (locked) {
            c.update {
                gesture {
                    drag = FxDrag.DISABLED
                    longPress = true
                }
                adsorb(FxAdsorb.None)
            }
            if (isSnapped(c)) unsnap(c)
        } else {
            c.update {
                gesture { drag = FxDrag.IMMEDIATE }
                adsorb(FxAdsorb.Edges(setOf(FxEdge.START, FxEdge.END), halfHide = FxHalfHide(0.5f)))
            }
        }
        updateLockBadge()
    }

    /** 取消贴边：把球移动回屏内完整可见的位置。 */
    private fun unsnap(c: FxControl) {
        val size = contentSize(c) ?: return
        val bounds = c.host.bounds().rect
        if (bounds.width <= 0f || bounds.height <= 0f) return
        val cur = c.position
        val x = when {
            cur.x < bounds.left -> bounds.left
            cur.x + size.first > bounds.right -> bounds.right - size.first
            else -> cur.x
        }
        val y = when {
            cur.y < bounds.top -> bounds.top
            cur.y + size.second > bounds.bottom -> bounds.bottom - size.second
            else -> cur.y
        }
        snapEdge = null
        renderLabel()
        c.moveTo(x, y, animate = true)
    }

    /** 同步锁图标可见性。 */
    private fun updateLockBadge() {
        lockView?.visibility = if (locked) View.VISIBLE else View.GONE
    }

    // ── 贴边判定与文字排版 ──

    /** 当前球是否被吸附到屏幕外（半隐）。 */
    private fun isSnapped(c: FxControl): Boolean = snappedEdge(c) != null

    /**
     * 判定球当前贴在左/右哪条边：内容有任意部分伸出屏幕左/右边界即为贴边。
     * 未贴边返回 null。上下贴边不半隐（[FxHalfHide] 仅左右生效），此处不识别。
     */
    private fun snappedEdge(c: FxControl): FxEdge? {
        val size = contentSize(c) ?: return null
        val bounds = c.host.bounds().rect
        if (bounds.width <= 0f) return null
        val x = c.position.x
        return when {
            x + size.first > bounds.right + SNAPPED_TOLERANCE_PX -> FxEdge.END
            x < bounds.left - SNAPPED_TOLERANCE_PX -> FxEdge.START
            else -> null
        }
    }

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
