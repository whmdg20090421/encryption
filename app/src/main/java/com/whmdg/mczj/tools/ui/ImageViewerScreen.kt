package com.whmdg.mczj.tools.ui

import android.net.Uri
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

    // 跟踪当前页是否处于放大状态，放大时禁止 pager 滑动
    var isZoomed by remember { mutableStateOf(false) }

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
                userScrollEnabled = !isZoomed,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val file = File(paths[page])
                AndroidView(
                    factory = { ctx ->
                        PhotoView(ctx).apply {
                            if (file.extension.equals("jxl", ignoreCase = true)) {
                                try {
                                    val bytes = file.readBytes()
                                    val bitmap = com.awxkee.jxlcoder.JxlCoder.decode(bytes)
                                    setImageBitmap(bitmap)
                                } catch (e: Exception) {
                                    DiagnosticLog.log("ImageViewer", "JXL 解码失败: ${e.message}")
                                }
                            } else {
                                setImageURI(Uri.fromFile(file))
                            }
                            maximumScale = 5f
                            minimumScale = 1f
                            setOnScaleChangeListener { _, _, _ ->
                                isZoomed = scale > 1.01f
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
