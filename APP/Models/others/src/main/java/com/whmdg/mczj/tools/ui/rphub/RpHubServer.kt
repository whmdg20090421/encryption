package com.whmdg.mczj.tools.ui.rphub

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
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

                val responseHeaders = mapOf("Content-Type" to mime)
                val elapsed = System.currentTimeMillis() - startTime

                if (ext == "html") {
                    val html = stream.bufferedReader().readText()
                    val debugMode = context.getSharedPreferences(AppDataPaths.PREFS_RP_HUB, Context.MODE_PRIVATE)
                        .getBoolean("debug_mode", false)
                    val processed = processHtml(html, debugMode)
                    updateLastEntry(
                        statusCode = 200,
                        responseHeaders = responseHeaders + ("Content-Length" to processed.length.toString()),
                        responseBody = processed.take(5000),
                        responseTime = elapsed
                    )
                    return newFixedLengthResponse(Response.Status.OK, mime, processed)
                }

                // 读取完整内容用于响应，同时截取前 5000 字符用于 TrafficLog
                val fullText = try { stream.bufferedReader().readText() } catch (_: Exception) { null }
                val logBody = fullText?.take(5000)
                updateLastEntry(
                    statusCode = 200,
                    responseHeaders = responseHeaders,
                    responseBody = logBody,
                    responseTime = elapsed
                )

                if (fullText != null) {
                    return newFixedLengthResponse(Response.Status.OK, mime, fullText)
                }
                val freshStream = context.assets.open(assetPath)
                return newChunkedResponse(Response.Status.OK, mime, freshStream)
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

    private fun processHtml(html: String, debugMode: Boolean = false): String {
        // 修复 HTML 中 </script> 字面量问题：将脚本内容中的 </script> 转义为 <\/script>
        // HTML 解析器不区分 JS 上下文，正则/字符串中的 </script> 会导致脚本被提前截断
        val fixed = escapeScriptCloseInContent(html)

        // 注入调试标记（在 patch 脚本之前执行）
        val debugFlag = if (debugMode) "\n<script>window.__RP_HUB_DEBUG__ = true;</script>\n" else ""
        val patches = loadPatchScripts()
        if (patches.isEmpty() && debugFlag.isEmpty()) return fixed
        val injection = debugFlag + patches.joinToString("\n") { "<script>$it</script>" }
        return fixed.replaceFirst("<head>", "<head>\n$injection\n")
    }

    /**
     * 将 <script>...</script> 块内容中的 </script> 字面量转义为 <\/script>，
     * 防止 HTML 解析器将其误认为脚本关闭标签（常见于正则表达式和字符串中）。
     */
    private fun escapeScriptCloseInContent(html: String): String {
        val regex = Regex("</script>", RegexOption.IGNORE_CASE)
        val sb = StringBuilder()
        var pos = 0

        while (pos < html.length) {
            val tagStart = html.indexOf("<script", pos, ignoreCase = true)
            if (tagStart == -1) {
                sb.append(html, pos, html.length)
                break
            }

            // 找到 <script> 标签的 > 位置
            val tagEnd = html.indexOf('>', tagStart)
            if (tagEnd == -1) {
                sb.append(html, pos, html.length)
                break
            }

            // 复制到 <script...> 之后
            sb.append(html, pos, tagEnd + 1)

            // 找到真正的 </script> 关闭标签
            val closeTag = regex.find(html, tagEnd + 1)
            if (closeTag == null) {
                sb.append(html, tagEnd + 1, html.length)
                pos = html.length
            } else {
                val contentStart = tagEnd + 1
                val contentEnd = closeTag.range.first
                val content = html.substring(contentStart, contentEnd)
                // 将脚本内容中的 </script> 转义为 <\/script>
                sb.append(regex.replace(content, "<\\/script>"))
                // 写入真正的 </script> 关闭标签
                sb.append(closeTag.value)
                pos = closeTag.range.last + 1
            }
        }
        return sb.toString()
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
