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

    private var cdnReplacements: Map<String, String> = emptyMap()

    override fun start() {
        super.start()
        cdnReplacements = loadCdnReplacements()
    }

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
        var result = html
        // 替换 CDN URL 为本地 vendor 路径（不修改原文件，在返回时动态替换）
        for ((cdnUrl, localPath) in cdnReplacements) {
            result = result.replace(cdnUrl, "http://localhost:$listeningPort$localPath")
        }
        // 注入 patches 目录下的 JS 脚本
        val patches = loadPatchScripts()
        if (patches.isNotEmpty()) {
            val injection = "\n" + patches.joinToString("\n") { "<script>$it</script>" } + "\n"
            result = result.replace("</head>", "$injection</head>")
        }
        return result
    }

    private fun loadCdnReplacements(): Map<String, String> {
        return try {
            val json = context.assets.open("rp-hub-adapter/patches/cdn-replacements.json")
                .bufferedReader().readText()
            val map = mutableMapOf<String, String>()
            val entries = json.trim().removeSurrounding("{", "}").split(",")
            for (entry in entries) {
                val parts = entry.split(":", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().removeSurrounding("\"")
                    val value = parts[1].trim().removeSurrounding("\"")
                    map[key] = value
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
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
