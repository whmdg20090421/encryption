package com.whmdg.mczj.tools.encryption.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.whmdg.mczj.tools.AppDataPaths
import java.io.File

/** 单条路径的大小缓存：字节数 + 修改时间（毫秒）。 */
data class FolderSizeInfo(
    val size: Long = 0,
    val lastModified: Long = 0
)

/**
 * 大小缓存写入接口。
 *
 * 统计算法 [com.whmdg.mczj.tools.util.calculateFolderSize] 只依赖这一个写入操作，
 * 从而既能直接写入 [FolderSizeDb]（即时持久化），也能写入内存暂存区
 * （统计期间不落盘，由用户确认后一次性提交）。
 */
interface FolderSizeCache {
    fun bulkPut(updates: Map<String, FolderSizeInfo>)
}

/**
 * 文件夹大小数据库（SQLite，`folder_sizes.db`）。
 *
 * ## 为什么用 SQLite
 *
 * 早期实现把「路径 → 大小」全量序列化成一个文本/JSON 文件，任何一次写入都要重写整个
 * 文件、启动时也要把全部条目读进内存。条目规模到达十万~百万级后写放大与内存占用都
 * 不可接受。SQLite 以 `path` 为主键：
 *   - 单条写入 = 一行 `INSERT OR REPLACE`，O(1)，不再全量重写；
 *   - 单条查询走主键索引，O(log N)，无需把全表读入内存；
 *   - 批量写入合并到一笔事务，避免逐条 fsync；
 *   - 开启 WAL，读写不互斥，多通道加密/解密时元数据写入不再互相阻塞。
 *
 * 本类是**存储层**，只负责增删改查；应用层统一走 [FolderSizeStore] 门面。
 */
class FolderSizeDb private constructor(
    context: Context,
    dbFile: File
) : SQLiteOpenHelper(context.applicationContext, dbFile.absolutePath, null, DB_VERSION), FolderSizeCache {

    companion object {
        private const val DB_VERSION = 1
        private const val TABLE = "folder_sizes"
        private const val COL_PATH = "path"
        private const val COL_SIZE = "size"
        private const val COL_MTIME = "last_modified"

        /** `path IN (...)` 单条 SQL 的最大变量数，按 SQLite 保守上限取 900。 */
        private const val SQLITE_IN_LIMIT = 900

        private val instances = HashMap<String, FolderSizeDb>()

        /** 取得本进程单例（按 DB 文件绝对路径缓存）。 */
        fun getInstance(context: Context): FolderSizeDb {
            val dbFile = AppDataPaths.folderSizeDb(context)
            return synchronized(this) {
                instances[dbFile.absolutePath]
                    ?: FolderSizeDb(context.applicationContext, dbFile)
                        .also { instances[dbFile.absolutePath] = it }
            }
        }

        /** 路径归一化：统一去除尾部 `/`。 */
        private fun normalize(path: String): String = path.trimEnd('/')
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_PATH  TEXT PRIMARY KEY,
                $COL_SIZE  INTEGER NOT NULL,
                $COL_MTIME INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // WAL：读写不互斥，多通道写入不互相阻塞。
        db.enableWriteAheadLogging()
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 纯缓存，可由重新扫描重建，版本变更直接弃表重建。
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    // ── 查询 ──

    /** 按路径读取（自动归一化）。不存在返回 null。 */
    fun get(path: String): FolderSizeInfo? {
        val key = normalize(path)
        readableDatabase.query(
            TABLE, arrayOf(COL_SIZE, COL_MTIME),
            "$COL_PATH = ?", arrayOf(key), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) return FolderSizeInfo(cursor.getLong(0), cursor.getLong(1))
        }
        return null
    }

    /**
     * 批量读取多个路径的大小（以调用方传入的原始路径为键）。
     * 分块执行 `path IN (...)` 以规避 SQLite 变量数上限；未命中的路径不出现。
     */
    fun getSizes(paths: Collection<String>): Map<String, Long> {
        if (paths.isEmpty()) return emptyMap()
        val keyToOriginal = HashMap<String, String>(paths.size)
        val result = HashMap<String, Long>(paths.size)
        for (p in paths) keyToOriginal[normalize(p)] = p
        val keys = keyToOriginal.keys.toList()
        for (chunk in keys.chunked(SQLITE_IN_LIMIT)) {
            val placeholders = chunk.joinToString(",") { "?" }
            readableDatabase.query(
                TABLE, arrayOf(COL_PATH, COL_SIZE),
                "$COL_PATH IN ($placeholders)", chunk.toTypedArray(), null, null, null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    keyToOriginal[key]?.let { result[it] = cursor.getLong(1) }
                }
            }
        }
        return result
    }

    /**
     * 读取路径自身及其所有后代的记录（范围查询子树）。
     * 供刷新时做「旧集合 - 新集合」差集，找出已消失的条目。
     */
    fun getDescendants(rootPath: String): Map<String, FolderSizeInfo> {
        val key = normalize(rootPath)
        val prefix = "$key/"
        val out = LinkedHashMap<String, FolderSizeInfo>()
        readableDatabase.query(
            TABLE, arrayOf(COL_PATH, COL_SIZE, COL_MTIME),
            "$COL_PATH = ? OR ($COL_PATH >= ? AND $COL_PATH < ?)",
            arrayOf(key, prefix, prefix + "\uFFFF"), null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out[cursor.getString(0)] = FolderSizeInfo(cursor.getLong(1), cursor.getLong(2))
            }
        }
        return out
    }

    // ── 写入 ──

    /** 批量写入：合并到一笔事务，避免逐条 fsync。 */
    override fun bulkPut(updates: Map<String, FolderSizeInfo>) {
        if (updates.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((path, info) in updates) {
                db.insertWithOnConflict(
                    TABLE, null, contentValues(normalize(path), info), SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 子树差量写入并同步更新祖先：在**一笔事务**内
     *   1. 按精确路径删除 [removedPaths]；
     *   2. 插入/更新子树 [updates]；
     *   3. 用 [ancestorUpdates] 覆盖写入祖先记录（由调用方算好新值）。
     *
     * 用于文件夹大小统计：统计某目录后自身子树落库，并把它相对旧值的增量
     * 逐级累加到所有父系目录，使父目录无需单独统计也能显示已统计子项之和。
     */
    fun applyDiffWithAncestors(
        updates: Map<String, FolderSizeInfo>,
        removedPaths: Collection<String>,
        ancestorUpdates: Map<String, FolderSizeInfo>
    ) {
        if (updates.isEmpty() && removedPaths.isEmpty() && ancestorUpdates.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (path in removedPaths) {
                db.delete(TABLE, "$COL_PATH = ?", arrayOf(normalize(path)))
            }
            for ((path, info) in updates) {
                db.insertWithOnConflict(
                    TABLE, null, contentValues(normalize(path), info), SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            for ((path, info) in ancestorUpdates) {
                db.insertWithOnConflict(
                    TABLE, null, contentValues(normalize(path), info), SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 删除路径自身及其所有后代。 */
    fun removeDescendants(path: String) {
        val key = normalize(path)
        val prefix = "$key/"
        writableDatabase.delete(
            TABLE, "$COL_PATH = ? OR ($COL_PATH >= ? AND $COL_PATH < ?)",
            arrayOf(key, prefix, prefix + "\uFFFF")
        )
    }

    private fun contentValues(path: String, info: FolderSizeInfo): ContentValues =
        ContentValues(3).apply {
            put(COL_PATH, path)
            put(COL_SIZE, info.size)
            put(COL_MTIME, info.lastModified)
        }
}
