package com.whmdg.mczj.tools.ui.components

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 内置打开方式（应用内查看器）。 */
enum class BuiltInOpenMethod { DOCUMENT, IMAGE, AUDIO, VIDEO, ARCHIVE, APK }

/** 一个后缀的默认打开方式。 */
sealed interface OpenMethod {
    /** 应用内方式。 */
    data class BuiltIn(val method: BuiltInOpenMethod) : OpenMethod

    /** 第三方应用（包名 + Activity + 展示名）。 */
    data class External(
        val packageName: String,
        val activityName: String,
        val label: String
    ) : OpenMethod
}

/**
 * 「打开器 → 后缀」配置存储。
 *
 * JSON 结构（`版本` 与数据绑定；顶层键为打开器名）：
 * ```
 * {
 *   "版本": 2,
 *   "打开方式": {
 *     "文档编辑器": { "后缀": ["txt", "md", ...] },
 *     "图片查看器": { "后缀": ["png", ...] },
 *     "音频播放器": { "后缀": [...] },
 *     "视频播放器": { "后缀": [...] },
 *     "压缩包":    { "后缀": [...] },
 *     "安装包":    { "后缀": ["apk"] },
 *     "第三方应用": {
 *       "某解压器": { "包名": "com.foo", "活动": "com.foo.Main", "后缀": ["zip"] }
 *     }
 *   }
 * }
 * ```
 * - 内置打开器用中文名，仅含 `后缀`。
 * - 所有第三方应用收在 `第三方应用` 容器下，以**桌面显示名**为子键。
 * - 同一后缀全局唯一：设置新默认时会从其他所有分组移除此后缀。
 *
 * 存储通过统一入口 [AppDataPaths.prefs] 落盘；旧格式数据不迁移，直接覆盖写入。
 */
object DefaultOpenMethodStore {

    /** 当前 JSON 数据格式版本。 */
    private const val VERSION = 2

    /** AppPrefs 内的键：整张配置序列化为一个 JSON 字符串。 */
    private const val KEY_METHODS = "methods"

    /** APK 归一化 key；`.apk` 与 `.apk.<数字>` 均归一到此。 */
    const val KEY_APK = "apk"

    /** 图片缩略图缓存后缀，与打开路由保持一致（图片候选含 thumb）。 */
    const val EXT_THUMB = "thumb"

    /** 顶层字段名。 */
    private const val FIELD_VERSION = "版本"
    private const val FIELD_METHODS = "打开方式"

    /** 分组内字段名。 */
    private const val FIELD_EXTENSIONS = "后缀"
    private const val FIELD_PACKAGE = "包名"
    private const val FIELD_ACTIVITY = "活动"

    /** 第三方应用容器名（`打开方式` 下的顶层键）。 */
    private const val GROUP_EXTERNAL = "第三方应用"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** 内置打开器 → 中文键（顺序固定，便于人工查看）。 */
    private val builtInKeys: Map<BuiltInOpenMethod, String> = linkedMapOf(
        BuiltInOpenMethod.DOCUMENT to "文档编辑器",
        BuiltInOpenMethod.IMAGE to "图片查看器",
        BuiltInOpenMethod.AUDIO to "音频播放器",
        BuiltInOpenMethod.VIDEO to "视频播放器",
        BuiltInOpenMethod.ARCHIVE to "压缩包",
        BuiltInOpenMethod.APK to "安装包"
    )

    /** 中文键 → 内置打开器。 */
    private val keyToBuiltIn: Map<String, BuiltInOpenMethod> =
        builtInKeys.entries.associate { (method, name) -> name to method }

    private fun prefs(context: Context) =
        AppDataPaths.prefs(context, AppDataPaths.PREFS_FILE_MANAGER)

    /** 由文件名得到配置 key。APK（含 `.apk.N`）归一为 [KEY_APK]，其余取小写后缀。 */
    fun keyFor(fileName: String): String {
        if (isApkFileName(fileName)) return KEY_APK
        return extractExtension(fileName)
    }

    /** 首次运行写入内置默认；若已是当前版本则跳过（旧版本数据直接覆盖重建）。 */
    fun ensureInitialized(context: Context) {
        if (readRoot(context).version >= VERSION) return

        val builtIns = linkedMapOf<String, List<String>>()
        builtIns[builtInKeys.getValue(BuiltInOpenMethod.DOCUMENT)] = TEXT_EXTENSIONS.toList()
        // 图片：与 openByBuiltIn(IMAGE) 的匹配口径一致（含 thumb 缩略图后缀）
        builtIns[builtInKeys.getValue(BuiltInOpenMethod.IMAGE)] = (IMAGE_EXTENSIONS + EXT_THUMB).toList()
        builtIns[builtInKeys.getValue(BuiltInOpenMethod.AUDIO)] = AUDIO_EXTENSIONS.toList()
        builtIns[builtInKeys.getValue(BuiltInOpenMethod.VIDEO)] = VIDEO_EXTENSIONS.toList()
        builtIns[builtInKeys.getValue(BuiltInOpenMethod.ARCHIVE)] = ARCHIVE_EXTENSIONS.toList()
        builtIns[builtInKeys.getValue(BuiltInOpenMethod.APK)] = listOf(KEY_APK)

        writeRoot(context, RootData(VERSION, builtIns, emptyMap()))
    }

    /** 查询某 key 的默认打开方式，无则为 null。 */
    fun get(context: Context, key: String): OpenMethod? {
        if (key.isEmpty()) return null
        val root = readRoot(context)
        for ((name, exts) in root.builtIns) {
            if (key in exts) {
                keyToBuiltIn[name]?.let { return OpenMethod.BuiltIn(it) }
            }
        }
        for ((label, ext) in root.externals) {
            if (key in ext.extensions) {
                return OpenMethod.External(ext.packageName, ext.activityName, label)
            }
        }
        return null
    }

    /** 写入（覆盖）某 key 的默认打开方式；该 key 会从其他所有分组移除以保证唯一。 */
    fun set(context: Context, key: String, method: OpenMethod) {
        if (key.isEmpty()) return
        val root = readRoot(context)

        // 1. 从所有分组移除该后缀（内置 + 第三方）
        val builtIns = root.builtIns.mapValuesTo(LinkedHashMap<String, List<String>>()) { it.value - key }
        val externals = LinkedHashMap<String, ExternalGroup>()
        for ((label, ext) in root.externals) {
            externals[label] = ext.copy(extensions = ext.extensions - key)
        }

        // 2. 写入目标分组
        when (method) {
            is OpenMethod.BuiltIn -> {
                val name = builtInKeys.getValue(method.method)
                builtIns[name] = (builtIns[name] ?: emptyList()) + key
            }
            is OpenMethod.External -> {
                val target = externals[method.label] ?: ExternalGroup(method.packageName, method.activityName)
                externals[method.label] = target.copy(
                    packageName = method.packageName,
                    activityName = method.activityName,
                    extensions = target.extensions + key
                )
            }
        }

        // 3. 清理第三方空分组
        externals.entries.removeAll { it.value.extensions.isEmpty() }

        writeRoot(context, root.copy(builtIns = builtIns, externals = LinkedHashMap(externals)))
    }

    /** 清除某 key 的默认打开方式。 */
    fun remove(context: Context, key: String) {
        if (key.isEmpty()) return
        val root = readRoot(context)
        var changed = false
        val builtIns = root.builtIns.mapValuesTo(LinkedHashMap<String, List<String>>()) {
            if (key in it.value) { changed = true; it.value - key } else it.value
        }
        val externals = LinkedHashMap<String, ExternalGroup>()
        for ((label, ext) in root.externals) {
            if (key in ext.extensions) {
                changed = true
                val trimmed = ext.copy(extensions = ext.extensions - key)
                if (trimmed.extensions.isNotEmpty()) externals[label] = trimmed
            } else {
                externals[label] = ext
            }
        }
        if (!changed) return
        writeRoot(context, root.copy(builtIns = builtIns, externals = externals))
    }

    // ── 内存模型 ────────────────────────────────────────────────────

    /** 第三方应用分组：包名 + Activity + 该应用负责的后缀。 */
    private data class ExternalGroup(
        val packageName: String,
        val activityName: String,
        val extensions: List<String> = emptyList()
    )

    private data class RootData(
        val version: Int,
        /** 内置分组：打开器中文名 → 后缀列表。 */
        val builtIns: Map<String, List<String>>,
        /** 第三方分组：桌面显示名 → 分组。 */
        val externals: Map<String, ExternalGroup>
    )

    // ── JSON 读写 ────────────────────────────────────────────────────

    private fun readRoot(context: Context): RootData {
        val empty = RootData(0, emptyMap(), emptyMap())
        val raw = prefs(context).getString(KEY_METHODS, null) ?: return empty
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            val version = obj[FIELD_VERSION]?.jsonPrimitive?.int ?: 0
            val methodsObj = obj[FIELD_METHODS]?.jsonObject ?: JsonObject(emptyMap())

            val builtIns = LinkedHashMap<String, List<String>>()
            val externals = LinkedHashMap<String, ExternalGroup>()
            for ((name, value) in methodsObj) {
                val groupObj = value as? JsonObject ?: continue
                if (name == GROUP_EXTERNAL) {
                    for ((label, sub) in groupObj) {
                        val subObj = sub as? JsonObject ?: continue
                        externals[label] = ExternalGroup(
                            packageName = subObj[FIELD_PACKAGE]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            activityName = subObj[FIELD_ACTIVITY]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            extensions = parseExtensions(subObj)
                        )
                    }
                } else {
                    builtIns[name] = parseExtensions(groupObj)
                }
            }
            RootData(version, builtIns, externals)
        } catch (_: Exception) {
            empty
        }
    }

    private fun parseExtensions(obj: JsonObject): List<String> =
        (obj[FIELD_EXTENSIONS] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()

    /** 写入 JSON，保持固定顺序：内置六项 + 第三方容器。 */
    private fun writeRoot(context: Context, root: RootData) {
        val methodsObj = buildJsonObject {
            for ((_, name) in builtInKeys) {
                put(name, extGroup(root.builtIns[name] ?: emptyList()))
            }
            put(GROUP_EXTERNAL, buildJsonObject {
                for ((label, ext) in root.externals) {
                    put(label, buildJsonObject {
                        put(FIELD_PACKAGE, JsonPrimitive(ext.packageName))
                        put(FIELD_ACTIVITY, JsonPrimitive(ext.activityName))
                        put(FIELD_EXTENSIONS, buildJsonArray {
                            ext.extensions.forEach { add(JsonPrimitive(it)) }
                        })
                    })
                }
            })
        }
        val jsonRoot = buildJsonObject {
            put(FIELD_VERSION, JsonPrimitive(VERSION))
            put(FIELD_METHODS, methodsObj)
        }
        prefs(context).edit()
            .putString(KEY_METHODS, json.encodeToString(JsonElement.serializer(), jsonRoot))
            .apply()
    }

    private fun extGroup(extensions: List<String>): JsonObject = buildJsonObject {
        put(FIELD_EXTENSIONS, buildJsonArray {
            extensions.forEach { add(JsonPrimitive(it)) }
        })
    }
}
