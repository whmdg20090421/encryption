package com.whmdg.mczj.tools.ui.viewer

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
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.whmdg.mczj.tools.util.ArchiveBrowser
import com.whmdg.mczj.tools.util.DiagnosticLog
import com.whmdg.mczj.tools.ui.filemanager.StandardDialog
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    filePath: String,
    imagePaths: List<String> = emptyList(),
    startIndex: Int = 0,
    totalCount: Int = 0,
    vaultSessionId: String? = null,
    archivePath: String? = null,
    archiveEntryPaths: List<String> = emptyList(),
    archivePassword: String = "",
    archivePermissionLevel: String = "NORMAL",
    onBack: () -> Unit
) {
    val paths = if (imagePaths.isNotEmpty()) imagePaths else listOf(filePath)
    val displayTotal = if (totalCount > 0) totalCount else paths.size
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
                when {
                    vaultSessionId != null -> VaultImagePage(
                        file = file,
                        vaultSessionId = vaultSessionId
                    )
                    archivePath != null -> ArchiveImagePage(
                        file = file,
                        archivePath = archivePath,
                        archiveEntryPath = archiveEntryPaths.getOrNull(page),
                        archivePassword = archivePassword,
                        archivePermissionLevel = archivePermissionLevel
                    )
                    else -> ArchiveImagePage(
                        file = file,
                        archivePath = null,
                        archiveEntryPath = null,
                        archivePassword = "",
                        archivePermissionLevel = ""
                    )
                }
            }

            // 底部半透明页码指示器
            if (displayTotal > 1) {
                Text(
                    text = "${pagerState.currentPage + 1}/$displayTotal",
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
    val context = LocalContext.current
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
        when (loadState) {
            1 -> CoilZoomAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(file)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onError = { state ->
                    DiagnosticLog.log("ImageViewer", "图片加载失败: ${state.result.throwable.message}")
                    loadError = state.result.throwable.message
                    loadState = 2
                }
            )
            2 -> Text(loadError ?: "图片解压失败", color = Color.White)
            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
private fun VaultImagePage(
    file: File,
    vaultSessionId: String
) {
    val context = LocalContext.current
    var loadState by remember(file.absolutePath) { mutableIntStateOf(if (file.exists()) 1 else 0) }
    var loadError by remember(file.absolutePath) { mutableStateOf<String?>(null) }

    LaunchedEffect(file.absolutePath) {
        if (!file.exists()) {
            val ctx = com.whmdg.mczj.tools.encryption.services.VaultKeyHolder.get(vaultSessionId)
            val encryptedPath = ctx?.vaultImageEntries?.get(file.absolutePath)
            if (ctx != null && encryptedPath != null) {
                try {
                    com.whmdg.mczj.tools.encryption.core.FileCodec.decrypt(
                        src = java.io.File(encryptedPath),
                        dst = file,
                        dek = ctx.dek,
                        customEncryption = ctx.customEncryption
                    )
                    loadState = 1
                } catch (e: Exception) {
                    DiagnosticLog.log("VaultImage", "解密失败: ${e.javaClass.simpleName}: ${e.message}, file=${file.name}, encryptedPath=$encryptedPath")
                    loadError = buildString {
                        appendLine("解密失败")
                        appendLine()
                        appendLine("文件: ${file.name}")
                        appendLine("异常: ${e.javaClass.simpleName}")
                        appendLine("原因: ${e.message ?: "(无)"}")
                        e.cause?.let { appendLine("内部原因: ${it.javaClass.simpleName}: ${it.message}") }
                    }
                    loadState = 2
                }
            } else {
                loadError = "会话已过期，请重新打开保险箱"
                loadState = 2
            }
        } else {
            loadState = 1
        }
    }

    if (loadState == 2 && loadError != null) {
        com.whmdg.mczj.tools.ui.filemanager.StandardDialog(
            onDismissRequest = { loadError = null },
            title = { Text("图片打开失败") },
            text = { Text(loadError!!, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { loadError = null }) {
                    Text("确定")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (loadState) {
            1 -> CoilZoomAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(file)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onError = { state ->
                    DiagnosticLog.log("VaultImage", "图片加载失败: ${state.result.throwable.message}")
                    loadError = state.result.throwable.message
                    loadState = 2
                }
            )
            2 -> Text("加载失败", color = Color.White)
            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}
