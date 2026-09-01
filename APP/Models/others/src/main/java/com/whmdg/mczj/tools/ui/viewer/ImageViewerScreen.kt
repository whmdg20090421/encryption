package com.whmdg.mczj.tools.ui.viewer

import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.awxkee.jxlcoder.JxlCoder
import com.github.chrisbanes.photoview.PhotoView
import com.whmdg.mczj.tools.util.ArchiveBrowser
import com.whmdg.mczj.tools.util.DiagnosticLog
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    filePath: String,
    imagePaths: List<String> = emptyList(),
    startIndex: Int = 0,
    archivePath: String? = null,
    archiveEntryPaths: List<String> = emptyList(),
    archivePassword: String = "",
    archivePermissionLevel: String = "NORMAL",
    onBack: () -> Unit
) {
    val paths = if (imagePaths.isNotEmpty()) imagePaths else listOf(filePath)
    val initialPage = if (imagePaths.isNotEmpty()) startIndex.coerceIn(0, paths.size - 1) else 0

    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { paths.size })

    val currentFile = remember(pagerState.currentPage) { File(paths[pagerState.currentPage]) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentFile.name,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val file = File(paths[page])
                ArchiveImagePage(
                    file = file,
                    archivePath = archivePath,
                    archiveEntryPath = archiveEntryPaths.getOrNull(page),
                    archivePassword = archivePassword,
                    archivePermissionLevel = archivePermissionLevel
                )
            }

            // 底部半透明页码指示器
            if (paths.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1}/${paths.size}",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun ArchiveImagePage(
    file: File,
    archivePath: String?,
    archiveEntryPath: String?,
    archivePassword: String,
    archivePermissionLevel: String
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var loadState by remember(file.absolutePath) { mutableIntStateOf(if (file.exists()) 1 else 0) }
    var loadError by remember(file.absolutePath) { mutableStateOf<String?>(null) }

    LaunchedEffect(file.absolutePath, archivePath, archiveEntryPath) {
        if (archivePath != null && archiveEntryPath != null && !file.exists()) {
            val result = ArchiveBrowser.extractSingleFile(
                context = context,
                archivePath = archivePath,
                entryPath = archiveEntryPath,
                destFile = file,
                password = archivePassword,
                permissionLevel = archivePermissionLevel
            )
            if (result.success) {
                loadState = 1
            } else {
                loadError = result.errorMessage
                loadState = 2
            }
        } else {
            loadState = 1
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loadState == 1) {
            AndroidView(
                factory = { context -> createPhotoView(context) },
                update = { photoView ->
                    if (photoView.tag != file.absolutePath) {
                        photoView.tag = file.absolutePath
                        if (file.extension.equals("jxl", ignoreCase = true)) {
                            try {
                                photoView.setImageBitmap(JxlCoder.decode(file.readBytes()))
                            } catch (e: Exception) {
                                DiagnosticLog.log("ImageViewer", "JXL 解码失败: ${e.message}")
                            }
                        } else {
                            photoView.setImageURI(Uri.fromFile(file))
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else if (loadState == 2) {
            Text(loadError ?: "图片解压失败", color = Color.White)
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

private class SmartPhotoView(context: android.content.Context) : PhotoView(context) {
    private var downX = 0f
    private var downY = 0f
    internal var atLeftEdge = false
    internal var atRightEdge = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                if (scale > 1f) parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (dx * dx + dy * dy > 225) {
                    val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).let {
                        if (it < 0) it + 360 else it
                    }
                    // atan2: 0°=右, 90°=下, 180°=左, 270°=上
                    val nearHorizontal = angle <= 10 || angle >= 350 || (angle in 170.0..190.0)
                    val nearVertical = angle in 80.0..100.0 || angle in 260.0..280.0
                    if (nearVertical) {
                        ev.offsetLocation(-ev.x + downX, 0f)
                    } else if (nearHorizontal) {
                        ev.offsetLocation(0f, -ev.y + downY)
                        if ((atLeftEdge && dx < 0) || (atRightEdge && dx > 0)) {
                            parent.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent.requestDisallowInterceptTouchEvent(false)
        }
        return super.dispatchTouchEvent(ev)
    }

    fun scaleForDoubleTap(): Float {
        val visibleRect = displayRect
        if (visibleRect == null || width <= 0 || height <= 0) return mediumScale
        val horizontalScale = width / visibleRect.width()
        val verticalScale = height / visibleRect.height()
        return scale * maxOf(horizontalScale, verticalScale)
    }
}

private fun createPhotoView(context: android.content.Context): PhotoView = SmartPhotoView(context).apply {
    minimumScale = 1f
    mediumScale = 2f
    maximumScale = Float.MAX_VALUE
    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
    setOnDoubleTapListener(object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val self = this@apply as SmartPhotoView
            val targetScale = if (scale > minimumScale + 0.01f) minimumScale else self.scaleForDoubleTap()
            setScale(targetScale, e.x, e.y, true)
            return true
        }
    })
    setOnMatrixChangeListener {
        val self = this@SmartPhotoView
        val rect = displayRect
        if (rect == null || rect.width() <= width) {
            self.atLeftEdge = false
            self.atRightEdge = false
            setAllowParentInterceptOnEdge(true)
        } else {
            self.atLeftEdge = rect.left >= -1f
            self.atRightEdge = rect.right <= width + 1f
            setAllowParentInterceptOnEdge(self.atLeftEdge || self.atRightEdge)
        }
    }
}
