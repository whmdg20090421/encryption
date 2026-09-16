package com.whmdg.mczj.tools.ui.viewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.media3.common.PlaybackException
import com.shuyu.gsyvideoplayer.compose.native_.GSYComposePlayer
import com.shuyu.gsyvideoplayer.compose.native_.GSYPlayerEvent
import com.shuyu.gsyvideoplayer.compose.native_.rememberGSYPlayerController
import com.shuyu.gsyvideoplayer.player.PlayerFactory
import com.whmdg.mczj.tools.ui.theme.工具箱Theme
import tv.danmaku.ijk.media.exo2.Exo2PlayerManager

/**
 * 独立的视频播放 Activity，与 ViewerActivity（图片 / 文本）并列。
 *
 * 采用 GSYVideoPlayer 的 Compose Native 模式，全部使用默认参数与控制条，
 * 不自定义 UI；仅在播放失败时按 Media3 错误码精确分类给出提示。
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

        // 使用 Exo2（Media3）内核
        PlayerFactory.setPlayManager(Exo2PlayerManager::class.java)

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
    val controller = rememberGSYPlayerController(
        url = filePath,
        autoPlay = true
    )

    // 播放失败提示（null = 无错误）
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // .ts 播放失败时询问是否改用文本编辑器打开
    var showTextFallbackDialog by remember { mutableStateOf(false) }

    LaunchedEffect(controller) {
        controller.events.collect { event ->
            if (event is GSYPlayerEvent.Error) {
                errorMessage = describePlaybackError(event.what)
                // .ts 是 TS 视频与 TypeScript 源码的共用后缀；播放失败时提供文本编辑器兜底
                if (filePath.substringAfterLast('.', "").equals("ts", ignoreCase = true)) {
                    showTextFallbackDialog = true
                }
            }
        }
    }

    BackHandler { onBack() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        GSYComposePlayer(
            controller = controller,
            modifier = Modifier.fillMaxSize(),
            showDefaultControls = true
        )

        errorMessage?.let { message ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.8f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = message,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge
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
 * 按 Media3 PlaybackException 错误码精确分类。
 *
 * GSY Exo2 内核在 onPlayerError 中原样透传 PlaybackException.errorCode 作为 what，
 * 因此这里可直接与 PlaybackException 常量比对，而非捕获通用异常。
 */
private fun describePlaybackError(what: Int): String = when (what) {
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "该视频格式暂不支持播放"

    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件不存在或无法访问"
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接失败"
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "读取文件失败"

    else -> "播放失败"
}
