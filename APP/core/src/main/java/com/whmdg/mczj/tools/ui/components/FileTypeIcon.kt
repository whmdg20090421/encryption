package com.whmdg.mczj.tools.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.whmdg.mczj.tools.core.R

/** 文件类型分类 */
enum class FileCategory {
    DOCUMENT,   // 文档类：txt, pdf, doc, docx, xls, xlsx, ppt, pptx, csv, rtf, md
    IMAGE,      // 图片类：jpg, jpeg, png, gif, bmp, webp, svg, ico, tiff, heic, heif
    VIDEO,      // 视频类：见 VIDEO_EXTENSIONS（取自 Media3 官方 FileTypes 视频容器家族）
    AUDIO,      // 音频类：mp3, flac, wav, aac, ogg, wma, m4a, opus, amr

    APK,        // 安装包：apk, xapk, apks, aab
    CODE,       // 代码文件：kt, java, py, js, ts, html, css, xml, json, c, cpp, h, go, rs, sh, bat, sql, yaml, yml, toml
    ARCHIVE,    // 压缩包：zip, 7z, rar, tar, gz, bz2, xz, lz4, zst, lzma, cab, iso, dmg
    OTHER       // 其他：无法识别的后缀
}

/**
 * 视频后缀集合（唯一数据源，供图标 / 缩略图 / 打开路由共用）。
 *
 * 取自 Media3 官方 FileTypes 中属于视频容器家族的后缀：FLV / MATROSKA / MP4 / PS / TS / AVI。
 * 仅收录视频意义明确的后缀，避免把 .m4a 等纯音频误判为视频。
 */
val VIDEO_EXTENSIONS: Set<String> = setOf(
    "mp4", "m4v", "mkv", "webm", "flv", "ps", "mpg", "mpeg", "m2p", "ts", "avi"
)

/**
 * 音频后缀集合（唯一数据源，供打开路由共用）。
 */
val AUDIO_EXTENSIONS: Set<String> = setOf(
    "mp3", "flac", "wav", "aac", "ogg", "wma", "m4a", "opus",
    "amr", "ape", "aiff", "mid", "midi"
)

/**
 * 图片后缀集合（唯一数据源，供图标 / 打开路由共用）。
 */
val IMAGE_EXTENSIONS: Set<String> = setOf(
    "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico",
    "tiff", "tif", "heic", "heif", "raw", "cr2", "nef", "avif", "jxl"
)

/**
 * 文本后缀集合（唯一数据源，供内置文本编辑器打开路由共用）。
 *
 * 仅收录可被纯文本编辑器正确读取的类型；PDF / DOC / DOCX 等
 * 二进制文档不在此列，交由外部应用打开。
 *
 * 注意：不包含 "ts"——.ts 优先按视频处理，播放失败时再询问是否改用文本编辑器。
 */
val TEXT_EXTENSIONS: Set<String> = setOf(
    "txt", "md", "json", "xml", "html", "htm", "css", "js",
    "kt", "java", "py", "sh", "bat", "log", "csv", "yaml", "yml",
    "toml", "ini", "conf", "cfg", "properties", "gradle", "kts",
    "c", "cpp", "h", "hpp", "rs", "go", "rb", "php", "sql",
    "lua", "r", "swift", "dart", "jsx", "tsx", "vue"
)

/**
 * 二进制文档后缀集合（唯一数据源，供图标分类共用）。
 */
val DOCUMENT_EXTENSIONS: Set<String> = setOf(
    "txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "csv", "rtf", "md", "epub", "mobi", "pages", "numbers", "keynote"
)

/**
 * 代码/配置后缀集合（唯一数据源，供图标分类共用）。
 */
val CODE_EXTENSIONS: Set<String> = setOf(
    "kt", "java", "py", "js", "ts", "tsx", "jsx", "html", "htm",
    "css", "scss", "less", "xml", "json", "c", "cpp", "h", "hpp",
    "go", "rs", "rb", "php", "swift", "dart", "lua", "r", "m",
    "sh", "bash", "bat", "cmd", "ps1", "sql", "yaml", "yml",
    "toml", "ini", "cfg", "conf", "gradle", "properties", "gitignore",
    "dockerfile", "makefile", "cmake"
)

/**
 * 压缩包后缀集合（唯一数据源，供图标分类与压缩包打开路由共用）。
 */
val ARCHIVE_EXTENSIONS: Set<String> = setOf(
    "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "lz4", "zst",
    "lzma", "cab", "iso", "dmg", "ar", "cpio", "wim", "xar"
)

/**
 * 安装包后缀集合（唯一数据源，供图标分类共用）。
 */
val APK_EXTENSIONS: Set<String> = setOf(
    "apk", "xapk", "apks", "aab"
)

/** 从文件名提取后缀（小写，不含点号） */
fun extractExtension(filename: String): String {
    val dotIndex = filename.lastIndexOf('.')
    return if (dotIndex > 0 && dotIndex < filename.length - 1) {
        filename.substring(dotIndex + 1).lowercase()
    } else {
        ""
    }
}

/** 后缀 → 文件类型分类 */
fun categorizeFile(extension: String): FileCategory = when (extension) {
    in DOCUMENT_EXTENSIONS -> FileCategory.DOCUMENT
    in IMAGE_EXTENSIONS -> FileCategory.IMAGE
    in VIDEO_EXTENSIONS -> FileCategory.VIDEO
    in AUDIO_EXTENSIONS -> FileCategory.AUDIO
    in ARCHIVE_EXTENSIONS -> FileCategory.ARCHIVE
    in APK_EXTENSIONS -> FileCategory.APK
    in CODE_EXTENSIONS -> FileCategory.CODE
    else -> FileCategory.OTHER
}

/** 获取文件类型对应的彩色图标资源 ID，APK 和 OTHER 返回 null（APK 动态加载） */
fun getFileTypeDrawableRes(category: FileCategory): Int? = when (category) {
    FileCategory.DOCUMENT -> R.drawable.file_type_document
    FileCategory.IMAGE -> R.drawable.file_type_image
    FileCategory.VIDEO -> R.drawable.file_type_video
    FileCategory.AUDIO -> R.drawable.file_type_audio

    FileCategory.APK -> null  // APK 图标从文件动态读取
    FileCategory.CODE -> R.drawable.file_type_code
    FileCategory.ARCHIVE -> R.drawable.file_type_archive
    FileCategory.OTHER -> null
}

/** 从 APK 文件中提取应用图标 */
private fun loadApkIcon(context: Context, apkPath: String): Drawable? {
    return try {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_META_DATA)
        info?.applicationInfo?.let { appInfo ->
            appInfo.sourceDir = apkPath
            appInfo.publicSourceDir = apkPath
            pm.getApplicationIcon(appInfo)
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * 文件类型图标 Composable
 *
 * @param filename 文件名（用于判断后缀）
 * @param filePath 文件完整路径（APK 图标提取需要）
 * @param modifier Modifier
 * @param iconSize 图标尺寸
 * @param fallbackIcon 无法识别时的兜底图标
 */
@Composable
fun FileTypeIcon(
    filename: String,
    filePath: String? = null,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
    fallbackIcon: ImageVector = Icons.Default.InsertDriveFile
) {
    val category = categorizeFile(extractExtension(filename))
    val context = LocalContext.current

    // APK 文件：动态读取应用自身图标
    if (category == FileCategory.APK && filePath != null) {
        val apkIconPainter = remember(filePath) { mutableStateOf<BitmapPainter?>(null) }

        LaunchedEffect(filePath) {
            val drawable = loadApkIcon(context, filePath)
            if (drawable != null) {
                val bitmap = drawable.toBitmap(
                    width = drawable.intrinsicWidth.coerceAtMost(128),
                    height = drawable.intrinsicHeight.coerceAtMost(128)
                )
                apkIconPainter.value = BitmapPainter(bitmap.asImageBitmap())
            }
        }

        val painter = apkIconPainter.value
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = modifier.then(Modifier.size(iconSize))
            )
            return
        }
        // 加载失败，降级到默认图标
    }

    // 非 APK 或 APK 加载失败：使用静态彩色图标
    val drawableRes = getFileTypeDrawableRes(category)
    if (drawableRes != null) {
        Icon(
            painter = painterResource(drawableRes),
            contentDescription = null,
            modifier = modifier.then(Modifier.size(iconSize))
        )
    } else {
        Icon(
            imageVector = fallbackIcon,
            contentDescription = null,
            modifier = modifier.then(Modifier.size(iconSize))
        )
    }
}
