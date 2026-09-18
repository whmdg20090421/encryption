package com.whmdg.mczj.tools.ui.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.whmdg.mczj.tools.ui.theme.工具箱Theme
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 独立的音频播放 Activity，与 VideoPlayerActivity（视频）、ViewerActivity（图片/文本）并列。
 *
 * 使用 Media3 ExoPlayer 播放音频，支持显示封面。
 */
class AudioPlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "file_path"

        fun createAudioIntent(context: Context, filePath: String): Intent {
            return Intent(context, AudioPlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_FILE_PATH, filePath)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置窗口大小：宽80%，高自适应
        window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels

            val maxWidth = (screenWidth * 0.8).toInt()

            window.setLayout(maxWidth, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            window.setGravity(android.view.Gravity.CENTER)
        }

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: run { finish(); return }

        val isDarkMode = getSharedPreferences("theme_prefs", MODE_PRIVATE)
            .getBoolean("is_dark_mode", true)

        // 计算最大高度（屏幕的70%）
        val maxHeight = (resources.displayMetrics.heightPixels * 0.7).toInt()

        setContent {
            工具箱Theme(darkTheme = isDarkMode) {
                AudioPlayerScreen(
                    filePath = filePath,
                    isDarkMode = isDarkMode,
                    maxHeight = maxHeight,
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
private fun AudioPlayerScreen(
    filePath: String,
    isDarkMode: Boolean,
    maxHeight: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val fileName = remember(filePath) { File(filePath).nameWithoutExtension }

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var artworkUri by remember { mutableStateOf<Uri?>(null) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(resolveMediaUri(filePath)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    duration = player.duration
                    artworkUri = player.currentMediaItem?.mediaMetadata?.artworkUri
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(isPlaying) {
        while (true) {
            if (!isSeeking) {
                currentPosition = player.currentPosition
            }
            delay(100)
        }
    }

    // 根据主题设置背景色和文字颜色
    val backgroundColor = if (isDarkMode) Color.Black else Color.White
    val contentColor = if (isDarkMode) Color.White else Color.Black
    val iconTint = contentColor
    val secondaryColor = if (isDarkMode) Color.LightGray else Color.DarkGray

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight.dp)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── 标题行 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = iconTint
                    )
                }
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                // 占位，保持标题居中
                IconButton(onClick = {}, enabled = false) {}
            }

            // ── 内容区域（封面） ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (artworkUri != null) {
                    AsyncImage(
                        model = artworkUri,
                        contentDescription = "封面",
                        modifier = Modifier
                            .size(200.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(CircleShape)
                            .background(secondaryColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = if (isDarkMode) Color.LightGray else Color.Gray
                        )
                    }
                }
            }

            // ── 进度条行 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = formatTime(if (isSeeking) (seekPosition * duration).toLong() else currentPosition),
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(40.dp)
                )
                Slider(
                    value = if (isSeeking) seekPosition else {
                        if (duration > 0) currentPosition.toFloat() / duration else 0f
                    },
                    onValueChange = { value ->
                        isSeeking = true
                        seekPosition = value
                    },
                    onValueChangeFinished = {
                        val target = (seekPosition * duration).toLong()
                        player.seekTo(target)
                        currentPosition = target
                        isSeeking = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = contentColor,
                        activeTrackColor = contentColor
                    )
                )
                Text(
                    text = formatTime(duration),
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(40.dp)
                )
            }

            // ── 播放/暂停按钮 ──
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = {
                    if (isPlaying) player.pause() else player.play()
                }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(64.dp),
                        tint = iconTint
                    )
                }
            }
        }
    }
}

private fun resolveMediaUri(filePath: String): Uri {
    val uri = Uri.parse(filePath)
    return if (uri.scheme.isNullOrEmpty()) Uri.fromFile(File(filePath)) else uri
}

private fun formatTime(ms: Long): String {
    if (ms < 0) return "0:00"
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
