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

    // 播放失败信息（null = 无错误）
    var error by remember { mutableStateOf<PlaybackErrorInfo?>(null) }
    // .ts 播放失败时询问是否改用文本编辑器打开
    var showTextFallbackDialog by remember { mutableStateOf(false) }

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

    BackHandler { onBack() }

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
                }
            },
            update = { view ->
                if (error != null) {
                    // 出错：停止渲染并把控件与加载动画全部隐藏
                    view.hideController()
                    view.controllerAutoShow = false
                    view.useController = false
                    view.visibility = android.view.View.INVISIBLE
                }
            }
        )

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
