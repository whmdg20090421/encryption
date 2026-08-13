package com.whmdg.mczj.tools.encryption.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * 云盘同步索引数据库。
 *
 * 每个保险箱独立一个 DB 文件：<vaultDir>/vault_sync.db
 * 两张表：local_entries（本地文件状态）、cloud_entries（云端文件状态）。
 * 按 path 字典序存储，查询时 ORDER BY path 即可得到树形结构。
 */
class SyncDatabase private constructor(context: Context, dbPath: String) :
    SQLiteOpenHelper(context, dbPath, null, DB_VERSION) {

    companion object {
        private const val DB_VERSION = 1
        private const val TAG = "SyncDatabase"

        private val instances = mutableMapOf<String, SyncDatabase>()

        fun getInstance(context: Context, vaultDir: String): SyncDatabase {
            val dbFile = File(vaultDir, "vault_sync.db")
            val path = dbFile.absolutePath
            return instances[path] ?: synchronized(this) {
                instances[path] ?: SyncDatabase(context.applicationContext, path).also {
                    instances[path] = it
                }
            }
        }

        private const val TABLE_LOCAL = "local_entries"
        private const val TABLE_CLOUD = "cloud_entries"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE local_entries (
                path          TEXT PRIMARY KEY,
                size          INTEGER NOT NULL,
                last_modified TEXT NOT NULL,
                md5           TEXT,
                cloud_hash    TEXT,
                status        TEXT NOT NULL DEFAULT 'PENDING',
                last_sync_time TEXT,
                fail_reason   TEXT
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE cloud_entries (
                path          TEXT PRIMARY KEY,
                size          INTEGER NOT NULL,
                last_modified TEXT NOT NULL,
                md5           TEXT NOT NULL,
                cloud_hash    TEXT,
                status        TEXT NOT NULL DEFAULT 'PENDING',
                last_sync_time TEXT,
                fail_reason   TEXT
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_local_status ON local_entries(status)")
        db.execSQL("CREATE INDEX idx_cloud_status ON cloud_entries(status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 未来版本升级时在此处理
    }

    // ── 查询 ──

    fun getEntry(table: String, path: String): SyncEntryRow? {
        val db = readableDatabase
        val cursor = db.query(table, null, "path = ?", arrayOf(path), null, null, null)
        return cursor.use {
            if (it.moveToFirst()) cursorToRow(it) else null
        }
    }

    fun getAllEntries(table: String): List<SyncEntryRow> {
        val db = readableDatabase
        val cursor = db.query(table, null, null, null, null, null, "path")
        return cursor.use {
            val list = mutableListOf<SyncEntryRow>()
            while (it.moveToNext()) {
                list.add(cursorToRow(it))
            }
            list
        }
    }

    fun getEntriesByStatus(table: String, status: SyncStatus): List<SyncEntryRow> {
        val db = readableDatabase
        val cursor = db.query(table, null, "status = ?", arrayOf(status.name), null, null, "path")
        return cursor.use {
            val list = mutableListOf<SyncEntryRow>()
            while (it.moveToNext()) {
                list.add(cursorToRow(it))
            }
            list
        }
    }

    /**
     * 获取指定目录下的直接子条目。
     * 使用范围查询避免前缀误匹配（如 /folder1 不匹配 /folder10）。
     */
    fun getEntriesByParent(table: String, parentPath: String): List<SyncEntryRow> {
        val db = readableDatabase
        val prefix = if (parentPath.endsWith("/")) parentPath else "$parentPath/"
        // 范围查询：path >= prefix AND path < prefix + '￿'
        val upperBound = prefix + "￿"
        val cursor = db.query(
            table, null,
            "path >= ? AND path < ?",
            arrayOf(prefix, upperBound),
            null, null, "path"
        )
        return cursor.use {
            val list = mutableListOf<SyncEntryRow>()
            while (it.moveToNext()) {
                list.add(cursorToRow(it))
            }
            list
        }
    }

    // ── 写入 ──

    fun upsertEntry(table: String, entry: SyncEntryRow) {
        val db = writableDatabase
        db.insertWithOnConflict(table, null, rowToValues(entry), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun upsertEntries(table: String, entries: List<SyncEntryRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (entry in entries) {
                db.insertWithOnConflict(table, null, rowToValues(entry), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateStatus(table: String, path: String, status: SyncStatus, failReason: String? = null) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("status", status.name)
            put("fail_reason", failReason)
            if (status == SyncStatus.COMPLETED) {
                put("last_sync_time", java.time.Instant.now().toString())
            }
        }
        db.update(table, values, "path = ?", arrayOf(path))
    }

    fun updateStatusBatch(table: String, paths: List<String>, status: SyncStatus) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("status", status.name)
                if (status == SyncStatus.COMPLETED) {
                    put("last_sync_time", java.time.Instant.now().toString())
                }
            }
            for (path in paths) {
                db.update(table, values, "path = ?", arrayOf(path))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateMd5(table: String, path: String, md5: String) {
        val db = writableDatabase
        val values = ContentValues().apply { put("md5", md5) }
        db.update(table, values, "path = ?", arrayOf(path))
    }

    fun updateCloudHash(table: String, path: String, cloudHash: String) {
        val db = writableDatabase
        val values = ContentValues().apply { put("cloud_hash", cloudHash) }
        db.update(table, values, "path = ?", arrayOf(path))
    }

    fun updateSize(table: String, path: String, size: Long, lastModified: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("size", size)
            put("last_modified", lastModified)
        }
        db.update(table, values, "path = ?", arrayOf(path))
    }

    // ── 删除 ──

    fun deleteEntry(table: String, path: String) {
        val db = writableDatabase
        db.delete(table, "path = ?", arrayOf(path))
    }

    fun deleteEntries(table: String, paths: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (path in paths) {
                db.delete(table, "path = ?", arrayOf(path))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 删除指定前缀下的所有条目（用于删除文件夹时清理索引） */
    fun deleteEntriesByPrefix(table: String, prefix: String) {
        val db = writableDatabase
        val prefixNorm = if (prefix.endsWith("/")) prefix else "$prefix/"
        val upperBound = prefixNorm + "￿"
        db.delete(table, "path >= ? AND path < ?", arrayOf(prefixNorm, upperBound))
    }

    // ── 统计 ──

    fun getStatusCounts(table: String): Map<SyncStatus, Int> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT status, COUNT(*) FROM $table GROUP BY status", null
        )
        return cursor.use {
            val map = mutableMapOf<SyncStatus, Int>()
            while (it.moveToNext()) {
                val status = SyncStatus.valueOf(it.getString(0))
                val count = it.getInt(1)
                map[status] = count
            }
            map
        }
    }

    fun getTotalSize(table: String): Long {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COALESCE(SUM(size), 0) FROM $table", null)
        return cursor.use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    fun getSyncedSize(table: String): Long {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT COALESCE(SUM(size), 0) FROM $table WHERE status = ?",
            arrayOf(SyncStatus.COMPLETED.name)
        )
        return cursor.use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    // ── 内部工具 ──

    private fun cursorToRow(cursor: android.database.Cursor): SyncEntryRow {
        return SyncEntryRow(
            path = cursor.getString(cursor.getColumnIndexOrThrow("path")),
            size = cursor.getLong(cursor.getColumnIndexOrThrow("size")),
            lastModified = cursor.getString(cursor.getColumnIndexOrThrow("last_modified")),
            md5 = cursor.getString(cursor.getColumnIndexOrThrow("md5")),
            cloudHash = cursor.getString(cursor.getColumnIndexOrThrow("cloud_hash")),
            status = SyncStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
            lastSyncTime = cursor.getString(cursor.getColumnIndexOrThrow("last_sync_time")),
            failReason = cursor.getString(cursor.getColumnIndexOrThrow("fail_reason"))
        )
    }

    private fun rowToValues(entry: SyncEntryRow): ContentValues {
        return ContentValues().apply {
            put("path", entry.path)
            put("size", entry.size)
            put("last_modified", entry.lastModified)
            put("md5", entry.md5)
            put("cloud_hash", entry.cloudHash)
            put("status", entry.status.name)
            put("last_sync_time", entry.lastSyncTime)
            put("fail_reason", entry.failReason)
        }
    }
}

/** 同步条目数据行 */
data class SyncEntryRow(
    val path: String,
    val size: Long,
    val lastModified: String,    // ISO8601
    val md5: String?,            // 本地表：上传过程中异步计算，初始为 NULL；云端表：必填
    val cloudHash: String?,      // 云端返回的内部编码（唯一性）
    val status: SyncStatus,
    val lastSyncTime: String?,   // ISO8601
    val failReason: String?
)
