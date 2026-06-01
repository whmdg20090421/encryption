package com.whmdg.mczj.tools.ui

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

// CDN URL → local vendor filename mapping
private val CDN_VENDOR_MAP = mapOf(
    "cdn.tailwindcss.com" to "tailwindcss.js",
    "unpkg.com/vue@3/dist/vue.global.prod.js" to "vue.global.prod.js",
    "cdn.jsdelivr.net/npm/marked/marked.min.js" to "marked.min.js",
    "cdn.jsdelivr.net/npm/dompurify@3.0.6/dist/purify.min.js" to "purify.min.js",
    "cdn.jsdelivr.net/npm/sortablejs@latest/Sortable.min.js" to "Sortable.min.js",
    "cdn.jsdelivr.net/npm/daisyui@4.7.2/dist/full.min.css" to "daisyui.full.min.css",
    "cdn.jsdelivr.net/npm/localforage@1.10.0/dist/localforage.min.js" to "localforage.min.js"
)

private val MIME_MAP = mapOf(
    "js" to "application/javascript",
    "css" to "text/css",
    "html" to "text/html",
    "json" to "application/json",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "svg" to "image/svg+xml",
    "ico" to "image/x-icon",
    "woff" to "font/woff",
    "woff2" to "font/woff2",
    "ttf" to "font/ttf"
)

private fun guessMime(url: String): String {
    val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
    return MIME_MAP[ext] ?: "application/octet-stream"
}

private fun isRpHubAssetPath(path: String): Boolean {
    return path.startsWith("/index.html") ||
           path.startsWith("/character/") ||
           path.startsWith("/assets/") ||
           path.startsWith("/css/")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RpHubScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RP-Hub") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        AndroidView(
            factory = {
                WebView(context).apply {
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
                            val path = request.url?.path ?: ""

                            // Intercept CDN requests → serve from rp-hub-adapter/vendor/
                            for ((cdnKey, vendorFile) in CDN_VENDOR_MAP) {
                                if (url.contains(cdnKey)) {
                                    try {
                                        val stream = context.assets.open("rp-hub-adapter/vendor/$vendorFile")
                                        return WebResourceResponse(guessMime(vendorFile), "UTF-8", stream)
                                    } catch (_: Exception) {
                                        return null // fall back to network
                                    }
                                }
                            }

                            // Serve RP-Hub assets from local files (unmodified)
                            if (host.isEmpty() || host == "localhost") {
                                if (isRpHubAssetPath(path)) {
                                    val assetPath = "rp-hub$path"
                                    try {
                                        val stream = context.assets.open(assetPath)
                                        return WebResourceResponse(guessMime(path), "UTF-8", stream)
                                    } catch (_: Exception) {
                                        return null
                                    }
                                }
                            }

                            // Everything else → network (CDN fallback if vendor download failed)
                            return null
                        }
                    }

                    loadUrl("http://localhost/index.html")
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}
