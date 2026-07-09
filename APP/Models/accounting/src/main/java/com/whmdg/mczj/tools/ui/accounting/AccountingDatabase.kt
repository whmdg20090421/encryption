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
        private const val DB_VERSION = 24
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
                sort_order INTEGER NOT NULL DEFAULT 0,
                overlay    TEXT
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
                category_name    TEXT DEFAULT '',
                subcategory_name TEXT,
                note             TEXT DEFAULT '',
                happened_at      INTEGER NOT NULL,
                year             INTEGER NOT NULL DEFAULT 0,
                month            INTEGER NOT NULL DEFAULT 0,
                day              INTEGER NOT NULL DEFAULT 0,
                account_id       TEXT,
                target_account_id      TEXT,
                discount_before         TEXT,
                discount_off            TEXT,
                discount_after          TEXT,
                reimbursement_account_id TEXT,
                attachments TEXT,
                exclude_from_stats INTEGER DEFAULT 0,
                exclude_from_budget INTEGER DEFAULT 0,
                reimburse_status INTEGER DEFAULT 0,
                reimburse_amount REAL DEFAULT 0,
                reimburse_after_amount TEXT,
                refund_amount REAL DEFAULT 0,
                address TEXT DEFAULT '',
                transaction_id TEXT DEFAULT '',
                merchant_order_id TEXT DEFAULT '',
                created_at INTEGER,
                updated_at INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_rec_book ON records(book_name)")
        db.execSQL("CREATE INDEX idx_rec_time ON records(happened_at)")
        db.execSQL("CREATE INDEX idx_rec_book_ymd ON records(book_name, year, month, day)")

        db.execSQL("""
            CREATE TABLE accounts (
                id              TEXT PRIMARY KEY,
                name            TEXT NOT NULL,
                type            TEXT NOT NULL,
                category        TEXT NOT NULL DEFAULT 'tradable',
                initial_amount  REAL NOT NULL DEFAULT 0,
                income          REAL NOT NULL DEFAULT 0,
                expense         REAL NOT NULL DEFAULT 0,
                current_balance REAL NOT NULL DEFAULT 0,
                note            TEXT DEFAULT '',
                created_at      INTEGER NOT NULL,
                updated_at      INTEGER NOT NULL
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

        // 彩色图标表
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS color_icons (
                id   TEXT PRIMARY KEY,
                name TEXT NOT NULL
            )
        """.trimIndent())

        // 月度收支汇总表（由 Repository 自动维护）
        db.execSQL("""
            CREATE TABLE monthly_summaries (
                book_name TEXT NOT NULL,
                year      INTEGER NOT NULL,
                month     INTEGER NOT NULL,
                income    REAL NOT NULL DEFAULT 0,
                expense   REAL NOT NULL DEFAULT 0,
                PRIMARY KEY (book_name, year, month)
            )
        """.trimIndent())

        // 定期存款扩展表（v24）
        db.execSQL("""
            CREATE TABLE fixed_deposits (
                id             TEXT PRIMARY KEY,
                record_id      TEXT NOT NULL,
                principal      REAL NOT NULL,
                interest_rate  REAL NOT NULL,
                term_value     INTEGER NOT NULL,
                term_unit      TEXT NOT NULL,
                start_date     INTEGER NOT NULL,
                maturity_date  INTEGER NOT NULL,
                status         TEXT NOT NULL DEFAULT 'active',
                income_bill_id TEXT,
                note           TEXT DEFAULT '',
                created_at     INTEGER NOT NULL
            )
        """.trimIndent())

        // 写入默认数据
        insertDefaultCategoriesToDb(db)
        insertDefaultSettingsToDb(db)
        insertColorIconsToDb(db)
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
        if (oldVersion < 10) {
            db.execSQL("ALTER TABLE records ADD COLUMN refund_amount REAL DEFAULT 0")
        }
        if (oldVersion < 11) {
            db.execSQL("ALTER TABLE records ADD COLUMN address TEXT DEFAULT ''")
        }
        if (oldVersion < 12) {
            db.execSQL("ALTER TABLE records ADD COLUMN discount_off TEXT")
            db.execSQL("ALTER TABLE records ADD COLUMN discount_after TEXT")
        }
        if (oldVersion < 13) {
            db.execSQL("ALTER TABLE records ADD COLUMN reimburse_after_amount TEXT")
        }
        if (oldVersion < 14) {
            db.execSQL("ALTER TABLE records ADD COLUMN created_at INTEGER")
            db.execSQL("ALTER TABLE records ADD COLUMN updated_at INTEGER")
        }
        if (oldVersion < 15) {
            db.execSQL("ALTER TABLE accounts ADD COLUMN income REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE accounts ADD COLUMN expense REAL NOT NULL DEFAULT 0")
        }
        if (oldVersion < 16) {
            db.execSQL("ALTER TABLE accounts ADD COLUMN current_balance REAL NOT NULL DEFAULT 0")
            // 用 initial_amount + income - expense 初始化当前余额
            db.execSQL("UPDATE accounts SET current_balance = initial_amount + income - expense")
        }
        if (oldVersion < 17) {
            db.execSQL("ALTER TABLE records ADD COLUMN category_name TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE records ADD COLUMN subcategory_name TEXT")
            // 从 categories 表填充中文名
            db.execSQL("UPDATE records SET category_name = (SELECT name FROM categories WHERE id = records.category_id)")
            db.execSQL("UPDATE records SET subcategory_name = (SELECT name FROM categories WHERE id = records.subcategory_id)")
        }
        if (oldVersion < 19) {
            // 彩色图标表
            db.execSQL("CREATE TABLE IF NOT EXISTS color_icons (id TEXT PRIMARY KEY, name TEXT NOT NULL)")
            insertColorIconsToDb(db)
            // categories 表加 overlay 列
            db.execSQL("ALTER TABLE categories ADD COLUMN overlay TEXT")
            // 更新分类 icon 字段为 build_in_XXXX
            for ((catId, buildInId) in ColorIconRegistry.CATEGORY_ICON_MAP) {
                db.execSQL("UPDATE categories SET icon = ? WHERE id = ?", arrayOf(buildInId, catId))
            }
            // 更新分类 overlay
            for ((catId, overlay) in ColorIconRegistry.CATEGORY_OVERLAY_MAP) {
                db.execSQL("UPDATE categories SET overlay = ? WHERE id = ?", arrayOf(overlay, catId))
            }
            // 兜底：将所有非 build_in_XXXX 的 icon 替换为默认图标（CSV 导入创建的分类使用 Material Icons 名称）
            db.execSQL("UPDATE categories SET icon = 'build_in_0233' WHERE icon NOT LIKE 'build_in_%'")
        }
        if (oldVersion < 20) {
            // 修复 CSV 导入创建的分类使用 Material Icons 的问题
            db.execSQL("UPDATE categories SET icon = 'build_in_0233' WHERE icon NOT LIKE 'build_in_%'")
        }
        if (oldVersion < 21) {
            // 新增 year/month/day 列，支持 账本→年→月→日 层级查询
            db.execSQL("ALTER TABLE records ADD COLUMN year INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE records ADD COLUMN month INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE records ADD COLUMN day INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX idx_rec_book_ymd ON records(book_name, year, month, day)")
            // 从 happened_at 回填 year/month/day
            db.execSQL("UPDATE records SET year = CAST(strftime('%Y', happened_at / 1000, 'unixepoch', 'localtime') AS INTEGER)")
            db.execSQL("UPDATE records SET month = CAST(strftime('%m', happened_at / 1000, 'unixepoch', 'localtime') AS INTEGER)")
            db.execSQL("UPDATE records SET day = CAST(strftime('%d', happened_at / 1000, 'unixepoch', 'localtime') AS INTEGER)")
        }
        if (oldVersion < 22) {
            // 月度收支汇总表
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS monthly_summaries (
                    book_name TEXT NOT NULL,
                    year      INTEGER NOT NULL,
                    month     INTEGER NOT NULL,
                    income    REAL NOT NULL DEFAULT 0,
                    expense   REAL NOT NULL DEFAULT 0,
                    PRIMARY KEY (book_name, year, month)
                )
            """.trimIndent())
            // 全量回填已有记录的月度汇总
            db.execSQL("""
                INSERT OR REPLACE INTO monthly_summaries (book_name, year, month, income, expense)
                SELECT
                    book_name,
                    year,
                    month,
                    SUM(CASE WHEN type = '收入' AND reimbursement_account_id IS NULL AND exclude_from_stats = 0 THEN CAST(amount AS REAL) ELSE 0 END) AS income,
                    SUM(CASE WHEN type = '支出' AND reimbursement_account_id IS NULL AND exclude_from_stats = 0 THEN CAST(amount AS REAL) ELSE 0 END) AS expense
                FROM records
                WHERE year > 0
                GROUP BY book_name, year, month
            """.trimIndent())
        }
        if (oldVersion < 23) {
            // 新增交易单号和商户单号（自动记账专用）
            db.execSQL("ALTER TABLE records ADD COLUMN transaction_id TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE records ADD COLUMN merchant_order_id TEXT DEFAULT ''")
        }
        if (oldVersion < 24) {
            // records 表新增转入账户列（转账专用）
            db.execSQL("ALTER TABLE records ADD COLUMN target_account_id TEXT")
            // 定期存款扩展表
            db.execSQL("""
                CREATE TABLE fixed_deposits (
                    id             TEXT PRIMARY KEY,
                    record_id      TEXT NOT NULL,
                    principal      REAL NOT NULL,
                    interest_rate  REAL NOT NULL,
                    term_value     INTEGER NOT NULL,
                    term_unit      TEXT NOT NULL,
                    start_date     INTEGER NOT NULL,
                    maturity_date  INTEGER NOT NULL,
                    status         TEXT NOT NULL DEFAULT 'active',
                    income_bill_id TEXT,
                    note           TEXT DEFAULT '',
                    created_at     INTEGER NOT NULL
                )
            """.trimIndent())
            // 插入新的默认分类（转账子分类 + 存款子分类）
            insertDefaultCategoriesToDb(db)
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
    // 彩色图标表
    // ─────────────────────────────────────────────

    private fun insertColorIconsToDb(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            for ((buildInId, name) in ColorIconRegistry.ID_TO_NAME) {
                val cv = ContentValues().apply {
                    put("id", buildInId)
                    put("name", name)
                }
                db.insertWithOnConflict("color_icons", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAllColorIcons(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val cursor = readableDatabase.rawQuery("SELECT id, name FROM color_icons ORDER BY id", null)
        try {
            while (cursor.moveToNext()) {
                result.add(Pair(cursor.getString(0), cursor.getString(1)))
            }
        } finally {
            cursor.close()
        }
        return result
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
                            val ov = ColorIconRegistry.CATEGORY_OVERLAY_MAP[cat.id]
                            if (ov != null) put("overlay", ov) else putNull("overlay")
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
                                val ov = ColorIconRegistry.CATEGORY_OVERLAY_MAP[child.id]
                                if (ov != null) put("overlay", ov) else putNull("overlay")
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
            "SELECT id, name, icon, overlay FROM categories WHERE page = ? AND type = ? AND parent_id IS NULL ORDER BY sort_order",
            arrayOf(page, type)
        )
        try {
            while (parentCursor.moveToNext()) {
                val id = parentCursor.getString(0)
                val name = parentCursor.getString(1)
                val icon = parentCursor.getString(2)
                val overlay = parentCursor.getString(3)
                // 查二级分类
                val children = mutableListOf<AccountingCategory>()
                val childCursor = db.rawQuery(
                    "SELECT id, name, icon, overlay FROM categories WHERE parent_id = ? ORDER BY sort_order",
                    arrayOf(id)
                )
                try {
                    while (childCursor.moveToNext()) {
                        children.add(AccountingCategory(
                            id = childCursor.getString(0),
                            name = childCursor.getString(1),
                            icon = childCursor.getString(2),
                            overlay = childCursor.getString(3)
                        ))
                    }
                } finally {
                    childCursor.close()
                }
                parents.add(AccountingCategory(id = id, name = name, icon = icon, children = children, overlay = overlay))
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
    /** 查询 A/B 系列分类的最大编号 */
    private fun nextCategorySeq(prefix: String): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT id FROM categories WHERE id LIKE ? ORDER BY id DESC LIMIT 1",
            arrayOf("${prefix}%")
        )
        val max = try {
            if (cursor.moveToFirst()) {
                cursor.getString(0).removePrefix(prefix).toIntOrNull() ?: 0
            } else 0
        } finally { cursor.close() }
        return max + 1
    }

    fun createParentCategory(name: String, type: String = "支出", icon: String = "build_in_0233"): String {
        val seq = nextCategorySeq("A")
        val id = "A%03d".format(seq)
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
    fun createChildCategory(name: String, parentId: String, type: String = "支出", icon: String = "build_in_0233"): String {
        val seq = nextCategorySeq("B")
        val id = "B%03d".format(seq)
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
            "SELECT id, book_name, type, amount, category_id, subcategory_id, note, happened_at, year, month, day, account_id, target_account_id, discount_before, discount_off, discount_after, reimbursement_account_id, attachments, exclude_from_stats, exclude_from_budget, reimburse_status, reimburse_amount, reimburse_after_amount, refund_amount, address, created_at, updated_at, category_name, subcategory_name, transaction_id, merchant_order_id FROM records ORDER BY happened_at DESC",
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
            "SELECT id, book_name, type, amount, category_id, subcategory_id, note, happened_at, year, month, day, account_id, target_account_id, discount_before, discount_off, discount_after, reimbursement_account_id, attachments, exclude_from_stats, exclude_from_budget, reimburse_status, reimburse_amount, reimburse_after_amount, refund_amount, address, created_at, updated_at, category_name, subcategory_name, transaction_id, merchant_order_id FROM records WHERE book_name = ? ORDER BY happened_at DESC",
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

    fun getRecordsByAccount(accountId: String): List<AccountingRecord> {
        val records = mutableListOf<AccountingRecord>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, book_name, type, amount, category_id, subcategory_id, note, happened_at, year, month, day, account_id, target_account_id, discount_before, discount_off, discount_after, reimbursement_account_id, attachments, exclude_from_stats, exclude_from_budget, reimburse_status, reimburse_amount, reimburse_after_amount, refund_amount, address, created_at, updated_at, category_name, subcategory_name, transaction_id, merchant_order_id FROM records WHERE account_id = ? ORDER BY happened_at DESC",
            arrayOf(accountId)
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

    fun getTransfersByAccount(accountId: String): List<AccountingRecord> {
        val records = mutableListOf<AccountingRecord>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, book_name, type, amount, category_id, subcategory_id, note, happened_at, year, month, day, account_id, target_account_id, discount_before, discount_off, discount_after, reimbursement_account_id, attachments, exclude_from_stats, exclude_from_budget, reimburse_status, reimburse_amount, reimburse_after_amount, refund_amount, address, created_at, updated_at, category_name, subcategory_name, transaction_id, merchant_order_id FROM records WHERE type = '转账' AND (account_id = ? OR target_account_id = ?) ORDER BY happened_at DESC",
            arrayOf(accountId, accountId)
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
            put("category_name", r.categoryName)
            if (r.subcategoryName != null) put("subcategory_name", r.subcategoryName) else putNull("subcategory_name")
            put("note", r.note)
            put("happened_at", r.happenedAt)
            put("year", r.year)
            put("month", r.month)
            put("day", r.day)
            if (r.accountId != null) put("account_id", r.accountId) else putNull("account_id")
            if (r.targetAccountId != null) put("target_account_id", r.targetAccountId) else putNull("target_account_id")
            if (r.discountBefore != null) put("discount_before", r.discountBefore) else putNull("discount_before")
            if (r.discountOff != null) put("discount_off", r.discountOff) else putNull("discount_off")
            if (r.discountAfter != null) put("discount_after", r.discountAfter) else putNull("discount_after")
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
            if (r.reimburseAfterAmount != null) put("reimburse_after_amount", r.reimburseAfterAmount) else putNull("reimburse_after_amount")
            put("refund_amount", r.refundAmount)
            put("address", r.address)
            put("transaction_id", r.transactionId)
            put("merchant_order_id", r.merchantOrderId)
            if (r.createdAt != null) put("created_at", r.createdAt) else putNull("created_at")
            if (r.updatedAt != null) put("updated_at", r.updatedAt) else putNull("updated_at")
        }
    }

    private fun cursorToRecord(c: android.database.Cursor): AccountingRecord {
        val attachmentsJson = c.getString(16)
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
            year = c.getInt(8),
            month = c.getInt(9),
            day = c.getInt(10),
            accountId = c.getString(11),  // 可能为 null
            targetAccountId = c.getString(12),  // 可能为 null
            discountBefore = c.getString(13),  // 可能为 null
            discountOff = c.getString(14),  // 可能为 null
            discountAfter = c.getString(15),  // 可能为 null
            reimbursementAccountId = c.getString(16),  // 可能为 null
            attachments = attachments,
            excludeFromStats = c.getInt(18) == 1,
            excludeFromBudget = c.getInt(19) == 1,
            reimburseStatus = c.getInt(20) == 1,
            reimburseAmount = c.getDouble(21),
            reimburseAfterAmount = c.getString(22),
            refundAmount = c.getDouble(23),
            address = c.getString(24) ?: "",
            createdAt = if (c.isNull(25)) null else c.getLong(25),
            updatedAt = if (c.isNull(26)) null else c.getLong(26),
            categoryName = c.getString(27) ?: "",
            subcategoryName = c.getString(28),
            transactionId = c.getString(29) ?: "",
            merchantOrderId = c.getString(30) ?: ""
        )
    }

    // ─────────────────────────────────────────────
    // 报销账户查询
    // ─────────────────────────────────────────────

    fun getRecordsByReimbursementAccount(reimbAccountId: String): List<AccountingRecord> {
        val records = mutableListOf<AccountingRecord>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, book_name, type, amount, category_id, subcategory_id, note, happened_at, year, month, day, account_id, target_account_id, discount_before, discount_off, discount_after, reimbursement_account_id, attachments, exclude_from_stats, exclude_from_budget, reimburse_status, reimburse_amount, reimburse_after_amount, refund_amount, address, created_at, updated_at, category_name, subcategory_name, transaction_id, merchant_order_id FROM records WHERE reimbursement_account_id = ? ORDER BY happened_at DESC",
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
    // 月度收支汇总（自动维护）
    // ─────────────────────────────────────────────

    /** 重算指定账本指定月的收支汇总并写入 monthly_summaries */
    fun recalculateMonthSummary(bookName: String, year: Int, month: Int) {
        val db = writableDatabase
        db.execSQL("""
            INSERT OR REPLACE INTO monthly_summaries (book_name, year, month, income, expense)
            SELECT
                ?,
                ?,
                ?,
                COALESCE(SUM(CASE WHEN type = '收入' AND reimbursement_account_id IS NULL AND exclude_from_stats = 0 THEN CAST(amount AS REAL) END), 0),
                COALESCE(SUM(CASE WHEN type = '支出' AND reimbursement_account_id IS NULL AND exclude_from_stats = 0 THEN CAST(amount AS REAL) END), 0)
            FROM records
            WHERE book_name = ? AND year = ? AND month = ?
        """.trimIndent(), arrayOf(bookName, year, month, bookName, year, month))
    }

    /** 获取指定账本指定月的收支汇总 */
    fun getMonthSummary(bookName: String, year: Int, month: Int): MonthlySummary {
        val cursor = readableDatabase.rawQuery(
            "SELECT income, expense FROM monthly_summaries WHERE book_name = ? AND year = ? AND month = ?",
            arrayOf(bookName, year.toString(), month.toString())
        )
        return try {
            if (cursor.moveToFirst()) {
                MonthlySummary(bookName, year, month, cursor.getDouble(0), cursor.getDouble(1))
            } else {
                MonthlySummary(bookName, year, month)
            }
        } finally {
            cursor.close()
        }
    }

    /** 获取指定账本所有月度汇总（按年月降序） */
    fun getAllMonthSummaries(bookName: String): List<MonthlySummary> {
        val result = mutableListOf<MonthlySummary>()
        val cursor = readableDatabase.rawQuery(
            "SELECT year, month, income, expense FROM monthly_summaries WHERE book_name = ? ORDER BY year DESC, month DESC",
            arrayOf(bookName)
        )
        try {
            while (cursor.moveToNext()) {
                result.add(MonthlySummary(bookName, cursor.getInt(0), cursor.getInt(1), cursor.getDouble(2), cursor.getDouble(3)))
            }
        } finally {
            cursor.close()
        }
        return result
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
            "SELECT id, name, type, category, initial_amount, income, expense, current_balance, note, created_at, updated_at FROM accounts ORDER BY created_at DESC",
            null
        )
        try {
            while (cursor.moveToNext()) {
                cursorToAccount(cursor)?.let { accounts.add(it) }
            }
        } finally {
            cursor.close()
        }
        return accounts
    }

    fun getAccountsByCategory(category: String): List<AccountingAccount> {
        val accounts = mutableListOf<AccountingAccount>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, name, type, category, initial_amount, income, expense, current_balance, note, created_at, updated_at FROM accounts WHERE category = ? ORDER BY created_at DESC",
            arrayOf(category)
        )
        try {
            while (cursor.moveToNext()) {
                cursorToAccount(cursor)?.let { accounts.add(it) }
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
            put("income", a.income)
            put("expense", a.expense)
            put("current_balance", a.currentBalance)
            put("note", a.note)
            put("created_at", a.createdAt)
            put("updated_at", a.updatedAt)
        }
    }

    private fun cursorToAccount(c: android.database.Cursor): AccountingAccount? {
        return try {
            AccountingAccount(
                id = c.getString(0),
                name = c.getString(1),
                type = c.getString(2),
                category = c.getString(3),
                initialAmount = c.getDouble(4),
                income = c.getDouble(5),
                expense = c.getDouble(6),
                currentBalance = c.getDouble(7),
                note = c.getString(8) ?: "",
                createdAt = c.getLong(9),
                updatedAt = c.getLong(10)
            )
        } catch (e: Exception) {
            android.util.Log.e("AccountingDB", "cursorToAccount failed: ${e.message}, columnCount=${c.columnCount}")
            null
        }
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
            "SELECT id, book_name, type, amount, category_id, subcategory_id, note, happened_at, year, month, day, account_id, target_account_id, discount_before, discount_off, discount_after, reimbursement_account_id, attachments, exclude_from_stats, exclude_from_budget, reimburse_status, reimburse_amount, reimburse_after_amount, refund_amount, address, created_at, updated_at, category_name, subcategory_name, transaction_id, merchant_order_id FROM records",
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
                    discountOff = recCursor.getString(10),
                    discountAfter = recCursor.getString(11),
                    reimbursementAccountId = recCursor.getString(12),
                    reimburseStatus = recCursor.getInt(16) == 1,
                    reimburseAmount = recCursor.getDouble(17),
                    reimburseAfterAmount = recCursor.getString(18),
                    refundAmount = recCursor.getDouble(19),
                    address = recCursor.getString(20) ?: "",
                    createdAt = if (recCursor.isNull(21)) null else recCursor.getLong(21),
                    updatedAt = if (recCursor.isNull(22)) null else recCursor.getLong(22)
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

        // fixed_deposits
        val deposits = mutableListOf<ExportFixedDeposit>()
        val depCursor = db.rawQuery(
            "SELECT id, record_id, principal, interest_rate, term_value, term_unit, start_date, maturity_date, status, income_bill_id, note, created_at FROM fixed_deposits", null
        )
        try {
            while (depCursor.moveToNext()) {
                deposits.add(ExportFixedDeposit(
                    id = depCursor.getString(0),
                    recordId = depCursor.getString(1),
                    principal = depCursor.getDouble(2),
                    interestRate = depCursor.getDouble(3),
                    termValue = depCursor.getInt(4),
                    termUnit = depCursor.getString(5),
                    startDate = depCursor.getLong(6),
                    maturityDate = depCursor.getLong(7),
                    status = depCursor.getString(8),
                    incomeBillId = depCursor.getString(9),
                    note = depCursor.getString(10) ?: "",
                    createdAt = depCursor.getLong(11)
                ))
            }
        } finally {
            depCursor.close()
        }

        val exportData = ExportData(
            settings = settings,
            categories = categories,
            records = records,
            accounts = accounts,
            fixedDeposits = deposits
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
        sb.appendLine("﻿类型,分类,二级分类,金额,账本,账户,备注,时间,优惠前金额,优惠金额,优惠后金额,报销账户,其他,地址,创建时间,修改时间")

        val recCursor = db.rawQuery(
            "SELECT type, amount, category_id, subcategory_id, book_name, account_id, note, happened_at, discount_before, discount_off, discount_after, reimbursement_account_id, attachments, exclude_from_stats, exclude_from_budget, reimburse_status, reimburse_amount, reimburse_after_amount, refund_amount, address, created_at, updated_at FROM records ORDER BY happened_at ASC",
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
                val discountOff = recCursor.getString(9) ?: ""
                val discountAfter = recCursor.getString(10) ?: ""
                val reimbName = reimbMap[recCursor.getString(11)] ?: recCursor.getString(11) ?: ""
                val excludeFromStats = recCursor.getInt(13) == 1
                val excludeFromBudget = recCursor.getInt(14) == 1
                val address = recCursor.getString(19) ?: ""
                val createdAtVal = if (recCursor.isNull(20)) null else recCursor.getLong(20)
                val updatedAtVal = if (recCursor.isNull(21)) null else recCursor.getLong(21)

                val otherParts = mutableListOf<String>()
                if (excludeFromStats) otherParts.add("不计入收支")
                if (excludeFromBudget) otherParts.add("不计入预算")
                val otherStr = otherParts.joinToString("、")

                val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(ts))

                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                val createdAtStr = if (createdAtVal != null) sdf.format(java.util.Date(createdAtVal)) else ""
                val updatedAtStr = if (updatedAtVal != null) sdf.format(java.util.Date(updatedAtVal)) else ""
                sb.appendLine("${csvEscape(type)},${csvEscape(catName)},${csvEscape(subCatName)},${csvEscape(amount)},${csvEscape(book)},${csvEscape(accName)},${csvEscape(note)},${csvEscape(dateStr)},${csvEscape(discountBefore)},${csvEscape(discountOff)},${csvEscape(discountAfter)},${csvEscape(reimbName)},${csvEscape(otherStr)},${csvEscape(address)},${csvEscape(createdAtStr)},${csvEscape(updatedAtStr)}")
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
        val lines = mergeCsvLines(csvString.lines()).filter { it.isNotBlank() }
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
        val discountIdx = header.indexOf("优惠前金额").let { if (it < 0) header.indexOf("优惠") else it }
        val reimbIdx = header.indexOf("报销账户")
        val reimbAmountIdx = header.indexOf("报销金额")
        val refundIdx = header.indexOf("退款")
        val otherIdx = header.indexOf("其他")
        val addressIdx = header.indexOf("地址")
        val createdAtIdx = header.indexOf("创建时间")
        val updatedAtIdx = header.indexOf("修改时间")

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

        val dateFormatFull = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val dateFormatShort = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())

        db.beginTransaction()
        try {
            if (!appendMode) db.delete("records", null, null)

            for (i in 1 until lines.size) {
                val cols = parseCsvLine(lines[i])
                if (cols.size < 3) continue

                val type = cols.getOrNull(typeIdx)?.trim() ?: continue
                val amount = cols.getOrNull(amountIdx)?.trim() ?: continue
                val timeStr = cols.getOrNull(timeIdx)?.trim() ?: continue

                val happenedAt = try {
                    dateFormatFull.parse(timeStr)?.time ?: dateFormatShort.parse(timeStr)?.time ?: 0L
                } catch (_: Exception) {
                    try { dateFormatShort.parse(timeStr)?.time ?: 0L } catch (_: Exception) { 0L }
                }
                val catName = cols.getOrNull(catIdx)?.trim() ?: ""
                val subCatName = cols.getOrNull(subCatIdx)?.trim() ?: ""
                val book = cols.getOrNull(bookIdx)?.trim() ?: "默认记账本"
                val accName = cols.getOrNull(accIdx)?.trim() ?: ""
                val note = cols.getOrNull(noteIdx)?.trim() ?: ""
                val discountStr = cols.getOrNull(discountIdx)?.trim() ?: ""
                val reimbName = cols.getOrNull(reimbIdx)?.trim() ?: ""
                val reimbAmountStr = cols.getOrNull(reimbAmountIdx)?.trim() ?: ""
                val refundStr = cols.getOrNull(refundIdx)?.trim() ?: ""
                val otherStr = cols.getOrNull(otherIdx)?.trim() ?: ""
                val addressStr = cols.getOrNull(addressIdx)?.trim() ?: ""
                val createdAtStr = cols.getOrNull(createdAtIdx)?.trim() ?: ""
                val updatedAtStr = cols.getOrNull(updatedAtIdx)?.trim() ?: ""
                val createdAt: Long? = if (createdAtStr.isNotEmpty()) {
                    try { dateFormatFull.parse(createdAtStr)?.time } catch (_: Exception) { null }
                } else null
                val updatedAt: Long? = if (updatedAtStr.isNotEmpty()) {
                    try { dateFormatFull.parse(updatedAtStr)?.time } catch (_: Exception) { null }
                } else null

                // 报销状态：空=非报销账单，0=未报销，非0=已报销
                val reimburseStatus = reimbName.isNotEmpty() && reimbAmountStr.isNotEmpty() && reimbAmountStr != "0"
                val reimburseAmount = if (reimburseStatus) {
                    try { reimbAmountStr.replace("[¥$,，]".toRegex(), "").toDouble() } catch (_: Exception) { 0.0 }
                } else 0.0

                // 退款金额（CSV金额已是净值，退款仅存储供UI显示）
                val refundAmount = refundStr.toDoubleOrNull() ?: 0.0

                val absAmount = amount.replace("-", "").replace("+", "").trim()

                // 报销后金额：报销账单 = -|amount| + reimburseAmount（未报销时视为0），非报销账单 = amount
                val reimburseAfterAmount = if (reimbName.isNotEmpty()) {
                    val absAmt = absAmount.toDoubleOrNull() ?: 0.0
                    val ra = if (reimburseStatus) reimburseAmount else 0.0
                    (-absAmt + ra).toString()
                } else null

                // 优惠三值（BigDecimal 精确运算）
                val discountOff: String?
                val discountAfter: String?
                val discountBefore: String?
                if (discountStr.isNotEmpty()) {
                    val discount = discountStr.toBigDecimalOrNull()
                    if (discount != null && discount.signum() > 0) {
                        val absAmt = absAmount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                        discountOff = discount.stripTrailingZeros().toPlainString()
                        discountAfter = absAmt.stripTrailingZeros().toPlainString()
                        discountBefore = absAmt.add(discount).stripTrailingZeros().toPlainString()
                    } else {
                        discountOff = null; discountAfter = null; discountBefore = null
                    }
                } else {
                    discountOff = null; discountAfter = null; discountBefore = null
                }
                val cv = ContentValues().apply {
                    put("id", java.util.UUID.randomUUID().toString())
                    put("book_name", book)
                    put("type", type)
                    put("amount", absAmount)
                    // 自动创建缺失分类
                    val catId = catNameToId[catName] ?: run {
                        val newId = createParentCategory(catName, type)
                        catNameToId[catName] = newId
                        newId
                    }
                    put("category_id", catId)
                    val subId = if (subCatName.isEmpty()) null
                        else catNameToId[subCatName] ?: run {
                            val newId = createChildCategory(subCatName, catId, type)
                            catNameToId[subCatName] = newId
                            newId
                        }
                    if (subId != null) put("subcategory_id", subId) else putNull("subcategory_id")
                    put("category_name", catName)
                    if (subCatName.isNotEmpty()) put("subcategory_name", subCatName) else putNull("subcategory_name")
                    put("note", note)
                    put("happened_at", happenedAt)
                    // 从 happenedAt 提取 year/month/day
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = happenedAt }
                    put("year", cal.get(java.util.Calendar.YEAR))
                    put("month", cal.get(java.util.Calendar.MONTH) + 1)
                    put("day", cal.get(java.util.Calendar.DAY_OF_MONTH))
                    val aId = accNameToId[accName]
                    if (aId != null) put("account_id", aId) else if (accName.isNotEmpty()) put("account_id", accName) else putNull("account_id")
                    if (discountBefore != null) put("discount_before", discountBefore) else putNull("discount_before")
                    if (discountOff != null) put("discount_off", discountOff) else putNull("discount_off")
                    if (discountAfter != null) put("discount_after", discountAfter) else putNull("discount_after")
                    val rId = reimbNameToId[reimbName]
                    if (rId != null) put("reimbursement_account_id", rId) else if (reimbName.isNotEmpty()) put("reimbursement_account_id", reimbName) else putNull("reimbursement_account_id")
                    put("reimburse_status", if (reimburseStatus) 1 else 0)
                    put("reimburse_amount", reimburseAmount)
                    if (reimburseAfterAmount != null) put("reimburse_after_amount", reimburseAfterAmount) else putNull("reimburse_after_amount")
                    put("refund_amount", refundAmount)
                    put("exclude_from_stats", if (otherStr.contains("不计入收支")) 1 else 0)
                    put("exclude_from_budget", if (otherStr.contains("不计入预算")) 1 else 0)
                    put("address", addressStr)
                    if (createdAt != null) put("created_at", createdAt) else putNull("created_at")
                    if (updatedAt != null) put("updated_at", updatedAt) else putNull("updated_at")
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
        val lines = mergeCsvLines(csvText.lines()).filter { it.isNotBlank() }
        if (lines.size < 2) throw IllegalArgumentException("CSV 文件无数据行。")

        val db = writableDatabase
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        val dateFormatFull = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())

        // 构建账户名→ID 映射
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
                if (type != "支出" && type != "收入" && type != "转账" && type != "债务" && type != "存款") continue
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
                val discountStr = col("优惠前金额") ?: ""
                val reimbName = col("报销账户") ?: ""
                val reimbAmountStr = col("报销金额") ?: ""
                val refundStr = col("退款") ?: ""
                val otherStr = col("其他") ?: ""
                val addressStr = col("地址") ?: ""
                val createdAtStr = col("创建时间") ?: ""
                val updatedAtStr = col("修改时间") ?: ""
                val createdAtVal: Long? = if (createdAtStr.isNotEmpty()) {
                    try { dateFormatFull.parse(createdAtStr)?.time } catch (_: Exception) { null }
                } else null
                val updatedAtVal: Long? = if (updatedAtStr.isNotEmpty()) {
                    try { dateFormatFull.parse(updatedAtStr)?.time } catch (_: Exception) { null }
                } else null

                // 报销状态：空=非报销账单，0=未报销，非0=已报销
                val reimburseStatus = reimbName.isNotEmpty() && reimbAmountStr.isNotEmpty() && reimbAmountStr != "0"
                val reimburseAmount = if (reimburseStatus) {
                    try { reimbAmountStr.replace("[¥$,，]".toRegex(), "").toDouble() } catch (_: Exception) { 0.0 }
                } else 0.0

                // 退款金额（CSV金额已是净值，退款仅存储供UI显示）
                val refundAmount = refundStr.toDoubleOrNull() ?: 0.0

                // 报销后金额：报销账单 = -|amount| + reimburseAmount（未报销时视为0），非报销账单 = null
                val reimburseAfterAmount = if (reimbName.isNotEmpty()) {
                    val ra = if (reimburseStatus) reimburseAmount else 0.0
                    (-amount + ra).toString()
                } else null

                // 优惠三值（BigDecimal 精确运算）
                val discountOff: String?
                val discountAfter: String?
                val discountBefore: String?
                if (discountStr.isNotEmpty()) {
                    val discount = discountStr.toBigDecimalOrNull()
                    if (discount != null && discount.signum() > 0) {
                        val absAmt = amount.toBigDecimal()
                        discountOff = discount.stripTrailingZeros().toPlainString()
                        discountAfter = absAmt.stripTrailingZeros().toPlainString()
                        discountBefore = absAmt.add(discount).stripTrailingZeros().toPlainString()
                    } else {
                        discountOff = null; discountAfter = null; discountBefore = null
                    }
                } else {
                    discountOff = null; discountAfter = null; discountBefore = null
                }

                // 分类映射（缺失时自动创建）
                val catId = if (catName.isEmpty()) ""
                    else categoryMapping[catName] ?: run {
                        val newId = createParentCategory(catName, type)
                        newId
                    }
                val subCatId = if (subCatName.isEmpty()) null
                    else categoryMapping[subCatName] ?: run {
                        if (catId.isNotEmpty()) createChildCategory(subCatName, catId, type) else subCatName
                    }

                val cv = ContentValues().apply {
                    put("id", java.util.UUID.randomUUID().toString())
                    put("book_name", book)
                    put("type", type)
                    put("amount", amountStr)
                    put("category_id", catId)
                    if (subCatId != null) put("subcategory_id", subCatId) else putNull("subcategory_id")
                    put("note", note)
                    put("happened_at", happenedAt)
                    // 从 happenedAt 提取 year/month/day
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = happenedAt }
                    put("year", cal.get(java.util.Calendar.YEAR))
                    put("month", cal.get(java.util.Calendar.MONTH) + 1)
                    put("day", cal.get(java.util.Calendar.DAY_OF_MONTH))
                    val aId = accNameToId[accName]
                    if (aId != null) put("account_id", aId) else if (accName.isNotEmpty()) put("account_id", accName) else putNull("account_id")
                    if (discountBefore != null) put("discount_before", discountBefore) else putNull("discount_before")
                    if (discountOff != null) put("discount_off", discountOff) else putNull("discount_off")
                    if (discountAfter != null) put("discount_after", discountAfter) else putNull("discount_after")
                    val rId = reimbNameToId[reimbName]
                    if (rId != null) put("reimbursement_account_id", rId) else if (reimbName.isNotEmpty()) put("reimbursement_account_id", reimbName) else putNull("reimbursement_account_id")
                    put("reimburse_status", if (reimburseStatus) 1 else 0)
                    put("reimburse_amount", reimburseAmount)
                    if (reimburseAfterAmount != null) put("reimburse_after_amount", reimburseAfterAmount) else putNull("reimburse_after_amount")
                    put("refund_amount", refundAmount)
                    put("exclude_from_stats", if (otherStr.contains("不计入收支")) 1 else 0)
                    put("exclude_from_budget", if (otherStr.contains("不计入预算")) 1 else 0)
                    put("address", addressStr)
                    if (createdAtVal != null) put("created_at", createdAtVal) else putNull("created_at")
                    if (updatedAtVal != null) put("updated_at", updatedAtVal) else putNull("updated_at")
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

    /**
     * 导入后重算账户余额。
     * 覆盖模式：先将所有账户 initial_amount 清零，再根据全部记录重算。
     * 追加模式：在现有 initial_amount 基础上，根据本次导入的记录调整。
     */
    fun recalculateBalances(replaceMode: Boolean) {
        val db = writableDatabase
        if (replaceMode) {
            db.execSQL("UPDATE accounts SET initial_amount = 0, income = 0, expense = 0, current_balance = 0")
        }

        // 1. 收入：普通收入记录的 amount
        val incomeMap = mutableMapOf<String, Double>()
        val incomeCursor = db.rawQuery(
            """SELECT account_id, SUM(CAST(amount AS REAL))
               FROM records WHERE account_id IS NOT NULL AND type = '收入'
               GROUP BY account_id""",
            null
        )
        try {
            while (incomeCursor.moveToNext()) {
                incomeMap[incomeCursor.getString(0)] = incomeCursor.getDouble(1)
            }
        } finally {
            incomeCursor.close()
        }

        // 2. 支出：普通支出 + 报销记录的原始金额（不抵扣报销）
        val expenseMap = mutableMapOf<String, Double>()
        val expenseCursor = db.rawQuery(
            """SELECT account_id, SUM(CAST(amount AS REAL))
               FROM records WHERE account_id IS NOT NULL
               AND (type = '支出' OR reimbursement_account_id IS NOT NULL)
               GROUP BY account_id""",
            null
        )
        try {
            while (expenseCursor.moveToNext()) {
                expenseMap[expenseCursor.getString(0)] = expenseCursor.getDouble(1)
            }
        } finally {
            expenseCursor.close()
        }

        // 3. 报销收入：已报销记录的 reimburse_amount
        val reimburseIncomeMap = mutableMapOf<String, Double>()
        val reimburseCursor = db.rawQuery(
            """SELECT account_id, SUM(CAST(reimburse_amount AS REAL))
               FROM records WHERE reimbursement_account_id IS NOT NULL
               AND reimburse_amount IS NOT NULL
               GROUP BY account_id""",
            null
        )
        try {
            while (reimburseCursor.moveToNext()) {
                reimburseIncomeMap[reimburseCursor.getString(0)] = reimburseCursor.getDouble(1)
            }
        } finally {
            reimburseCursor.close()
        }

        // 合并所有涉及的账户 ID
        val allAccIds = (incomeMap.keys + expenseMap.keys + reimburseIncomeMap.keys).distinct()
        val now = System.currentTimeMillis()

        for (accId in allAccIds) {
            val income = (incomeMap[accId] ?: 0.0) + (reimburseIncomeMap[accId] ?: 0.0)
            val expense = expenseMap[accId] ?: 0.0
            val delta = income - expense

            if (replaceMode) {
                db.execSQL(
                    "UPDATE accounts SET initial_amount = ?, income = ?, expense = ?, current_balance = ?, updated_at = ? WHERE id = ?",
                    arrayOf(delta, income, expense, delta, now, accId)
                )
            } else {
                db.execSQL(
                    "UPDATE accounts SET initial_amount = initial_amount + ?, income = income + ?, expense = expense + ?, current_balance = current_balance + ?, updated_at = ? WHERE id = ?",
                    arrayOf(delta, income, expense, delta, now, accId)
                )
            }
        }
    }

    /**
     * 全量重算报销统计，结果存入 settings 表。
     * 可报销 = reimbursementAccountId != NULL 且 reimburse_status = 0 的金额之和
     * 已报销 = reimbursementAccountId != NULL 且 reimburse_status = 1 的金额之和
     */
    fun recalculateReimburseTotals() {
        val db = readableDatabase
        val pendingCursor = db.rawQuery(
            "SELECT SUM(CAST(amount AS REAL)) FROM records WHERE reimbursement_account_id IS NOT NULL AND reimburse_status = 0",
            null
        )
        val pending = try { if (pendingCursor.moveToFirst()) pendingCursor.getDouble(0) else 0.0 } finally { pendingCursor.close() }

        val doneCursor = db.rawQuery(
            "SELECT SUM(CAST(reimburse_amount AS REAL)) FROM records WHERE reimbursement_account_id IS NOT NULL AND reimburse_status = 1",
            null
        )
        val done = try { if (doneCursor.moveToFirst()) doneCursor.getDouble(0) else 0.0 } finally { doneCursor.close() }

        setSetting("reimburse_pending_total", pending.toString())
        setSetting("reimburse_done_total", done.toString())
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

    /**
     * 将 CSV 原始行合并为逻辑记录行（处理跨行的引号字段）。
     * 例如报销明细字段包含换行时，需要将多行合并为一行再解析。
     */
    private fun mergeCsvLines(rawLines: List<String>): List<String> {
        val merged = mutableListOf<String>()
        val buffer = StringBuilder()
        var inQuotes = false
        for (line in rawLines) {
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(line)
            // 计算引号数（忽略转义的双引号 ""）
            var quoteCount = 0
            var j = 0
            while (j < line.length) {
                if (line[j] == '"') {
                    if (j + 1 < line.length && line[j + 1] == '"') { j += 2; continue }
                    quoteCount++
                }
                j++
            }
            if (inQuotes) quoteCount++  // 加上之前未关闭的引号
            inQuotes = quoteCount % 2 != 0
            if (!inQuotes) {
                merged.add(buffer.toString())
                buffer.clear()
            }
        }
        if (buffer.isNotEmpty()) merged.add(buffer.toString())
        return merged
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
            db.delete("fixed_deposits", null, null)

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
                    // 从 happenedAt 提取 year/month/day
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = r.happenedAt }
                    put("year", cal.get(java.util.Calendar.YEAR))
                    put("month", cal.get(java.util.Calendar.MONTH) + 1)
                    put("day", cal.get(java.util.Calendar.DAY_OF_MONTH))
                    if (r.accountId != null) put("account_id", r.accountId) else putNull("account_id")
                    if (r.discountBefore != null) put("discount_before", r.discountBefore) else putNull("discount_before")
                    if (r.discountOff != null) put("discount_off", r.discountOff) else putNull("discount_off")
                    if (r.discountAfter != null) put("discount_after", r.discountAfter) else putNull("discount_after")
                    if (r.reimbursementAccountId != null) put("reimbursement_account_id", r.reimbursementAccountId) else putNull("reimbursement_account_id")
                    put("reimburse_status", if (r.reimburseStatus) 1 else 0)
                    put("reimburse_amount", r.reimburseAmount)
                    if (r.reimburseAfterAmount != null) put("reimburse_after_amount", r.reimburseAfterAmount) else putNull("reimburse_after_amount")
                    put("refund_amount", r.refundAmount)
                    put("address", r.address)
                    if (r.createdAt != null) put("created_at", r.createdAt) else putNull("created_at")
                    if (r.updatedAt != null) put("updated_at", r.updatedAt) else putNull("updated_at")
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
            for (dep in data.fixedDeposits) {
                val cv = ContentValues().apply {
                    put("id", dep.id)
                    put("record_id", dep.recordId)
                    put("principal", dep.principal)
                    put("interest_rate", dep.interestRate)
                    put("term_value", dep.termValue)
                    put("term_unit", dep.termUnit)
                    put("start_date", dep.startDate)
                    put("maturity_date", dep.maturityDate)
                    put("status", dep.status)
                    put("income_bill_id", dep.incomeBillId)
                    put("note", dep.note)
                    put("created_at", dep.createdAt)
                }
                db.insertWithOnConflict("fixed_deposits", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            // 计算当前余额（兼容旧导出数据）
            db.execSQL("UPDATE accounts SET current_balance = initial_amount + income - expense")

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
    val discountOff: String? = null,
    val discountAfter: String? = null,
    val reimbursementAccountId: String? = null,
    val reimburseStatus: Boolean = false,
    val reimburseAmount: Double = 0.0,
    val reimburseAfterAmount: String? = null,
    val refundAmount: Double = 0.0,
    val address: String = "",
    val createdAt: Long? = null,
    val updatedAt: Long? = null
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
private data class ExportFixedDeposit(
    val id: String,
    val recordId: String,
    val principal: Double,
    val interestRate: Double,
    val termValue: Int,
    val termUnit: String,
    val startDate: Long,
    val maturityDate: Long,
    val status: String = "active",
    val incomeBillId: String? = null,
    val note: String = "",
    val createdAt: Long
)

@Serializable
private data class ExportData(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: List<ExportSetting> = emptyList(),
    val categories: List<ExportCategory> = emptyList(),
    val records: List<ExportRecord> = emptyList(),
    val accounts: List<ExportAccount> = emptyList(),
    val fixedDeposits: List<ExportFixedDeposit> = emptyList()
)
