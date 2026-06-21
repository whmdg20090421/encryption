package com.whmdg.mczj.tools.util

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 压缩服务，支持多种格式、多源文件、进度回调、取消。
 */
object CompressService {

    private const val TAG = "CompressService"
    private const val BUFFER_SIZE = 8192

    data class CompressOptions(
        val sourcePaths: List<String>,
        val outputPath: String,
        val format: String,           // zip, 7z, tar, tar.gz, tar.bz2, tar.xz
        val compressionLevel: Int,    // 0-9 (format dependent)
        val password: String = "",    // 空=不加密
        val useAes: Boolean = true,   // zip 加密方式
    )

    data class ProgressInfo(
        val currentFile: Int,
        val totalFiles: Int,
        val progress: Float,          // 0.0 - 1.0（基于字节大小）
        val currentFileName: String = "",
        val bytesProcessed: Long = 0,
        val totalBytes: Long = 0
    )

    interface ProgressCallback {
        fun onProgress(info: ProgressInfo)
        fun onComplete(success: Boolean, outputPath: String?, error: String?)
    }

    /**
     * 压缩入口。
     * @param cancelFlag 外部可设为 true 取消任务
     */
    fun compress(
        options: CompressOptions,
        cancelFlag: AtomicBoolean,
        callback: ProgressCallback
    ) {
        // TODO: 实现压缩功能
        callback.onComplete(false, null, "压缩功能未实现")
    }

    /** 获取压缩文件后缀 */
    fun getSuffix(format: String): String = when (format) {
        "zip" -> ".zip"
        "7z" -> ".7z"
        "tar" -> ".tar"
        "tar.gz" -> ".tar.gz"
        "tar.bz2" -> ".tar.bz2"
        "tar.xz" -> ".tar.xz"
        else -> ".zip"
    }

    /** 各格式压缩级别范围 */
    fun getLevelRange(format: String): IntRange? = when (format) {
        "zip", "7z", "tar.gz", "tar.xz" -> 0..9
        "tar.bz2" -> 1..9
        "tar" -> null
        else -> 0..9
    }

    /** 各格式默认压缩级别 */
    fun getDefaultLevel(format: String): Int = 5
}
