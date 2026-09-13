package com.whmdg.mczj.tools.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.BitmapImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.request.ImageRequest
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

    private fun videoCacheFile(): File {
        val extDir = context.getExternalFilesDir(null)
            ?: File(context.filesDir, "video_cache")
        return File(extDir, "视频缓存/${data.encryptedPath}")
    }

    private fun isCacheValid(file: File): Boolean {
        if (!file.exists()) return false
        val ageMs = System.currentTimeMillis() - file.lastModified()
        return ageMs < 12 * 60 * 60 * 1000 // 12 小时
    }

    override suspend fun fetch(): FetchResult {
        val isVideo = data.entryPath.substringAfterLast('.', "").lowercase() in videoExtensions

        if (isVideo) {
            return fetchVideoThumbnail()
        }

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

        return ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = false,
            dataSource = DataSource.DISK
        )
    }

    private fun fetchVideoThumbnail(): FetchResult {
        val cacheFile = videoCacheFile()

        // 1. 检查缓存（12 小时有效）
        if (isCacheValid(cacheFile)) {
            val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
            if (bitmap != null) {
                return ImageFetchResult(image = bitmap.asImage(), isSampled = false, dataSource = DataSource.DISK)
            }
        }

        // 2. 内存解密 → Coil 提取帧
        val bytes = VaultThumbnailExtractor.decryptToBytes(
            File(data.encryptedPath), data.dek, data.customEncryption
        ) ?: return whiteResult()

        val imageLoader = SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context)
            .data(bytes)
            .size(options.size)
            .build()
        val result = imageLoader.execute(request)

        val image = result.image ?: return whiteResult()
        val bitmap = (image as? BitmapImage)?.bitmap ?: return whiteResult()

        // 3. 保存缓存
        cacheFile.parentFile?.mkdirs()
        cacheFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
        }

        return ImageFetchResult(image = image, isSampled = false, dataSource = DataSource.MEMORY)
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
