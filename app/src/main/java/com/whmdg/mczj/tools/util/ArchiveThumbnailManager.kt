package com.whmdg.mczj.tools.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.whmdg.mczj.tools.ui.FileEntry
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 压缩包内图片缩略图缓存管理器。
 *
 * 按需解压单个图片 → 缩放到 200×200 → 内存缓存。
 * 退出压缩包时调用 [clearCache] 释放全部内存。
 */
object ArchiveThumbnailManager {

    private const val THUMBNAIL_SIZE = 200

    /** 缓存：key = "archivePath|entryPath" → 缩略图 */
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    /** 正在加载的任务：key 同上，用于避免重复加载和取消 */
    private val loadingJobs = ConcurrentHashMap<String, Job>()

    /** 缓存更新计数器，用于触发 Compose recomposition */
    private var _cacheVersion = mutableIntStateOf(0)

    /** 公开的缓存版本，用于在 Compose 中观察 */
    val cacheVersion: Int get() = _cacheVersion.intValue

    /** 检查是否已有缓存 */
    fun getThumbnail(archivePath: String, entryPath: String): ImageBitmap? {
        return cache[key(archivePath, entryPath)]
    }

    /** 提交预加载任务（异步，不阻塞） */
    fun preloadThumbnail(
        scope: CoroutineScope,
        archivePath: String,
        format: String,
        password: String,
        entryPath: String,
        memEntry: CompressService.ArchiveMemFile
    ) {
        val k = key(archivePath, entryPath)
        if (cache.containsKey(k)) return       // 已缓存
        if (loadingJobs.containsKey(k)) return  // 已在加载

        loadingJobs[k] = scope.launch(Dispatchers.IO) {
            try {
                val data = CompressService.extractSingleFile(archivePath, format, password, memEntry)
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                    ?: return@launch
                val scaled = Bitmap.createScaledBitmap(bitmap, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true)
                if (scaled !== bitmap) bitmap.recycle()
                cache[k] = scaled.asImageBitmap()
                // 触发 recomposition
                _cacheVersion.intValue++
            } catch (_: Exception) {
                // 解压失败，静默跳过，列表项继续显示默认图标
            } finally {
                loadingJobs.remove(k)
            }
        }
    }

    /** 批量预加载指定范围的图片条目 */
    fun preloadRange(
        scope: CoroutineScope,
        archivePath: String,
        format: String,
        password: String,
        memFs: CompressService.ArchiveMemFs,
        entries: List<FileEntry>,
        startIndex: Int,
        endIndex: Int
    ) {
        for (i in startIndex.coerceAtLeast(0)..endIndex.coerceAtMost(entries.lastIndex)) {
            val entry = entries[i]
            if (entry.isDirectory) continue
            val ext = entry.name.substringAfterLast('.', "").lowercase()
            if (ext !in IMAGE_EXTENSIONS) continue
            val memEntry = memFs.entries[entry.path] as? CompressService.ArchiveMemFile ?: continue
            preloadThumbnail(scope, archivePath, format, password, entry.path, memEntry)
        }
    }

    /** 退出压缩包时清空缓存 */
    fun clearCache() {
        loadingJobs.values.forEach { it.cancel() }
        loadingJobs.clear()
        cache.clear()
        _cacheVersion.intValue++
    }

    private fun key(archivePath: String, entryPath: String) = "$archivePath|$entryPath"

    private val IMAGE_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "jxl", "thumb"
    )
}
