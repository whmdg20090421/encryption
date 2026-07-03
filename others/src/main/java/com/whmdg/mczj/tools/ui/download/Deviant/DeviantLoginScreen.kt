package com.whmdg.mczj.tools.ui.download.Deviant

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviantLoginScreen(
    onBack: () -> Unit,
    onLoginSuccess: (String) -> Unit  // cookie
) {
    var isLoading by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("正在加载...") }
    var showCookieDialog by remember { mutableStateOf(false) }
    var extractedCookies by remember { mutableStateOf("") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var cookiesDetected by remember { mutableStateOf(false) }
    var showNoCookieDialog by remember { mutableStateOf(false) }
    var showManualInput by remember { mutableStateOf(false) }
    var manualCookie by remember { mutableStateOf("") }

    /** 提取 Cookie 并弹出确认对话框 */
    fun extractAndShowCookie() {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie("https://www.deviantart.com")
        if (cookies == null || !hasDACookies(cookies)) {
            statusText = "未检测到有效登录，请先完成登录"
            cookiesDetected = false
            showNoCookieDialog = true
            return
        }
        extractedCookies = cookies
        showCookieDialog = true
    }

    if (showNoCookieDialog) {
        AlertDialog(
            onDismissRequest = { showNoCookieDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("未检测到登录凭证") },
            text = {
                Text("当前未检测到有效的 DeviantArt 登录 Cookie。请在下方 WebView 中完成登录操作后，再次点击右上角的勾勾按钮。")
            },
            confirmButton = {
                TextButton(onClick = { showNoCookieDialog = false }) {
                    Text("知道了")
                }
            }
        )
    }

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
            title = { Text("检测到登录凭证") },
            text = {
                Column {
                    Text("已成功提取 DeviantArt Cookie。是否保存？")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = extractedCookies.take(100) + "...",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCookieDialog = false
                    onLoginSuccess(extractedCookies)
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCookieDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 手动输入 Cookie 对话框
    if (showManualInput) {
        AlertDialog(
            onDismissRequest = { showManualInput = false },
            title = { Text("手动输入 Cookie") },
            text = {
                Column {
                    Text("请从浏览器中复制 DeviantArt 的 Cookie 并粘贴到下方：")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualCookie,
                        onValueChange = { manualCookie = it },
                        label = { Text("Cookie") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showManualInput = false
                        if (manualCookie.isNotBlank()) {
                            onLoginSuccess(manualCookie.trim())
                        }
                    },
                    enabled = manualCookie.isNotBlank()
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualInput = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DeviantArt 登录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 手动输入
                    TextButton(onClick = { showManualInput = true }) {
                        Text("手动输入")
                    }
                    // 提取 Cookie
                    IconButton(onClick = { extractAndShowCookie() }) {
                        Icon(Icons.Default.Check, contentDescription = "提取Cookie")
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
            // 状态栏
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = statusText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // WebView
            AndroidView(
                factory = { context ->
                    val webView = WebView(context)
                    webView.apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                        }

                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(webView, true)
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                statusText = "请在页面中完成登录，然后点击右上角 ✓"

                                // 自动检测登录状态
                                val cookies = CookieManager.getInstance().getCookie("https://www.deviantart.com")
                                if (cookies != null && hasDACookies(cookies)) {
                                    cookiesDetected = true
                                    statusText = "检测到登录凭证，点击右上角 ✓ 保存"
                                }
                            }
                        }

                        loadUrl("https://www.deviantart.com/users/login")
                        webViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** 检查是否包含 DeviantArt 的关键 Cookie */
private fun hasDACookies(cookies: String): Boolean {
    // DeviantArt 登录后通常有 auth 或 userinfo 相关 cookie
    return cookies.contains("auth=", ignoreCase = true) ||
            cookies.contains("userinfo=", ignoreCase = true) ||
            cookies.contains("td=", ignoreCase = true)
}
