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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

    // 播放失败信息（null = 无错误）
    var error by remember { mutableStateOf<PlaybackErrorInfo?>(null) }
    // .ts 播放失败时询问是否改用文本编辑器打开
    var showTextFallbackDialog by remember { mutableStateOf(false) }

    LaunchedEffect(controller) {
        controller.events.collect { event ->
            if (event is GSYPlayerEvent.Error) {
                error = PlaybackErrorInfo(event.what, event.extra)
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

        error?.let { info ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.85f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = info.summary,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = info.detail,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
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
 * 播放失败信息：summary 为分类后的中文提示，detail 为原始错误码明细。
 *
 * GSY Exo2 内核在 onPlayerError 中原样透传 PlaybackException.errorCode 作为 what，
 * extra 固定为 IMediaPlayer.MEDIA_ERROR_UNKNOWN，因此按 Media3 错误码精确分类。
 */
private data class PlaybackErrorInfo(val what: Int, val extra: Int) {

    val summary: String = when (what) {
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

    /** 错误码明细：优先使用中文说明，未命中匹配时回退为英文原文。 */
    val detail: String = "${errorCodeDescription(what)} ($what)\nextra: $extra"

    private fun errorCodeDescription(code: Int): String = when (code) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "容器格式损坏，无法解析"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "容器格式不受支持"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "清单文件损坏，无法解析"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "清单文件格式不受支持"

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器初始化失败"
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "解码器查询失败"
        PlaybackException.ERROR_CODE_DECODING_FAILED -> "音视频解码失败"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "编码规格超出设备解码能力"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "解码格式不受支持"

        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "音频轨道初始化失败"
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "音频轨道写入失败"

        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "输入/输出错误（未指明）"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接超时"
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "HTTP 响应内容类型无效"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "HTTP 状态码异常"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件不存在"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "没有读取权限"
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "禁止明文网络传输"
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "读取位置超出文件范围"

        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> "DRM 错误（未指明）"
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED -> "DRM 方案不受支持"
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED -> "DRM 设备预配置失败"
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR -> "DRM 内容错误"
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED -> "DRM 许可证获取失败"
        PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION -> "DRM 操作不被允许"
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR -> "DRM 系统错误"
        PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED -> "DRM 设备已被吊销"
        PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED -> "DRM 许可证已过期"

        PlaybackException.ERROR_CODE_UNSPECIFIED -> "未指明的错误"
        PlaybackException.ERROR_CODE_REMOTE_ERROR -> "远端返回错误"
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "直播进度落后于窗口"
        PlaybackException.ERROR_CODE_TIMEOUT -> "操作超时"
        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK -> "运行时检查失败"

        else -> "UNKNOWN"
    }
}
