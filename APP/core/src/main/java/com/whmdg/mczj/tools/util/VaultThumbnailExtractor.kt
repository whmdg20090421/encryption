package com.whmdg.mczj.tools.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import com.whmdg.mczj.tools.encryption.core.FileCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

    /**
     * 从加密视频文件提取首帧。通过管道解密，MediaMetadataRetriever 读取管道读端。
     */
    suspend fun extractVideoThumbnail(
        encryptedPath: String,
        dek: ByteArray,
        customEncryption: Boolean
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val src = File(encryptedPath)
            if (!src.exists()) return@withContext null

            val pipe = ParcelFileDescriptor.createPipe()
            val readFd = pipe[0]
            val writeFd = pipe[1]

            coroutineScope {
                launch(Dispatchers.IO) {
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { out ->
                            FileCodec.decryptToStream(src, out, dek, customEncryption)
                        }
                    } catch (_: Exception) {
                        try { writeFd.close() } catch (_: Exception) {}
                    }
                }

                withContext(Dispatchers.IO) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(readFd.fileDescriptor)
                        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (_: Exception) {
                        null
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                        try { readFd.close() } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从普通视频文件提取首帧，降采样后带磁盘缓存。
     * 缓存路径：{cacheDir}/video_thumbs/{pathHash}.thumb
     */
    suspend fun extractVideoThumbnailFromPlain(
        videoPath: String,
        cacheDir: File,
        targetSize: Int = 200
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val file = File(videoPath)
            if (!file.exists()) return@withContext null

            val thumbFile = File(cacheDir, "video_thumbs/${videoPath.hashCode()}.thumb")
            if (thumbFile.exists()) {
                val cached = BitmapFactory.decodeFile(thumbFile.absolutePath)
                if (cached != null) return@withContext cached
            }

            val retriever = MediaMetadataRetriever()
            val fullBitmap = try {
                retriever.setDataSource(file.absolutePath)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
                null
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }

            if (fullBitmap != null) {
                val scaled = scaleBitmap(fullBitmap, targetSize)
                if (scaled !== fullBitmap) fullBitmap.recycle()
                thumbFile.parentFile?.mkdirs()
                thumbFile.outputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
                }
                scaled
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleBitmap(src: Bitmap, targetSize: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= targetSize && h <= targetSize) return src
        val scale = targetSize.toFloat() / maxOf(w, h)
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, true)
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
