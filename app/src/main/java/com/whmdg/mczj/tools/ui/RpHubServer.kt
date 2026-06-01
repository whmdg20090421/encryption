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
        val startTime = System.currentTimeMillis()
        var uri = session.uri
        if (uri == "/") uri = "/index.html"

        // 解析请求详情
        val headers = session.headers.entries.associate { it.key to it.value }
        val params = session.parms.entries.associate { it.key to it.value }
        val cookies = headers["cookie"]

        // 读取请求体（POST/PUT）
        var requestBody: String? = null
        if (session.method == Method.POST || session.method == Method.PUT) {
            try {
                val bodyMap = HashMap<String, String>()
                session.parseBody(bodyMap)
                // NanoHTTPD 将表单数据存在 bodyMap["postData"] 中
                requestBody = bodyMap["postData"]
            } catch (_: Exception) {}
        }

        // 记录请求到 TrafficLog
        TrafficLog.add(
            TrafficEntry(
                url = "http://localhost:$listeningPort$uri",
                method = session.method.name,
                isLocal = true,
                requestHeaders = headers,
                requestParams = params,
                requestBody = requestBody,
                cookies = cookies
            )
        )

        val assetPaths = listOf("rp-hub$uri", "rp-hub-adapter$uri")

        for (assetPath in assetPaths) {
            try {
                val stream: InputStream = context.assets.open(assetPath)
                val ext = uri.substringAfterLast('.', "").lowercase()
                val mime = mimeTypes[ext] ?: "application/octet-stream"

                if (ext == "html") {
                    val html = stream.bufferedReader().readText()
                    val processed = processHtml(html)
                    val elapsed = System.currentTimeMillis() - startTime

                    // 更新最后一条记录的响应信息
                    updateLastEntry(
                        statusCode = 200,
                        responseHeaders = mapOf("Content-Type" to mime, "Content-Length" to processed.length.toString()),
                        responseBody = processed.take(2000),
                        responseTime = elapsed
                    )

                    return newFixedLengthResponse(Response.Status.OK, mime, processed)
                }

                val elapsed = System.currentTimeMillis() - startTime
                updateLastEntry(statusCode = 200, responseTime = elapsed)
                return newChunkedResponse(Response.Status.OK, mime, stream)
            } catch (_: Exception) {
                // try next path
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        updateLastEntry(statusCode = 404, responseTime = elapsed)
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
    }

    private fun updateLastEntry(
        statusCode: Int? = null,
        responseHeaders: Map<String, String> = emptyMap(),
        responseBody: String? = null,
        responseTime: Long? = null
    ) {
        val entries = TrafficLog.entries
        if (entries.isEmpty()) return
        val last = entries[0]
        if (!last.isLocal) return
        entries[0] = last.copy(
            statusCode = statusCode ?: last.statusCode,
            responseHeaders = responseHeaders.ifEmpty { last.responseHeaders },
            responseBody = responseBody ?: last.responseBody,
            responseTime = responseTime ?: last.responseTime
        )
    }

    private fun processHtml(html: String): String {
        val patches = loadPatchScripts()
        if (patches.isEmpty()) return html
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
