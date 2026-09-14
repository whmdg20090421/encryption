package com.whmdg.mczj.tools.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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

    /**
     * 从加密视频文件提取首帧。内存解密后通过 MediaMetadataRetriever 提取。
     */
    suspend fun extractVideoThumbnail(
        encryptedPath: String,
        dek: ByteArray,
        customEncryption: Boolean
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val src = File(encryptedPath)
            if (!src.exists()) return@withContext null

            val bytes = decryptToBytes(src, dek, customEncryption) ?: return@withContext null

            // MediaMetadataRetriever 不接受 ByteArray，写入临时文件后提取
            val tmpFile = File.createTempFile("vault_vid_", ".tmp")
            try {
                tmpFile.writeBytes(bytes)
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(tmpFile.absolutePath, null)
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (_: Exception) {
                    null
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            } finally {
                tmpFile.delete()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 内存解密：将加密文件完整解密为 [ByteArray]，用于缩略图提取等场景。
     */
    fun decryptToBytes(src: File, dek: ByteArray, customEncryption: Boolean): ByteArray? {
        return try {
            val buffer = ByteArrayOutputStream()
            FileCodec.decryptToStream(src, buffer, dek, customEncryption)
            buffer.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 部分解密：仅解密前 [maxBytes] 字节，用于视频缩略图只需头部数据的场景。
     */
    fun decryptPartialToBytes(src: File, dek: ByteArray, customEncryption: Boolean, maxBytes: Long): ByteArray? {
        return try {
            val buffer = ByteArrayOutputStream()
            FileCodec.decryptPartial(src, buffer, dek, customEncryption, maxBytes)
            buffer.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 流式完整解密到 [dst] 文件（不占内存）。用于视频缩略图：MediaMetadataRetriever
     * 需要一个结构完整的文件才能随机访问。
     */
    fun decryptToFile(src: File, dst: File, dek: ByteArray, customEncryption: Boolean): Boolean {
        return try {
            dst.outputStream().use { out ->
                FileCodec.decryptToStream(src, out, dek, customEncryption)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 在解密的头部数据中按 atom 链扫描是否存在 [moov] 原子（faststart 标识）。
     *
     * MediaMetadataRetriever 只能处理结构完整的 MP4，因此只有 moov 位于头部
     * （即文件头部密文段里就能读到 moov）时才值得全量解密提取首帧。
     * 返回 true 表示头部已包含 moov，false 表示 moov 不在头部（不解密尾部，直接放弃）。
     */
    fun headerContainsMoov(headerBytes: ByteArray): Boolean {
        var pos = 0
        while (pos + 8 <= headerBytes.size) {
            val size = ((headerBytes[pos].toLong() and 0xFF) shl 24) or
                    ((headerBytes[pos + 1].toLong() and 0xFF) shl 16) or
                    ((headerBytes[pos + 2].toLong() and 0xFF) shl 8) or
                    (headerBytes[pos + 3].toLong() and 0xFF)
            val type = String(headerBytes, pos + 4, 4, Charsets.ISO_8859_1)
            if (type == "moov") return true
            // ftyp 等正常原子的 size 至少为 8；size==0 表示延伸到文件末尾，size==1 为 64 位长度
            if (size < 8L) return false
            // moov 之前不可能是 mdat（否则 moov 必在尾部），可直接判定不在头部
            if (type == "mdat") return false
            if (size > Int.MAX_VALUE) return false
            pos += size.toInt()
        }
        // 头部字节不足以覆盖到 moov，视为 moov 不在头部
        return false
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
