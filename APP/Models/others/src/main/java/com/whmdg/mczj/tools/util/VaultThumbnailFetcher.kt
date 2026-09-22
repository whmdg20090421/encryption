package com.whmdg.mczj.tools.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import com.whmdg.mczj.tools.encryption.services.VaultCacheType
import com.whmdg.mczj.tools.encryption.services.VaultDecryptCache
import java.io.File

class VaultThumbnailFetcher(
    private val context: Context,
    private val data: VaultThumbnailRequest
) : Fetcher {

    /**
     * 头部 moov 探测失败时，允许完整解密到内存提帧的最大文件大小。
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

        // 缩略图缓存命中：索引记录有效且 .thumb 存在时直接复用
        val thumbPath = VaultDecryptCache.thumbPathFor(context, data.encryptedPath)
        val thumbFile = File(thumbPath)
        if (thumbFile.exists() && VaultDecryptCache.isHit(context, thumbPath, srcFile)) {
            val cached = BitmapFactory.decodeFile(thumbPath)
            if (cached != null) {
                return ImageFetchResult(image = cached.asImage(), isSampled = false, dataSource = DataSource.DISK)
            }
        }

        // 先部分解密文件头，按 atom 链探测 moov（faststart）。
        // MediaMetadataRetriever 需要结构完整的文件，只有 moov 位于头部时才可能提取首帧。
        val headerBytes = VaultThumbnailExtractor.decryptPartialToBytes(
            srcFile, data.dek, data.customEncryption, maxBytes = 2L * 1024 * 1024
        )

        val frame: Bitmap? = if (headerBytes != null &&
            VaultThumbnailExtractor.headerContainsMoov(headerBytes)
        ) {
            // moov 在头部 → 解密成完整明文文件 → MediaMetadataRetriever 提取首帧
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
            // 头部探测失败或不支持，且文件小于 20MB → 完整解密到内存直接提帧
            extractFrameViaMemory(srcFile)
        } else {
            null
        }
        val bitmap: Bitmap = frame ?: return whiteResult()

        // 保存缩略图缓存并登记索引（以源文件 size/mtime 判定有效性）
        thumbFile.parentFile?.mkdirs()
        thumbFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
        }
        VaultDecryptCache.register(context, thumbPath, srcFile)

        return ImageFetchResult(image = bitmap.asImage(), isSampled = false, dataSource = DataSource.MEMORY)
    }

    /** moov 在头部：从已解密的完整明文文件用 MediaMetadataRetriever 提取首帧。 */
    private fun extractFrameViaFile(plainFile: File): Bitmap? = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(plainFile.absolutePath, null)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 头部探测失败时的兜底：完整解密到内存字节数组，直接交给
     * [MediaMetadataRetriever]（经 [MediaDataSource]）提取首帧。
     *
     * 不落地任何明文文件，提帧后内存随局部变量释放；[MediaMetadataRetriever]
     * 原生支持内存数据源，无需 Coil 解码器。
     */
    private fun extractFrameViaMemory(srcFile: File): Bitmap? {
        val bytes = VaultThumbnailExtractor.decryptToBytes(srcFile, data.dek, data.customEncryption)
            ?: return null
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ByteArrayMediaDataSource(bytes))
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 以内存字节数组为后端的 [MediaDataSource]，供 [MediaMetadataRetriever] 直接提帧。 */
    private class ByteArrayMediaDataSource(private val bytes: ByteArray) : MediaDataSource() {
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

    class Factory(private val context: Context) : Fetcher.Factory<VaultThumbnailRequest> {
        override fun create(
            data: VaultThumbnailRequest,
            options: Options,
            imageLoader: ImageLoader
        ) = VaultThumbnailFetcher(context, data)
    }
}
