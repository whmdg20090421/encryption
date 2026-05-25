package com.whmdg.mczj.tools.encryption.data

import kotlinx.serialization.json.*
import java.util.*

/**
 * Python `json.dumps(obj, sort_keys=True, ensure_ascii=False)` 的 Kotlin 等价实现。
 */
object CanonicalJson {

    fun encode(element: JsonElement): String {
        val sb = StringBuilder()
        write(element, sb)
        return sb.toString()
    }

    private fun write(element: JsonElement, sb: StringBuilder) {
        when (element) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> {
                if (element.isString) {
                    writeString(element.content, sb)
                } else {
                    val content = element.content
                    if (content == "true" || content == "false") {
                        sb.append(content)
                    } else {
                        // 处理数字，保持与 Python/Dart 一致性
                        val d = content.toDoubleOrNull()
                        if (d != null && d == d.toInt().toDouble()) {
                             // 如果是整数形式的浮点数，Dart 版是 sb.write("${obj.toInt()}.0")
                             // 但如果是真正的 int，Dart 写的是 sb.write(obj)
                             // 这里需要非常小心，因为 Kotlin JsonPrimitive 不区分 Int 和 Double
                             // 按照 Dart 实现：if (obj is int) sb.write(obj) else if (obj is double) ...
                             // 我们检查原始字符串是否包含 '.'
                             if (!content.contains('.')) {
                                 sb.append(content)
                             } else {
                                 sb.append(d.toInt().toString()).append(".0")
                             }
                        } else {
                            sb.append(content)
                        }
                    }
                }
            }
            is JsonArray -> {
                sb.append("[")
                element.forEachIndexed { index, item ->
                    if (index > 0) sb.append(", ")
                    write(item, sb)
                }
                sb.append("]")
            }
            is JsonObject -> {
                val keys = element.keys.toMutableList()
                Collections.sort(keys)
                sb.append("{")
                keys.forEachIndexed { index, key ->
                    if (index > 0) sb.append(", ")
                    writeString(key, sb)
                    sb.append(": ")
                    write(element[key]!!, sb)
                }
                sb.append("}")
            }
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (char in s) {
            when (char) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\u000C' -> sb.append("\\f") // \f
                '\r' -> sb.append("\\r")
                else -> {
                    if (char.code < 0x20) {
                        sb.append("\\u")
                        sb.append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(char)
                    }
                }
            }
        }
        sb.append('"')
    }

    private val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "    "
    }

    fun prettyEncode(element: JsonElement): String {
        // 简单实现，如果需要排序可以转换成 TreeMap
        return prettyJson.encodeToString(JsonElement.serializer(), element)
    }
}
