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
import com.whmdg.mczj.tools.util.DiagnosticLog
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    filePath: String,
    imagePaths: List<String> = emptyList(),
    startIndex: Int = 0,
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
                AndroidView(
                    factory = { context ->
                        PhotoView(context).apply {
                            minimumScale = 1f
                            mediumScale = 2f
                            maximumScale = 5f
                            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER

                            if (file.extension.equals("jxl", ignoreCase = true)) {
                                try {
                                    setImageBitmap(JxlCoder.decode(file.readBytes()))
                                } catch (e: Exception) {
                                    DiagnosticLog.log("ImageViewer", "JXL 解码失败: ${e.message}")
                                }
                            } else {
                                setImageURI(Uri.fromFile(file))
                            }

                            setOnDoubleTapListener(object : GestureDetector.SimpleOnGestureListener() {
                                override fun onDoubleTap(e: MotionEvent): Boolean {
                                    setScale(if (scale > 1.5f) 1f else 2f, e.x, e.y, true)
                                    return true
                                }
                            })

                            // PhotoView clamps matrix movement to its bounds. Once the
                            // image reaches a horizontal edge, the pager may intercept
                            // an outward drag to switch images.
                            setOnMatrixChangeListener {
                                val rect = displayRect
                                if (rect == null || rect.width() <= width) {
                                    setAllowParentInterceptOnEdge(true)
                                } else {
                                    val atLeft = rect.left >= -1f
                                    val atRight = rect.right <= width + 1f
                                    setAllowParentInterceptOnEdge(atLeft || atRight)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
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
