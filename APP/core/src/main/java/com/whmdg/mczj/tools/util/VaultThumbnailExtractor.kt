package com.whmdg.mczj.tools.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.whmdg.mczj.tools.encryption.core.FileCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

object VaultThumbnailExtractor {
    suspend fun extractThumbnail(
        encryptedPath: String,
        dek: ByteArray,
        customEncryption: Boolean,
        targetSize: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val src = File(encryptedPath)
            if (!src.exists()) return@withContext null

            val buffer = ByteArrayOutputStream()
            FileCodec.decryptToStream(src, buffer, dek, customEncryption)

            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(buffer.toByteArray(), 0, buffer.size(), opts)

            opts.inSampleSize = calculateInSampleSize(opts, targetSize, targetSize)
            opts.inJustDecodeBounds = false
            opts.inPreferredConfig = Bitmap.Config.RGB_565

            BitmapFactory.decodeByteArray(buffer.toByteArray(), 0, buffer.size(), opts)
        } catch (e: Exception) {
            null
        }
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
