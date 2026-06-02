package com.whmdg.mczj.tools.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
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
import com.whmdg.mczj.tools.R

/** 文件类型分类 */
enum class FileCategory {
    DOCUMENT,   // 文档类：txt, pdf, doc, docx, xls, xlsx, ppt, pptx, csv, rtf, md
    IMAGE,      // 图片类：jpg, jpeg, png, gif, bmp, webp, svg, ico, tiff, heic, heif
    VIDEO,      // 视频类：mp4, mkv, avi, mov, wmv, flv, webm, 3gp, ts, rmvb
    AUDIO,      // 音频类：mp3, flac, wav, aac, ogg, wma, m4a, opus, amr
    ARCHIVE,    // 压缩包：zip, 7z, rar, tar, gz, bz2, xz, lz4, zst
    APK,        // 安装包：apk, xapk, apks, aab
    CODE,       // 代码文件：kt, java, py, js, ts, html, css, xml, json, c, cpp, h, go, rs, sh, bat, sql, yaml, yml, toml
    OTHER       // 其他：无法识别的后缀
}

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
    "txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "csv", "rtf", "md", "epub", "mobi", "pages", "numbers", "keynote" -> FileCategory.DOCUMENT
    "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico",
    "tiff", "tif", "heic", "heif", "raw", "cr2", "nef", "avif" -> FileCategory.IMAGE
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp",
    "ts", "rmvb", "rm", "vob", "m4v", "f4v" -> FileCategory.VIDEO
    "mp3", "flac", "wav", "aac", "ogg", "wma", "m4a", "opus",
    "amr", "ape", "aiff", "mid", "midi" -> FileCategory.AUDIO
    "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "lz4", "zst",
    "lzma", "cab", "iso", "dmg" -> FileCategory.ARCHIVE
    "apk", "xapk", "apks", "aab" -> FileCategory.APK
    "kt", "java", "py", "js", "ts", "tsx", "jsx", "html", "htm",
    "css", "scss", "less", "xml", "json", "c", "cpp", "h", "hpp",
    "go", "rs", "rb", "php", "swift", "dart", "lua", "r", "m",
    "sh", "bash", "bat", "cmd", "ps1", "sql", "yaml", "yml",
    "toml", "ini", "cfg", "conf", "gradle", "properties", "gitignore",
    "dockerfile", "makefile", "cmake" -> FileCategory.CODE
    else -> FileCategory.OTHER
}

/** 获取文件类型对应的彩色图标资源 ID，APK 和 OTHER 返回 null（APK 动态加载） */
fun getFileTypeDrawableRes(category: FileCategory): Int? = when (category) {
    FileCategory.DOCUMENT -> R.drawable.file_type_document
    FileCategory.IMAGE -> R.drawable.file_type_image
    FileCategory.VIDEO -> R.drawable.file_type_video
    FileCategory.AUDIO -> R.drawable.file_type_audio
    FileCategory.ARCHIVE -> R.drawable.file_type_archive
    FileCategory.APK -> null  // APK 图标从文件动态读取
    FileCategory.CODE -> R.drawable.file_type_code
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
 * @param size 图标尺寸
 * @param fallbackIcon 无法识别时的兜底图标
 */
@Composable
fun FileTypeIcon(
    filename: String,
    filePath: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
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
                modifier = modifier.then(Modifier.size(size))
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
            modifier = modifier.then(Modifier.size(size))
        )
    } else {
        Icon(
            imageVector = fallbackIcon,
            contentDescription = null,
            modifier = modifier.then(Modifier.size(size))
        )
    }
}
