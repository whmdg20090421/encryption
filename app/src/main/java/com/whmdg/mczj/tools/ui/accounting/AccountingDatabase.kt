package com.whmdg.mczj.tools.ui.accounting

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.whmdg.mczj.tools.AppDataPaths
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 记账模块统一数据库（参考 BeeCount Drift 架构，适配 Kotlin SQLiteOpenHelper）。
 * 三张表：settings（键值设置）、categories（分类）、records（记账记录）。
 * 一个 .db 文件 = 完整备份（除附件外）。
 */
class AccountingDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "accounting.db"
        private const val DB_VERSION = 2
        private const val TAG = "AccountingDatabase"

        @Volatile
        private var INSTANCE: AccountingDatabase? = null

        fun getInstance(context: Context): AccountingDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AccountingDatabase(context.applicationContext).also { INSTANCE = it }
            }
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
                id             TEXT PRIMARY KEY,
                book_name      TEXT NOT NULL,
                type           TEXT NOT NULL,
                amount         TEXT NOT NULL,
                category_id    TEXT NOT NULL,
                subcategory_id TEXT,
                note           TEXT DEFAULT '',
                happened_at    INTEGER NOT NULL
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
            "SELECT id, book_name, type, amount, category_id, subcategory_id, note, happened_at FROM records ORDER BY happened_at DESC",
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
            "SELECT id, book_name, type, amount, category_id, subcategory_id, note, happened_at FROM records WHERE book_name = ? ORDER BY happened_at DESC",
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
        }
    }

    private fun cursorToRecord(c: android.database.Cursor): AccountingRecord {
        return AccountingRecord(
            id = c.getString(0),
            bookName = c.getString(1),
            type = c.getString(2),
            amount = c.getString(3),
            categoryId = c.getString(4),
            subcategoryId = c.getString(5),  // 可能为 null
            note = c.getString(6) ?: "",
            happenedAt = c.getLong(7)
        )
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
}
