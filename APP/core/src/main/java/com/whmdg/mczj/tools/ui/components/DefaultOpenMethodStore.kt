package com.whmdg.mczj.tools.ui.components

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

/** 持久化用的单条记录（字段名缩短以减小体积）。 */
@Serializable
private data class OpenMethodEntry(
    @SerialName("t") val type: String,
    @SerialName("m") val method: String? = null,
    @SerialName("pkg") val pkg: String? = null,
    @SerialName("act") val act: String? = null,
    @SerialName("label") val label: String? = null
)

/**
 * 「后缀 → 默认打开方式」配置存储。
 *
 * 首次使用时由 [ensureInitialized] 将所有内置可识别后缀写入默认值
 * （文本→文档编辑、图片→图片、音频→音频、视频→视频、压缩包→压缩包、APK→安装包）。
 * 用户可在「选择打开方式」弹窗中长按某项将其设为默认或取消默认。
 *
 * 存储：通过统一入口 [AppDataPaths.prefs]（[com.whmdg.mczj.tools.AppPrefs]）落盘，
 * 不使用系统 SharedPreferences。
 */
object DefaultOpenMethodStore {

    private const val VERSION = 1

    /** AppPrefs 内的键：整张「后缀 → 打开方式」表序列化为一个 JSON 字符串。 */
    private const val KEY_METHODS = "methods"

    /** AppPrefs 内的键：初始化版本标记。 */
    private const val KEY_VERSION = "version"

    /** APK 归一化 key；`.apk` 与 `.apk.<数字>` 均归一到此。 */
    const val KEY_APK = "apk"

    /** 图片缩略图缓存后缀，与打开路由保持一致（图片候选含 thumb）。 */
    const val EXT_THUMB = "thumb"

    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context) =
        AppDataPaths.prefs(context, AppDataPaths.PREFS_FILE_MANAGER)

    /** 由文件名得到配置 key。APK（含 `.apk.N`）归一为 [KEY_APK]，其余取小写后缀。 */
    fun keyFor(fileName: String): String {
        if (isApkFileName(fileName)) return KEY_APK
        return extractExtension(fileName)
    }

    /** 首次运行写入内置后缀的默认值；已初始化则跳过。 */
    fun ensureInitialized(context: Context) {
        val p = prefs(context)
        if (p.getInt(KEY_VERSION, 0) >= VERSION) return

        val defaults = mutableMapOf<String, OpenMethodEntry>()
        for (ext in TEXT_EXTENSIONS) defaults[ext] = inEntry(BuiltInOpenMethod.DOCUMENT)
        // 图片：与 openByBuiltIn(IMAGE) 的匹配口径一致（含 thumb 缩略图后缀）
        for (ext in IMAGE_EXTENSIONS + EXT_THUMB) defaults[ext] = inEntry(BuiltInOpenMethod.IMAGE)
        for (ext in AUDIO_EXTENSIONS) defaults[ext] = inEntry(BuiltInOpenMethod.AUDIO)
        for (ext in VIDEO_EXTENSIONS) defaults[ext] = inEntry(BuiltInOpenMethod.VIDEO)
        for (ext in ARCHIVE_EXTENSIONS) defaults[ext] = inEntry(BuiltInOpenMethod.ARCHIVE)
        defaults[KEY_APK] = inEntry(BuiltInOpenMethod.APK)

        p.edit()
            .putString(KEY_METHODS, json.encodeToString(defaults))
            .putInt(KEY_VERSION, VERSION)
            .apply()
    }

    /** 查询某 key 的默认打开方式，无则为 null。 */
    fun get(context: Context, key: String): OpenMethod? {
        if (key.isEmpty()) return null
        return read(context)[key]?.toOpenMethod()
    }

    /** 写入（覆盖）某 key 的默认打开方式。 */
    fun set(context: Context, key: String, method: OpenMethod) {
        if (key.isEmpty()) return
        val map = read(context).toMutableMap()
        map[key] = method.toEntry()
        write(context, map)
    }

    /** 清除某 key 的默认打开方式。 */
    fun remove(context: Context, key: String) {
        if (key.isEmpty()) return
        val map = read(context).toMutableMap()
        if (map.remove(key) != null) write(context, map)
    }

    private fun read(context: Context): Map<String, OpenMethodEntry> {
        val raw = prefs(context).getString(KEY_METHODS, null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, OpenMethodEntry>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun write(context: Context, map: Map<String, OpenMethodEntry>) {
        prefs(context).putString(KEY_METHODS, json.encodeToString(map))
    }

    private fun inEntry(method: BuiltInOpenMethod) = OpenMethodEntry(type = "in", method = method.name)

    private fun OpenMethod.toEntry(): OpenMethodEntry = when (this) {
        is OpenMethod.BuiltIn -> OpenMethodEntry(type = "in", method = method.name)
        is OpenMethod.External -> OpenMethodEntry(
            type = "ext", pkg = packageName, act = activityName, label = label
        )
    }

    private fun OpenMethodEntry.toOpenMethod(): OpenMethod? = when (type) {
        "in" -> method
            ?.let { runCatching { BuiltInOpenMethod.valueOf(it) }.getOrNull() }
            ?.let { OpenMethod.BuiltIn(it) }
        "ext" -> if (pkg != null && act != null) OpenMethod.External(pkg, act, label ?: pkg) else null
        else -> null
    }
}
