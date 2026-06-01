package com.whmdg.mczj.tools.ui

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

private val VENDOR_MAP = mapOf(
    "https://cdn.tailwindcss.com" to "tailwindcss.js",
    "https://unpkg.com/vue@3/dist/vue.global.prod.js" to "vue.global.prod.js",
    "https://cdn.jsdelivr.net/npm/marked/marked.min.js" to "marked.min.js",
    "https://cdn.jsdelivr.net/npm/dompurify@3.0.6/dist/purify.min.js" to "purify.min.js",
    "https://cdn.jsdelivr.net/npm/sortablejs@latest/Sortable.min.js" to "Sortable.min.js",
    "https://cdn.jsdelivr.net/npm/daisyui@4.7.2/dist/full.min.css" to "daisyui.full.min.css",
    "https://cdn.jsdelivr.net/npm/localforage@1.10.0/dist/localforage.min.js" to "localforage.min.js"
)

private fun getUpdatedDir(context: Context): File =
    File(context.filesDir, "rp-hub/vendor")

private fun hasUpdatedFiles(context: Context): Boolean =
    getUpdatedDir(context).list()?.isNotEmpty() == true

private fun rpHubBaseUrl(context: Context): String {
    return if (hasUpdatedFiles(context)) {
        "file://${context.filesDir.absolutePath}/rp-hub/"
    } else {
        "file:///android_asset/rp-hub/"
    }
}

private suspend fun updateVendorFiles(context: Context): Int {
    val dir = getUpdatedDir(context)
    dir.mkdirs()
    var successCount = 0
    withContext(Dispatchers.IO) {
        VENDOR_MAP.forEach { (url, filename) ->
            try {
                val data = URL(url).readBytes()
                File(dir, filename).writeBytes(data)
                successCount++
            } catch (_: Exception) {
                // 下载失败，跳过此文件
            }
        }
    }
    // 无论成功多少个，都复制 index.html 和其他资源到内部存储
    // 以便 WebView 用统一的 base URL 加载
    val rpHubDir = File(context.filesDir, "rp-hub")
    copyAssetDir(context, "rp-hub", rpHubDir, exclude = listOf("vendor"))
    return successCount
}

private fun copyAssetDir(context: Context, assetPath: String, targetDir: File, exclude: List<String> = emptyList()) {
    targetDir.mkdirs()
    val files = context.assets.list(assetPath) ?: return
    for (file in files) {
        if (file in exclude) continue
        val assetFilePath = "$assetPath/$file"
        val targetFile = File(targetDir, file)
        val subFiles = context.assets.list(assetFilePath)
        if (subFiles != null && subFiles.isNotEmpty()) {
            copyAssetDir(context, assetFilePath, targetFile, exclude)
        } else {
            context.assets.open(assetFilePath).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RpHubScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isUpdating by remember { mutableStateOf(false) }
    var webViewKey by remember { mutableStateOf(0) }

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
                    IconButton(
                        onClick = {
                            if (isUpdating) return@IconButton
                            isUpdating = true
                            scope.launch {
                                val count = updateVendorFiles(context)
                                isUpdating = false
                                if (count > 0) {
                                    Toast.makeText(context, "更新成功 ($count/${VENDOR_MAP.size})，重新加载中...", Toast.LENGTH_SHORT).show()
                                    webViewKey++
                                } else {
                                    Toast.makeText(context, "更新失败，请检查网络", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isUpdating
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "更新")
                    }
                }
            )
        }
    ) { innerPadding ->
        val baseUrl = remember(webViewKey) { rpHubBaseUrl(context) }
        AndroidView(
            key = webViewKey,
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return null
                            // 拦截 vendor 请求，优先从内部存储读取
                            if (url.contains("/vendor/")) {
                                val filename = url.substringAfterLast("/")
                                val updatedFile = File(getUpdatedDir(context), filename)
                                if (updatedFile.exists()) {
                                    val mimeType = when {
                                        filename.endsWith(".js") -> "application/javascript"
                                        filename.endsWith(".css") -> "text/css"
                                        else -> "application/octet-stream"
                                    }
                                    return WebResourceResponse(mimeType, "UTF-8", updatedFile.inputStream())
                                }
                            }
                            return null
                        }
                    }
                    loadUrl("${baseUrl}index.html")
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}
