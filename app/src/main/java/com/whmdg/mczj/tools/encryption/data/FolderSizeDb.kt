package com.whmdg.mczj.tools.encryption.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class FolderSizeInfo(
    val size: Long = 0,
    val lastModified: Long = 0
)

@Serializable
data class FolderSizeDb(
    val folders: MutableMap<String, FolderSizeInfo> = mutableMapOf()
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = false
            encodeDefaults = true
        }

        private const val FILE_NAME = "folder_sizes.json"

        fun load(vaultDir: File): FolderSizeDb {
            val file = File(vaultDir, FILE_NAME)
            if (!file.exists()) return FolderSizeDb()
            return try {
                val db = json.decodeFromString<FolderSizeDb>(file.readText())
                // 清理 ./ 和 ../ 脏数据
                db.folders.keys.removeAll { key ->
                    key.endsWith("/.") || key.endsWith("/..") || key == "." || key == ".."
                }
                db
            } catch (_: Exception) {
                FolderSizeDb()
            }
        }
    }

    fun save(vaultDir: File) {
        val file = File(vaultDir, FILE_NAME)
        file.writeText(json.encodeToString(serializer(), this))
    }

    fun get(relativePath: String): FolderSizeInfo? = folders[relativePath]

    fun put(relativePath: String, info: FolderSizeInfo) {
        // 拦截 ./ 和 ../ 路径
        if (relativePath.endsWith("/.") || relativePath.endsWith("/..") || relativePath == "." || relativePath == "..") return
        folders[relativePath] = info
    }

    fun remove(relativePath: String) {
        folders.remove(relativePath)
    }

    fun removeDescendants(relativePath: String) {
        val prefix = if (relativePath.isEmpty()) "" else "$relativePath/"
        folders.keys.removeAll { it == relativePath || it.startsWith(prefix) }
    }
}
