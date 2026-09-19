package com.whmdg.mczj.tools.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.whmdg.mczj.tools.encryption.core.FileCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

object VaultThumbnailExtractor {
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
     * 从已解密的明文图片文件提取缩略图（降采样解码）。
     */
    suspend fun extractThumbnailFromPlain(
        plainPath: String,
        targetSize: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(plainPath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return@withContext null

            opts.inSampleSize = calculateInSampleSize(opts, targetSize, targetSize)
            opts.inJustDecodeBounds = false
            opts.inPreferredConfig = Bitmap.Config.RGB_565
            BitmapFactory.decodeFile(plainPath, opts)
        } catch (_: Exception) {
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
