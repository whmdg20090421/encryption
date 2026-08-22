package com.whmdg.mczj.tools.ui.download

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.whmdg.mczj.tools.tomato.TomatoDownloader

/**
 * 番茄小说下载器页面状态
 */
private enum class TomatoPageState {
    LOADING,     // 正在启动服务器
    READY,       // 服务器就绪，WebView 加载中
    ERROR        // 启动失败
}

/**
 * 番茄小说下载器页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TomatoNovelScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var pageState by remember { mutableStateOf(TomatoPageState.LOADING) }
    var errorMessage by remember { mutableStateOf("") }
    var showExitDialog by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // 启动服务器
    LaunchedEffect(Unit) {
        TomatoDownloader.startServer(
            context = context,
            onReady = {
                pageState = TomatoPageState.READY
            },
            onError = { errorCode, message ->
                pageState = TomatoPageState.ERROR
                errorMessage = when (errorCode) {
                    "PORT_IN_USE" -> "端口被占用：$message"
                    else -> "启动失败：$message"
                }
            }
        )
    }

    // 拦截系统返回键
    BackHandler {
        showExitDialog = true
    }

    // 退出确认弹窗
    if (showExitDialog) {
        TomatoExitDialog(
            onDismiss = { showExitDialog = false },
            onConfirm = {
                showExitDialog = false
                webView?.stopLoading()
                TomatoDownloader.stopServer()
                onBack()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("番茄小说下载器") },
                navigationIcon = {
                    IconButton(onClick = { showExitDialog = true }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (pageState) {
                TomatoPageState.LOADING -> {
                    // 加载中状态
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "正在启动下载服务...",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                TomatoPageState.READY -> {
                    // WebView
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webView = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = true
                                settings.allowContentAccess = true
                                webViewClient = WebViewClient()
                                loadUrl(TomatoDownloader.getServerUrl())
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                TomatoPageState.ERROR -> {
                    // 错误状态
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "启动失败",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            pageState = TomatoPageState.LOADING
                            TomatoDownloader.startServer(
                                context = context,
                                onReady = { pageState = TomatoPageState.READY },
                                onError = { errorCode, message ->
                                    pageState = TomatoPageState.ERROR
                                    errorMessage = when (errorCode) {
                                        "PORT_IN_USE" -> "端口被占用：$message"
                                        else -> "启动失败：$message"
                                    }
                                }
                            )
                        }) {
                            Text("重试")
                        }
                    }
                }
            }
        }
    }
}
