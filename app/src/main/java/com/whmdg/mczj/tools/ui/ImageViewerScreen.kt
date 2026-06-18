package com.whmdg.mczj.tools.ui

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
import androidx.core.net.toUri
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

                // 翻页后重置缩放状态
                LaunchedEffect(pagerState.settledPage) {
                    if (pagerState.settledPage != page) {
                        zoomableState.resetZoom(animationSpec = SnapSpec())
                    }
                }

                ZoomableAsyncImage(
                    model = File(paths[page]).toUri(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    state = imageState,
                    contentScale = ContentScale.Fit,
                    // 双击在 1x ↔ 2x 之间切换
                    onDoubleClick = DoubleClickToZoomListener.cycle(maxZoomFactor = 2f)
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
