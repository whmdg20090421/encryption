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

    override fun serve(session: IHTTPSession): Response {
        var uri = session.uri
        if (uri == "/") uri = "/index.html"

        val assetPaths = listOf("rp-hub$uri", "rp-hub-adapter$uri")

        for (assetPath in assetPaths) {
            try {
                val stream: InputStream = context.assets.open(assetPath)
                val ext = uri.substringAfterLast('.', "").lowercase()
                val mime = mimeTypes[ext] ?: "application/octet-stream"

                // 对 HTML 文件注入适配脚本（不修改原文件）
                if (ext == "html") {
                    val html = stream.bufferedReader().readText()
                    val injected = injectPatches(html)
                    return newFixedLengthResponse(Response.Status.OK, mime, injected)
                }

                return newChunkedResponse(Response.Status.OK, mime, stream)
            } catch (_: Exception) {
                // try next path
            }
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
    }

    private fun injectPatches(html: String): String {
        val patches = loadPatchScripts()
        if (patches.isEmpty()) return html
        val injection = "\n" + patches.joinToString("\n") { "<script>$it</script>" } + "\n"
        return html.replace("</head>", "$injection</head>")
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
