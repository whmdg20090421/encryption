package com.whmdg.mczj.tools.encryption.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import java.io.File

data class FolderSizeInfo(
    val size: Long = 0,
    val lastModified: Long = 0
)

/**
 * 文件夹大小数据库（v1.2 文本格式）。
 * 文件第一行为版本标识 "v1.2"，不匹配则删除重建。
 * 行格式：`<绝对路径>\t<size>\t<mtime>`
 *
 * 全局单例：同一个 dir 只会创建一个实例，所有调用方共享同一份内存数据。
 * [version] 供 Compose 观察，任何写操作自动递增触发 recomposition。
 */
class FolderSizeDb() {
    companion object {
        private const val CURRENT_VERSION = "v1.2"
        private const val FILE_NAME = "folder_sizes.txt"

        private val cache = HashMap<String, FolderSizeDb>()

        fun load(dir: File): FolderSizeDb {
            val key = dir.canonicalPath
            cache[key]?.let { return it }
            val db = FolderSizeDb()
            val file = File(dir, FILE_NAME)
            if (file.exists()) {
                try {
                    val lines = file.readLines()
                    if (lines.isNotEmpty() && lines[0] == CURRENT_VERSION) {
                        for (i in 1 until lines.size) {
                            val line = lines[i]
                            if (line.isBlank()) continue
                            val parts = line.split("\t")
                            if (parts.size >= 2) {
                                val path = parts[0]
                                val size = parts[1].toLongOrNull() ?: 0L
                                val mtime = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                                db.folders[path] = FolderSizeInfo(size, mtime)
                            }
                        }
                    } else {
                        file.delete()
                    }
                } catch (_: Exception) {
                    file.delete()
                }
            }
            cache[key] = db
            return db
        }
    }

    val folders: MutableMap<String, FolderSizeInfo> = mutableMapOf()

    /** 保护 [folders] 的并发读写（多通道加密/解密时会被并行访问）。 */
    private val lock = Any()

    /** 版本计数器：每次写操作递增，Compose 读取此字段可触发 recomposition */
    var version by mutableStateOf(0)
        private set

    private fun bumpVersion() {
        // 后台线程写入时需要进入 snapshot，确保主线程 Compose 能观察到变更
        Snapshot.withMutableSnapshot { version++ }
    }

    fun save(dir: File) {
        val snapshot = synchronized(lock) { folders.toMap() }
        val file = File(dir, FILE_NAME)
        val sb = StringBuilder()
        sb.appendLine(CURRENT_VERSION)
        for ((path, info) in snapshot) {
            sb.appendLine("$path\t${info.size}\t${info.lastModified}")
        }
        file.writeText(sb.toString())
    }

    fun get(path: String): FolderSizeInfo? = synchronized(lock) { folders[path] }

    /** 规范化查找：去除尾部 / 后再匹配，避免目录路径格式不一致导致查不到 */
    fun getNormalized(path: String): FolderSizeInfo? = synchronized(lock) { folders[path.trimEnd('/')] }

    fun put(path: String, info: FolderSizeInfo) {
        synchronized(lock) { folders[path] = info }
        bumpVersion()
    }

    fun bulkPut(updates: Map<String, FolderSizeInfo>) {
        synchronized(lock) { folders.putAll(updates) }
        bumpVersion()
    }

    fun remove(path: String) {
        synchronized(lock) { folders.remove(path) }
        bumpVersion()
    }

    fun removeDescendants(path: String) {
        val prefix = if (path.isEmpty()) "" else "$path/"
        synchronized(lock) { folders.keys.removeAll { it == path || it.startsWith(prefix) } }
        bumpVersion()
    }

    /** 读取子树范围内（含 rootPath 自身）所有记录，用于差异统计快照。 */
    fun getDescendants(rootPath: String): Map<String, FolderSizeInfo> {
        val prefix = if (rootPath.isEmpty()) "" else "$rootPath/"
        val result = HashMap<String, FolderSizeInfo>()
        synchronized(lock) {
            for ((path, info) in folders) {
                if (path == rootPath || path.startsWith(prefix)) {
                    result[path] = info
                }
            }
        }
        return result
    }
}
