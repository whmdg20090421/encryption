package com.whmdg.mczj.tools.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import okio.buffer
import okio.source
import java.io.ByteArrayOutputStream
import java.io.File

data class ArchiveThumbnailRequest(
    val archivePath: String,
    val entryPath: String,
    val archiveName: String,
    val password: String
)

class ArchiveThumbnailFetcher(
    private val data: ArchiveThumbnailRequest,
    private val options: Options,
    private val cacheDir: File
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val cacheFile = File(cacheDir, "archive_cache/${data.archiveName}/${data.entryPath}")
        val thumbFile = File("${cacheFile.absolutePath}.thumb")

        // 优先级: 原图 > 缩略图 > 生成
        val imageFile = when {
            cacheFile.exists() -> cacheFile
            thumbFile.exists() -> thumbFile
            else -> {
                val bitmap = ArchiveThumbnailExtractor.extractThumbnail(
                    archivePath = data.archivePath,
                    entryPath = data.entryPath,
                    password = data.password,
                    targetSize = 200
                ) ?: return ImageFetchResult(
                    image = android.graphics.drawable.ColorDrawable(0).asImage(),
                    isSampled = false,
                    dataSource = DataSource.DISK
                )

                thumbFile.parentFile?.mkdirs()
                thumbFile.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it)
                }
                bitmap.recycle()
                thumbFile
            }
        }

        // 使用 BitmapFactory 解码文件并转换为 Coil Image
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: return ImageFetchResult(
                image = android.graphics.drawable.ColorDrawable(0).asImage(),
                isSampled = false,
                dataSource = DataSource.DISK
            )

        return ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = false,
            dataSource = DataSource.DISK
        )
    }

    class Factory(private val cacheDir: File) : Fetcher.Factory<ArchiveThumbnailRequest> {
        override fun create(
            data: ArchiveThumbnailRequest,
            options: Options,
            imageLoader: ImageLoader
        ) = ArchiveThumbnailFetcher(data, options, cacheDir)
    }
}
