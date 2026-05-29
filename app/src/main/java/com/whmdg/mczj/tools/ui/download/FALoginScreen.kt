package com.whmdg.mczj.tools.ui.download

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FALoginScreen(
    onBack: () -> Unit,
    onLoginSuccess: (String, String) -> Unit  // (cookie, username)
) {
    var isLoading by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("正在加载...") }
    var showCookieDialog by remember { mutableStateOf(false) }
    var extractedCookies by remember { mutableStateOf("") }
    var extractedUsername by remember { mutableStateOf("") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var cookiesDetected by remember { mutableStateOf(false) }

    /** 提取 Cookie 并弹出确认对话框 */
    fun extractAndShowCookie() {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie("furaffinity.net")
        if (cookies == null || !hasFACookies(cookies)) {
            statusText = "未检测到有效登录，请先完成登录"
            cookiesDetected = false
            return
        }
        extractedCookies = cookies
        try {
            webViewRef?.evaluateJavascript(
                "(function() { " +
                    "var el = document.querySelector('.my-username, .username, a[href*=\"/user/\"]');" +
                    "return el ? el.textContent.trim() : '';" +
                    "})()"
            ) { result ->
                extractedUsername = result?.removeSurrounding("\"") ?: ""
                showCookieDialog = true
            }
        } catch (_: Exception) {
            extractedUsername = ""
            showCookieDialog = true
        }
    }

    // Cookie confirmation dialog
    if (showCookieDialog) {
        AlertDialog(
            onDismissRequest = { showCookieDialog = false },
            icon = {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("登录信息确认") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "检测到以下登录凭证：",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (extractedUsername.isNotEmpty()) {
                        Text(
                            "用户名: $extractedUsername",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text(
                        "Cookie 信息:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            extractedCookies,
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showCookieDialog = false
                    onLoginSuccess(extractedCookies, extractedUsername)
                }) {
                    Text("确认保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCookieDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FA 登录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { extractAndShowCookie() }) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "获取Cookie",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 状态栏 — 固定在 WebView 上方，不遮挡
            if (!isLoading) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (cookiesDetected)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (cookiesDetected) "✓ 已检测到登录凭证" else "请在下方完成登录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (cookiesDetected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (cookiesDetected) {
                            TextButton(onClick = { extractAndShowCookie() }) {
                                Text("获取Cookie")
                            }
                        }
                    }
                }
            }

            // WebView — 填充剩余空间
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false

                                    val cookieManager = CookieManager.getInstance()
                                    val cookies = cookieManager.getCookie("furaffinity.net")
                                    if (cookies != null && hasFACookies(cookies)) {
                                        cookiesDetected = true
                                        statusText = "登录成功，请点击右上角「确定」保存"
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    return false
                                }
                            }

                            webViewRef = this
                            loadUrl("https://www.furaffinity.net")
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 加载指示器
                if (isLoading) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Check if cookies contain FA authentication tokens.
 * FA uses cookies 'a' and 'b' (both UUIDs) for session auth,
 * plus 'cf_clearance' for Cloudflare bypass.
 */
private fun hasFACookies(cookies: String): Boolean {
    return cookies.contains("a=") && cookies.contains("b=") && cookies.contains("cf_clearance=")
}
