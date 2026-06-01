package com.whmdg.mczj.tools.ui

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream

class RpHubServer(
    private val context: Context,
    port: Int = 18900
) : NanoHTTPD(port) {

    private val mimeTypes = mapOf(
        "html" to "text/html",
        "css" to "text/css",
        "js" to "application/javascript",
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

    // CDN URL → 本地 vendor 映射（补丁脚本使用）
    val cdnToLocalMap = mapOf(
        "https://cdn.tailwindcss.com" to "/vendor/tailwindcss.js",
        "https://unpkg.com/vue@3/dist/vue.global.prod.js" to "/vendor/vue.global.prod.js",
        "https://cdn.jsdelivr.net/npm/marked/marked.min.js" to "/vendor/marked.min.js",
        "https://cdn.jsdelivr.net/npm/dompurify@3.0.6/dist/purify.min.js" to "/vendor/purify.min.js",
        "https://cdn.jsdelivr.net/npm/sortablejs@latest/Sortable.min.js" to "/vendor/Sortable.min.js",
        "https://cdn.jsdelivr.net/npm/daisyui@4.7.2/dist/full.min.css" to "/vendor/daisyui.full.min.css",
        "https://cdn.jsdelivr.net/npm/localforage@1.10.0/dist/localforage.min.js" to "/vendor/localforage.min.js"
    )

    override fun serve(session: IHTTPSession): Response {
        var uri = session.uri
        if (uri == "/") uri = "/index.html"

        val assetPaths = listOf("rp-hub$uri", "rp-hub-adapter$uri")

        for (assetPath in assetPaths) {
            try {
                val stream: InputStream = context.assets.open(assetPath)
                val ext = uri.substringAfterLast('.', "").lowercase()
                val mime = mimeTypes[ext] ?: "application/octet-stream"

                if (ext == "html") {
                    val html = stream.bufferedReader().readText()
                    val processed = processHtml(html)
                    return newFixedLengthResponse(Response.Status.OK, mime, processed)
                }

                return newChunkedResponse(Response.Status.OK, mime, stream)
            } catch (_: Exception) {
                // try next path
            }
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
    }

    private fun processHtml(html: String): String {
        val patches = loadPatchScripts()
        if (patches.isEmpty()) return html
        // 注入到 <head> 之后（在 CDN 脚本之前执行）
        val injection = "\n" + patches.joinToString("\n") { "<script>$it</script>" } + "\n"
        return html.replace("<head>", "<head>$injection")
    }

    private fun loadPatchScripts(): List<String> {
        val scripts = mutableListOf<String>()
        try {
            val files = context.assets.list("rp-hub-adapter/patches") ?: emptyArray()
            for (file in files.sorted()) {
                if (file.endsWith(".js")) {
                    scripts.add(context.assets.open("rp-hub-adapter/patches/$file").bufferedReader().readText())
                }
            }
        } catch (_: Exception) {}
        return scripts
    }
}
