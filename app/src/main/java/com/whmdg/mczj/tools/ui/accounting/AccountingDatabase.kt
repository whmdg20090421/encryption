package com.whmdg.mczj.tools.ui.accounting

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.whmdg.mczj.tools.AppDataPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 记账模块统一数据库（参考 BeeCount Drift 架构，适配 Kotlin SQLiteOpenHelper）。
 * 三张表：settings（键值设置）、categories（分类）、records（记账记录）。
 * 一个 .db 文件 = 完整备份（除附件外）。
 */
internal class AccountingDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, dbPath(context), null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "accounting.db"
        private const val DB_VERSION = 9
        private const val TAG = "AccountingDatabase"

        @Volatile
        private var INSTANCE: AccountingDatabase? = null

        internal fun getInstance(context: Context): AccountingDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AccountingDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        /** DB 文件存储在 AppDataPaths.accounting() 目录下 */
        private fun dbPath(context: Context): String {
            val dir = AppDataPaths.accounting(context)
            if (!dir.exists()) dir.mkdirs()
            // 清理默认 databases/ 目录下的旧 DB 文件（迁移前的残留）
            val legacyDb = context.getDatabasePath(DB_NAME)
            if (legacyDb.exists()) legacyDb.delete()
            val legacyJournal = File(legacyDb.parent, "$DB_NAME-journal")
            if (legacyJournal.exists()) legacyJournal.delete()
            val legacyWal = File(legacyDb.parent, "$DB_NAME-wal")
            if (legacyWal.exists()) legacyWal.delete()
            val legacyShm = File(legacyDb.parent, "$DB_NAME-shm")
            if (legacyShm.exists()) legacyShm.delete()
            return File(dir, DB_NAME).absolutePath
        }
    }

    // ─────────────────────────────────────────────
    // 建表 & 升级
    // ─────────────────────────────────────────────

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE settings (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE categories (
                id         TEXT PRIMARY KEY,
                name       TEXT NOT NULL,
                icon       TEXT NOT NULL,
                page       TEXT NOT NULL,
                type       TEXT NOT NULL,
                parent_id  TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_cat_page_type ON categories(page, type)")
        db.execSQL("CREATE INDEX idx_cat_parent ON categories(parent_id)")

        db.execSQL("""
            CREATE TABLE records (
                id               TEXT PRIMARY KEY,
                book_name        TEXT NOT NULL,
                type             TEXT NOT NULL,
                amount           TEXT NOT NULL,
                category_id      TEXT NOT NULL,
                subcategory_id   TEXT,
                note             TEXT DEFAULT '',
                happened_at      INTEGER NOT NULL,
                account_id       TEXT,
                discount_before         TEXT,
                reimbursement_account_id TEXT,
                attachments TEXT,
                exclude_from_stats INTEGER DEFAULT 0,
                exclude_from_budget INTEGER DEFAULT 0,
                reimburse_status INTEGER DEFAULT 0,
                reimburse_amount REAL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_rec_book ON records(book_name)")
        db.execSQL("CREATE INDEX idx_rec_time ON records(happened_at)")

        db.execSQL("""
            CREATE TABLE accounts (
                id             TEXT PRIMARY KEY,
                name           TEXT NOT NULL,
                type           TEXT NOT NULL,
                category       TEXT NOT NULL DEFAULT 'tradable',
                initial_amount REAL NOT NULL DEFAULT 0,
                note           TEXT DEFAULT '',
                created_at     INTEGER NOT NULL,
                updated_at     INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_acc_category ON accounts(category)")

        db.execSQL("""
            CREATE TABLE attachment_trash (
                id                    TEXT PRIMARY KEY,
                attachment_json       TEXT NOT NULL,
                original_record_id    TEXT NOT NULL,
                original_record_status TEXT NOT NULL,
                deleted_at            INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_trash_deleted_at ON attachment_trash(deleted_at)")

        // 写入默认数据
        insertDefaultCategoriesToDb(db)
        insertDefaultSettingsToDb(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("""
                CREATE TABLE accounts (
                    id             TEXT PRIMARY KEY,
                    name           TEXT NOT NULL,
                    type           TEXT NOT NULL,
                    category       TEXT NOT NULL DEFAULT 'tradable',
                    initial_amount REAL NOT NULL DEFAULT 0,
                    note           TEXT DEFAULT '',
                    created_at     INTEGER NOT NULL,
                    updated_at     INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX idx_acc_category ON accounts(category)")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE records ADD COLUMN account_id TEXT")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE records ADD COLUMN discount_before TEXT")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE records ADD COLUMN reimbursement_account_id TEXT")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE records ADD COLUMN attachments TEXT")
        }
        if (oldVersion < 7) {
            db.execSQL("""
                CREATE TABLE attachment_trash (
                    id                    TEXT PRIMARY KEY,
                    attachment_json       TEXT NOT NULL,
                    original_record_id    TEXT NOT NULL,
                    original_record_status TEXT NOT NULL,
                    deleted_at            INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX idx_trash_deleted_at ON attachment_trash(deleted_at)")
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE records ADD COLUMN exclude_from_stats INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE records ADD COLUMN exclude_from_budget INTEGER DEFAULT 0")
        }
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE records ADD COLUMN reimburse_status INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE records ADD COLUMN reimburse_amount REAL DEFAULT 0")
        }
    }

    // ─────────────────────────────────────────────
    // 设置表
    // ─────────────────────────────────────────────

    fun getSetting(key: String): String? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT value FROM settings WHERE key = ?", arrayOf(key))
        return try {
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } finally {
            cursor.close()
        }
    }

    fun setSetting(key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insertDefaultSettingsToDb(db: SQLiteDatabase) {
        val defaults = mapOf(
            "category_icon_color" to "#5C6BC0",
            "categories_version" to "2"
        )
        for ((k, v) in defaults) {
            val cv = ContentValues().apply { put("key", k); put("value", v) }
            db.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    // ─────────────────────────────────────────────
    // 分类表
    // ─────────────────────────────────────────────

    /** 公共入口：写入默认分类 */
    fun insertDefaultCategories() {
        insertDefaultCategoriesToDb(writableDatabase)
    }

    private fun insertDefaultCategoriesToDb(db: SQLiteDatabase) {
        val categoryDb = AccountingCategoryDb.defaultCategories()
        db.beginTransaction()
        try {
            var order = 0
            for ((page, typeMap) in categoryDb.pages) {
                for ((type, cats) in typeMap) {
                    for (cat in cats) {
                        // 一级分类
                        val cv = ContentValues().apply {
                            put("id", cat.id)
                            put("name", cat.name)
                            put("icon", cat.icon)
                            put("page", page)
                            put("type", type)
                            putNull("parent_id")
                            put("sort_order", order++)
                        }
                        db.insertWithOnConflict("categories", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                        // 二级分类
                        for ((childOrder, child) in cat.children.withIndex()) {
                            val childCv = ContentValues().apply {
                                put("id", child.id)
                                put("name", child.name)
                                put("icon", child.icon)
                                put("page", page)
                                put("type", type)
                                put("parent_id", cat.id)
                                put("sort_order", childOrder)
                            }
                            db.insertWithOnConflict("categories", null, childCv, SQLiteDatabase.CONFLICT_REPLACE)
                        }
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 从 SQLite 读取分类并组装为树形结构（一级分类 → children 列表）。
     * 参考 BeeCount LEFT JOIN 模式，这里用两条查询 + 内存组装。
     */
    fun getCategories(page: String, type: String): List<AccountingCategory> {
        val db = readableDatabase
        // 查一级分类
        val parents = mutableListOf<AccountingCategory>()
        val parentCursor = db.rawQuery(
            "SELECT id, name, icon FROM categories WHERE page = ? AND type = ? AND parent_id IS NULL ORDER BY sort_order",
            arrayOf(page, type)
        )
        try {
            while (parentCursor.moveToNext()) {
                val id = parentCursor.getString(0)
                val name = parentCursor.getString(1)
                val icon = parentCursor.getString(2)
                // 查二级分类
                val children = mutableListOf<AccountingCategory>()
                val childCursor = db.rawQuery(
                    "SELECT id, name, icon FROM categories WHERE parent_id = ? ORDER BY sort_order",
                    arrayOf(id)
                )
                try {
                    while (childCursor.moveToNext()) {
                        children.add(AccountingCategory(
                            id = childCursor.getString(0),
                            name = childCursor.getString(1),
                            icon = childCursor.getString(2)
                        ))
                    }
                } finally {
                    childCursor.close()
                }
                parents.add(AccountingCategory(id = id, name = name, icon = icon, children = children))
            }
        } finally {
            parentCursor.close()
        }
        return parents
    }

    // ─────────────────────────────────────────────
    // 分类 CRUD（供导入等外部流程调用）
    // ─────────────────────────────────────────────

    /** 获取所有分类（扁平列表，含一级和二级） */
    fun getAllCategoriesFlat(): List<Triple<String, String, String?>> {
        val result = mutableListOf<Triple<String, String, String?>>()
        val cursor = readableDatabase.rawQuery("SELECT id, name, parent_id FROM categories", null)
        try {
            while (cursor.moveToNext()) {
                result.add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
            }
        } finally {
            cursor.close()
        }
        return result
    }

    /** 创建一级分类，返回新分类 ID */
    fun createParentCategory(name: String, type: String = "支出", icon: String = "category"): String {
        val id = "auto_${name.hashCode().toString(16)}"
        val cv = ContentValues().apply {
            put("id", id)
            put("name", name)
            put("icon", icon)
            put("page", "记账页")
            put("type", type)
            putNull("parent_id")
            put("sort_order", 0)
        }
        writableDatabase.insertWithOnConflict("categories", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        return id
    }

    /** 创建二级分类，返回新分类 ID */
    fun createChildCategory(name: String, parentId: String, type: String = "支出", icon: String = "subcategory"): String {
        val id = "auto_${name.hashCode().toString(16)}"
        val cv = ContentValues().apply {
            put("id", id)
            put("name", name)
            put("icon", icon)
            put("page", "记账页")
            put("type", type)
            put("parent_id", parentId)
            put("sort_order", 0)
        }
        writableDatabase.insertWithOnConflict("categories", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        return id
    }

    /** 更新一级分类名称 */
    fun updateParentCategory(id: String, newName: String) {
        val cv = ContentValues().apply { put("name", newName) }
        writableDatabase.update("categories", cv, "id = ?", arrayOf(id))
    }

    /** 更新二级分类名称 */
    fun updateChildCategory(id: String, newName: String) {
        val cv = ContentValues().apply { put("name", newName) }
        writableDatabase.update("categories", cv, "id = ?", arrayOf(id))
    }

    /** 删除一级分类（同时删除其下所有二级分类） */
    fun deleteParentCategory(id: String) {
        writableDatabase.delete("categories", "parent_id = ?", arrayOf(id))
        writableDatabase.delete("categories", "id = ?", arrayOf(id))
    }

    /** 删除二级分类 */
    fun deleteChildCategory(id: String) {
        writableDatabase.delete("categories", "id = ?", arrayOf(id))
    }

    fun getCategoryVersion(): Int {
        return getSetting("categories_version")?.toIntOrNull() ?: 0
    }

    fun setCategoryVersion(v: Int) {
        setSetting("categories_version", v.toString())
    }

    // ─────────────────────────────────────────────
    // 记录表（参考 BeeCount LocalTransactionRepository 模式）
    // ─────────────────────────────────────────────

    fun insertRecord(record: AccountingRecord) {
        val cv = recordToContentValues(record)
        writableDatabase.insertWithOnConflict("records", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateRecord(record: AccountingRecord) {
        val cv = recordToContentValues(record)
        writableDatabase.update("records", cv, "id = ?", arrayOf(record.id))
    }

    fun deleteRecord(id: String) {
        writableDatabase.delete("records", "id = ?", arrayOf(id))
    }

    fun getAllRecords(): List<AccountingRecord> {
        val records = mutableListOf<AccountingRecord>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, book_name, type, amount, category_id, subcategory_id, note, happened_at, account_id, discount_before, reimbursement_account_id, attachments, exclude_from_stats, exclude_from_budget, reimburse_status, reimburse_amount FROM records ORDER BY happened_at DESC",
            null
        )
        try {
            while (cursor.moveToNext()) {
                records.add(cursorToRecord(cursor))
            }
        } finally {
            cursor.close()
        }
        return records
    }

    fun getRecordsByBook(bookName: String): List<AccountingRecord> {
        val records = mutableListOf<AccountingRecord>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, book_name, type, amount, category_id, subcategory_id, note, happened_at, account_id, discount_before, reimbursement_account_id, attachments, exclude_from_stats, exclude_from_budget, reimburse_status, reimburse_amount FROM records WHERE book_name = ? ORDER BY happened_at DESC",
            arrayOf(bookName)
        )
        try {
            while (cursor.moveToNext()) {
                records.add(cursorToRecord(cursor))
            }
        } finally {
            cursor.close()
        }
        return records
    }

    private fun recordToContentValues(r: AccountingRecord): ContentValues {
        return ContentValues().apply {
            put("id", r.id)
            put("book_name", r.bookName)
            put("type", r.type)
            put("amount", r.amount)
            put("category_id", r.categoryId)
            if (r.subcategoryId != null) put("subcategory_id", r.subcategoryId) else putNull("subcategory_id")
            put("note", r.note)
            put("happened_at", r.happenedAt)
            if (r.accountId != null) put("account_id", r.accountId) else putNull("account_id")
            if (r.discountBefore != null) put("discount_before", r.discountBefore) else putNull("discount_before")
            if (r.reimbursementAccountId != null) put("reimbursement_account_id", r.reimbursementAccountId) else putNull("reimbursement_account_id")
            if (r.attachments.isNotEmpty()) {
                put("attachments", Json.encodeToString(r.attachments))
            } else {
                putNull("attachments")
            }
            put("exclude_from_stats", if (r.excludeFromStats) 1 else 0)
            put("exclude_from_budget", if (r.excludeFromBudget) 1 else 0)
            put("reimburse_status", if (r.reimburseStatus) 1 else 0)
            put("reimburse_amount", r.reimburseAmount)
        }
    }

    private fun cursorToRecord(c: android.database.Cursor): AccountingRecord {
        val attachmentsJson = c.getString(11)
        val attachments = if (!attachmentsJson.isNullOrEmpty()) {
            try { Json.decodeFromString<List<AttachmentInfo>>(attachmentsJson) } catch (_: Exception) { emptyList() }
        } else emptyList()
        return AccountingRecord(
            id = c.getString(0),
            bookName = c.getString(1),
            type = c.getString(2),
            amount = c.getString(3),
            categoryId = c.getString(4),
            subcategoryId = c.getString(5),  // 可能为 null
            note = c.getString(6) ?: "",
            happenedAt = c.getLong(7),
            accountId = c.getString(8),  // 可能为 null
            discountBefore = c.getString(9),  // 可能为 null
            reimbursementAccountId = c.getString(10),  // 可能为 null
            attachments = attachments,
            excludeFromStats = c.getInt(12) == 1,
            excludeFromBudget = c.getInt(13) == 1,
            reimburseStatus = c.getInt(14) == 1,
            reimburseAmount = c.getDouble(15)
        )
    }

    // ─────────────────────────────────────────────
    // 报销账户查询
    // ─────────────────────────────────────────────

    fun getRecordsByReimbursementAccount(reimbAccountId: String): List<AccountingRecord> {
        val records = mutableListOf<AccountingRecord>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, book_name, type, amount, category_id, subcategory_id, note, happened_at, account_id, discount_before, reimbursement_account_id, attachments, exclude_from_stats, exclude_from_budget, reimburse_status, reimburse_amount FROM records WHERE reimbursement_account_id = ? ORDER BY happened_at DESC",
            arrayOf(reimbAccountId)
        )
        try {
            while (cursor.moveToNext()) {
                records.add(cursorToRecord(cursor))
            }
        } finally {
            cursor.close()
        }
        return records
    }

    // ─────────────────────────────────────────────
    // 账户表
    // ─────────────────────────────────────────────

    fun insertAccount(account: AccountingAccount) {
        val cv = accountToContentValues(account)
        writableDatabase.insertWithOnConflict("accounts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateAccount(account: AccountingAccount) {
        val cv = accountToContentValues(account)
        writableDatabase.update("accounts", cv, "id = ?", arrayOf(account.id))
    }

    fun deleteAccount(id: String) {
        writableDatabase.delete("accounts", "id = ?", arrayOf(id))
    }

    fun getAllAccounts(): List<AccountingAccount> {
        val accounts = mutableListOf<AccountingAccount>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, name, type, category, initial_amount, note, created_at, updated_at FROM accounts ORDER BY created_at DESC",
            null
        )
        try {
            while (cursor.moveToNext()) {
                accounts.add(cursorToAccount(cursor))
            }
        } finally {
            cursor.close()
        }
        return accounts
    }

    fun getAccountsByCategory(category: String): List<AccountingAccount> {
        val accounts = mutableListOf<AccountingAccount>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, name, type, category, initial_amount, note, created_at, updated_at FROM accounts WHERE category = ? ORDER BY created_at DESC",
            arrayOf(category)
        )
        try {
            while (cursor.moveToNext()) {
                accounts.add(cursorToAccount(cursor))
            }
        } finally {
            cursor.close()
        }
        return accounts
    }

    private fun accountToContentValues(a: AccountingAccount): ContentValues {
        return ContentValues().apply {
            put("id", a.id)
            put("name", a.name)
            put("type", a.type)
            put("category", a.category)
            put("initial_amount", a.initialAmount)
            put("note", a.note)
            put("created_at", a.createdAt)
            put("updated_at", a.updatedAt)
        }
    }

    private fun cursorToAccount(c: android.database.Cursor): AccountingAccount {
        return AccountingAccount(
            id = c.getString(0),
            name = c.getString(1),
            type = c.getString(2),
            category = c.getString(3),
            initialAmount = c.getDouble(4),
            note = c.getString(5) ?: "",
            createdAt = c.getLong(6),
            updatedAt = c.getLong(7)
        )
    }

    // ─────────────────────────────────────────────
    // 附件回收站表
    // ─────────────────────────────────────────────

    fun insertTrashEntry(entry: AttachmentTrashEntry) {
        val cv = ContentValues().apply {
            put("id", entry.id)
            put("attachment_json", Json.encodeToString(entry.attachment))
            put("original_record_id", entry.originalRecordId)
            put("original_record_status", entry.originalRecordStatus)
            put("deleted_at", entry.deletedAt)
        }
        writableDatabase.insertWithOnConflict("attachment_trash", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllTrashEntries(): List<AttachmentTrashEntry> {
        val entries = mutableListOf<AttachmentTrashEntry>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, attachment_json, original_record_id, original_record_status, deleted_at FROM attachment_trash ORDER BY deleted_at DESC",
            null
        )
        try {
            while (cursor.moveToNext()) {
                entries.add(cursorToTrashEntry(cursor))
            }
        } finally {
            cursor.close()
        }
        return entries
    }

    fun getTrashEntriesByRecord(recordId: String): List<AttachmentTrashEntry> {
        val entries = mutableListOf<AttachmentTrashEntry>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, attachment_json, original_record_id, original_record_status, deleted_at FROM attachment_trash WHERE original_record_id = ? ORDER BY deleted_at DESC",
            arrayOf(recordId)
        )
        try {
            while (cursor.moveToNext()) {
                entries.add(cursorToTrashEntry(cursor))
            }
        } finally {
            cursor.close()
        }
        return entries
    }

    fun deleteTrashEntry(id: String) {
        writableDatabase.delete("attachment_trash", "id = ?", arrayOf(id))
    }

    fun deleteTrashEntriesByRecord(recordId: String) {
        writableDatabase.delete("attachment_trash", "original_record_id = ?", arrayOf(recordId))
    }

    fun updateTrashEntryRecordStatus(recordId: String, newStatus: String) {
        val cv = ContentValues().apply {
            put("original_record_status", newStatus)
        }
        writableDatabase.update("attachment_trash", cv, "original_record_id = ?", arrayOf(recordId))
    }

    private fun cursorToTrashEntry(c: android.database.Cursor): AttachmentTrashEntry {
        val attachmentJson = c.getString(1)
        val attachment = Json.decodeFromString<AttachmentInfo>(attachmentJson)
        return AttachmentTrashEntry(
            id = c.getString(0),
            attachment = attachment,
            originalRecordId = c.getString(2),
            originalRecordStatus = c.getString(3),
            deletedAt = c.getLong(4)
        )
    }

    // ─────────────────────────────────────────────
    // 数据迁移（JSON + SharedPreferences → SQLite）
    // ─────────────────────────────────────────────

    fun migrateFromLegacy(context: Context) {
        // 检查是否已迁移
        if (getSetting("migrated") == "true") return

        val db = writableDatabase
        db.beginTransaction()
        try {
            // 1. 迁移记录（accounting_records.json → records 表）
            val dir = AppDataPaths.accounting(context)
            val recordsFile = File(dir, "accounting_records.json")
            if (recordsFile.exists()) {
                try {
                    val json = Json { ignoreUnknownKeys = true }
                    val recordDb = json.decodeFromString<AccountingRecordDb>(recordsFile.readText())
                    for (record in recordDb.records) {
                        val cv = recordToContentValues(record)
                        db.insertWithOnConflict("records", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                    Log.i(TAG, "迁移了 ${recordDb.records.size} 条记录")
                } catch (e: Exception) {
                    Log.e(TAG, "迁移记录失败", e)
                }
            }

            // 2. 迁移设置（SharedPreferences → settings 表）
            val prefs = context.getSharedPreferences(AppDataPaths.PREFS_ACCOUNTING, Context.MODE_PRIVATE)
            val iconColor = prefs.getString(AppDataPaths.PREF_KEY_ICON_COLOR, null)
            if (iconColor != null && getSetting("category_icon_color") == null) {
                setSetting("category_icon_color", iconColor)
                Log.i(TAG, "迁移了图标颜色: $iconColor")
            }

            // 3. 标记迁移完成
            setSetting("migrated", "true")
            db.setTransactionSuccessful()
            Log.i(TAG, "数据迁移完成")
        } finally {
            db.endTransaction()
        }

        // 4. 删除旧 JSON 文件和 SharedPreferences 数据
        val dir = AppDataPaths.accounting(context)
        for (name in listOf(
            "accounting_records.json", "accounting_records.backup.json",
            "accounting_categories.json", "accounting_categories.backup.json"
        )) {
            val f = File(dir, name)
            if (f.exists()) {
                f.delete()
                Log.i(TAG, "已删除旧文件: $name")
            }
        }
        context.getSharedPreferences(AppDataPaths.PREFS_ACCOUNTING, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    // ─────────────────────────────────────────────
    // 数据导出
    // ─────────────────────────────────────────────

    fun exportToJson(): String {
        val db = readableDatabase

        // settings
        val settings = mutableListOf<ExportSetting>()
        val settingsCursor = db.rawQuery("SELECT key, value FROM settings", null)
        try {
            while (settingsCursor.moveToNext()) {
                settings.add(ExportSetting(
                    key = settingsCursor.getString(0),
                    value = settingsCursor.getString(1)
                ))
            }
        } finally {
            settingsCursor.close()
        }

        // categories
        val categories = mutableListOf<ExportCategory>()
        val catCursor = db.rawQuery(
            "SELECT id, name, icon, page, type, parent_id, sort_order FROM categories", null
        )
        try {
            while (catCursor.moveToNext()) {
                categories.add(ExportCategory(
                    id = catCursor.getString(0),
                    name = catCursor.getString(1),
                    icon = catCursor.getString(2),
                    page = catCursor.getString(3),
                    type = catCursor.getString(4),
                    parentId = catCursor.getString(5),
                    sortOrder = catCursor.getInt(6)
                ))
            }
        } finally {
            catCursor.close()
        }

        // records
        val records = mutableListOf<ExportRecord>()
        val recCursor = db.rawQuery(
            "SELECT id, book_name, type, amount, category_id, subcategory_id, note, happened_at, account_id, discount_before, reimbursement_account_id, attachments, exclude_from_stats, exclude_from_budget, reimburse_status, reimburse_amount FROM records",
            null
        )
        try {
            while (recCursor.moveToNext()) {
                records.add(ExportRecord(
                    id = recCursor.getString(0),
                    bookName = recCursor.getString(1),
                    type = recCursor.getString(2),
                    amount = recCursor.getString(3),
                    categoryId = recCursor.getString(4),
                    subcategoryId = recCursor.getString(5),
                    note = recCursor.getString(6) ?: "",
                    happenedAt = recCursor.getLong(7),
                    accountId = recCursor.getString(8),
                    discountBefore = recCursor.getString(9),
                    reimbursementAccountId = recCursor.getString(10),
                    reimburseStatus = recCursor.getInt(14) == 1,
                    reimburseAmount = recCursor.getDouble(15)
                ))
            }
        } finally {
            recCursor.close()
        }

        // accounts
        val accounts = mutableListOf<ExportAccount>()
        val accCursor = db.rawQuery(
            "SELECT id, name, type, category, initial_amount, note, created_at, updated_at FROM accounts", null
        )
        try {
            while (accCursor.moveToNext()) {
                accounts.add(ExportAccount(
                    id = accCursor.getString(0),
                    name = accCursor.getString(1),
                    type = accCursor.getString(2),
                    category = accCursor.getString(3),
                    initialAmount = accCursor.getDouble(4),
                    note = accCursor.getString(5) ?: "",
                    createdAt = accCursor.getLong(6),
                    updatedAt = accCursor.getLong(7)
                ))
            }
        } finally {
            accCursor.close()
        }

        val exportData = ExportData(
            settings = settings,
            categories = categories,
            records = records,
            accounts = accounts
        )

        val json = Json { prettyPrint = true; encodeDefaults = true }
        return json.encodeToString(ExportData.serializer(), exportData)
    }

    // ─────────────────────────────────────────────
    // CSV 导出
    // ─────────────────────────────────────────────

    fun exportToCsv(): String {
        val db = readableDatabase

        // 构建 id→name 映射
        val catMap = mutableMapOf<String, String>()
        val catCursor = db.rawQuery("SELECT id, name FROM categories", null)
        try { while (catCursor.moveToNext()) catMap[catCursor.getString(0)] = catCursor.getString(1) }
        finally { catCursor.close() }

        val accMap = mutableMapOf<String, String>()
        val accCursor = db.rawQuery("SELECT id, name FROM accounts", null)
        try { while (accCursor.moveToNext()) accMap[accCursor.getString(0)] = accCursor.getString(1) }
        finally { accCursor.close() }

        val reimbMap = mutableMapOf<String, String>()
        // 报销账户名存在 settings 表的 JSON 中，从 ReimbursementAccountEntity 解析
        try {
            val json = getSetting("reimbursement_accounts")
            if (json != null) {
                val entities = kotlinx.serialization.json.Json.decodeFromString<List<ReimbursementAccountEntity>>(json)
                entities.forEach { reimbMap[it.id] = it.name }
            }
        } catch (_: Exception) {}

        val sb = StringBuilder()
        sb.appendLine("﻿类型,分类,二级分类,金额,账本,账户,备注,时间,优惠前金额,报销账户")

        val recCursor = db.rawQuery(
            "SELECT type, amount, category_id, subcategory_id, book_name, account_id, note, happened_at, discount_before, reimbursement_account_id, attachments, exclude_from_stats, exclude_from_budget, reimburse_status, reimburse_amount FROM records ORDER BY happened_at ASC",
            null
        )
        try {
            while (recCursor.moveToNext()) {
                val type = recCursor.getString(0) ?: ""
                val amount = recCursor.getString(1) ?: ""
                val catName = catMap[recCursor.getString(2)] ?: recCursor.getString(2) ?: ""
                val subCatName = catMap[recCursor.getString(3)] ?: recCursor.getString(3) ?: ""
                val book = recCursor.getString(4) ?: ""
                val accName = accMap[recCursor.getString(5)] ?: recCursor.getString(5) ?: ""
                val note = recCursor.getString(6) ?: ""
                val ts = recCursor.getLong(7)
                val discountBefore = recCursor.getString(8) ?: ""
                val reimbName = reimbMap[recCursor.getString(9)] ?: recCursor.getString(9) ?: ""

                val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(ts))

                sb.appendLine("${csvEscape(type)},${csvEscape(catName)},${csvEscape(subCatName)},${csvEscape(amount)},${csvEscape(book)},${csvEscape(accName)},${csvEscape(note)},${csvEscape(dateStr)},${csvEscape(discountBefore)},${csvEscape(reimbName)}")
            }
        } finally {
            recCursor.close()
        }
        return sb.toString()
    }

    private fun csvEscape(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    // ─────────────────────────────────────────────
    // CSV 导入
    // ─────────────────────────────────────────────

    fun validateImportCsv(csvString: String) {
        try {
            val lines = csvString.lines().filter { it.isNotBlank() }
            if (lines.size < 2) throw IllegalArgumentException("该CSV文件数据格式不正确或已损坏。")
            val header = parseCsvLine(lines[0])
            if (header.isEmpty()) throw IllegalArgumentException("该CSV文件数据格式不正确或已损坏。")
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            throw IllegalArgumentException("该CSV文件数据格式不正确或已损坏。")
        }
    }

    fun importFromCsv(csvString: String, appendMode: Boolean = false) {
        val lines = csvString.lines().filter { it.isNotBlank() }
        if (lines.size < 2) throw IllegalArgumentException("该CSV文件数据格式不正确或已损坏。")

        val header = parseCsvLine(lines[0]).map { it.trim() }
        val typeIdx = header.indexOf("类型")
        val catIdx = header.indexOf("分类")
        val subCatIdx = header.indexOf("二级分类")
        val amountIdx = header.indexOf("金额")
        val bookIdx = header.indexOf("账本")
        val accIdx = header.indexOf("账户")
        val noteIdx = header.indexOf("备注")
        val timeIdx = header.indexOf("时间")
        val discountIdx = header.indexOf("优惠前金额")
        val reimbIdx = header.indexOf("报销账户")

        if (typeIdx < 0 || amountIdx < 0 || timeIdx < 0) {
            throw IllegalArgumentException("该CSV文件数据格式不正确或已损坏。")
        }

        // 构建 name→id 映射
        val db = writableDatabase
        val catNameToId = mutableMapOf<String, String>()
        val catCursor = db.rawQuery("SELECT id, name FROM categories", null)
        try { while (catCursor.moveToNext()) catNameToId[catCursor.getString(1)] = catCursor.getString(0) }
        finally { catCursor.close() }

        val accNameToId = mutableMapOf<String, String>()
        val accCursor = db.rawQuery("SELECT id, name FROM accounts", null)
        try { while (accCursor.moveToNext()) accNameToId[accCursor.getString(1)] = accCursor.getString(0) }
        finally { accCursor.close() }

        val reimbNameToId = mutableMapOf<String, String>()
        try {
            val json = getSetting("reimbursement_accounts")
            if (json != null) {
                val entities = kotlinx.serialization.json.Json.decodeFromString<List<ReimbursementAccountEntity>>(json)
                entities.forEach { reimbNameToId[it.name] = it.id }
            }
        } catch (_: Exception) {}

        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())

        db.beginTransaction()
        try {
            if (!appendMode) db.delete("records", null, null)

            for (i in 1 until lines.size) {
                val cols = parseCsvLine(lines[i])
                if (cols.size < 3) continue

                val type = cols.getOrNull(typeIdx)?.trim() ?: continue
                val amount = cols.getOrNull(amountIdx)?.trim() ?: continue
                val timeStr = cols.getOrNull(timeIdx)?.trim() ?: continue

                val happenedAt = try { dateFormat.parse(timeStr)?.time ?: 0L } catch (_: Exception) { 0L }
                val catName = cols.getOrNull(catIdx)?.trim() ?: ""
                val subCatName = cols.getOrNull(subCatIdx)?.trim() ?: ""
                val book = cols.getOrNull(bookIdx)?.trim() ?: "默认记账本"
                val accName = cols.getOrNull(accIdx)?.trim() ?: ""
                val note = cols.getOrNull(noteIdx)?.trim() ?: ""
                val discountBefore = cols.getOrNull(discountIdx)?.trim()?.ifEmpty { null }
                val reimbName = cols.getOrNull(reimbIdx)?.trim() ?: ""

                val cv = ContentValues().apply {
                    put("id", java.util.UUID.randomUUID().toString())
                    put("book_name", book)
                    put("type", type)
                    put("amount", amount)
                    put("category_id", catNameToId[catName] ?: catName)
                    val subId = catNameToId[subCatName] ?: subCatName
                    if (subId.isNotEmpty()) put("subcategory_id", subId) else putNull("subcategory_id")
                    put("note", note)
                    put("happened_at", happenedAt)
                    val aId = accNameToId[accName]
                    if (aId != null) put("account_id", aId) else if (accName.isNotEmpty()) put("account_id", accName) else putNull("account_id")
                    if (discountBefore != null) put("discount_before", discountBefore) else putNull("discount_before")
                    val rId = reimbNameToId[reimbName]
                    if (rId != null) put("reimbursement_account_id", rId) else if (reimbName.isNotEmpty()) put("reimbursement_account_id", reimbName) else putNull("reimbursement_account_id")
                }
                db.insertWithOnConflict("records", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            db.setTransactionSuccessful()
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            throw IllegalArgumentException("该CSV文件数据格式不正确或已损坏。")
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 从 CSV 导入记录（带列映射和分类映射）。
     * @param csvText         CSV 文本内容
     * @param columnMapping   fieldKey -> CSV 列索引映射
     * @param categoryMapping CSV分类名 -> 目标分类ID映射（null=保持原名）
     * @param replaceMode     true=替换全部旧记录，false=追加
     * @return 成功导入的记录数
     */
    fun importFromCsvWithMapping(
        csvText: String,
        columnMapping: Map<String, Int?>,
        categoryMapping: Map<String, String?>,
        replaceMode: Boolean
    ): Int {
        val lines = csvText.lines().filter { it.isNotBlank() }
        if (lines.size < 2) throw IllegalArgumentException("CSV 文件无数据行。")

        val db = writableDatabase
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        val dateFormatFull = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())

        // 构建账户名→ID 映射
        val accNameToId = mutableMapOf<String, String>()
        val accCursor = db.rawQuery("SELECT id, name FROM accounts", null)
        try { while (accCursor.moveToNext()) accNameToId[accCursor.getString(1)] = accCursor.getString(0) }
        finally { accCursor.close() }

        var imported = 0
        db.beginTransaction()
        try {
            if (replaceMode) db.delete("records", null, null)

            for (i in 1 until lines.size) {
                val cols = parseCsvLine(lines[i])
                if (cols.isEmpty()) continue

                fun col(key: String): String? {
                    val idx = columnMapping[key] ?: return null
                    return cols.getOrNull(idx)?.trim()?.ifEmpty { null }
                }

                val type = col("类型") ?: continue
                if (type != "支出" && type != "收入" && type != "转账" && type != "债务") continue
                val amountRaw = col("金额") ?: continue
                val amount = try {
                    kotlin.math.abs(amountRaw.replace("[¥$,，]".toRegex(), "").toDouble())
                } catch (_: Exception) { continue }
                val amountStr = if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()

                val timeStr = col("时间") ?: continue
                val happenedAt = try {
                    (dateFormat.parse(timeStr)?.time ?: dateFormatFull.parse(timeStr)?.time) ?: 0L
                } catch (_: Exception) { 0L }

                val catName = col("分类") ?: ""
                val subCatName = col("二级分类") ?: ""
                val book = col("账本") ?: "默认记账本"
                val accName = col("账户") ?: ""
                val note = col("备注") ?: ""
                val discountBefore = col("优惠前金额")
                val reimbName = col("报销账户") ?: ""

                // 分类映射
                val catId = if (catName.isNotEmpty()) categoryMapping[catName] ?: catName else ""
                val subCatId = if (subCatName.isNotEmpty()) categoryMapping[subCatName] ?: subCatName else null

                val cv = ContentValues().apply {
                    put("id", java.util.UUID.randomUUID().toString())
                    put("book_name", book)
                    put("type", type)
                    put("amount", amountStr)
                    put("category_id", catId)
                    if (subCatId != null) put("subcategory_id", subCatId) else putNull("subcategory_id")
                    put("note", note)
                    put("happened_at", happenedAt)
                    val aId = accNameToId[accName]
                    if (aId != null) put("account_id", aId) else if (accName.isNotEmpty()) put("account_id", accName) else putNull("account_id")
                    if (discountBefore != null) put("discount_before", discountBefore) else putNull("discount_before")
                    putNull("reimbursement_account_id")
                }
                db.insertWithOnConflict("records", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                imported++
            }
            db.setTransactionSuccessful()
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            throw IllegalArgumentException("导入过程中发生错误。")
        } finally {
            db.endTransaction()
        }
        return imported
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && !inQuotes -> inQuotes = true
                c == '"' && inQuotes -> {
                    if (i + 1 < line.length && line[i + 1] == '"') { current.append('"'); i++ }
                    else inQuotes = false
                }
                c == ',' && !inQuotes -> { result.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    fun validateImportData(jsonString: String) {
        try {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString(ExportData.serializer(), jsonString)
        } catch (_: Exception) {
            throw IllegalArgumentException("该JSON文件数据格式不正确或已损坏。")
        }
    }

    fun importFromJson(jsonString: String) {
        val data = try {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString(ExportData.serializer(), jsonString)
        } catch (_: Exception) {
            throw IllegalArgumentException("该JSON文件数据格式不正确或已损坏。")
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("records", null, null)
            db.delete("accounts", null, null)
            db.delete("categories", null, null)
            db.delete("settings", null, null)

            for (s in data.settings) {
                val cv = ContentValues().apply { put("key", s.key); put("value", s.value) }
                db.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (c in data.categories) {
                val cv = ContentValues().apply {
                    put("id", c.id); put("name", c.name); put("icon", c.icon)
                    put("page", c.page); put("type", c.type)
                    if (c.parentId != null) put("parent_id", c.parentId) else putNull("parent_id")
                    put("sort_order", c.sortOrder)
                }
                db.insertWithOnConflict("categories", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (r in data.records) {
                val cv = ContentValues().apply {
                    put("id", r.id); put("book_name", r.bookName); put("type", r.type)
                    put("amount", r.amount); put("category_id", r.categoryId)
                    if (r.subcategoryId != null) put("subcategory_id", r.subcategoryId) else putNull("subcategory_id")
                    put("note", r.note); put("happened_at", r.happenedAt)
                    if (r.accountId != null) put("account_id", r.accountId) else putNull("account_id")
                    if (r.discountBefore != null) put("discount_before", r.discountBefore) else putNull("discount_before")
                    if (r.reimbursementAccountId != null) put("reimbursement_account_id", r.reimbursementAccountId) else putNull("reimbursement_account_id")
                    put("reimburse_status", if (r.reimburseStatus) 1 else 0)
                    put("reimburse_amount", r.reimburseAmount)
                }
                db.insertWithOnConflict("records", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (a in data.accounts) {
                val cv = ContentValues().apply {
                    put("id", a.id); put("name", a.name); put("type", a.type)
                    put("category", a.category); put("initial_amount", a.initialAmount)
                    put("note", a.note); put("created_at", a.createdAt); put("updated_at", a.updatedAt)
                }
                db.insertWithOnConflict("accounts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            db.setTransactionSuccessful()
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            throw IllegalArgumentException("该JSON文件数据格式不正确或已损坏。")
        } finally {
            db.endTransaction()
        }
    }
}

@Serializable
private data class ExportSetting(val key: String, val value: String)

@Serializable
private data class ExportCategory(
    val id: String,
    val name: String,
    val icon: String,
    val page: String,
    val type: String,
    val parentId: String? = null,
    val sortOrder: Int = 0
)

@Serializable
private data class ExportRecord(
    val id: String,
    val bookName: String,
    val type: String,
    val amount: String,
    val categoryId: String,
    val subcategoryId: String? = null,
    val note: String = "",
    val happenedAt: Long,
    val accountId: String? = null,
    val discountBefore: String? = null,
    val reimbursementAccountId: String? = null,
    val reimburseStatus: Boolean = false,
    val reimburseAmount: Double = 0.0
)

@Serializable
private data class ExportAccount(
    val id: String,
    val name: String,
    val type: String,
    val category: String = "tradable",
    val initialAmount: Double = 0.0,
    val note: String = "",
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
private data class ExportData(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: List<ExportSetting> = emptyList(),
    val categories: List<ExportCategory> = emptyList(),
    val records: List<ExportRecord> = emptyList(),
    val accounts: List<ExportAccount> = emptyList()
)
