package com.whmdg.mczj.tools.ui.diary

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DiaryBook(
    val id: Int = 0,
    val name: String = "",
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("last_edited_at") val lastEditedAt: Long = 0L,
    @SerialName("entry_count") val entryCount: Int = 0
)

@Serializable
data class DiaryDb(
    val version: Int = 1,
    val books: MutableList<DiaryBook> = mutableListOf()
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            prettyPrintIndent = "    "
            encodeDefaults = true
        }

        fun empty() = DiaryDb()

        /** 主配置 → 备份，双副本加载 */
        fun load(context: Context): DiaryDb {
            val dir = AppDataPaths.diary(context)
            val primary = File(dir, "diary_db.json")
            if (primary.exists()) {
                try {
                    return json.decodeFromString<DiaryDb>(primary.readText())
                } catch (_: Exception) { }
            }
            val backup = File(dir, "diary_db.backup.json")
            if (backup.exists()) {
                try {
                    return json.decodeFromString<DiaryDb>(backup.readText())
                } catch (_: Exception) { }
            }
            return empty()
        }
    }

    /** 双副本保存：主配置 + 备份 */
    fun save(context: Context) {
        val dir = AppDataPaths.diary(context)
        val text = json.encodeToString(serializer(), this)
        File(dir, "diary_db.json").writeText(text)
        try {
            File(dir, "diary_db.backup.json").writeText(text)
        } catch (_: Exception) { }
    }

    fun addBook(book: DiaryBook): DiaryBook {
        val assigned = book.copy(id = books.size + 1)
        books.add(assigned)
        return assigned
    }

    fun removeBook(id: Int) {
        books.removeAll { it.id == id }
        for (i in books.indices) {
            if (books[i].id != i + 1) books[i] = books[i].copy(id = i + 1)
        }
    }

    fun isNameTaken(name: String): Boolean = books.any { it.name == name }

    /** 每个日记本独立目录：日记/books/{id}/ */
    fun bookDir(context: Context, book: DiaryBook): File {
        val dir = File(AppDataPaths.diary(context), "books/${book.id}")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
