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
        private const val DB_VERSION = 8
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
        private const val TABLE_META = "sync_meta"

        /** 本地 cloud_entries 快照最后变更时间（UTC ISO8601），用于云端/本地主从判定。 */
        private const val KEY_CLOUD_DB_UPDATED_AT = "cloud_db_updated_at"

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
         * 明文内容指纹落库的进程内批次缓冲。
         *
         * 逐文件写库会产生大量独立 fsync，是小文件加密场景的主要瓶颈。本缓冲把记录攒够
         * [HASH_FLUSH_THRESHOLD] 条或显式 [flushContentHashBatch] 时，用一笔事务批量提交。
         *
         * 边界纪律（与调用方契约定死）：
         * - 只有密文 `renameTo` 成功后才允许 [enqueueContentHash]，因此缓冲里永远不含
         *   "加密到一半被清理"的残留记录；
         * - 用户取消 / 任务结束 / 进程退出前，调用方必须在 `finally` 中 [flushContentHashBatch]，
         *   保证已落盘密文的指纹不丢；
         * - 进程被杀时未 flush 的批次随加密线程一起消亡，与逐条写入的崩溃窗口等价，
         *   缺口由下次扫描重建，密文始终是权威数据。
         */
        private const val HASH_FLUSH_THRESHOLD = 64

        private val hashBatchLock = Any()
        private val hashPending = HashMap<String, ArrayList<LocalHashRecord>>()

        /** 记录一个"已成功落盘"密文的明文内容指纹；达到阈值时自动提交该保险箱的一批。 */
        fun enqueueContentHash(context: Context, syncName: String, record: LocalHashRecord) {
            val toFlush: List<LocalHashRecord>?
            synchronized(hashBatchLock) {
                val list = hashPending.getOrPut(syncName) { ArrayList(HASH_FLUSH_THRESHOLD) }
                list.add(record)
                toFlush = if (list.size >= HASH_FLUSH_THRESHOLD) {
                    val snapshot = ArrayList(list)
                    list.clear()
                    snapshot
                } else null
            }
            if (toFlush != null) submitHashBatch(context, syncName, toFlush)
        }

        /** 强制提交某保险箱的当前缓冲。取消、任务结束前必须调用。 */
        fun flushContentHashBatch(context: Context, syncName: String) {
            val snapshot: List<LocalHashRecord>?
            synchronized(hashBatchLock) {
                val list = hashPending[syncName]
                snapshot = if (list.isNullOrEmpty()) null else ArrayList(list).also { list.clear() }
            }
            if (snapshot != null) submitHashBatch(context, syncName, snapshot)
        }

        /**
         * 丢弃某保险箱尚未提交的内容指纹缓冲（不写库）。
         * 删除保险箱时必须调用，避免残留缓冲在下次同名建库时把旧数据写回。
         */
        fun discardContentHashBatch(syncName: String) {
            synchronized(hashBatchLock) {
                hashPending.remove(syncName)
            }
        }

        private fun submitHashBatch(context: Context, syncName: String, records: List<LocalHashRecord>) {
            if (records.isEmpty()) return
            try {
                getInstance(context, syncName).upsertLocalHashBatch(records)
            } catch (e: Exception) {
                // 密文已落盘，元数据写失败不应回滚业务；缺口由下次扫描重建
                com.whmdg.mczj.tools.util.DiagnosticLog.log(
                    TAG, "批量写入 ${records.size} 条明文内容指纹失败，本批次丢弃: ${e.message}"
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
                content_hash  TEXT,
                cloud_hash    TEXT,
                status        TEXT NOT NULL DEFAULT 'PENDING',
                last_sync_time TEXT,
                fail_reason   TEXT,
                original_name TEXT
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE cloud_entries (
                path          TEXT PRIMARY KEY,
                size          INTEGER NOT NULL,
                uploaded_size INTEGER NOT NULL DEFAULT 0,
                last_modified TEXT NOT NULL,
                content_hash  TEXT NOT NULL,
                cloud_hash    TEXT,
                status        TEXT NOT NULL DEFAULT 'PENDING',
                last_sync_time TEXT,
                fail_reason   TEXT,
                dir_created   INTEGER NOT NULL DEFAULT 0,
                original_name TEXT
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

        db.execSQL("""
            CREATE TABLE sync_meta (
                key   TEXT PRIMARY KEY,
                value TEXT
            )
        """.trimIndent())
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // WAL：读写不互斥，加密多通道的元数据写入不再与其他通道互相阻塞
        db.enableWriteAheadLogging()
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v6：内容指纹列由 md5 更名为 content_hash，且算法由 MD5 改为 SHA-256。
        // v7：新增 original_name 列（文件名加密改为 AES-ECB 后，原始名权威来源落库）。
        // 旧记录中的指纹是旧算法产物、且旧库不含原始名，无法复用，因此直接弃库重建
        //（云端快照为权威数据，本地缺口由下次扫描重建）。
        if (oldVersion < 7) {
            db.execSQL("DROP TABLE IF EXISTS local_entries")
            db.execSQL("DROP TABLE IF EXISTS cloud_entries")
            db.execSQL("DROP TABLE IF EXISTS sync_stats")
            db.execSQL("DROP TABLE IF EXISTS sync_meta")
            onCreate(db)
            return
        }
        // v8：新增 sync_meta 表记录 cloud_entries 快照变更时戳，用于云端/本地主从判定。
        // 不弃库重建：旧库数据全部保留，仅补建缺失的元数据表。
        // 老库升级时若本地已有 cloud_entries 且尚无时戳，则补种为当前时刻：
        // 遵循「云端无时戳时优先采用本地」的规则，避免首次同步被云端滞后快照覆盖。
        if (oldVersion < 8) {
            db.execSQL("CREATE TABLE IF NOT EXISTS sync_meta (key TEXT PRIMARY KEY, value TEXT)")
            val hasEntries = db.rawQuery("SELECT COUNT(*) FROM cloud_entries", null)
                .use { if (it.moveToFirst()) it.getInt(0) > 0 else false }
            if (hasEntries) {
                db.execSQL(
                    "INSERT OR REPLACE INTO sync_meta (key, value) VALUES (?, ?)",
                    arrayOf(KEY_CLOUD_DB_UPDATED_AT, java.time.Instant.now().toString())
                )
            }
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

    /**
     * 查询单个目录条目的「是否已创建」标记。
     * 目录条目的 path 以 '/' 结尾；不存在或非目录条目时返回 null。
     */
    fun getDirCreated(path: String): Boolean? {
        val db = readableDatabase
        val dirPath = if (path.endsWith("/")) path else "$path/"
        val cursor = db.query(
            "cloud_entries", arrayOf("dir_created"), "path = ?",
            arrayOf(dirPath), null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) != 0 else null
        }
    }

    /** 写入/更新目录条目的「是否已创建」标记（目录条目 path 以 '/' 结尾）。 */
    fun setDirCreated(path: String, created: Boolean) {
        val db = writableDatabase
        val dirPath = if (path.endsWith("/")) path else "$path/"
        // 行不存在则插入一条目录条目（size=0、content_hash 为空、status=COMPLETED），存在则只更新 dir_created
        val insertDir = ContentValues().apply {
            put("path", dirPath)
            put("size", 0L)
            put("uploaded_size", 0L)
            put("last_modified", java.time.Instant.now().toString())
            put("content_hash", "")
            put("status", SyncStatus.COMPLETED.name)
            put("dir_created", if (created) 1 else 0)
        }
        db.insertWithOnConflict("cloud_entries", null, insertDir, SQLiteDatabase.CONFLICT_IGNORE)
        db.update(
            "cloud_entries",
            ContentValues().apply { put("dir_created", if (created) 1 else 0) },
            "path = ?", arrayOf(dirPath)
        )
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
        db.insertWithOnConflict(table, null, rowToValues(entry, table), SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * 写入条目，但 [SyncEntryRow.originalName] 为空时保留库中已有值。
     *
     * 扫描 / 云端对比等"只更新文件状态、不掌握原始名"的路径必须用它，
     * 否则 CONFLICT_REPLACE 会用 NULL 覆盖掉加密时写入的 `original_name`。
     */
    fun upsertEntryPreservingOriginalName(table: String, entry: SyncEntryRow) {
        val merged = if (entry.originalName == null) {
            entry.copy(originalName = getEntry(table, entry.path)?.originalName)
        } else {
            entry
        }
        upsertEntry(table, merged)
    }

    /** 写入 / 更新单个条目的原始文件名（path 必须已存在或由调用方补齐其余字段）。 */
    fun setOriginalName(table: String, path: String, originalName: String?) {
        val db = writableDatabase
        db.update(table, ContentValues().apply { put("original_name", originalName) }, "path = ?", arrayOf(path))
    }

    /**
     * 为某条本地文件记录写入原始名：行已存在则只更新 `original_name`（不动上传状态），
     * 不存在则插入一条 PENDING 行（内容指纹留空，由后续扫描补全）。
     *
     * 用于「加密文件名」开关迁移：把磁盘上已存在的明文文件改写为密文名时，
     * 这些文件可能尚未被扫描入同步库，需在此补齐显示名。
     */
    fun upsertLocalOriginalName(
        path: String,
        size: Long,
        lastModified: String,
        originalName: String
    ) {
        val db = writableDatabase
        val insert = ContentValues().apply {
            put("path", path)
            put("size", size)
            put("uploaded_size", 0L)
            put("last_modified", lastModified)
            put("content_hash", null as String?)
            put("status", SyncStatus.PENDING.name)
            put("original_name", originalName)
        }
        db.insertWithOnConflict("local_entries", null, insert, SQLiteDatabase.CONFLICT_IGNORE)
        db.update(
            "local_entries",
            ContentValues().apply { put("original_name", originalName) },
            "path = ?", arrayOf(path)
        )
    }

    /**
     * 批量取某个目录下直接子条目的 `路径 → 原始名` 映射（仅含 non-null 原始名）。
     * 供文件管理器一次查询完成整目录文件名还原，避免逐文件查库。
     */
    fun getOriginalNamesByParent(table: String, parentPath: String): Map<String, String> {
        val result = HashMap<String, String>()
        for (row in getEntriesByParent(table, parentPath)) {
            val name = row.originalName
            if (!name.isNullOrEmpty()) result[row.path] = name
        }
        return result
    }

    fun upsertEntries(table: String, entries: List<SyncEntryRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (entry in entries) {
                db.insertWithOnConflict(table, null, rowToValues(entry, table), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 仅写入本地条目的明文内容指纹（加密导入时调用）。
     *
     * 行不存在则插入（status=PENDING），已存在则只更新 content_hash，不动其他字段，
     * 避免与扫描写行产生竞态把已有状态覆盖。
     */
    fun upsertLocalContentHash(path: String, contentHash: String, size: Long, lastModified: String, originalName: String? = null) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("path", path)
            put("size", size)
            put("uploaded_size", 0L)
            put("last_modified", lastModified)
            put("content_hash", contentHash)
            put("status", SyncStatus.PENDING.name)
            put("original_name", originalName)
        }
        db.insertWithOnConflict("local_entries", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        db.update(
            "local_entries",
            ContentValues().apply {
                put("content_hash", contentHash)
                if (originalName != null) put("original_name", originalName)
            },
            "path = ?", arrayOf(path)
        )
    }

    /**
     * 批量写入明文内容指纹：整个批次放在一笔事务里提交，把逐文件的 fsync 摊销到一次。
     *
     * 语义与逐条 [upsertLocalContentHash] 完全一致，仅改变提交粒度。调用方须保证传入的每条记录
     * 都对应一个"已成功落盘"的密文文件（密文 renameTo 成功后才产生记录）。
     *
     * 云端已有同路径记录且内容指纹一致时，直接置为 COMPLETED（与逐条写入时的后置比对等价）。
     */
    fun upsertLocalHashBatch(records: List<LocalHashRecord>) {
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
                    put("content_hash", r.contentHash)
                    put("status", SyncStatus.PENDING.name)
                    put("original_name", r.originalName)
                }
                db.insertWithOnConflict("local_entries", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                db.update(
                    "local_entries",
                    ContentValues().apply {
                        put("content_hash", r.contentHash)
                        put("original_name", r.originalName)
                    },
                    "path = ?", arrayOf(r.path)
                )

                val cloud = queryCloudContentHashLocked(db, r.path)
                if (!cloud.isNullOrEmpty() && cloud == r.contentHash) {
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

    /** 事务内查询云端条目的明文内容指纹（复用同一连接，避免嵌套获取只读连接）。 */
    private fun queryCloudContentHashLocked(db: SQLiteDatabase, path: String): String? {
        db.query("cloud_entries", arrayOf("content_hash"), "path = ?", arrayOf(path), null, null, null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    /** 待写入的一批本地明文内容指纹记录。 */
    data class LocalHashRecord(
        val path: String,
        val contentHash: String,
        val size: Long,
        val lastModified: String,
        /** 该密文对应的原始文件名（文件名加密时必填）。 */
        val originalName: String? = null
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
            // 导入后本地视作与云端一致：时戳随云端快照走（云端无时戳则保持本地原值，
            // 由上层主从判定决定是否回传，避免无意义的本地时戳被清空）。
            readCloudDbTimestampFromDb(source)?.let { setCloudDbTimestamp(it) }
        } finally {
            source.close()
        }
    }

    /**
     * 从解压出的云端 SQLite 文件「并集合并」cloud_entries，保留本地 local_entries。
     *
     * 与 [importCloudEntriesFromFile] 的全量替换不同，本方法只增不删：
     *  - 保留本地全部条目（本设备已上传、云端快照尚未包含的记录）；
     *  - 并入云端有、本地没有的条目（其他设备上传、本设备尚不知晓的记录）；
     *  - 同路径以本地为准（本地时戳领先时本地更权威）。
     *
     * 用于主从判定为「本地领先」的场景：此时若用滞后/部分云端快照整表覆盖，
     * 会把云端已有的记录抹掉，导致上传后云端数据反而变少；并集合并可保证两边
     * 信息都不丢失，随后回传的并集即为云端全量。
     */
    fun mergeCloudEntriesFromFile(sourceFile: File) {
        if (!sourceFile.exists()) return
        val source = SQLiteDatabase.openDatabase(sourceFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val remoteEntries = mutableListOf<SyncEntryRow>()
            source.query("cloud_entries", null, null, null, null, null, "path").use { cursor ->
                while (cursor.moveToNext()) remoteEntries.add(cursorToRow(cursor))
            }
            val localPaths = getAllEntries("cloud_entries").map { it.path }.toHashSet()
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (entry in remoteEntries) {
                    // 本地不存在该路径才写入；同路径以本地为准，避免本地新版本被云端旧值覆盖
                    if (entry.path !in localPaths) {
                        db.insertWithOnConflict(
                            TABLE_CLOUD, null, rowToValues(entry, TABLE_CLOUD), SQLiteDatabase.CONFLICT_REPLACE
                        )
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            source.close()
        }
    }

    /** 只读探测云端快照文件内的 cloud_db_updated_at，不修改本地库。 */
    fun readCloudDbTimestampFromFile(sourceFile: File): String? {
        if (!sourceFile.exists()) return null
        val source = SQLiteDatabase.openDatabase(sourceFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            readCloudDbTimestampFromDb(source)
        } finally {
            source.close()
        }
    }

    /** 从已打开的源库读取 cloud_db_updated_at；无 sync_meta 表或无记录返回 null。 */
    private fun readCloudDbTimestampFromDb(source: SQLiteDatabase): String? {
        val hasTable = source.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='sync_meta'", null
        ).use { it.moveToFirst() }
        if (!hasTable) return null
        source.query(TABLE_META, arrayOf("value"), "key = ?", arrayOf(KEY_CLOUD_DB_UPDATED_AT), null, null, null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
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
                    content_hash  TEXT NOT NULL,
                    cloud_hash    TEXT,
                    status        TEXT NOT NULL DEFAULT 'PENDING',
                    last_sync_time TEXT,
                    fail_reason   TEXT,
                    dir_created   INTEGER NOT NULL DEFAULT 0,
                    original_name TEXT
                )
            """.trimIndent())
            snapshot.execSQL("CREATE INDEX idx_cloud_status ON cloud_entries(status)")
            // sync_meta 随快照一并导出，使云端能读到本快照的变更时戳（主从判定的依据）。
            snapshot.execSQL("CREATE TABLE sync_meta (key TEXT PRIMARY KEY, value TEXT)")
            val entries = getAllEntries("cloud_entries")
            snapshot.beginTransaction()
            try {
                for (entry in entries) {
                    snapshot.insertWithOnConflict("cloud_entries", null, rowToValues(entry, TABLE_CLOUD), SQLiteDatabase.CONFLICT_REPLACE)
                }
                getCloudDbTimestamp()?.let { ts ->
                    snapshot.insertWithOnConflict(
                        "sync_meta", null,
                        ContentValues().apply { put("key", KEY_CLOUD_DB_UPDATED_AT); put("value", ts) },
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
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
                db.insertWithOnConflict(table, null, rowToValues(entry, table), SQLiteDatabase.CONFLICT_REPLACE)
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

            val now = java.time.Instant.now().toString()
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
                arrayOf(localCount, localSize, localCount, now)
            )
            // cloud_entries 已清空：刷新快照时戳
            db.insertWithOnConflict(
                TABLE_META, null,
                ContentValues().apply { put("key", KEY_CLOUD_DB_UPDATED_AT); put("value", now) },
                SQLiteDatabase.CONFLICT_REPLACE
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
            updated.contentHash?.let { put("content_hash", it) }
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
        val dirIdx = cursor.getColumnIndex("dir_created")
        val origIdx = cursor.getColumnIndex("original_name")
        return SyncEntryRow(
            path = cursor.getString(cursor.getColumnIndexOrThrow("path")),
            size = cursor.getLong(cursor.getColumnIndexOrThrow("size")),
            uploadedSize = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L,
            lastModified = cursor.getString(cursor.getColumnIndexOrThrow("last_modified")),
            contentHash = cursor.getString(cursor.getColumnIndexOrThrow("content_hash")),
            cloudHash = cursor.getString(cursor.getColumnIndexOrThrow("cloud_hash")),
            status = SyncStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
            lastSyncTime = cursor.getString(cursor.getColumnIndexOrThrow("last_sync_time")),
            failReason = cursor.getString(cursor.getColumnIndexOrThrow("fail_reason")),
            dirCreated = if (dirIdx >= 0) cursor.getInt(dirIdx) != 0 else false,
            originalName = if (origIdx >= 0) cursor.getString(origIdx) else null
        )
    }

    /**
     * 序列化条目为 ContentValues。
     *
     * `dir_created` 仅存在于 cloud_entries（目录是否已在云端创建），
     * 写入 local_entries 时必须剔除，否则触发 "no column named dir_created"。
     */
    private fun rowToValues(entry: SyncEntryRow, table: String = TABLE_CLOUD): ContentValues {
        return ContentValues().apply {
            put("path", entry.path)
            put("size", entry.size)
            put("uploaded_size", entry.uploadedSize)
            put("last_modified", entry.lastModified)
            put("content_hash", entry.contentHash)
            put("cloud_hash", entry.cloudHash)
            put("status", entry.status.name)
            put("last_sync_time", entry.lastSyncTime)
            put("fail_reason", entry.failReason)
            if (table == TABLE_CLOUD) put("dir_created", if (entry.dirCreated) 1 else 0)
            put("original_name", entry.originalName)
        }
    }

    // ── 元数据（sync_meta）──

    private fun getMeta(key: String): String? {
        val db = readableDatabase
        val cursor = db.query(TABLE_META, arrayOf("value"), "key = ?", arrayOf(key), null, null, null)
        return cursor.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun setMeta(key: String, value: String) {
        val db = writableDatabase
        db.insertWithOnConflict(
            TABLE_META, null,
            ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** 读取本地 cloud_entries 快照的最后变更时戳（UTC ISO8601），无记录返回 null。 */
    fun getCloudDbTimestamp(): String? = getMeta(KEY_CLOUD_DB_UPDATED_AT)

    /** 写入本地 cloud_entries 快照的最后变更时戳（UTC ISO8601）。 */
    fun setCloudDbTimestamp(iso: String) = setMeta(KEY_CLOUD_DB_UPDATED_AT, iso)

    /** 以当前时刻刷新 cloud_entries 快照时戳。 */
    fun touchCloudDbTimestamp() = setCloudDbTimestamp(java.time.Instant.now().toString())

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
    val contentHash: String?,    // 明文内容指纹；本地表在加密导入时写入，云端表在上传成功后从本地复制
    val cloudHash: String?,      // 云端返回的内部编码（唯一性）
    val status: SyncStatus,
    val lastSyncTime: String?,   // ISO8601
    val failReason: String?,
    val dirCreated: Boolean = false,  // 仅 cloud_entries 的目录条目使用：该目录是否已在云端创建
    /** 磁盘密文名对应的原始文件名；文件名为 AES-ECB 加密名时的权威还原来源。目录条目为 null。 */
    val originalName: String? = null
)
