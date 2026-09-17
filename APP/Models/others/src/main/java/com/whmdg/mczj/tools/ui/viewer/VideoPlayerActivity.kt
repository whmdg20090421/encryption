package com.whmdg.mczj.tools.ui.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.whmdg.mczj.tools.others.R
import com.whmdg.mczj.tools.ui.theme.工具箱Theme
import java.io.File

/**
 * 独立的视频播放 Activity，与 ViewerActivity（图片 / 文本）并列。
 *
 * 直接使用 Media3 ExoPlayer，不经过 GSYVideoPlayer：
 * - 播放失败时可拿到完整的 [PlaybackException]，输出真实错误码 / 错误名 / 原始异常链（报错原文）
 * - 完全掌控 UI 层：出错时隐藏播放器控件与加载动画，避免残留转圈图标
 * - 每个 Activity 独立持有 ExoPlayer 实例，不会与其它实例争抢全局单例
 */
class VideoPlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "file_path"

        fun createVideoIntent(context: Context, filePath: String): Intent {
            return Intent(context, VideoPlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_FILE_PATH, filePath)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: run { finish(); return }

        val isDarkMode = getSharedPreferences("theme_prefs", MODE_PRIVATE)
            .getBoolean("is_dark_mode", true)

        setContent {
            工具箱Theme(darkTheme = isDarkMode) {
                VideoPlayerScreen(
                    filePath = filePath,
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
private fun VideoPlayerScreen(
    filePath: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // 顶部栏需要避开系统状态栏（时间/WiFi 那条常驻栏），向下偏移其高度
    val statusBarHeight = androidx.compose.foundation.layout.WindowInsets.statusBars
        .getTop(androidx.compose.ui.platform.LocalDensity.current)

    // 播放失败信息（null = 无错误）
    var error by remember { mutableStateOf<PlaybackErrorInfo?>(null) }
    // .ts 播放失败时询问是否改用文本编辑器打开
    var showTextFallbackDialog by remember { mutableStateOf(false) }
    // 横向滑动拖进度时显示的预览文本（null = 未在滑动）
    var seekPreview by remember { mutableStateOf<String?>(null) }

    // 每个 Composable 持有自己的 ExoPlayer，退出时释放
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(resolveMediaUri(filePath)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = PlaybackErrorInfo(e)
                // .ts 是 TS 视频与 TypeScript 源码的共用后缀；播放失败时提供文本编辑器兜底
                if (filePath.substringAfterLast('.', "").equals("ts", ignoreCase = true)) {
                    showTextFallbackDialog = true
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // 横屏（全屏）时返回键先退出全屏，竖屏时才关闭页面
    BackHandler {
        if (isLandscape) {
            (context as? android.app.Activity)?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // 出错后由 Compose 覆盖层接管；播放器控件在错误态下也一并隐藏
                    useController = true
                    // 注册全屏回调（注册后全屏按钮才会显示）；点击时切换横竖屏并更新图标
                    setFullscreenButtonClickListener { enterFullscreen ->
                        val activity = ctx as? android.app.Activity ?: return@setFullscreenButtonClickListener
                        activity.requestedOrientation = if (enterFullscreen) {
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        } else {
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                        setFullscreenButtonState(enterFullscreen)
                    }

                    // 顶部栏：左侧返回按钮退出到文件管理器，中间显示视频文件名
                    findViewById<android.view.View>(R.id.video_back_button)
                        ?.setOnClickListener { onBack() }
                    findViewById<android.widget.TextView>(R.id.video_title)?.text =
                        File(filePath).name

                    // 画面横向滑动拖进度：滑动时间 = 距离dp × 系数 × 速度倍率
                    attachSeekGesture(
                        player = player,
                        onPreview = { seekPreview = it },
                        onPreviewEnd = { seekPreview = null }
                    )
                }
            },
            update = { view ->
                // 顶栏避开状态栏：设置 topMargin 为状态栏高度
                view.findViewById<android.view.View>(R.id.exo_top_controls)?.let { top ->
                    (top.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                        if (lp.topMargin != statusBarHeight) {
                            lp.topMargin = statusBarHeight
                            top.layoutParams = lp
                        }
                    }
                }
                // 根据当前方向同步全屏按钮图标（系统旋转或返回键退出全屏后也保持一致）
                view.setFullscreenButtonState(isLandscape)
                if (error != null) {
                    // 出错：停止渲染并把控件与加载动画全部隐藏
                    view.hideController()
                    view.controllerAutoShow = false
                    view.useController = false
                    view.visibility = android.view.View.INVISIBLE
                }
            }
        )

        // 横向滑动拖进度时，画面中央显示目标时间预览
        seekPreview?.let { preview ->
            Text(
                text = preview,
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }

        error?.let { info ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.9f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "无法播放该视频",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = info.report,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Start,
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onBack) { Text("返回") }
            }
        }
    }

    if (showTextFallbackDialog) {
        AlertDialog(
            onDismissRequest = { showTextFallbackDialog = false },
            title = { Text("无法作为视频播放") },
            text = { Text("该 .ts 文件无法作为视频播放，它可能是文本文件（如 TypeScript 源码）。是否使用文本编辑器打开？") },
            confirmButton = {
                TextButton(onClick = {
                    showTextFallbackDialog = false
                    context.startActivity(ViewerActivity.createTextIntent(context, filePath))
                    onBack()
                }) { Text("使用文本编辑器") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTextFallbackDialog = false
                    onBack()
                }) { Text("取消") }
            }
        )
    }
}

/**
 * 把文件系统绝对路径解析为 ExoPlayer 可用的 URI。
 *
 * 普通模式传入的是 `/storage/...` 形式的裸路径，统一补成 `file://` scheme 后再交给
 * Media3，避免不同设备对无 scheme 路径的解析差异。已经带 scheme 的直接原样返回。
 */
private fun resolveMediaUri(filePath: String): Uri {
    val uri = Uri.parse(filePath)
    return if (uri.scheme.isNullOrEmpty()) Uri.fromFile(File(filePath)) else uri
}

// 横向滑动拖进度的换算参数（真机手感可微调）
private const val SEEK_MS_PER_DP = 100f      // 基础系数：每滑动 1dp 对应的时间
private const val SEEK_SPEED_MIN = 0.5f      // 慢速滑动的速度倍率下限
private const val SEEK_SPEED_MAX = 3.0f      // 快速滑动的速度倍率上限
private const val SEEK_SPEED_REF = 1000f     // 速度倍率的参考速度（dp/秒）
private const val SEEK_MIN_TRIGGER_DP = 8f   // 最小触发距离（dp）：区分"点击弹控制条"与"滑动拖进度"
private const val SEEK_EDGE_RATIO = 0.15f    // 左右各 15% 为忽略区，仅中央 70% 宽度可起手滑动

/**
 * 给播放画面的左右滑动绑定拖进度手势：
 *
 *     拖动时间 = 滑动距离(dp) × [SEEK_MS_PER_DP] × 速度倍率
 *     速度倍率 = clamp(瞬时速度 / SEEK_SPEED_REF, SEEK_SPEED_MIN, SEEK_SPEED_MAX)
 *
 * - 快滑倍率高（拉得快），慢滑倍率低（便于精细微调），且始终与滑动距离成正比。
 * - 只有**起手点**落在水平中央 70% 区间内才识别为拖进度；左右各 15% 边缘区忽略。
 * - 水平位移需超过 [SEEK_MIN_TRIGGER_DP] 才触发，避免把"点一下弹控制条"误判为滑动。
 * - 滑动过程实时 seek，画面中央通过 [onPreview] 显示目标时间；松手调用 [onPreviewEnd]。
 * - 单击（未达阈值）不 Seek，事件继续交给 PlayerView，全屏任意位置都可弹控制条。
 */
private fun android.view.View.attachSeekGesture(
    player: ExoPlayer,
    onPreview: (String) -> Unit,
    onPreviewEnd: () -> Unit
) {
    val context = context
    val density = resources.displayMetrics.density
    val minTriggerPx = SEEK_MIN_TRIGGER_DP * density
    val gestureDetector = android.view.GestureDetector(
        context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {

            private var startPositionMs = 0L
            private var startX = 0f
            private var startY = 0f
            private var lastX = 0f
            private var lastTimeMs = 0L
            private var seeking = false
            // 起手点是否落在中央 70% 有效区（onDown 时判定）
            private var eligible = false

            override fun onDown(e: android.view.MotionEvent): Boolean {
                startPositionMs = player.currentPosition
                startX = e.x
                startY = e.y
                lastX = e.x
                lastTimeMs = System.currentTimeMillis()
                seeking = false
                val width = width.toFloat()
                eligible = width <= 0f ||
                    (e.x >= width * SEEK_EDGE_RATIO && e.x <= width * (1f - SEEK_EDGE_RATIO))
                return true
            }

            override fun onScroll(
                e1: android.view.MotionEvent?,
                e2: android.view.MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                e1 ?: return false
                // 起手点不在中央 70% 区域，忽略本次滑动
                if (!eligible) return false

                // 横向位移未达最小触发距离、或不够水平，交给 PlayerView 处理（保留单击行为）
                val dx = e2.x - startX
                val dy = e2.y - startY
                if (!seeking && (kotlin.math.abs(dx) < minTriggerPx || kotlin.math.abs(dx) <= kotlin.math.abs(dy))) {
                    return false
                }
                seeking = true

                val durationMs = player.duration.takeIf { it > 0L } ?: return true

                // 速度倍率按"瞬时速度"计算：本次相对上次移动的位移/时间差。
                // 一直慢拖 → 倍率低（精细微调）；突然快速甩动 → 倍率高（拉得快）。
                val now = System.currentTimeMillis()
                val stepDx = e2.x - lastX
                val elapsedSec = ((now - lastTimeMs).coerceAtLeast(1L)) / 1000f
                lastX = e2.x
                lastTimeMs = now
                val speedDpPerSec = kotlin.math.abs(stepDx) / density / elapsedSec
                val speedFactor = (speedDpPerSec / SEEK_SPEED_REF)
                    .coerceIn(SEEK_SPEED_MIN, SEEK_SPEED_MAX)

                val deltaMs = (dx / density * SEEK_MS_PER_DP * speedFactor).toLong()
                val target = (startPositionMs + deltaMs).coerceIn(0L, durationMs)
                player.seekTo(target)
                onPreview("${formatSeekMs(target - startPositionMs)}  ${formatSeekMs(target)}")
                return true
            }
        }
    )

    setOnTouchListener { _, event ->
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP ||
            event.actionMasked == android.view.MotionEvent.ACTION_CANCEL
        ) {
            onPreviewEnd()
        }
        // 始终返回 false：seeking 已在滑动手势里完成，
        // 事件继续交给 PlayerView 处理，保持"点一下弹控制条"的默认行为。
        false
    }
}

/** 把毫秒格式化为 `mm:ss` 或 `+mm:ss` / `-mm:ss`，用于滑动进度的预览文本。 */
private fun formatSeekMs(ms: Long): String {
    val sign = if (ms < 0) "-" else "+"
    val totalSec = kotlin.math.abs(ms) / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%s%02d:%02d".format(sign, m, s)
}


/**
 * 播放失败信息：直接输出 Media3 [PlaybackException] 的完整原文。
 *
 * 包含错误码、错误名（[PlaybackException.errorCodeName]）以及从异常链里提取的
 * 真实原因（异常类名 + message）；未知错误码也会如实打印数字，不再吞成 "UNKNOWN"。
 */
private class PlaybackErrorInfo(exception: PlaybackException) {

    val report: String = buildString {
        append("错误码: ").append(exception.errorCode)
        append("  (").append(exception.errorCodeName).append(")\n")
        append("错误类型: ").append(exception.javaClass.simpleName)
        exception.message?.let { append("\n错误信息: ").append(it) }

        // 沿异常链找到第一个非 PlaybackException 的根因
        var cause: Throwable? = exception.cause
        var depth = 0
        while (cause != null && depth < 8) {
            append("\n")
            append("原因[").append(depth).append("]: ")
                .append(cause.javaClass.name)
            cause.message?.let { append(": ").append(it) }
            cause = cause.cause
            depth++
        }
    }
}
