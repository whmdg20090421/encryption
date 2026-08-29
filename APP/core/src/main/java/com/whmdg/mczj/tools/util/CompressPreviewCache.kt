package com.whmdg.mczj.tools.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * 压缩包预览缓存管理器。
 *
 * 目录结构：
 * externalCacheDir/
 * └── compress_preview/
 *     ├── 压缩包1/
 *     │   └── 解压的文件...
 *     └── 压缩包2/
 *         └── 解压的文件...
 * └── compress_preview_meta.txt    ← 元数据文件
 *
 * 元数据格式（每行一个压缩包记录）：
 * 压缩包名称|最后修改时间戳|文件大小|解压时间戳
 */
object CompressPreviewCache {
    private const val TAG = "CompressPreviewCache"
    private const val META_FILE_NAME = "compress_preview_meta.txt"
    private const val CACHE_DIR_NAME = "compress_preview"
    private const val MAX_CACHE_AGE_MS = 24 * 60 * 60 * 1000L // 1天

    /**
     * 缓存元数据记录
     */
    data class CacheRecord(
        val archiveName: String,
        val lastModified: Long,
        val fileSize: Long,
        val extractTime: Long
    )

    /**
     * 获取缓存目录
     */
    fun getCacheDir(context: Context): File {
        return File(context.externalCacheDir, CACHE_DIR_NAME)
    }

    /**
     * 获取压缩包的缓存子目录
     */
    fun getArchiveCacheDir(context: Context, archiveName: String): File {
        val baseName = File(archiveName).nameWithoutExtension
        return File(getCacheDir(context), baseName)
    }

    /**
     * 获取元数据文件
     */
    private fun getMetaFile(context: Context): File {
        return File(context.externalCacheDir, META_FILE_NAME)
    }

    /**
     * 读取所有缓存记录
     */
    fun readAllRecords(context: Context): List<CacheRecord> {
        val metaFile = getMetaFile(context)
        if (!metaFile.exists()) return emptyList()

        return try {
            metaFile.readLines().mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 4) {
                    CacheRecord(
                        archiveName = parts[0],
                        lastModified = parts[1].toLongOrNull() ?: 0L,
                        fileSize = parts[2].toLongOrNull() ?: 0L,
                        extractTime = parts[3].toLongOrNull() ?: 0L
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取元数据失败", e)
            emptyList()
        }
    }

    /**
     * 写入所有缓存记录
     */
    private fun writeAllRecords(context: Context, records: List<CacheRecord>) {
        val metaFile = getMetaFile(context)
        try {
            metaFile.writeText(records.joinToString("\n") { record ->
                "${record.archiveName}|${record.lastModified}|${record.fileSize}|${record.extractTime}"
            })
        } catch (e: Exception) {
            Log.e(TAG, "写入元数据失败", e)
        }
    }

    /**
     * 检查缓存是否命中
     *
     * @return CacheHitResult 包含是否命中、缓存目录、需要解压的文件列表
     */
    data class CacheHitResult(
        val hit: Boolean,
        val cacheDir: File?,
        val filesToExtract: List<String>? // 如果命中但有未解压的文件，返回这些文件的相对路径
    )

    /**
     * 检查缓存是否命中
     *
     * @param archivePath 压缩包路径
     * @param lastModified 压缩包最后修改时间
     * @param fileSize 压缩包大小
     * @param relativePaths 需要访问的压缩包内文件相对路径列表
     * @return CacheHitResult
     */
    fun checkCacheHit(
        context: Context,
        archivePath: String,
        lastModified: Long,
        fileSize: Long,
        relativePaths: List<String>
    ): CacheHitResult {
        val archiveName = File(archivePath).name
        val records = readAllRecords(context)
        val record = records.find { it.archiveName == archiveName }

        if (record == null) {
            // 没有缓存记录
            return CacheHitResult(hit = false, cacheDir = null, filesToExtract = null)
        }

        // 检查时间戳和大小
        if (record.lastModified != lastModified || record.fileSize != fileSize) {
            // 时间戳或大小不同，需要清理并重新解压
            Log.d(TAG, "缓存不匹配: $archiveName (时间戳或大小不同)")
            cleanArchiveCache(context, archiveName)
            return CacheHitResult(hit = false, cacheDir = null, filesToExtract = null)
        }

        // 缓存命中，检查哪些文件需要解压
        val cacheDir = getArchiveCacheDir(context, archiveName)
        if (!cacheDir.exists()) {
            return CacheHitResult(hit = false, cacheDir = null, filesToExtract = null)
        }

        // 检查哪些文件还未解压
        val filesToExtract = relativePaths.filter { relativePath ->
            val cacheFile = File(cacheDir, relativePath)
            !cacheFile.exists()
        }

        return CacheHitResult(
            hit = true,
            cacheDir = cacheDir,
            filesToExtract = if (filesToExtract.isEmpty()) null else filesToExtract
        )
    }

    /**
     * 更新缓存记录（解压完成后调用）
     */
    fun updateRecord(
        context: Context,
        archivePath: String,
        lastModified: Long,
        fileSize: Long
    ) {
        val archiveName = File(archivePath).name
        val records = readAllRecords(context).toMutableList()

        // 移除旧记录
        records.removeAll { it.archiveName == archiveName }

        // 添加新记录
        records.add(
            CacheRecord(
                archiveName = archiveName,
                lastModified = lastModified,
                fileSize = fileSize,
                extractTime = System.currentTimeMillis()
            )
        )

        writeAllRecords(context, records)
        Log.d(TAG, "更新缓存记录: $archiveName")
    }

    /**
     * 清理单个压缩包的缓存
     */
    fun cleanArchiveCache(context: Context, archiveName: String) {
        val baseName = File(archiveName).nameWithoutExtension
        val cacheDir = File(getCacheDir(context), baseName)

        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
            Log.d(TAG, "清理缓存目录: ${cacheDir.absolutePath}")
        }

        // 从元数据中移除记录
        val records = readAllRecords(context).toMutableList()
        records.removeAll { it.archiveName == archiveName }
        writeAllRecords(context, records)
    }

    /**
     * 清理过期的缓存（超过1天）
     * 在打开任意压缩包时调用
     */
    fun cleanExpiredCaches(context: Context) {
        val records = readAllRecords(context)
        val currentTime = System.currentTimeMillis()
        val expiredRecords = records.filter { currentTime - it.extractTime >= MAX_CACHE_AGE_MS }

        if (expiredRecords.isEmpty()) {
            Log.d(TAG, "没有过期的缓存")
            return
        }

        Log.d(TAG, "发现 ${expiredRecords.size} 个过期缓存，开始清理")

        expiredRecords.forEach { record ->
            cleanArchiveCache(context, record.archiveName)
        }
    }

    /**
     * 清理所有缓存
     */
    fun cleanAllCaches(context: Context) {
        val cacheDir = getCacheDir(context)
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
            Log.d(TAG, "清理所有缓存目录")
        }

        val metaFile = getMetaFile(context)
        if (metaFile.exists()) {
            metaFile.delete()
            Log.d(TAG, "清理元数据文件")
        }
    }
}
