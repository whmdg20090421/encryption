package com.whmdg.mczj.tools.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import java.io.File

class VaultThumbnailFetcher(
    private val context: Context,
    private val data: VaultThumbnailRequest,
    private val options: Options,
    private val cacheDir: File
) : Fetcher {

    private val videoExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp",
        "ts", "rmvb", "rm", "vob", "m4v", "f4v"
    )

    // ── 缓存路径 ──

    private val videoCacheDir: File by lazy {
        val extDir = context.getExternalFilesDir(null)
            ?: File(context.filesDir, "video_cache")
        // 保险箱相对路径：/sdcard/.../vault_1/secret/1.mp4 → secret/1.mp4
        val relativePath = data.encryptedPath.removePrefix(data.vaultDir).removePrefix("/")
        File(extDir, "视频缓存/${data.vaultName}/${File(relativePath).parent}")
    }

    private fun cacheFileForVideo(): File =
        File(videoCacheDir, "${File(data.encryptedPath).name}.thumb")

    private fun metaFileForVideo(): File =
        File(videoCacheDir, "cache_meta.txt")

    // ── 元数据读写 ──

    private fun readMeta(): LinkedHashMap<String, Pair<Long, Long>> {
        val metaFile = metaFileForVideo()
        val map = linkedMapOf<String, Pair<Long, Long>>()
        if (!metaFile.exists()) return map
        metaFile.readLines().forEach { line ->
            val parts = line.split("|")
            if (parts.size == 3) {
                val ts = parts[1].toLongOrNull() ?: return@forEach
                val sz = parts[2].toLongOrNull() ?: return@forEach
                map[parts[0]] = ts to sz
            }
        }
        return map
    }

    private fun writeMeta(map: LinkedHashMap<String, Pair<Long, Long>>) {
        val metaFile = metaFileForVideo()
        metaFile.parentFile?.mkdirs()
        metaFile.writeText(map.entries.joinToString("\n") { (name, pair) ->
            "$name|${pair.first}|${pair.second}"
        })
    }

    private fun isEntryValid(meta: LinkedHashMap<String, Pair<Long, Long>>, filename: String): Boolean {
        val srcFile = File(data.encryptedPath)
        val recorded = meta[filename] ?: return false
        return srcFile.lastModified() == recorded.first && srcFile.length() == recorded.second
    }

    // ── 入口 ──

    override suspend fun fetch(): FetchResult {
        val isVideo = data.entryPath.substringAfterLast('.', "").lowercase() in videoExtensions
        if (isVideo) return fetchVideoThumbnail()

        // 图片：沿用原有逻辑
        val baseFile = File(cacheDir, "vault_cache/${data.vaultName}/${data.entryPath}")
        val thumbFile = File("${baseFile.absolutePath}.thumb")

        val imageFile = when {
            baseFile.exists() -> baseFile
            thumbFile.exists() -> thumbFile
            else -> {
                val bitmap = VaultThumbnailExtractor.extractThumbnail(
                    encryptedPath = data.encryptedPath,
                    dek = data.dek,
                    customEncryption = data.customEncryption,
                    targetSize = 200
                ) ?: return whiteResult()

                thumbFile.parentFile?.mkdirs()
                thumbFile.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it)
                }
                bitmap.recycle()
                thumbFile
            }
        }

        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return whiteResult()
        return ImageFetchResult(image = bitmap.asImage(), isSampled = false, dataSource = DataSource.DISK)
    }

    // ── 视频缩略图 ──

    private suspend fun fetchVideoThumbnail(): FetchResult {
        val filename = File(data.encryptedPath).name
        val cacheFile = cacheFileForVideo()
        val meta = readMeta()

        // 校验：元数据存在 + 缓存文件存在 + 源文件未变化
        if (meta.containsKey(filename) && cacheFile.exists() && isEntryValid(meta, filename)) {
            val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
            if (bitmap != null) {
                return ImageFetchResult(image = bitmap.asImage(), isSampled = false, dataSource = DataSource.DISK)
            }
        }

        // 缓存失效 → 删除旧缓存
        if (cacheFile.exists()) cacheFile.delete()

        // 部分解密（4MB 足够覆盖视频头+首个关键帧）→ 临时文件 → 提取首帧
        val bytes = VaultThumbnailExtractor.decryptPartialToBytes(
            File(data.encryptedPath), data.dek, data.customEncryption, maxBytes = 4L * 1024 * 1024
        ) ?: return whiteResult()

        val tmpFile = File.createTempFile("vault_vid_", ".tmp")
        val bitmap = try {
            tmpFile.writeBytes(bytes)
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(tmpFile.absolutePath, null)
                retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
                null
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        } finally {
            tmpFile.delete()
        } ?: return whiteResult()

        // 保存缩略图缓存
        cacheFile.parentFile?.mkdirs()
        cacheFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
        }

        // 更新元数据
        val srcFile = File(data.encryptedPath)
        synchronized(meta) {
            meta[filename] = srcFile.lastModified() to srcFile.length()
            writeMeta(meta)
        }

        return ImageFetchResult(image = bitmap.asImage(), isSampled = false, dataSource = DataSource.MEMORY)
    }

    private fun whiteResult() = ImageFetchResult(
        image = android.graphics.drawable.ColorDrawable(0).asImage(),
        isSampled = false,
        dataSource = DataSource.DISK
    )

    class Factory(private val context: Context, private val cacheDir: File) : Fetcher.Factory<VaultThumbnailRequest> {
        override fun create(
            data: VaultThumbnailRequest,
            options: Options,
            imageLoader: ImageLoader
        ) = VaultThumbnailFetcher(context, data, options, cacheDir)
    }
}
