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
        // Priority: vault_preview (original) > vault_cache (thumbnail) > generate
        val previewFile = File(context.cacheDir, "vault_preview/${data.vaultName}/${data.entryPath}")
        val thumbFile = File(cacheDir, "vault_cache/${data.vaultName}/${data.entryPath}.thumb")

        val imageFile = when {
            previewFile.exists() -> previewFile
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