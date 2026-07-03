package com.whmdg.mczj.tools.encryption.data

import java.io.File

data class FolderSizeInfo(
    val size: Long = 0,
    val lastModified: Long = 0
)

/**
 * 文件夹大小数据库（v1.2 文本格式）。
 * 文件第一行为版本标识 "v1.2"，不匹配则删除重建。
 * 行格式：`<绝对路径>\t<size>\t<mtime>`
 */
class FolderSizeDb {
    companion object {
        private const val CURRENT_VERSION = "v1.2"
        private const val FILE_NAME = "folder_sizes.txt"

        fun load(dir: File): FolderSizeDb {
            val file = File(dir, FILE_NAME)
            if (!file.exists()) return FolderSizeDb()
            return try {
                val lines = file.readLines()
                if (lines.isEmpty() || lines[0] != CURRENT_VERSION) {
                    file.delete()
                    return FolderSizeDb()
                }
                val db = FolderSizeDb()
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
                db
            } catch (_: Exception) {
                file.delete()
                FolderSizeDb()
            }
        }
    }

    val folders: MutableMap<String, FolderSizeInfo> = mutableMapOf()

    fun save(dir: File) {
        val file = File(dir, FILE_NAME)
        val sb = StringBuilder()
        sb.appendLine(CURRENT_VERSION)
        for ((path, info) in folders) {
            sb.appendLine("$path\t${info.size}\t${info.lastModified}")
        }
        file.writeText(sb.toString())
    }

    fun get(path: String): FolderSizeInfo? = folders[path]

    fun put(path: String, info: FolderSizeInfo) {
        folders[path] = info
    }

    fun bulkPut(updates: Map<String, FolderSizeInfo>) {
        folders.putAll(updates)
    }

    fun remove(path: String) {
        folders.remove(path)
    }

    fun removeDescendants(path: String) {
        val prefix = if (path.isEmpty()) "" else "$path/"
        folders.keys.removeAll { it == path || it.startsWith(prefix) }
    }

    /** 读取子树范围内（含 rootPath 自身）所有记录，用于差异统计快照。 */
    fun getDescendants(rootPath: String): Map<String, FolderSizeInfo> {
        val prefix = if (rootPath.isEmpty()) "" else "$rootPath/"
        val result = HashMap<String, FolderSizeInfo>()
        for ((path, info) in folders) {
            if (path == rootPath || path.startsWith(prefix)) {
                result[path] = info
            }
        }
        return result
    }
}
