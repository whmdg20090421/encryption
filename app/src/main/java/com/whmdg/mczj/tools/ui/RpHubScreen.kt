package com.whmdg.mczj.tools.ui

import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

private const val RP_HUB_PORT = 18900

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RpHubScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val server = remember { RpHubServer(context, RP_HUB_PORT) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showTrafficPanel by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        server.start()
        onDispose {
            server.stop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RP-Hub") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // Web 面板按钮
                    IconButton(onClick = { showTrafficPanel = true }) {
                        Icon(Icons.Default.BugReport, contentDescription = "Web 面板")
                    }
                    // 刷新按钮
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { innerPadding ->
        AndroidView(
            factory = {
                WebView(context).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return null
                            val host = request.url?.host ?: ""
                            val isLocal = host.isEmpty() || host == "localhost"
                            val reqHeaders = request.requestHeaders?.entries?.associate { it.key to it.value } ?: emptyMap()
                            TrafficLog.add(
                                TrafficEntry(
                                    url = url,
                                    method = request.method ?: "GET",
                                    isLocal = isLocal,
                                    requestHeaders = reqHeaders,
                                    cookies = reqHeaders["Cookie"]
                                )
                            )
                            return null
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            if (TrafficLog.externalEnabled.value) {
                                handler?.proceed()
                            } else {
                                handler?.cancel()
                            }
                        }
                    }

                    loadUrl("http://localhost:$RP_HUB_PORT/index.html")
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }

    // Web 面板 BottomSheet
    if (showTrafficPanel) {
        ModalBottomSheet(
            onDismissRequest = { showTrafficPanel = false }
        ) {
            RpHubTrafficPanel(
                onDismiss = { showTrafficPanel = false }
            )
        }
    }
}
