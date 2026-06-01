package com.whmdg.mczj.tools.ui

import android.net.http.SslError
import android.webkit.ConsoleMessage
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.whmdg.mczj.tools.util.DiagnosticLog
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
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
        DiagnosticLog.beginSession("RP-Hub")
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
                            val method = request.method ?: "GET"
                            val reqHeaders = request.requestHeaders?.entries?.associate { it.key to it.value } ?: emptyMap()

                            if (isLocal) return null // 本地请求由 NanoHTTPD 处理

                            if (!TrafficLog.externalEnabled.value) return null

                            // 代理外部请求以捕获响应
                            return proxyExternalRequest(url, method, reqHeaders)
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

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            consoleMessage?.let {
                                val msg = it.message()
                                val level = when (it.messageLevel()) {
                                    ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                                    ConsoleMessage.MessageLevel.WARNING -> "WARN"
                                    else -> "INFO"
                                }
                                DiagnosticLog.log("WebView/$level", "[${it.sourceId()}:${it.lineNumber()}] $msg")
                            }
                            return true
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

/**
 * 代理外部请求：通过 HttpURLConnection 发起实际请求，捕获完整响应。
 * 在 shouldInterceptRequest（后台线程）中调用。
 */
private fun proxyExternalRequest(
    url: String,
    method: String,
    reqHeaders: Map<String, String>
): WebResourceResponse? {
    val startTime = System.currentTimeMillis()
    try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        // 忽略 SSL 证书验证（用于读取 HTTPS 明文）
        if (conn is javax.net.ssl.HttpsURLConnection) {
            conn.sslSocketFactory = createTrustAllSslFactory()
            conn.hostnameVerifier = { _, _ -> true }
        }
        // 转发请求头
        for ((key, value) in reqHeaders) {
            if (!key.equals("Host", ignoreCase = true)) {
                conn.setRequestProperty(key, value)
            }
        }
        conn.connect()

        val statusCode = conn.responseCode
        val respHeaders = conn.headerFields
            .filter { it.key != null }
            .mapValues { it.value.joinToString(", ") }
        val mime = conn.contentType ?: "text/plain"
        val charset = Regex("charset=([\\w-]+)").find(mime)?.groupValues?.get(1) ?: "UTF-8"
        val baseMime = mime.substringBefore(";").trim()

        // 读取响应体
        val stream = try { conn.inputStream } catch (_: Exception) { conn.errorStream }
        val bodyBytes = stream?.readBytes() ?: ByteArray(0)
        val bodyText = if (baseMime.startsWith("text/") || baseMime.contains("javascript") ||
            baseMime.contains("json") || baseMime.contains("xml") || baseMime.contains("html")) {
            try { String(bodyBytes, charset(charset)).take(10000) } catch (_: Exception) { null }
        } else null

        val elapsed = System.currentTimeMillis() - startTime

        // 记录到 TrafficLog
        TrafficLog.add(
            TrafficEntry(
                url = url,
                method = method,
                statusCode = statusCode,
                isLocal = false,
                requestHeaders = reqHeaders,
                cookies = reqHeaders["Cookie"],
                responseHeaders = respHeaders,
                responseBody = bodyText,
                responseTime = elapsed
            )
        )

        conn.disconnect()

        // 构造 WebResourceResponse 返回给 WebView
        val response = WebResourceResponse(baseMime, charset, ByteArrayInputStream(bodyBytes))
        response.setStatusCodeAndReasonPhrase(statusCode, conn.responseMessage ?: "OK")
        return response
    } catch (e: Exception) {
        val elapsed = System.currentTimeMillis() - startTime
        // 代理失败，记录请求但不拦截（让 WebView 自己处理）
        TrafficLog.add(
            TrafficEntry(
                url = url,
                method = method,
                statusCode = -1,
                isLocal = false,
                requestHeaders = reqHeaders,
                cookies = reqHeaders["Cookie"],
                responseHeaders = mapOf("Error" to (e.message ?: "Unknown")),
                responseTime = elapsed
            )
        )
        return null
    }
}

private fun createTrustAllSslFactory(): javax.net.ssl.SSLSocketFactory {
    val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    })
    val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
    sslContext.init(null, trustAllCerts, java.security.SecureRandom())
    return sslContext.socketFactory
}
