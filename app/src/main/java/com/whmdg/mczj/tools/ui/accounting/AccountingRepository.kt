package com.whmdg.mczj.tools.ui.accounting

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.whmdg.mczj.tools.AppDataPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

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

    /** 插入一条记录（增量维护记账天数） */
    fun insertRecord(context: Context, record: AccountingRecord) {
        val db = getDb(context)
        // 检查当天是否已有记录（插入前）
        if (!isDateHasRecords(context, record.bookName, record.happenedAt)) {
            // 新的一天，天数 +1
            setSetting(context, KEY_DAY_COUNT, (getDayCount(context) + 1).toString())
        }
        db.insertRecord(record)
    }

    /** 更新一条记录 */
    fun updateRecord(context: Context, record: AccountingRecord) {
        getDb(context).updateRecord(record)
    }

    /** 删除一条记录（增量维护记账天数） */
    fun deleteRecord(context: Context, id: String) {
        val db = getDb(context)
        // 先查出被删记录的信息
        val record = getRecordById(context, id)
        if (record != null) {
            // 检查是否为当天最后一笔（排除自身）
            if (!isDateHasOtherRecords(context, record.bookName, record.happenedAt, id)) {
                // 当天最后一笔，天数 -1
                val newCount = maxOf(getDayCount(context) - 1, 0)
                setSetting(context, KEY_DAY_COUNT, newCount.toString())
            }
        }
        db.deleteRecord(id)
    }

    /** 按 ID 查单条记录 */
    private fun getRecordById(context: Context, id: String): AccountingRecord? {
        val sqlDb = getDb(context).readableDatabase
        val cursor = sqlDb.rawQuery(
            "SELECT id, book_name, type, amount, category_id, subcategory_id, note, happened_at, account_id, discount_before, reimbursement_account_id FROM records WHERE id = ?",
            arrayOf(id)
        )
        return try {
            if (cursor.moveToFirst()) {
                AccountingRecord(
                    id = cursor.getString(0),
                    bookName = cursor.getString(1),
                    type = cursor.getString(2),
                    amount = cursor.getString(3),
                    categoryId = cursor.getString(4),
                    subcategoryId = cursor.getString(5),
                    note = cursor.getString(6),
                    happenedAt = cursor.getLong(7),
                    accountId = cursor.getString(8),
                    discountBefore = cursor.getString(9),
                    reimbursementAccountId = cursor.getString(10)
                )
            } else null
        } finally {
            cursor.close()
        }
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
                    if (record.reimbursementAccountId != null) put("reimbursement_account_id", record.reimbursementAccountId) else putNull("reimbursement_account_id")
                }
                sqlDb.insertWithOnConflict("records", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            sqlDb.setTransactionSuccessful()
        } finally {
            sqlDb.endTransaction()
        }
    }

    // ─────────────────────────────────────────────
    // 记账天数 (Day Count — 增量维护)
    // ─────────────────────────────────────────────

    private const val KEY_DAY_COUNT = "accounting_day_count"

    /** 获取记账天数（首次调用时全量计算并缓存） */
    fun getDayCount(context: Context): Int {
        val cached = getSetting(context, KEY_DAY_COUNT)
        if (cached != null) return cached.toIntOrNull() ?: 0
        // 首次：全量计算
        val count = calcDayCountAll(context)
        setSetting(context, KEY_DAY_COUNT, count.toString())
        return count
    }

    /** 全量计算所有记录的记账天数（按自然日去重） */
    private fun calcDayCountAll(context: Context): Int {
        val db = getDb(context)
        val sqlDb = db.readableDatabase
        val cursor = sqlDb.rawQuery("SELECT happened_at FROM records ORDER BY happened_at ASC", null)
        val cal = java.util.Calendar.getInstance()
        var count = 0
        var lastDay = -1
        try {
            while (cursor.moveToNext()) {
                val ts = cursor.getLong(0)
                cal.timeInMillis = ts
                val day = cal.get(java.util.Calendar.YEAR) * 10000 +
                        cal.get(java.util.Calendar.MONTH) * 100 +
                        cal.get(java.util.Calendar.DAY_OF_MONTH)
                if (day != lastDay) {
                    count++
                    lastDay = day
                }
            }
        } finally {
            cursor.close()
        }
        return count
    }

    /** 指定账本指定日期是否已有记录 */
    private fun isDateHasRecords(context: Context, bookName: String, happenedAt: Long): Boolean {
        val (start, end) = dayRange(happenedAt)
        val sqlDb = getDb(context).readableDatabase
        val cursor = sqlDb.rawQuery(
            "SELECT 1 FROM records WHERE book_name = ? AND happened_at BETWEEN ? AND ? LIMIT 1",
            arrayOf(bookName, start.toString(), end.toString())
        )
        return try { cursor.moveToFirst() } finally { cursor.close() }
    }

    /** 指定账本指定日期是否存在除 excludeId 以外的记录 */
    private fun isDateHasOtherRecords(context: Context, bookName: String, happenedAt: Long, excludeId: String): Boolean {
        val (start, end) = dayRange(happenedAt)
        val sqlDb = getDb(context).readableDatabase
        val cursor = sqlDb.rawQuery(
            "SELECT 1 FROM records WHERE book_name = ? AND happened_at BETWEEN ? AND ? AND id != ? LIMIT 1",
            arrayOf(bookName, start.toString(), end.toString(), excludeId)
        )
        return try { cursor.moveToFirst() } finally { cursor.close() }
    }

    /** 将时间戳拆为当天 00:00:00.000 ~ 23:59:59.999 的毫秒范围 */
    private fun dayRange(happenedAt: Long): Pair<Long, Long> {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = happenedAt }
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH)
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val start = java.util.Calendar.getInstance().apply {
            set(year, month, day, 0, 0, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val end = java.util.Calendar.getInstance().apply {
            set(year, month, day, 23, 59, 59); set(java.util.Calendar.MILLISECOND, 999)
        }
        return start.timeInMillis to end.timeInMillis
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
    // 头像 & 签名 (Mine Page)
    // ─────────────────────────────────────────────

    private const val KEY_AVATAR_PATH = "mine_avatar_path"
    private const val KEY_NICKNAME = "mine_nickname"
    private const val KEY_USERNAME = "mine_username"
    private const val DEFAULT_USERNAME = "默认用户名"
    private const val DEFAULT_NICKNAME = "记一笔流水账，守一份岁月长"

    /** 获取头像绝对路径（验证文件存在才返回） */
    fun getAvatarPath(context: Context): String? {
        val relative = getSetting(context, KEY_AVATAR_PATH) ?: return null
        val absolute = File(AppDataPaths.accounting(context), relative)
        return if (absolute.exists()) absolute.absolutePath else null
    }

    /** 从 URI 保存头像到 avatars/ 目录，返回绝对路径 */
    fun saveAvatar(context: Context, uri: Uri): String? {
        val avatarDir = File(AppDataPaths.accounting(context), "avatars")
        if (!avatarDir.exists()) avatarDir.mkdirs()

        // 删除旧头像
        deleteAvatar(context)

        val timestamp = System.currentTimeMillis()
        val ext = getExtensionFromUri(context, uri) ?: "jpg"
        val fileName = "avatar_${timestamp}.$ext"
        val target = File(avatarDir, fileName)

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (_: Exception) {
            return null
        }

        val relative = "avatars/$fileName"
        setSetting(context, KEY_AVATAR_PATH, relative)
        return target.absolutePath
    }

    /** 删除头像文件和 SP 记录 */
    fun deleteAvatar(context: Context) {
        val path = getAvatarPath(context)
        if (path != null) {
            File(path).delete()
        }
        setSetting(context, KEY_AVATAR_PATH, "")
    }

    /** 获取用户签名（默认诗意短句） */
    fun getNickname(context: Context): String {
        return getSetting(context, KEY_NICKNAME) ?: DEFAULT_NICKNAME
    }

    /** 设置用户签名 */
    fun setNickname(context: Context, name: String) {
        setSetting(context, KEY_NICKNAME, name)
    }

    /** 获取用户名（默认 "默认用户名"） */
    fun getUsername(context: Context): String {
        return getSetting(context, KEY_USERNAME) ?: DEFAULT_USERNAME
    }

    /** 设置用户名 */
    fun setUsername(context: Context, name: String) {
        setSetting(context, KEY_USERNAME, name)
    }

    /** 从 URI 推断文件扩展名 */
    private fun getExtensionFromUri(context: Context, uri: Uri): String? {
        val mimeType = context.contentResolver.getType(uri)
        return when {
            mimeType?.contains("png") == true -> "png"
            mimeType?.contains("webp") == true -> "webp"
            mimeType?.contains("gif") == true -> "gif"
            else -> "jpg"
        }
    }

    // ─────────────────────────────────────────────
    // 报销账户
    // ─────────────────────────────────────────────

    private const val KEY_REIMBURSEMENT_ACCOUNTS = "reimbursement_accounts"

    /** 保存报销账户（新增） */
    fun saveReimbursementAccount(context: Context, account: ReimbursementAccountEntity) {
        val list = getReimbursementAccounts(context).toMutableList()
        list.add(account)
        setSetting(context, KEY_REIMBURSEMENT_ACCOUNTS, Json.encodeToString(list))
    }

    /** 获取全部报销账户 */
    fun getReimbursementAccounts(context: Context): List<ReimbursementAccountEntity> {
        val json = getSetting(context, KEY_REIMBURSEMENT_ACCOUNTS)
        if (json != null) {
            try {
                return Json.decodeFromString<List<ReimbursementAccountEntity>>(json)
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    /** 检查名称是否已存在 */
    fun isReimbursementAccountNameExists(context: Context, name: String): Boolean {
        return getReimbursementAccounts(context).any { it.name == name }
    }

    // ─────────────────────────────────────────────
    // 内部工具
    // ─────────────────────────────────────────────

    private fun getDb(context: Context): AccountingDatabase {
        return AccountingDatabase.getInstance(context)
    }
}

/** 报销账户持久化数据 */
@Serializable
data class ReimbursementAccountEntity(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val note: String = "",
    val iconPath: String? = null,
    val allBooks: Boolean = true,
    val selectedBooks: List<String> = emptyList(),
    val groupName: String = "报销",
    val createdAt: Long = System.currentTimeMillis()
)
