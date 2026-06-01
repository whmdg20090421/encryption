package com.whmdg.mczj.tools.ui

import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class CdnResource(
    val name: String,
    val src: String,
    val status: Int?,
    val duration: Int?,
    val error: String?
)

data class JsError(
    val msg: String,
    val src: String,
    val line: Int,
    val col: Int,
    val stack: String
)

data class DebugSnapshot(
    val cdn: List<CdnResource>,
    val jsErrors: List<JsError>,
    val globals: Map<String, String>,
    val url: String,
    val readyState: String,
    val appChildren: Int,
    val vCloak: Int,
    val injectedScripts: Int
)

@Composable
fun RpHubDebugPanel(
    webView: WebView?,
    onDismiss: () -> Unit
) {
    var snapshot by remember { mutableStateOf<DebugSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("window.__RP_HUB_COLLECT_DEBUG__()") { json ->
                snapshot = parseDebugSnapshot(json)
                loading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Debug 诊断",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (loading) "加载中..." else "${snapshot?.cdn?.size ?: 0} 资源 | ${snapshot?.jsErrors?.size ?: 0} 错误",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        if (loading) {
            Text(
                "正在从 WebView 收集数据...",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 32.dp)
            )
        } else {
            val snap = snapshot
            if (snap == null) {
                Text(
                    "无法获取调试数据",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 32.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .height(400.dp)
                ) {
                    // CDN 资源
                    if (snap.cdn.isNotEmpty()) {
                        item {
                            SectionTitle("CDN 资源")
                        }
                        items(snap.cdn) { res ->
                            CdnResourceCard(res)
                        }
                    } else {
                        item {
                            Text(
                                "CDN 检测尚未完成",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }

                    // JS 错误
                    if (snap.jsErrors.isNotEmpty()) {
                        item {
                            SectionTitle("JS 错误 (${snap.jsErrors.size})")
                        }
                        items(snap.jsErrors) { err ->
                            JsErrorCard(err)
                        }
                    }

                    // 全局变量
                    item {
                        SectionTitle("全局变量")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            snap.globals.forEach { (name, type) ->
                                val ok = type != "undefined"
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text("$name: $type", fontSize = 10.sp) },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = if (ok) Color(0xFFDCFCE7) else Color(0xFFF3F4F6),
                                        labelColor = if (ok) Color(0xFF166534) else Color(0xFF9CA3AF)
                                    ),
                                    border = BorderStroke(1.dp, if (ok) Color(0xFFBBF7D0) else Color(0xFFE5E7EB))
                                )
                            }
                        }
                    }

                    // DOM 状态
                    item {
                        SectionTitle("DOM 状态")
                        Text("URL: ${snap.url}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("readyState: ${snap.readyState}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("#app children: ${snap.appChildren}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("[v-cloak]: ${snap.vCloak}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("injected scripts: ${snap.injectedScripts}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // 按钮栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    loading = true
                    webView?.evaluateJavascript("window.__RP_HUB_COLLECT_DEBUG__()") { json ->
                        snapshot = parseDebugSnapshot(json)
                        loading = false
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("刷新", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = {
                    webView?.evaluateJavascript("window.__RP_HUB_COLLECT_DEBUG__()") { json ->
                        val text = try {
                            buildReportText(parseDebugSnapshot(json))
                        } catch (_: Exception) { json ?: "" }
                        try {
                            android.content.ClipData.newPlainText("debug", text)?.let {
                                webView?.context?.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    ?.let { cm -> (cm as android.content.ClipboardManager).setPrimaryClip(it) }
                            }
                        } catch (_: Exception) {}
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("复制", fontSize = 13.sp)
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("关闭", fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun CdnResourceCard(res: CdnResource) {
    val ok = res.src != "fail"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(
                if (ok) Color(0xFFF0FDF4) else Color(0xFFFEF2F2),
                RoundedCornerShape(8.dp)
            )
            .padding(8.dp, 6.dp)
    ) {
        Text(
            res.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (ok) Color(0xFF166534) else Color(0xFF991B1B)
        )
        val detail = buildString {
            append(res.src)
            if (res.status != null) append(" | HTTP ${res.status}")
            if (res.duration != null) append(" ${res.duration}ms")
            if (res.error != null) append(" | ${res.error}")
        }
        Text(detail, fontSize = 11.sp, color = Color(0xFF6B7280))
    }
}

@Composable
private fun JsErrorCard(err: JsError) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(Color(0xFFFEF2F2), RoundedCornerShape(8.dp))
            .padding(8.dp, 6.dp)
    ) {
        Text(err.msg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF991B1B))
        Text(
            "${err.src.ifEmpty { "inline" }}:${err.line}",
            fontSize = 10.sp, color = Color(0xFF6B7280)
        )
        if (err.stack.isNotEmpty()) {
            Text(
                err.stack.lines().take(2).joinToString(" <- "),
                fontSize = 10.sp, color = Color(0xFF9CA3AF),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun parseDebugSnapshot(json: String?): DebugSnapshot? {
    if (json == null || json == "null") return null
    return try {
        val obj = JSONObject(json)
        val cdnArr = obj.optJSONArray("cdn") ?: org.json.JSONArray()
        val cdn = (0 until cdnArr.length()).map { i ->
            val o = cdnArr.getJSONObject(i)
            CdnResource(
                name = o.optString("name"),
                src = o.optString("src"),
                status = if (o.isNull("status")) null else o.optInt("status"),
                duration = if (o.isNull("duration")) null else o.optInt("duration"),
                error = if (o.isNull("error")) null else o.optString("error")
            )
        }
        val errArr = obj.optJSONArray("jsErrors") ?: org.json.JSONArray()
        val errors = (0 until errArr.length()).map { i ->
            val o = errArr.getJSONObject(i)
            JsError(
                msg = o.optString("msg"),
                src = o.optString("src"),
                line = o.optInt("line"),
                col = o.optInt("col"),
                stack = o.optString("stack")
            )
        }
        val globalsObj = obj.optJSONObject("globals") ?: JSONObject()
        val globals = globalsObj.keys().asSequence().associateWith { globalsObj.getString(it) }
        DebugSnapshot(
            cdn = cdn,
            jsErrors = errors,
            globals = globals,
            url = obj.optString("url"),
            readyState = obj.optString("readyState"),
            appChildren = obj.optInt("appChildren"),
            vCloak = obj.optInt("vCloak"),
            injectedScripts = obj.optInt("injectedScripts")
        )
    } catch (_: Exception) {
        null
    }
}

private fun buildReportText(snap: DebugSnapshot?): String {
    if (snap == null) return "无数据"
    val sb = StringBuilder()
    sb.appendLine("=== CDN 加载结果 ===")
    if (snap.cdn.isEmpty()) sb.appendLine("  尚未检测完成")
    else snap.cdn.forEach {
        sb.appendLine("  ${it.name}: ${it.src}${if (it.status != null) " | HTTP ${it.status} ${it.duration}ms" else ""}")
    }
    sb.appendLine()
    sb.appendLine("=== JS 错误 (${snap.jsErrors.size}) ===")
    snap.jsErrors.forEach {
        sb.appendLine("  ${it.msg} @ ${it.src.ifEmpty { "inline" }}:${it.line}")
        if (it.stack.isNotEmpty()) sb.appendLine("    ${it.stack.lines().take(2).joinToString(" <- ")}")
    }
    sb.appendLine()
    sb.appendLine("=== 全局变量 ===")
    snap.globals.forEach { (k, v) -> sb.appendLine("  $k: $v") }
    sb.appendLine()
    sb.appendLine("=== DOM 状态 ===")
    sb.appendLine("  URL: ${snap.url}")
    sb.appendLine("  readyState: ${snap.readyState}")
    sb.appendLine("  #app children: ${snap.appChildren}")
    sb.appendLine("  [v-cloak]: ${snap.vCloak}")
    sb.appendLine("  injected scripts: ${snap.injectedScripts}")
    return sb.toString()
}
