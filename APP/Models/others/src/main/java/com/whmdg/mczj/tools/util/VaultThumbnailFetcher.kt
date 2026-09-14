package com.whmdg.mczj.tools.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.ImageRequest
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

    /**
     * 头部 moov 探测失败时，允许完整解密到内存交给 Coil 提帧的最大文件大小。
     * 超过此值不做内存解密，避免 OOM，直接回退默认视频图标。
     */
    private val memoryFallbackLimit = 20L * 1024 * 1024

    // ── 缓存路径 ──

    /**
     * 视频缓存根目录。普通视频与保险箱视频共用，缓存路径统一为：
     * 视频缓存根 + 源文件绝对路径（去首斜杠）+ .thumb
     * 保险箱源文件即磁盘上的加密文件（.whm）绝对路径，不做特殊处理。
     */
    private val videoCacheRoot: File by lazy {
        val extDir = context.getExternalFilesDir(null)
            ?: File(context.filesDir, "video_cache")
        File(extDir, "视频缓存")
    }

    private fun cacheFileForVideo(): File =
        File(videoCacheRoot, "${data.encryptedPath.removePrefix("/")}.thumb")

    private fun metaFileForVideo(): File =
        File(videoCacheRoot, "cache_meta.txt")

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

    private fun isEntryValid(meta: LinkedHashMap<String, Pair<Long, Long>>, sourcePath: String): Boolean {
        val srcFile = File(sourcePath)
        val recorded = meta[sourcePath] ?: return false
        return srcFile.lastModified() == recorded.first && srcFile.length() == recorded.second
    }

    // ── 入口 ──

    override suspend fun fetch(): FetchResult {
        val isVideo = data.displayName.substringAfterLast('.', "").lowercase() in videoExtensions
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
        val sourcePath = data.encryptedPath
        val cacheFile = cacheFileForVideo()
        val meta = readMeta()

        // 校验：元数据存在 + 缓存文件存在 + 源文件未变化
        if (meta.containsKey(sourcePath) && cacheFile.exists() && isEntryValid(meta, sourcePath)) {
            val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
            if (bitmap != null) {
                return ImageFetchResult(image = bitmap.asImage(), isSampled = false, dataSource = DataSource.DISK)
            }
        }

        // 缓存失效 → 删除旧缓存
        if (cacheFile.exists()) cacheFile.delete()

        // 先部分解密文件头，按 atom 链探测 moov（faststart）。
        // MediaMetadataRetriever 需要结构完整的文件，只有 moov 位于头部时才可能提取首帧。
        val srcFile = File(sourcePath)
        val headerBytes = VaultThumbnailExtractor.decryptPartialToBytes(
            srcFile, data.dek, data.customEncryption, maxBytes = 2L * 1024 * 1024
        )

        val frame: Bitmap? = if (headerBytes != null &&
            VaultThumbnailExtractor.headerContainsMoov(headerBytes)
        ) {
            // moov 在头部 → 流式完整解密到临时文件（不占内存）→ MediaMetadataRetriever 提取首帧
            extractViaTempFile(srcFile)
        } else if (srcFile.length() < memoryFallbackLimit) {
            // 头部探测失败或不支持，且文件小于 20MB → 完整解密到内存流交给 Coil 提帧
            extractViaMemory(srcFile)
        } else {
            null
        }
        val bitmap: Bitmap = frame ?: return whiteResult()

        // 保存缩略图缓存
        cacheFile.parentFile?.mkdirs()
        cacheFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
        }

        // 更新元数据
        synchronized(meta) {
            meta[sourcePath] = srcFile.lastModified() to srcFile.length()
            writeMeta(meta)
        }

        return ImageFetchResult(image = bitmap.asImage(), isSampled = false, dataSource = DataSource.MEMORY)
    }

    /**
     * moov 在头部：流式完整解密到临时文件 → MediaMetadataRetriever 提取首帧。
     * 除最终缩略图外不落任何持久文件，临时文件提取后立即删除。
     */
    private fun extractViaTempFile(srcFile: File): Bitmap? {
        val tmpFile = File.createTempFile("vault_vid_", ".mp4")
        return try {
            if (!VaultThumbnailExtractor.decryptToFile(
                    srcFile, tmpFile, data.dek, data.customEncryption
                )
            ) {
                return null
            }
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
        }
    }

    /**
     * 头部探测失败时的兜底：完整解密到内存字节数组，交给 Coil 提帧。
     * 全程使用内存流（不写临时文件），仅最终缩略图写入本地缓存。
     *
     * 通过 [MediaDataSource] 交给 Coil：coil-video 的 MediaDataSourceFetcher +
     * VideoFrameDecoder 会直接以内存数据源提帧（setDataSource(mediaDataSource)），
     * 不会落地临时文件。
     */
    private suspend fun extractViaMemory(srcFile: File): Bitmap? {
        val bytes = VaultThumbnailExtractor.decryptToBytes(srcFile, data.dek, data.customEncryption)
            ?: return null
        val loader = SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context)
            .data(ByteArrayMediaDataSource(bytes))
            .size(options.size)
            .build()
        val result = loader.execute(request)
        val image = result.image ?: return null
        return (image as? BitmapImage)?.bitmap
    }

    /** 以内存字节数组为后端的 [android.media.MediaDataSource]，供 Coil 直接提帧。 */
    private class ByteArrayMediaDataSource(private val bytes: ByteArray) : android.media.MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val available = (bytes.size - position).toInt()
            val count = minOf(size, available)
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = bytes.size.toLong()

        override fun close() {}
    }

    private fun whiteResult(): Nothing =
        throw IllegalStateException("保险箱缩略图提取失败")

    class Factory(private val context: Context, private val cacheDir: File) : Fetcher.Factory<VaultThumbnailRequest> {
        override fun create(
            data: VaultThumbnailRequest,
            options: Options,
            imageLoader: ImageLoader
        ) = VaultThumbnailFetcher(context, data, options, cacheDir)
    }
}
