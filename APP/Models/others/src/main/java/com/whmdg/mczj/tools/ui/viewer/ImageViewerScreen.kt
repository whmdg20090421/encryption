package com.whmdg.mczj.tools.ui.viewer

import androidx.compose.animation.core.SnapSpec
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import coil3.compose.AsyncImagePainter
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
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
                val zoomableState = rememberZoomableState()
                val imageState = rememberZoomableImageState(zoomableState)
                val painter = rememberAsyncImagePainter(model = File(paths[page]))

                // 翻页后重置缩放状态
                LaunchedEffect(pagerState.settledPage) {
                    if (pagerState.settledPage != page) {
                        zoomableState.resetZoom(animationSpec = SnapSpec())
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    ZoomableAsyncImage(
                        model = File(paths[page]),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        state = imageState,
                        contentScale = ContentScale.Fit,
                        onDoubleClick = DoubleClickToZoomListener.cycle(maxZoomFactor = 2f)
                    )

                    // Coil 加载失败时显示错误信息
                    if (painter.state is AsyncImagePainter.State.Error) {
                        val error = (painter.state as AsyncImagePainter.State.Error).result.throwable
                        Text(
                            text = "加载失败: ${error.message ?: error.javaClass.simpleName}\n路径: ${paths[page]}",
                            color = Color(0xFFFF1744),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
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
