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
    private val data: VaultThumbnailRequest
) : Fetcher {

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

        // 解密为完整明文缓存文件（与图片 / 音频一致，统一走 VaultDecryptCache）。
        // 之后与普通视频共用同一套 Coil 提帧实现（coil-video 的 VideoFrameDecoder），
        // 不再手写 MediaMetadataRetriever，避免对特定编码 / 容器提帧失败。
        val plainPath = VaultDecryptCache.decryptToCache(
            context = context,
            vaultDir = data.vaultDir,
            encryptedPath = data.encryptedPath,
            dek = data.dek,
            customEncryption = data.customEncryption,
            type = VaultCacheType.VIDEO
        ).getOrElse { return whiteResult() }

        val bitmap = extractFrameViaCoil(plainPath) ?: return whiteResult()

        // 保存缩略图缓存并登记索引（以源文件 size/mtime 判定有效性）
        thumbFile.parentFile?.mkdirs()
        thumbFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
        }
        VaultDecryptCache.register(context, thumbPath, srcFile)

        return ImageFetchResult(image = bitmap.asImage(), isSampled = false, dataSource = DataSource.MEMORY)
    }

    /**
     * 用全局 Coil ImageLoader（已注册 coil-video 的 VideoFrameDecoder）提取首帧，
     * 与普通视频缩略图走完全相同的实现，保证不同编码 / 容器下的一致表现。
     */
    private suspend fun extractFrameViaCoil(plainPath: String): Bitmap? {
        val loader = SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context)
            .data(plainPath)
            .size(coil3.size.Size(200, 200))
            .build()
        val result = loader.execute(request)
        val image = result.image ?: return null
        return (image as? BitmapImage)?.bitmap
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
