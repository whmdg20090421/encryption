package com.whmdg.mczj.tools.ui.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import com.whmdg.mczj.tools.util.AudioTagReader
import com.whmdg.mczj.tools.util.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
        const val EXTRA_AUDIO_PATHS = "audio_paths"
        const val EXTRA_START_INDEX = "start_index"

        /**
         * 创建音频播放器 Intent。
         *
         * @param filePath 当前音频路径（[audioPaths] 为空时作为单曲播放的唯一条目）
         * @param audioPaths 同目录音频播放列表，保持文件管理器中的显示顺序
         * @param startIndex [audioPaths] 中当前音频的索引，越界时回退为 0
         */
        fun createAudioIntent(
            context: Context,
            filePath: String,
            audioPaths: List<String> = emptyList(),
            startIndex: Int = 0
        ): Intent {
            return Intent(context, AudioPlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_FILE_PATH, filePath)
                putStringArrayListExtra(EXTRA_AUDIO_PATHS, ArrayList(audioPaths))
                putExtra(EXTRA_START_INDEX, startIndex)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val displayMetrics = resources.displayMetrics
        val windowWidth = (displayMetrics.widthPixels * 0.8).toInt()
        val windowHeight60 = (displayMetrics.heightPixels * 0.6).toInt()
        val windowHeight70 = (displayMetrics.heightPixels * 0.7).toInt()

        // 初始显示封面：宽 80%、高 60%
        window?.let { window ->
            window.setLayout(windowWidth, windowHeight60)
            window.setGravity(android.view.Gravity.CENTER)
        }

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: run { finish(); return }
        val playlist = intent.getStringArrayListExtra(EXTRA_AUDIO_PATHS).orEmpty()
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)

        val isDarkMode = getSharedPreferences("theme_prefs", MODE_PRIVATE)
            .getBoolean("is_dark_mode", true)

        setContent {
            工具箱Theme(darkTheme = isDarkMode) {
                AudioPlayerScreen(
                    initialFilePath = filePath,
                    playlist = playlist,
                    startIndex = startIndex,
                    isDarkMode = isDarkMode,
                    onBack = { finish() },
                    onShowLyricsChanged = { showLyrics ->
                        val height = if (showLyrics) windowHeight70 else windowHeight60
                        window?.setLayout(windowWidth, height)
                    }
                )
            }
        }
    }
}

/** 循环播放模式：顺序播放 → 单曲循环 → 随机播放，点按依次切换。 */
private enum class RepeatPlaybackMode { SEQUENTIAL, SINGLE, SHUFFLE }

@Composable
private fun AudioPlayerScreen(
    initialFilePath: String,
    playlist: List<String>,
    startIndex: Int,
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onShowLyricsChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current

    // 播放列表：传入为空时回退为单曲
    val mediaPaths = remember(initialFilePath, playlist) {
        if (playlist.isEmpty()) listOf(initialFilePath) else playlist
    }
    val initialIndex = remember(mediaPaths, startIndex, initialFilePath) {
        startIndex.takeIf { it in mediaPaths.indices }
            ?: mediaPaths.indexOf(initialFilePath).takeIf { it >= 0 }
            ?: 0
    }

    var currentMediaIndex by remember { mutableIntStateOf(initialIndex) }
    val currentFilePath = mediaPaths.getOrElse(currentMediaIndex) { initialFilePath }
    val currentFileName = remember(currentFilePath) { File(currentFilePath).nameWithoutExtension }

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var coverBytes by remember { mutableStateOf<ByteArray?>(null) }
    var lyrics by remember { mutableStateOf<List<LrcParser.LyricLine>>(emptyList()) }
    var showLyrics by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(RepeatPlaybackMode.SEQUENTIAL) }
    var hasPrevious by remember { mutableStateOf(false) }
    var hasNext by remember { mutableStateOf(false) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    // 曲目切换时重新读取封面与歌词。
    // 仅首次加载时根据内容自动决定视图；后续切歌保留用户当前的封面/歌词偏好。
    var isInitialTagLoad by remember { mutableStateOf(true) }
    LaunchedEffect(currentFilePath) {
        coverBytes = null
        lyrics = emptyList()
        val tags = withContext(Dispatchers.IO) { AudioTagReader.read(currentFilePath) }
        val parsedLyrics = LrcParser.parse(tags.lyrics)
        coverBytes = tags.coverBytes
        lyrics = parsedLyrics
        if (isInitialTagLoad) {
            showLyrics = tags.coverBytes == null && parsedLyrics.isNotEmpty()
            isInitialTagLoad = false
        } else if (showLyrics && parsedLyrics.isEmpty()) {
            // 新曲目无歌词时退回封面视图
            showLyrics = false
        }
    }

    // 切换封面/歌词时动态调整弹窗高度：封面 60%，歌词 70%
    LaunchedEffect(showLyrics) {
        onShowLyricsChanged(showLyrics)
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItems(
                mediaPaths.map { MediaItem.fromUri(resolveMediaUri(it)) },
                initialIndex,
                0L
            )
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
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentMediaIndex = player.currentMediaItemIndex
                hasPrevious = player.hasPreviousMediaItem()
                hasNext = player.hasNextMediaItem()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                hasPrevious = player.hasPreviousMediaItem()
                hasNext = player.hasNextMediaItem()
            }
        }
        player.addListener(listener)
        // 初始化导航状态
        hasPrevious = player.hasPreviousMediaItem()
        hasNext = player.hasNextMediaItem()
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

    // 循环模式变更时同步到播放器
    LaunchedEffect(repeatMode) {
        when (repeatMode) {
            RepeatPlaybackMode.SEQUENTIAL -> {
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_OFF
            }
            RepeatPlaybackMode.SINGLE -> {
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_ONE
            }
            RepeatPlaybackMode.SHUFFLE -> {
                player.shuffleModeEnabled = true
                player.repeatMode = Player.REPEAT_MODE_OFF
            }
        }
        hasPrevious = player.hasPreviousMediaItem()
        hasNext = player.hasNextMediaItem()
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
                .fillMaxSize()
                .padding(
                    horizontal = 12.dp,
                    vertical = CONTENT_VERTICAL_PADDING_DP.dp
                ),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── 标题行 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .requiredHeight(TITLE_ROW_HEIGHT_DP.dp),
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
                    text = currentFileName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                // 有封面且有歌词时，提供封面/歌词切换
                if (coverBytes != null && lyrics.isNotEmpty()) {
                    IconButton(onClick = { showLyrics = !showLyrics }) {
                        Icon(
                            imageVector = if (showLyrics) Icons.Default.MusicNote
                            else Icons.AutoMirrored.Filled.Subject,
                            contentDescription = if (showLyrics) "显示封面" else "显示歌词",
                            tint = iconTint
                        )
                    }
                } else {
                    IconButton(onClick = {}, enabled = false) {}
                }
            }

            // ── 内容区域（封面 / 歌词，点击圆形区域切换） ──
            // 占满窗口剩余高度；封面圆按宽度比例绘制，避免被高度撑大
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val canToggle = lyrics.isNotEmpty()
                if (showLyrics && canToggle) {
                    LyricsView(
                        lyrics = lyrics,
                        currentPosition = currentPosition,
                        contentColor = contentColor,
                        secondaryColor = secondaryColor,
                        onToggle = { showLyrics = false },
                        onSeek = { player.seekTo(it) }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .aspectRatio(1f, matchHeightConstraintsFirst = true)
                            .clip(CircleShape)
                            .background(if (coverBytes != null) backgroundColor else secondaryColor)
                            .then(
                                if (canToggle) Modifier.clickable { showLyrics = true } else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (coverBytes != null) {
                            AsyncImage(
                                model = coverBytes,
                                contentDescription = "封面",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(0.4f),
                                tint = if (isDarkMode) Color.LightGray else Color.Gray
                            )
                        }
                    }
                }
            }

            // ── 进度条行 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = formatTime(if (isSeeking) (seekPosition * duration).toLong() else currentPosition),
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(32.dp)
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
                    modifier = Modifier
                        .weight(1f)
                        .requiredHeight(PROGRESS_SLIDER_HEIGHT_DP.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = contentColor,
                        activeTrackColor = contentColor
                    )
                )
                Text(
                    text = formatTime(duration),
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(32.dp)
                )
            }

            // ── 控制行：循环模式 / 上一集 / 播放暂停 / 下一集 / 歌单 ──
            PlayerControlsRow(
                isPlaying = isPlaying,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                repeatMode = repeatMode,
                isPlaylistVisible = showPlaylist,
                tint = iconTint,
                onToggleRepeat = { repeatMode = repeatMode.next() },
                onPrevious = { player.seekToPreviousMediaItem() },
                onPlayPause = { if (isPlaying) player.pause() else player.play() },
                onNext = { player.seekToNextMediaItem() },
                onTogglePlaylist = { showPlaylist = !showPlaylist }
            )
        }

        // ── 播放列表覆盖层：从进度条上沿向上覆盖，底部控制条保持可见 ──
        PlaylistOverlay(
            visible = showPlaylist,
            mediaPaths = mediaPaths,
            currentIndex = currentMediaIndex,
            title = "播放列表",
            backgroundColor = backgroundColor,
            contentColor = contentColor,
            secondaryColor = secondaryColor,
            onClose = { showPlaylist = false },
            onSelect = { index ->
                player.seekTo(index, 0L)
                player.play()
            },
            bottomInsetDp = CONTROL_ROW_HEIGHT_DP + PROGRESS_ROW_HEIGHT_DP + CONTENT_VERTICAL_PADDING_DP
        )
    }
}

/** 顺序播放 → 单曲循环 → 随机播放 的循环顺序。 */
private fun RepeatPlaybackMode.next(): RepeatPlaybackMode = when (this) {
    RepeatPlaybackMode.SEQUENTIAL -> RepeatPlaybackMode.SINGLE
    RepeatPlaybackMode.SINGLE -> RepeatPlaybackMode.SHUFFLE
    RepeatPlaybackMode.SHUFFLE -> RepeatPlaybackMode.SEQUENTIAL
}

@Composable
private fun PlayerControlsRow(
    isPlaying: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    repeatMode: RepeatPlaybackMode,
    isPlaylistVisible: Boolean,
    tint: Color,
    onToggleRepeat: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onTogglePlaylist: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .requiredHeight(CONTROL_ROW_HEIGHT_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // 循环模式
        IconButton(onClick = onToggleRepeat) {
            Icon(
                imageVector = when (repeatMode) {
                    RepeatPlaybackMode.SEQUENTIAL -> Icons.Default.Repeat
                    RepeatPlaybackMode.SINGLE -> Icons.Default.RepeatOne
                    RepeatPlaybackMode.SHUFFLE -> Icons.Default.Shuffle
                },
                contentDescription = when (repeatMode) {
                    RepeatPlaybackMode.SEQUENTIAL -> "顺序播放"
                    RepeatPlaybackMode.SINGLE -> "单曲循环"
                    RepeatPlaybackMode.SHUFFLE -> "随机播放"
                },
                tint = tint
            )
        }
        // 上一集
        IconButton(onClick = onPrevious, enabled = hasPrevious) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "上一集",
                tint = if (hasPrevious) tint else tint.copy(alpha = 0.3f)
            )
        }
        // 播放 / 暂停
        IconButton(onClick = onPlayPause) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                modifier = Modifier.size(PLAY_PAUSE_ICON_SIZE_DP.dp),
                tint = tint
            )
        }
        // 下一集
        IconButton(onClick = onNext, enabled = hasNext) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "下一集",
                tint = if (hasNext) tint else tint.copy(alpha = 0.3f)
            )
        }
        // 歌单
        IconButton(onClick = onTogglePlaylist) {
            Icon(
                imageVector = Icons.Default.PlaylistPlay,
                contentDescription = "播放列表",
                tint = if (isPlaylistVisible) tint.copy(alpha = 0.5f) else tint
            )
        }
    }
}

@Composable
private fun PlaylistOverlay(
    visible: Boolean,
    mediaPaths: List<String>,
    currentIndex: Int,
    title: String,
    backgroundColor: Color,
    contentColor: Color,
    secondaryColor: Color,
    onClose: () -> Unit,
    onSelect: (Int) -> Unit,
    bottomInsetDp: Int
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomInsetDp.dp)
                .background(backgroundColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "收起播放列表",
                        tint = contentColor
                    )
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(mediaPaths) { index, path ->
                    val isCurrent = index == currentIndex
                    val displayName = File(path).name
                    Text(
                        text = displayName,
                        color = if (isCurrent) contentColor else secondaryColor,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

/**
 * 歌词视图。
 *
 * 交互模型：
 * - 默认「跟随模式」：当前播放行始终垂直居中，随播放进度自动滚动。
 * - 单击 → 切换回封面（[onToggle]）。
 * - 上下拖动 → 进入「浏览模式」：停止自动跟随，屏幕中央显示指示线；松手后指示线吸附到最近一行，
 *   并显示跳转按钮；点击按钮 seek 到该行并退出浏览模式回到跟随。
 * - 浏览模式持续 5 秒无任何操作 → 自动回到跟随模式。
 */
@Composable
private fun LyricsView(
    lyrics: List<LrcParser.LyricLine>,
    currentPosition: Long,
    contentColor: Color,
    secondaryColor: Color,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit
) {
    val listState = rememberLazyListState()

    val currentIndex = remember(lyrics, currentPosition) {
        LrcParser.currentIndex(lyrics, currentPosition)
    }

    var isBrowsing by remember { mutableStateOf(false) }
    var snappedIndex by remember { mutableIntStateOf(-1) }
    var lastInteractionAt by remember { mutableLongStateOf(0L) }

    // 跟随模式：自动将当前播放行滚动到垂直居中
    LaunchedEffect(currentIndex, isBrowsing) {
        if (!isBrowsing && currentIndex >= 0) {
            listState.scrollItemToCenter(currentIndex)
        }
    }

    // 浏览模式：5 秒无操作后自动回到跟随模式
    LaunchedEffect(isBrowsing, lastInteractionAt) {
        if (isBrowsing) {
            delay(BROWSE_IDLE_TIMEOUT_MS)
            if (isBrowsing && System.currentTimeMillis() - lastInteractionAt >= BROWSE_IDLE_TIMEOUT_MS) {
                isBrowsing = false
                snappedIndex = -1
            }
        }
    }

    fun snapToNearestLine() {
        snappedIndex = listState.nearestIndexToCenter()
        lastInteractionAt = System.currentTimeMillis()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 拖动优先：进入浏览模式并跟手滚动
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        isBrowsing = true
                        lastInteractionAt = System.currentTimeMillis()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        lastInteractionAt = System.currentTimeMillis()
                        listState.dispatchRawDelta(-dragAmount.y)
                    },
                    onDragEnd = { snapToNearestLine() },
                    onDragCancel = {
                        isBrowsing = false
                        snappedIndex = -1
                    }
                )
            }
            // 单击切换回封面
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onToggle() })
            }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 上下留白各为半屏，使首尾行也能滚动到垂直中心
            val halfViewport = maxHeight / 2
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = halfViewport),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                itemsIndexed(lyrics) { index, line ->
                    val isCurrent = index == currentIndex
                    val isSnapped = isBrowsing && index == snappedIndex
                    Text(
                        text = line.text,
                        color = when {
                            isSnapped -> contentColor
                            isCurrent && !isBrowsing -> contentColor
                            else -> secondaryColor
                        },
                        fontWeight = if (isSnapped || (isCurrent && !isBrowsing)) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Normal
                        },
                        style = if (isSnapped || (isCurrent && !isBrowsing)) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    )
                }
            }
        }

        // 浏览模式：中央指示线 + 跳转按钮
        if (isBrowsing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .height(CENTER_INDICATOR_HEIGHT_DP.dp)
                    .background(contentColor.copy(alpha = 0.15f))
            )
            if (snappedIndex in lyrics.indices && lyrics[snappedIndex].isTimed) {
                IconButton(
                    onClick = {
                        onSeek(lyrics[snappedIndex].timeMs)
                        isBrowsing = false
                        snappedIndex = -1
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "跳转到此句",
                        tint = contentColor
                    )
                }
            }
        }
    }
}

private const val BROWSE_IDLE_TIMEOUT_MS = 5_000L
private const val CENTER_INDICATOR_HEIGHT_DP = 2
private const val MAX_CENTER_ATTEMPTS = 5

/** 进度条行高度：Material3 Slider 默认约 48dp，此处压缩约 22% 以减小上下粗度。 */
private const val PROGRESS_SLIDER_HEIGHT_DP = 37

/** 底部控制行高度，用于计算播放列表覆盖层的底部留白。 */
private const val CONTROL_ROW_HEIGHT_DP = 56

/** 进度条行的实际高度（含 Slider 与文字）。 */
private const val PROGRESS_ROW_HEIGHT_DP = 48

/** 播放/暂停图标尺寸，较其他控制图标更大以突出主操作。 */
private const val PLAY_PAUSE_ICON_SIZE_DP = 64

/** 标题行高度。 */
private const val TITLE_ROW_HEIGHT_DP = 48

/** 主内容列的水平/垂直内边距。 */
private const val CONTENT_VERTICAL_PADDING_DP = 24

/** 将 [index] 行滚动到列表垂直中心。 */
private suspend fun LazyListState.scrollItemToCenter(index: Int) {
    if (index < 0) return
    animateScrollToItem(index)
    repeat(MAX_CENTER_ATTEMPTS) {
        val info = layoutInfo.visibleItemsInfo.find { it.index == index }
        if (info == null) {
            animateScrollToItem(index)
        } else {
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            val itemCenter = info.offset + info.size / 2
            val delta = itemCenter - viewportCenter
            if (delta == 0) return
            animateScrollBy(delta.toFloat())
        }
    }
}

/** 返回当前最接近列表垂直中心的可见行索引，无可见项返回 -1。 */
private fun LazyListState.nearestIndexToCenter(): Int {
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    return layoutInfo.visibleItemsInfo.minByOrNull { item ->
        kotlin.math.abs(item.offset + item.size / 2 - viewportCenter)
    }?.index ?: -1
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
