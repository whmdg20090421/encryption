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
import com.whmdg.mczj.tools.encryption.services.VaultCacheType
import com.whmdg.mczj.tools.encryption.services.VaultDecryptCache
import java.io.File

class VaultThumbnailFetcher(
    private val context: Context,
    private val data: VaultThumbnailRequest,
    private val options: Options,
    private val cacheDir: File
) : Fetcher {

    /**
     * 头部 moov 探测失败时，允许完整解密到内存交给 Coil 提帧的最大文件大小。
     * 超过此值不做内存解密，避免 OOM，直接回退默认视频图标。
     */
    private val memoryFallbackLimit = 20L * 1024 * 1024

    // ── 入口 ──

    override suspend fun fetch(): FetchResult {
        val isVideo = data.displayName
            .substringAfterLast('.', "")
            .lowercase() in com.whmdg.mczj.tools.ui.components.VIDEO_EXTENSIONS
        if (isVideo) return fetchVideoThumbnail()
        return fetchImageThumbnail()
    }

    // ── 图片缩略图 ──

    private suspend fun fetchImageThumbnail(): FetchResult {
        // 解密后的明文基文件（统一走 VaultDecryptCache）
        val basePath = VaultDecryptCache.decryptToCache(
            context = context,
            vaultDir = data.vaultDir,
            encryptedPath = data.encryptedPath,
            dek = data.dek,
            customEncryption = data.customEncryption,
            type = VaultCacheType.IMAGE
        ).getOrElse { return whiteResult() }

        val baseFile = File(basePath)
        val thumbFile = File("${baseFile.absolutePath}.thumb")

        val imageFile = when {
            // 缩略图比明文基文件新才算有效（基文件被重新解密会更新 mtime）
            thumbFile.exists() && thumbFile.lastModified() >= baseFile.lastModified() -> thumbFile
            else -> {
                val bitmap = VaultThumbnailExtractor.extractThumbnailFromPlain(
                    plainPath = baseFile.absolutePath,
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
        val srcFile = File(data.encryptedPath)
        if (!srcFile.exists()) return whiteResult()

        // 缩略图缓存命中：明文视频缓存有效（由统一索引判定）且 .thumb 存在时直接复用
        val videoCachePath = VaultDecryptCache.cachePathFor(
            context = context,
            vaultDir = data.vaultDir,
            encryptedPath = data.encryptedPath,
            type = VaultCacheType.VIDEO
        )
        val thumbFile = File("$videoCachePath.thumb")
        val cachedBitmap = if (thumbFile.exists()) {
            val plainFile = File(videoCachePath)
            // 缩略图需比明文视频文件新；明文文件被重新解密会更新 mtime
            if (plainFile.exists() && thumbFile.lastModified() >= plainFile.lastModified()) {
                BitmapFactory.decodeFile(thumbFile.absolutePath)
            } else {
                null
            }
        } else {
            null
        }
        if (cachedBitmap != null) {
            return ImageFetchResult(image = cachedBitmap.asImage(), isSampled = false, dataSource = DataSource.DISK)
        }

        // 先部分解密文件头，按 atom 链探测 moov（faststart）。
        // MediaMetadataRetriever 需要结构完整的文件，只有 moov 位于头部时才可能提取首帧。
        val headerBytes = VaultThumbnailExtractor.decryptPartialToBytes(
            srcFile, data.dek, data.customEncryption, maxBytes = 2L * 1024 * 1024
        )

        val frame: Bitmap? = if (headerBytes != null &&
            VaultThumbnailExtractor.headerContainsMoov(headerBytes)
        ) {
            // moov 在头部 → 通过统一缓存解密成完整明文文件 → MediaMetadataRetriever 提取首帧
            val plainPath = VaultDecryptCache.decryptToCache(
                context = context,
                vaultDir = data.vaultDir,
                encryptedPath = data.encryptedPath,
                dek = data.dek,
                customEncryption = data.customEncryption,
                type = VaultCacheType.VIDEO
            ).getOrElse { return whiteResult() }
            extractFrameViaFile(File(plainPath))
        } else if (srcFile.length() < memoryFallbackLimit) {
            // 头部探测失败或不支持，且文件小于 20MB → 完整解密到内存流交给 Coil 提帧
            extractFrameViaMemory(srcFile)
        } else {
            null
        }
        val bitmap: Bitmap = frame ?: return whiteResult()

        // 保存缩略图缓存（复用统一视频缓存路径 + .thumb 后缀）
        thumbFile.parentFile?.mkdirs()
        thumbFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
        }

        return ImageFetchResult(image = bitmap.asImage(), isSampled = false, dataSource = DataSource.MEMORY)
    }

    /** moov 在头部：从已解密的完整明文文件用 MediaMetadataRetriever 提取首帧。 */
    private fun extractFrameViaFile(plainFile: File): Bitmap? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(plainFile.absolutePath, null)
            retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
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
    private suspend fun extractFrameViaMemory(srcFile: File): Bitmap? {
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
