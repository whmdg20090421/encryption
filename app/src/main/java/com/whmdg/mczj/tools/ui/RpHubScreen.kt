package com.whmdg.mczj.tools.ui

import android.net.http.SslError
import android.os.Environment
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.util.DiagnosticLog
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val RP_HUB_PORT = 18900

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RpHubScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val server = remember { RpHubServer(context, RP_HUB_PORT) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showTrafficPanel by remember { mutableStateOf(false) }
    var showDownloadPanel by remember { mutableStateOf(false) }
    var showDebugPanel by remember { mutableStateOf(false) }
    var pendingSaveAsEntry by remember { mutableStateOf<DownloadEntry?>(null) }
    var pendingFileChooserCallback by remember { mutableStateOf<android.webkit.ValueCallback<Array<android.net.Uri>>?>(null) }
    val isDebugMode = remember {
        context.getSharedPreferences(AppDataPaths.PREFS_RP_HUB, android.content.Context.MODE_PRIVATE)
            .getBoolean("debug_mode", false)
    }

    // SAF 另存为 launcher
    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val entry = pendingSaveAsEntry ?: return@rememberLauncherForActivityResult
        pendingSaveAsEntry = null
        scope.launch {
            val src = File(entry.externalPath)
            if (!src.exists()) {
                Toast.makeText(context, "源文件不存在", Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    }
                }
                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // WebView 文件选择器 launcher
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // 备份 PNG 角色卡到内部存储
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val isPng = mimeType == "image/png" || uri.toString().lowercase().endsWith(".png")
            if (isPng) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val backupDir = File(AppDataPaths.rpHub(context), "characters/imported").apply { mkdirs() }
                        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                        val backupFile = File(backupDir, "card_${timestamp}.png")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            backupFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        DiagnosticLog.log("RP-Hub", "角色卡已备份: ${backupFile.absolutePath}")
                    } catch (e: Exception) {
                        DiagnosticLog.log("RP-Hub/ERROR", "角色卡备份失败: ${e.message}")
                    }
                }
            }
            // 将选中的文件 URI 传回 WebView
            pendingFileChooserCallback?.onReceiveValue(arrayOf(uri))
        } else {
            // 用户取消选择
            pendingFileChooserCallback?.onReceiveValue(null)
        }
        pendingFileChooserCallback = null
    }

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
                    // Debug 按钮（仅 Debug 模式时显示）
                    if (isDebugMode) {
                        IconButton(onClick = { showDebugPanel = true }) {
                            Icon(Icons.Default.Code, contentDescription = "Debug")
                        }
                    }
                    // 下载按钮
                    IconButton(onClick = { showDownloadPanel = true }) {
                        Icon(Icons.Default.Download, contentDescription = "下载")
                    }
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
                    settings.databaseEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                    // 启用 Cookie（万相广场等 iframe 内第三方网站登录需要）
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

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

                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            // 取消之前的未完成请求
                            pendingFileChooserCallback?.onReceiveValue(null)
                            pendingFileChooserCallback = filePathCallback
                            val types = fileChooserParams?.acceptTypes ?: emptyArray()
                            val filtered = types.filter { it.isNotEmpty() }
                            val mime: Array<String> = if (filtered.isEmpty()) arrayOf("*/*") else filtered.toTypedArray()
                            fileChooserLauncher.launch(mime)
                            return true
                        }
                    }

                    // 下载监听
                    setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                                val cookies = CookieManager.getInstance().getCookie(url)

                                // 下载文件数据
                                val conn = URL(url).openConnection() as HttpURLConnection
                                cookies?.let { conn.setRequestProperty("Cookie", it) }
                                conn.setRequestProperty("User-Agent", userAgent)
                                conn.connect()
                                val data = conn.inputStream.use { it.readBytes() }
                                conn.disconnect()

                                // 外部存储：Download/RP-Hub/download/cards/
                                val extDir = File(
                                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                    "RP-Hub/download/cards"
                                ).apply { mkdirs() }
                                val extFile = File(extDir, fileName)
                                extFile.writeBytes(data)

                                // 内部存储：AppDataPaths.rpHub/download/cards/
                                val intDir = File(
                                    AppDataPaths.rpHub(context),
                                    "download/cards"
                                ).apply { mkdirs() }
                                val intFile = File(intDir, fileName)
                                intFile.writeBytes(data)

                                DownloadLog.add(
                                    DownloadEntry(
                                        fileName = fileName,
                                        externalPath = extFile.absolutePath,
                                        internalPath = intFile.absolutePath
                                    )
                                )

                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "已下载: $fileName", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                                DiagnosticLog.log("RP-Hub/ERROR", "Download failed: ${e.message}")
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

    // 下载面板 BottomSheet
    if (showDownloadPanel) {
        ModalBottomSheet(
            onDismissRequest = { showDownloadPanel = false }
        ) {
            RpHubDownloadPanel(
                onDismiss = { showDownloadPanel = false },
                onSaveAs = { entry ->
                    showDownloadPanel = false
                    pendingSaveAsEntry = entry
                    safLauncher.launch(entry.fileName)
                }
            )
        }
    }

    // Debug 面板 BottomSheet
    if (showDebugPanel) {
        ModalBottomSheet(
            onDismissRequest = { showDebugPanel = false }
        ) {
            RpHubDebugPanel(
                webView = webView,
                onDismiss = { showDebugPanel = false }
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
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
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
