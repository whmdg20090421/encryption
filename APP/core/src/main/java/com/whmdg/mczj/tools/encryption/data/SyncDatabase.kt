package com.whmdg.mczj.tools.encryption.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.whmdg.mczj.tools.AppDataPaths
import java.io.File

/**
 * 云盘同步索引数据库。
 *
 * 每个同步任务独立一个 DB 文件：<AppDataPaths.encryption>/云盘同步/<syncName>/vault_sync.db
 * 两张表：local_entries（本地文件状态）、cloud_entries（云端文件状态）。
 * 按 path 字典序存储，查询时 ORDER BY path 即可得到树形结构。
 */
class SyncDatabase private constructor(
    context: Context,
    private val dbFile: File
) : SQLiteOpenHelper(context, dbFile.absolutePath, null, DB_VERSION) {

    companion object {
        private const val DB_VERSION = 4
        private const val TAG = "SyncDatabase"

        private val instances = mutableMapOf<String, SyncDatabase>()

        /**
         * 获取同步数据库实例。
         * @param syncName 同步任务名称（保险箱名或用户输入的名称）
         * DB 路径：<AppDataPaths.encryption>/云盘同步/<syncName>/vault_sync.db
         *
         * 每次取用都会校验缓存实例指向的 DB 文件是否仍然存在：文件已被删除或替换时，
         * 旧实例指向的是失效的文件描述符（再次写入会抛 SQLITE_READONLY_DBMOVED），
         * 因此先关闭并移除旧实例，再按该路径重新指向——存在同名文件则打开它，
         * 不存在则由 SQLiteOpenHelper 重新建库。
         */
        fun getInstance(context: Context, syncName: String): SyncDatabase {
            val syncDir = File(AppDataPaths.encryption(context), "云盘同步/$syncName")
            if (!syncDir.exists()) syncDir.mkdirs()
            val dbFile = File(syncDir, "vault_sync.db")
            val path = dbFile.absolutePath
            synchronized(this) {
                val cached = instances[path]
                if (cached != null && cached.dbFile.exists()) return cached
                if (cached != null) {
                    cached.close()
                    instances.remove(path)
                }
                return SyncDatabase(context.applicationContext, dbFile).also { instances[path] = it }
            }
        }

        private const val TABLE_LOCAL = "local_entries"
        private const val TABLE_CLOUD = "cloud_entries"
        private const val TABLE_STATS = "sync_stats"

        /**
         * 关闭并移除指定同步目录的缓存实例，释放文件句柄。
         * 删除同步目录前必须调用，否则残留的打开句柄会阻止文件被真正删除。
         */
        fun closeInstance(context: Context, syncName: String) {
            val dbFile = File(File(AppDataPaths.encryption(context), "云盘同步/$syncName"), "vault_sync.db")
            synchronized(this) {
                val cached = instances.remove(dbFile.absolutePath)
                cached?.close()
            }
        }

        /**
         * 明文 MD5 落库的进程内批次缓冲。
         *
         * 逐文件写库会产生大量独立 fsync，是小文件加密场景的主要瓶颈。本缓冲把记录攒够
         * [MD5_FLUSH_THRESHOLD] 条或显式 [flushMd5Batch] 时，用一笔事务批量提交。
         *
         * 边界纪律（与调用方契约定死）：
         * - 只有密文 `renameTo` 成功后才允许 [enqueueMd5]，因此缓冲里永远不含
         *   "加密到一半被清理"的残留记录；
         * - 用户取消 / 任务结束 / 进程退出前，调用方必须在 `finally` 中 [flushMd5Batch]，
         *   保证已落盘密文的 MD5 不丢；
         * - 进程被杀时未 flush 的批次随加密线程一起消亡，与逐条写入的崩溃窗口等价，
         *   缺口由下次扫描重建，密文始终是权威数据。
         */
        private const val MD5_FLUSH_THRESHOLD = 64

        private val md5BatchLock = Any()
        private val md5Pending = HashMap<String, ArrayList<LocalMd5Record>>()

        /** 记录一个"已成功落盘"密文的明文 MD5；达到阈值时自动提交该保险箱的一批。 */
        fun enqueueMd5(context: Context, syncName: String, record: LocalMd5Record) {
            val toFlush: List<LocalMd5Record>?
            synchronized(md5BatchLock) {
                val list = md5Pending.getOrPut(syncName) { ArrayList(MD5_FLUSH_THRESHOLD) }
                list.add(record)
                toFlush = if (list.size >= MD5_FLUSH_THRESHOLD) {
                    val snapshot = ArrayList(list)
                    list.clear()
                    snapshot
                } else null
            }
            if (toFlush != null) submitMd5Batch(context, syncName, toFlush)
        }

        /** 强制提交某保险箱的当前缓冲。取消、任务结束前必须调用。 */
        fun flushMd5Batch(context: Context, syncName: String) {
            val snapshot: List<LocalMd5Record>?
            synchronized(md5BatchLock) {
                val list = md5Pending[syncName]
                snapshot = if (list.isNullOrEmpty()) null else ArrayList(list).also { list.clear() }
            }
            if (snapshot != null) submitMd5Batch(context, syncName, snapshot)
        }

        /**
         * 丢弃某保险箱尚未提交的 MD5 缓冲（不写库）。
         * 删除保险箱时必须调用，避免残留缓冲在下次同名建库时把旧数据写回。
         */
        fun discardMd5Batch(syncName: String) {
            synchronized(md5BatchLock) {
                md5Pending.remove(syncName)
            }
        }

        private fun submitMd5Batch(context: Context, syncName: String, records: List<LocalMd5Record>) {
            if (records.isEmpty()) return
            try {
                getInstance(context, syncName).upsertLocalMd5Batch(records)
            } catch (e: Exception) {
                // 密文已落盘，元数据写失败不应回滚业务；缺口由下次扫描重建
                com.whmdg.mczj.tools.util.DiagnosticLog.log(
                    TAG, "批量写入 ${records.size} 条明文 MD5 失败，本批次丢弃: ${e.message}"
                )
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE local_entries (
                path          TEXT PRIMARY KEY,
                size          INTEGER NOT NULL,
                uploaded_size INTEGER NOT NULL DEFAULT 0,
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
                uploaded_size INTEGER NOT NULL DEFAULT 0,
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

        db.execSQL("""
            CREATE TABLE sync_stats (
                id                INTEGER PRIMARY KEY CHECK(id = 1),
                local_file_count  INTEGER NOT NULL DEFAULT 0,
                cloud_file_count  INTEGER NOT NULL DEFAULT 0,
                local_size        INTEGER NOT NULL DEFAULT 0,
                cloud_size        INTEGER NOT NULL DEFAULT 0,
                diff_count        INTEGER NOT NULL DEFAULT 0,
                last_update       TEXT
            )
        """.trimIndent())
        db.execSQL("INSERT INTO sync_stats (id) VALUES (1)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // WAL：读写不互斥，加密多通道的元数据写入不再与其他通道互相阻塞
        db.enableWriteAheadLogging()
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE local_entries ADD COLUMN uploaded_size INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE local_entries SET uploaded_size = size WHERE status = 'COMPLETED'")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE cloud_entries ADD COLUMN uploaded_size INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE cloud_entries SET uploaded_size = size WHERE status = 'COMPLETED'")
        }
        if (oldVersion < 4) {
            db.execSQL("""
                CREATE TABLE sync_stats (
                    id                INTEGER PRIMARY KEY CHECK(id = 1),
                    local_file_count  INTEGER NOT NULL DEFAULT 0,
                    cloud_file_count  INTEGER NOT NULL DEFAULT 0,
                    local_size        INTEGER NOT NULL DEFAULT 0,
                    cloud_size        INTEGER NOT NULL DEFAULT 0,
                    diff_count        INTEGER NOT NULL DEFAULT 0,
                    last_update       TEXT
                )
            """.trimIndent())
            db.execSQL("INSERT INTO sync_stats (id) VALUES (1)")
        }
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

    /**
     * 获取指定路径前缀下的所有条目（用于删除文件夹时收集整棵子树）。
     * 前缀规范化与 deleteEntriesByPrefix 保持一致：path >= prefix/ AND path < prefix/￿。
     * 注意：结果不含传入路径自身。
     */
    fun getEntriesByPrefix(table: String, prefix: String): List<SyncEntryRow> {
        val db = readableDatabase
        val prefixNorm = if (prefix.endsWith("/")) prefix else "$prefix/"
        val upperBound = prefixNorm + "￿"
        val cursor = db.query(
            table, null,
            "path >= ? AND path < ?",
            arrayOf(prefixNorm, upperBound),
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

    /**
     * 仅写入本地条目的明文 MD5（加密导入时调用）。
     *
     * 行不存在则插入（status=PENDING），已存在则只更新 md5，不动其他字段，
     * 避免与扫描写行产生竞态把已有状态覆盖。
     */
    fun upsertLocalMd5(path: String, md5: String, size: Long, lastModified: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("path", path)
            put("size", size)
            put("uploaded_size", 0L)
            put("last_modified", lastModified)
            put("md5", md5)
            put("status", SyncStatus.PENDING.name)
        }
        db.insertWithOnConflict("local_entries", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        db.update("local_entries", ContentValues().apply { put("md5", md5) }, "path = ?", arrayOf(path))
    }

    /**
     * 批量写入明文 MD5：整个批次放在一笔事务里提交，把逐文件的 fsync 摊销到一次。
     *
     * 语义与逐条 [upsertLocalMd5] 完全一致，仅改变提交粒度。调用方须保证传入的每条记录
     * 都对应一个"已成功落盘"的密文文件（密文 renameTo 成功后才产生记录）。
     *
     * 云端已有同路径记录且明文 MD5 一致时，直接置为 COMPLETED（与逐条写入时的后置比对等价）。
     */
    fun upsertLocalMd5Batch(records: List<LocalMd5Record>) {
        if (records.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in records) {
                val values = ContentValues().apply {
                    put("path", r.path)
                    put("size", r.size)
                    put("uploaded_size", 0L)
                    put("last_modified", r.lastModified)
                    put("md5", r.md5)
                    put("status", SyncStatus.PENDING.name)
                }
                db.insertWithOnConflict("local_entries", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                db.update("local_entries", ContentValues().apply { put("md5", r.md5) }, "path = ?", arrayOf(r.path))

                val cloud = queryCloudMd5Locked(db, r.path)
                if (!cloud.isNullOrEmpty() && cloud == r.md5) {
                    db.execSQL(
                        "UPDATE local_entries SET status = ?, uploaded_size = size, last_sync_time = ? WHERE path = ?",
                        arrayOf(SyncStatus.COMPLETED.name, java.time.Instant.now().toString(), r.path)
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 事务内查询云端条目的明文 MD5（复用同一连接，避免嵌套获取只读连接）。 */
    private fun queryCloudMd5Locked(db: SQLiteDatabase, path: String): String? {
        db.query("cloud_entries", arrayOf("md5"), "path = ?", arrayOf(path), null, null, null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    /** 待写入的一批本地明文 MD5 记录。 */
    data class LocalMd5Record(
        val path: String,
        val md5: String,
        val size: Long,
        val lastModified: String
    )

    /**
     * 从解压出的云端 SQLite 文件全量替换 cloud_entries，保留本地 local_entries。
     *
     * 云端数据库是权威全量快照：导入前先清空本地 cloud_entries，再写入云端条目。
     * 使用替换而非合并语义，确保云端已删除的文件不会残留在本地（否则旧文件结构会阴魂不散）。
     */
    fun importCloudEntriesFromFile(sourceFile: File) {
        if (!sourceFile.exists()) return
        val source = SQLiteDatabase.openDatabase(sourceFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val entries = mutableListOf<SyncEntryRow>()
            source.query("cloud_entries", null, null, null, null, null, "path").use { cursor ->
                while (cursor.moveToNext()) entries.add(cursorToRow(cursor))
            }
            replaceEntries("cloud_entries", entries)
        } finally {
            source.close()
        }
    }

    /**
     * 导出仅含 cloud_entries 的离线快照到目标文件（用于上传到云端）。
     *
     * 云端数据库是各设备共享的权威云端快照，只应包含 cloud_entries；
     * local_entries 是每台设备私有的本地状态记录，绝不上传。
     * 目标文件若已存在会被覆盖，且文件名必须为 vault_sync.db（压缩包内的条目名）。
     */
    fun exportCloudOnlyTo(destFile: File) {
        if (destFile.exists()) destFile.delete()
        val snapshot = SQLiteDatabase.openOrCreateDatabase(destFile, null)
        try {
            snapshot.execSQL("""
                CREATE TABLE cloud_entries (
                    path          TEXT PRIMARY KEY,
                    size          INTEGER NOT NULL,
                    uploaded_size INTEGER NOT NULL DEFAULT 0,
                    last_modified TEXT NOT NULL,
                    md5           TEXT NOT NULL,
                    cloud_hash    TEXT,
                    status        TEXT NOT NULL DEFAULT 'PENDING',
                    last_sync_time TEXT,
                    fail_reason   TEXT
                )
            """.trimIndent())
            snapshot.execSQL("CREATE INDEX idx_cloud_status ON cloud_entries(status)")
            val entries = getAllEntries("cloud_entries")
            snapshot.beginTransaction()
            try {
                for (entry in entries) {
                    snapshot.insertWithOnConflict("cloud_entries", null, rowToValues(entry), SQLiteDatabase.CONFLICT_REPLACE)
                }
                snapshot.setTransactionSuccessful()
            } finally {
                snapshot.endTransaction()
            }
        } finally {
            snapshot.close()
        }
    }

    /** 全量替换表内容：在单个事务内先清空再写入，失败时回滚，保留旧数据。 */
    private fun replaceEntries(table: String, entries: List<SyncEntryRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(table, null, null)
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
            when (status) {
                SyncStatus.COMPLETED -> {
                    put("last_sync_time", java.time.Instant.now().toString())
                    // 完成时 uploaded_size = size
                    db.execSQL("UPDATE $table SET uploaded_size = size WHERE path = ?", arrayOf(path))
                }
                SyncStatus.UPLOADING -> {
                    put("uploaded_size", 0)
                }
                else -> {}
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

    fun updateCloudHash(table: String, path: String, cloudHash: String) {
        val db = writableDatabase
        val values = ContentValues().apply { put("cloud_hash", cloudHash) }
        db.update(table, values, "path = ?", arrayOf(path))
    }

    /**
     * 云端已确认被删除时，作废本地所有"已同步"标记（单事务执行，失败回滚）。
     *
     * - cloud_entries 整表清空：云端快照已不存在，本地镜像必须作废；
     * - local_entries 的 COMPLETED 重置为 PENDING 并清零已上传进度：
     *   "已同步（绿色）"必须有云端凭据支撑，凭据消失后继续显示绿色即为假象；
     * - sync_stats 的云端统计归零，diff_count 更新为本地文件数（全部成为差异）。
     *
     * 幂等：云端状态已作废时重复调用无副作用。
     */
    fun invalidateCloudState() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_CLOUD, null, null)

            val pending = ContentValues().apply {
                put("status", SyncStatus.PENDING.name)
                put("uploaded_size", 0)
                put("fail_reason", null as String?)
            }
            db.update(TABLE_LOCAL, pending, "status = ?", arrayOf(SyncStatus.COMPLETED.name))

            val localCount = db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_LOCAL WHERE path NOT LIKE '%/'", null
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val localSize = db.rawQuery(
                "SELECT COALESCE(SUM(size), 0) FROM $TABLE_LOCAL WHERE path NOT LIKE '%/'", null
            ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

            db.execSQL(
                """
                UPDATE $TABLE_STATS SET
                    cloud_file_count = 0,
                    cloud_size = 0,
                    local_file_count = ?,
                    local_size = ?,
                    diff_count = ?,
                    last_update = ?
                WHERE id = 1
                """.trimIndent(),
                arrayOf(localCount, localSize, localCount, java.time.Instant.now().toString())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 通用的条目更新方法，使用 transform 函数更新指定字段 */
    fun updateEntry(table: String, path: String, transform: (SyncEntryRow) -> SyncEntryRow) {
        val db = writableDatabase
        val entry = getEntry(table, path) ?: return
        val updated = transform(entry)

        val values = ContentValues().apply {
            put("size", updated.size)
            put("uploaded_size", updated.uploadedSize)
            put("last_modified", updated.lastModified)
            updated.md5?.let { put("md5", it) }
            updated.cloudHash?.let { put("cloud_hash", it) }
            put("status", updated.status.name)
            updated.lastSyncTime?.let { put("last_sync_time", it) }
            updated.failReason?.let { put("fail_reason", it) }
        }
        db.update(table, values, "path = ?", arrayOf(path))
    }

    /** 重置所有 UPLOADING 状态为 PENDING（中断恢复用，WebDAV 不支持断点续传） */
    fun resetUploadingToPending(table: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("status", SyncStatus.PENDING.name)
            put("uploaded_size", 0)
            put("fail_reason", null as String?)
        }
        db.update(table, values, "status = ?", arrayOf(SyncStatus.UPLOADING.name))
    }

    fun updateUploadedSize(table: String, path: String, uploadedSize: Long) {
        val db = writableDatabase
        val values = ContentValues().apply { put("uploaded_size", uploadedSize) }
        db.update(table, values, "path = ?", arrayOf(path))
    }

    fun updateSize(table: String, path: String, size: Long, lastModified: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("size", size)
            put("last_modified", lastModified)
            put("uploaded_size", 0)  // 文件大小变化时重置已上传大小
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

    /** 统计已完成的文件数量（排除文件夹，文件夹路径以 / 结尾） */
    fun getCompletedFileCount(table: String): Int {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $table WHERE status = ? AND path NOT LIKE '%/'",
            arrayOf(SyncStatus.COMPLETED.name)
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    // ── 内部工具 ──

    private fun cursorToRow(cursor: android.database.Cursor): SyncEntryRow {
        val sizeIdx = cursor.getColumnIndex("uploaded_size")
        return SyncEntryRow(
            path = cursor.getString(cursor.getColumnIndexOrThrow("path")),
            size = cursor.getLong(cursor.getColumnIndexOrThrow("size")),
            uploadedSize = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L,
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
            put("uploaded_size", entry.uploadedSize)
            put("last_modified", entry.lastModified)
            put("md5", entry.md5)
            put("cloud_hash", entry.cloudHash)
            put("status", entry.status.name)
            put("last_sync_time", entry.lastSyncTime)
            put("fail_reason", entry.failReason)
        }
    }

    // ── 统计数据 ──

    fun getStats(): SyncStatsRow {
        val db = readableDatabase
        val cursor = db.query(TABLE_STATS, null, "id = 1", null, null, null, null)
        return cursor.use {
            if (it.moveToFirst()) {
                SyncStatsRow(
                    localFileCount = it.getInt(it.getColumnIndexOrThrow("local_file_count")),
                    cloudFileCount = it.getInt(it.getColumnIndexOrThrow("cloud_file_count")),
                    localSize = it.getLong(it.getColumnIndexOrThrow("local_size")),
                    cloudSize = it.getLong(it.getColumnIndexOrThrow("cloud_size")),
                    diffCount = it.getInt(it.getColumnIndexOrThrow("diff_count")),
                    lastUpdate = it.getString(it.getColumnIndexOrThrow("last_update"))
                )
            } else {
                SyncStatsRow()
            }
        }
    }

    fun updateStats(stats: SyncStatsRow) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("local_file_count", stats.localFileCount)
            put("cloud_file_count", stats.cloudFileCount)
            put("local_size", stats.localSize)
            put("cloud_size", stats.cloudSize)
            put("diff_count", stats.diffCount)
            put("last_update", stats.lastUpdate)
        }
        db.update(TABLE_STATS, values, "id = 1", null)
    }

    fun adjustLocalStats(deltaFiles: Int, deltaSize: Long) {
        val db = writableDatabase
        db.execSQL("""
            UPDATE $TABLE_STATS SET
                local_file_count = MAX(0, local_file_count + ?),
                local_size = MAX(0, local_size + ?),
                last_update = ?
            WHERE id = 1
        """.trimIndent(), arrayOf(deltaFiles, deltaSize, java.time.Instant.now().toString()))
    }

    fun adjustCloudStats(deltaFiles: Int, deltaSize: Long) {
        val db = writableDatabase
        db.execSQL("""
            UPDATE $TABLE_STATS SET
                cloud_file_count = MAX(0, cloud_file_count + ?),
                cloud_size = MAX(0, cloud_size + ?),
                last_update = ?
            WHERE id = 1
        """.trimIndent(), arrayOf(deltaFiles, deltaSize, java.time.Instant.now().toString()))
    }
}

data class SyncStatsRow(
    val localFileCount: Int = 0,
    val cloudFileCount: Int = 0,
    val localSize: Long = 0L,
    val cloudSize: Long = 0L,
    val diffCount: Int = 0,
    val lastUpdate: String? = null
)

/** 同步条目数据行 */
data class SyncEntryRow(
    val path: String,
    val size: Long,
    val uploadedSize: Long = 0,  // 已上传字节数（仅 local_entries 使用）
    val lastModified: String,    // ISO8601
    val md5: String?,            // 明文 MD5；本地表在加密导入时写入，云端表在上传成功后从本地复制
    val cloudHash: String?,      // 云端返回的内部编码（唯一性）
    val status: SyncStatus,
    val lastSyncTime: String?,   // ISO8601
    val failReason: String?
)
