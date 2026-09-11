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
    override suspend fun fetch(): FetchResult {
        // 优先级 2→1→生成，同目录：原图 = {path}，缩略图 = {path}.thumb
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

    class Factory(private val context: Context, private val cacheDir: File) : Fetcher.Factory<VaultThumbnailRequest> {
        override fun create(
            data: VaultThumbnailRequest,
            options: Options,
            imageLoader: ImageLoader
        ) = VaultThumbnailFetcher(context, data, options, cacheDir)
    }
}
