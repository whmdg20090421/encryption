package com.whmdg.mczj.tools

import android.util.Xml
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * 受管键值配置存储（替代系统的 `SharedPreferences`）。
 *
 * ## 为什么不用 SharedPreferences
 *
 * 系统 `SharedPreferences` 的落盘位置 `/data/data/<包名>/shared_prefs/` 被本项目列为禁区，
 * 且该路径由框架硬编码、无法重定向。若实现一个同名 `SharedPreferences` 接口，会与系统
 * 原生调用难以区分、排查困难。因此这里用**独立命名**的 [AppPrefs]：
 * - 合法入口只有 [AppDataPaths.prefs]；
 * - 全项目检索 `getSharedPreferences` 即可发现所有违规调用；
 * - 全项目检索 `AppPrefs` 即可发现所有合规调用。
 *
 * 方法面刻意与 `SharedPreferences` 保持一致（`getXxx` / `edit().putXxx().apply()`），
 * 以便既有调用点只需替换「获取那行」，降低迁移风险。
 *
 * ## 存储
 *
 * 每个配置对应受管目录下一个 JSON 文件（`{模块目录}/<name>.json`；无模块归属的全局配置
 * 落入 `艨艟战舰工具箱数据/全局设置/`），值带类型标签以区分 String / Int / Long / Float /
 * Boolean / StringSet。具体目录由 [AppDataPaths] 按配置名解析。
 *
 * ## 兼容迁移
 *
 * 首次加载时若受管 JSON 不存在，会**直接解析旧 `shared_prefs/<name>.xml`**（不调用
 * `getSharedPreferences`）导入历史数据，随后删除旧 XML。
 *
 * 线程安全：内部读写均加锁。
 */
class AppPrefs internal constructor(
    private val file: File,
    private val legacyFile: File?
) {

    private val lock = Any()
    private val map = LinkedHashMap<String, Any?>()

    @Volatile
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (!loaded) {
                loadLocked()
                loaded = true
            }
        }
    }

    private fun loadLocked() {
        if (file.exists()) {
            runCatching { parseJson(file.readText()) }.getOrNull()?.let { map.putAll(it) }
            return
        }
        val legacy = legacyFile
        if (legacy != null && legacy.exists()) {
            runCatching { importLegacyXml(legacy) }.getOrNull()?.let { imported ->
                map.putAll(imported)
                persistLocked()
                runCatching { legacy.delete() }
            }
        }
    }

    private fun persistLocked() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(encodeJson())
        }
    }

    // ── 读取 ──

    fun getAll(): MutableMap<String, Any?> {
        ensureLoaded()
        synchronized(lock) { return LinkedHashMap(map) }
    }

    fun getString(key: String, defValue: String?): String? {
        ensureLoaded()
        synchronized(lock) { return map[key] as? String ?: defValue }
    }

    @Suppress("UNCHECKED_CAST")
    fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        ensureLoaded()
        synchronized(lock) {
            val v = map[key]
            return if (v is Set<*>) (v as Set<String>).toMutableSet() else defValues
        }
    }

    fun getInt(key: String, defValue: Int): Int {
        ensureLoaded()
        synchronized(lock) { return map[key] as? Int ?: defValue }
    }

    fun getLong(key: String, defValue: Long): Long {
        ensureLoaded()
        synchronized(lock) { return map[key] as? Long ?: defValue }
    }

    fun getFloat(key: String, defValue: Float): Float {
        ensureLoaded()
        synchronized(lock) { return map[key] as? Float ?: defValue }
    }

    fun getBoolean(key: String, defValue: Boolean): Boolean {
        ensureLoaded()
        synchronized(lock) { return map[key] as? Boolean ?: defValue }
    }

    fun contains(key: String): Boolean {
        ensureLoaded()
        synchronized(lock) { return map.containsKey(key) }
    }

    // ── 编辑（与 SharedPreferences 同形） ──

    fun edit(): Editor {
        ensureLoaded()
        return Editor()
    }

    inner class Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clearRequested = false

        fun putString(key: String, value: String?): Editor = set(key, value)
        fun putStringSet(key: String, value: Set<String>?): Editor = set(key, value?.toSet())
        fun putInt(key: String, value: Int): Editor = set(key, value)
        fun putLong(key: String, value: Long): Editor = set(key, value)
        fun putFloat(key: String, value: Float): Editor = set(key, value)
        fun putBoolean(key: String, value: Boolean): Editor = set(key, value)

        fun remove(key: String): Editor {
            synchronized(lock) {
                removals.add(key)
                changes.remove(key)
            }
            return this
        }

        fun clear(): Editor {
            synchronized(lock) {
                clearRequested = true
                changes.clear()
                removals.clear()
            }
            return this
        }

        private fun set(key: String, value: Any?): Editor {
            synchronized(lock) {
                removals.remove(key)
                changes[key] = value
            }
            return this
        }

        fun commit(): Boolean {
            return applyChanges()
        }

        fun apply() {
            applyChanges()
        }

        /** 合并变更 → 落盘；成功 true。 */
        private fun applyChanges(): Boolean {
            synchronized(lock) {
                ensureLoaded()
                if (clearRequested) map.clear()
                for (k in removals) map.remove(k)
                for ((k, v) in changes) {
                    if (v == null) map.remove(k) else map[k] = v
                }
                return runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(encodeJson())
                    true
                }.getOrDefault(false)
            }
        }
    }

    // ── 序列化：带类型标签的 JSON ──

    private fun encodeJson(): String {
        val obj = buildJsonObject {
            synchronized(lock) {
                for ((k, v) in map) encodeValue(v)?.let { put(k, it) }
            }
        }
        return obj.toString()
    }

    private fun parseJson(raw: String): Map<String, Any?> {
        val obj = Json.parseToJsonElement(raw).jsonObject
        val out = LinkedHashMap<String, Any?>()
        for ((k, el) in obj) {
            val o = el as? JsonObject ?: continue
            decodeValue(o)?.let { out[k] = it }
        }
        return out
    }

    private fun encodeValue(v: Any?): JsonObject? = when (v) {
        is String -> typed("s", v)
        is Int -> typed("i", v)
        is Long -> typed("l", v)
        is Float -> typed("f", v)
        is Boolean -> typed("b", v)
        is Set<*> -> buildJsonObject {
            put("t", "ss")
            put("v", buildJsonArray { for (item in v) add(JsonPrimitive(item?.toString() ?: "")) })
        }
        else -> null
    }

    private fun decodeValue(o: JsonObject): Any? = when (o["t"]?.jsonPrimitive?.contentOrNull) {
        "s" -> o["v"]?.jsonPrimitive?.contentOrNull
        "i" -> o["v"]?.jsonPrimitive?.intOrNull
        "l" -> o["v"]?.jsonPrimitive?.longOrNull
        "f" -> o["v"]?.jsonPrimitive?.floatOrNull
        "b" -> o["v"]?.jsonPrimitive?.booleanOrNull
        "ss" -> (o["v"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
        else -> null
    }

    private fun typed(type: String, value: Any): JsonObject = buildJsonObject {
        put("t", type)
        when (value) {
            is String -> put("v", value)
            is Boolean -> put("v", value)
            is Int -> put("v", value)
            is Long -> put("v", value)
            is Float -> put("v", value)
        }
    }

    // ── 旧 shared_prefs XML 解析（直接读文件，不经 getSharedPreferences） ──

    private fun importLegacyXml(legacy: File): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val parser = Xml.newPullParser()
        legacy.inputStream().use { parser.setInput(it, "utf-8") }

        var current: String? = null
        var type: String? = null
        val setValues = mutableListOf<String>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    if (tag == "set") {
                        current = parser.getAttributeValue(null, "name")
                        type = "set"
                        setValues.clear()
                    } else if (tag != "map") {
                        val name = parser.getAttributeValue(null, "name")
                        if (type == "set" && tag == "string") {
                            // set 内条目，文本在后续 TEXT 事件读取
                        } else if (name != null) {
                            current = name
                            type = tag
                            val attr = parser.getAttributeValue(null, "value")
                            if (attr != null) {
                                out[name] = convertValue(tag, attr)
                                current = null
                                type = null
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text ?: ""
                    if (type == "set") {
                        if (text.isNotBlank()) setValues.add(text)
                    } else if (current != null && type != null) {
                        out[current!!] = convertValue(type!!, text)
                        current = null
                        type = null
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "set" && current != null) {
                        out[current!!] = setValues.toSet()
                        current = null
                        type = null
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun convertValue(type: String, value: String): Any? = when (type) {
        "string" -> value
        "int" -> value.toIntOrNull()
        "long" -> value.toLongOrNull()
        "float" -> value.toFloatOrNull()
        "boolean" -> value.toBooleanStrictOrNull()
        else -> null
    }
}
