package com.whmdg.mczj.tools.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object ArchiveThumbnailExtractor {
    suspend fun extractThumbnail(
        archivePath: String,
        entryPath: String,
        password: String,
        targetSize: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        val buffer = ByteArrayOutputStream()

        JBindingClient.extractSingleFileToSink(
            archivePath = archivePath,
            entryPath = entryPath,
            password = password,
            onBytes = { buffer.write(it) }
        ).fold(
            onSuccess = {
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(buffer.toByteArray(), 0, buffer.size(), opts)

                opts.inSampleSize = calculateInSampleSize(opts, targetSize, targetSize)
                opts.inJustDecodeBounds = false
                opts.inPreferredConfig = Bitmap.Config.RGB_565

                BitmapFactory.decodeByteArray(buffer.toByteArray(), 0, buffer.size(), opts)
            },
            onFailure = { null }
        )
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (width, height) = options.outWidth to options.outHeight
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight &&
                   halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
