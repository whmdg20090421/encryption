package com.whmdg.mczj.tools.ui.accounting

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.whmdg.mczj.tools.AppDataPaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * 记账模块统一数据访问层（Repository 模式）。
 * 所有对 AccountingDatabase 和相关 SharedPreferences 的操作必须通过此类进行。
 * 外部文件禁止直接使用 AccountingDatabase。
 */
object AccountingRepository {

    private const val KEY_LAST_ACCOUNT_ID = "last_account_id"

    // ─────────────────────────────────────────────
    // 分类 (Categories)
    // ─────────────────────────────────────────────

    /** 从 SQLite 加载指定页面、指定类型的分类树 */
    fun loadCategories(context: Context, page: String, type: String): List<AccountingCategory> {
        return getDb(context).getCategories(page, type)
    }

    /** 获取分类数据版本号 */
    fun getCategoryVersion(context: Context): Int {
        return getDb(context).getCategoryVersion()
    }

    /** 设置分类数据版本号 */
    fun setCategoryVersion(context: Context, version: Int) {
        getDb(context).setCategoryVersion(version)
    }

    /** 写入默认分类数据到数据库 */
    fun insertDefaultCategories(context: Context) {
        getDb(context).insertDefaultCategories()
    }

    /** 执行数据迁移（JSON + SharedPreferences → SQLite） */
    fun migrateFromLegacy(context: Context) {
        getDb(context).migrateFromLegacy(context)
    }

    // ─────────────────────────────────────────────
    // 设置 (Settings)
    // ─────────────────────────────────────────────

    /** 读取分类图标主题色（十六进制颜色值，默认靛蓝 #5C6BC0） */
    fun getCategoryIconColor(context: Context): String {
        return getDb(context).getSetting("category_icon_color") ?: "#5C6BC0"
    }

    /** 写入分类图标主题色 */
    fun setCategoryIconColor(context: Context, colorHex: String) {
        getDb(context).setSetting("category_icon_color", colorHex)
    }

    /** 获取通用设置值 */
    fun getSetting(context: Context, key: String): String? {
        return getDb(context).getSetting(key)
    }

    /** 设置通用设置值 */
    fun setSetting(context: Context, key: String, value: String) {
        getDb(context).setSetting(key, value)
    }

    // ─────────────────────────────────────────────
    // 记录 (Records)
    // ─────────────────────────────────────────────

    /** 获取所有记录（按时间降序） */
    fun getAllRecords(context: Context): List<AccountingRecord> {
        return getDb(context).getAllRecords()
    }

    /** 按账本名获取记录 */
    fun getRecordsByBook(context: Context, bookName: String): List<AccountingRecord> {
        return getDb(context).getRecordsByBook(bookName)
    }

    /** 插入一条记录 */
    fun insertRecord(context: Context, record: AccountingRecord) {
        getDb(context).insertRecord(record)
    }

    /** 更新一条记录 */
    fun updateRecord(context: Context, record: AccountingRecord) {
        getDb(context).updateRecord(record)
    }

    /** 删除一条记录 */
    fun deleteRecord(context: Context, id: String) {
        getDb(context).deleteRecord(id)
    }

    /**
     * 整体替换记录表：使数据库与传入的列表完全同步。
     * 删除列表中不存在的记录，插入或更新列表中的记录。
     * 注意：此操作风险较高，仅在需要批量同步时使用。
     */
    fun replaceAllRecords(context: Context, records: List<AccountingRecord>) {
        val db = getDb(context)
        val sqlDb = db.writableDatabase
        sqlDb.beginTransaction()
        try {
            // 找出需要删除的记录
            val currentIds = records.map { it.id }.toSet()
            val existingIds = mutableSetOf<String>()
            val cursor = sqlDb.rawQuery("SELECT id FROM records", null)
            try {
                while (cursor.moveToNext()) existingIds.add(cursor.getString(0))
            } finally {
                cursor.close()
            }
            for (id in existingIds - currentIds) {
                sqlDb.delete("records", "id = ?", arrayOf(id))
            }
            // 插入或更新所有记录
            for (record in records) {
                val cv = ContentValues().apply {
                    put("id", record.id)
                    put("book_name", record.bookName)
                    put("type", record.type)
                    put("amount", record.amount)
                    put("category_id", record.categoryId)
                    if (record.subcategoryId != null) put("subcategory_id", record.subcategoryId) else putNull("subcategory_id")
                    put("note", record.note)
                    put("happened_at", record.happenedAt)
                    if (record.accountId != null) put("account_id", record.accountId) else putNull("account_id")
                    if (record.discountBefore != null) put("discount_before", record.discountBefore) else putNull("discount_before")
                }
                sqlDb.insertWithOnConflict("records", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            sqlDb.setTransactionSuccessful()
        } finally {
            sqlDb.endTransaction()
        }
    }

    // ─────────────────────────────────────────────
    // 账户 (Accounts)
    // ─────────────────────────────────────────────

    /** 获取所有账户 */
    fun getAllAccounts(context: Context): List<AccountingAccount> {
        return getDb(context).getAllAccounts()
    }

    /** 按分类获取账户（tradable / valuation） */
    fun getAccountsByCategory(context: Context, category: String): List<AccountingAccount> {
        return getDb(context).getAccountsByCategory(category)
    }

    /** 插入一个账户 */
    fun insertAccount(context: Context, account: AccountingAccount) {
        getDb(context).insertAccount(account)
    }

    /** 更新一个账户 */
    fun updateAccount(context: Context, account: AccountingAccount) {
        getDb(context).updateAccount(account)
    }

    /** 删除一个账户 */
    fun deleteAccount(context: Context, id: String) {
        getDb(context).deleteAccount(id)
    }

    // ─────────────────────────────────────────────
    // SharedPreferences: 记账偏好
    // ─────────────────────────────────────────────

    /** 获取上次使用的账户 ID */
    fun getLastAccountId(context: Context): String? {
        return context.getSharedPreferences(AppDataPaths.PREFS_ACCOUNTING, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ACCOUNT_ID, null)
    }

    /** 保存上次使用的账户 ID */
    fun setLastAccountId(context: Context, accountId: String) {
        context.getSharedPreferences(AppDataPaths.PREFS_ACCOUNTING, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_ACCOUNT_ID, accountId).apply()
    }

    // ─────────────────────────────────────────────
    // 记账本管理（存储在 settings 表）
    // ─────────────────────────────────────────────

    private const val KEY_BOOK_LIST = "accounting_books"
    private const val KEY_LAST_BOOK = "last_book_name"
    private const val DEFAULT_BOOK = "默认记账本"

    /** 获取所有记账本名称列表（至少包含默认记账本） */
    fun getBookList(context: Context): List<String> {
        val json = getSetting(context, KEY_BOOK_LIST)
        if (json != null) {
            try {
                val list = Json.decodeFromString<List<String>>(json)
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {}
        }
        return listOf(DEFAULT_BOOK)
    }

    /** 保存记账本名称列表 */
    fun setBookList(context: Context, books: List<String>) {
        setSetting(context, KEY_BOOK_LIST, Json.encodeToString(books))
    }

    /** 添加一个新记账本（如果不存在） */
    fun addBook(context: Context, bookName: String): Boolean {
        val books = getBookList(context).toMutableList()
        if (books.contains(bookName)) return false
        books.add(bookName)
        setBookList(context, books)
        return true
    }

    /** 获取上次使用的记账本名称 */
    fun getLastBookName(context: Context): String {
        return getSetting(context, KEY_LAST_BOOK) ?: DEFAULT_BOOK
    }

    /** 保存上次使用的记账本名称 */
    fun setLastBookName(context: Context, bookName: String) {
        setSetting(context, KEY_LAST_BOOK, bookName)
    }

    // ─────────────────────────────────────────────
    // 内部工具
    // ─────────────────────────────────────────────

    private fun getDb(context: Context): AccountingDatabase {
        return AccountingDatabase.getInstance(context)
    }
}
