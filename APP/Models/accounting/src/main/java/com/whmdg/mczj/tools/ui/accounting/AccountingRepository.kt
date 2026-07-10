package com.whmdg.mczj.tools.ui.accounting

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
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

    /** 获取记账本数据目录 */
    fun getAccountingDir(context: Context): File = AppDataPaths.accounting(context)

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

    /** 获取所有分类（扁平列表：id, name, parentId） */
    fun getAllCategoriesFlat(context: Context): List<Triple<String, String, String?>> {
        return getDb(context).getAllCategoriesFlat()
    }

    /** 创建一级分类，返回 ID */
    fun createParentCategory(context: Context, name: String, type: String = "支出"): String {
        return getDb(context).createParentCategory(name, type)
    }

    /** 创建二级分类，返回 ID */
    fun createChildCategory(context: Context, name: String, parentId: String, type: String = "支出"): String {
        return getDb(context).createChildCategory(name, parentId, type)
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

    /** 获取所有不重复的非空备注（按最近使用倒序） */
    fun getAllNotes(context: Context): List<String> {
        val db = getDb(context).readableDatabase
        val cursor = db.rawQuery(
            "SELECT DISTINCT note FROM records WHERE note != '' ORDER BY happenedAt DESC", null
        )
        val notes = mutableListOf<String>()
        while (cursor.moveToNext()) {
            notes.add(cursor.getString(0))
        }
        cursor.close()
        return notes
    }

    /**
     * 记录写入的唯一入口。
     * 所有 INSERT/UPDATE/DELETE 操作必须经由此方法，确保月度汇总自动维护。
     */
    private fun writeRecord(
        context: Context,
        record: AccountingRecord,
        operation: String,        // "INSERT" / "UPDATE" / "DELETE"
        oldMonth: Triple<String, Int, Int>? = null  // UPDATE 时旧记录的 (bookName, year, month)
    ) {
        val db = getDb(context)
        when (operation) {
            "INSERT" -> db.insertRecord(record)
            "UPDATE" -> db.updateRecord(record)
            "DELETE" -> db.deleteRecord(record.id)
        }
        // 自动维护月度收支汇总
        db.recalculateMonthSummary(record.bookName, record.year, record.month)
        // UPDATE 跨月时，旧月也需要重算
        if (oldMonth != null && (oldMonth.first != record.bookName || oldMonth.second != record.year || oldMonth.third != record.month)) {
            db.recalculateMonthSummary(oldMonth.first, oldMonth.second, oldMonth.third)
        }
    }

    /**
     * 保存一条记录（新建 + 编辑统一入口）。
     * 自动判断：record 已存在 → 编辑（先回退旧余额再应用新余额）；不存在 → 新建。
     * 同步维护：记账天数、月度汇总、账户余额、每笔账单余额、附件回收站级联。
     */
    fun saveRecord(context: Context, record: AccountingRecord) {
        val db = getDb(context)
        val oldRecord = getRecordById(context, record.id)
        val isEdit = oldRecord != null

        val cal = java.util.Calendar.getInstance().apply { timeInMillis = record.happenedAt }
        val y = cal.get(java.util.Calendar.YEAR)
        val m = cal.get(java.util.Calendar.MONTH) + 1
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)

        // 新建：增量维护记账天数
        if (!isEdit && !isDateHasRecords(context, y, m, d)) {
            setSetting(context, KEY_DAY_COUNT, (getDayCount(context) + 1).toString())
        }

        val now = System.currentTimeMillis()

        // 计算此笔账单的滚动余额
        val computedBalance = if (record.accountId != null) {
            val accCursor = db.readableDatabase.rawQuery(
                "SELECT initial_amount FROM accounts WHERE id = ?", arrayOf(record.accountId)
            )
            val initialAmount = try {
                if (accCursor.moveToFirst()) accCursor.getDouble(0) else 0.0
            } finally { accCursor.close() }

            val prevBalance = db.getPreviousBalance(record.accountId, record.happenedAt, record.id)
                ?: initialAmount

            val delta = db.computeRecordDeltaPublic(record)
            prevBalance + delta
        } else {
            0.0
        }

        val withTime = record.copy(year = y, month = m, day = d,
            createdAt = record.createdAt ?: now,
            updatedAt = if (isEdit) now else (record.updatedAt ?: now),
            balance = computedBalance)

        val oldMonth = oldRecord?.let { Triple(it.bookName, it.year, it.month) }
        writeRecord(context, withTime, if (isEdit) "UPDATE" else "INSERT", oldMonth)

        // 编辑：先回退旧记录的余额影响
        if (oldRecord != null) {
            updateAccountBalanceOnRecordChange(context, oldRecord, isAdd = false)
        }
        // 应用新记录的余额影响
        updateAccountBalanceOnRecordChange(context, withTime, isAdd = true)

        // 编辑：从该记录开始重算后续账单的余额（增量级联）
        if (isEdit) {
            db.recalculateBalancesFromRecord(context, record.id)
        }

        autoSyncIfNeeded(context)
    }

    /** 仅更新记录的地址字段（异步定位完成后调用） */
    fun updateRecordAddress(context: Context, recordId: String, address: String) {
        val db = getDb(context).writableDatabase
        db.execSQL(
            "UPDATE records SET address = ?, updated_at = ? WHERE id = ?",
            arrayOf(address, System.currentTimeMillis(), recordId)
        )
    }

    /** 删除一条记录（增量维护记账天数 + 附件回收站级联 + 月度汇总自动更新 + 余额级联重算） */
    fun deleteRecord(context: Context, id: String) {
        val record = getRecordById(context, id)
        if (record != null) {
            if (!isDateHasOtherRecords(context, record.year, record.month, record.day, id)) {
                val newCount = maxOf(getDayCount(context) - 1, 0)
                setSetting(context, KEY_DAY_COUNT, newCount.toString())
            }
            if (record.attachments.isNotEmpty()) {
                moveToTrashBatch(context, record.attachments, id)
            }
            cascadeUpdateTrashStatus(context, id, "deleted")
            updateAccountBalanceOnRecordChange(context, record, isAdd = false)
            // 先重算后续账单余额（记录还存在），再删除
            if (record.accountId != null) {
                getDb(context).recalculateBalancesFromRecord(context, id)
            }
            writeRecord(context, record, "DELETE")
            autoSyncIfNeeded(context)
        }
    }

    /** 记录变更时同步更新账户当前余额 */
    private fun updateAccountBalanceOnRecordChange(
        context: Context, record: AccountingRecord, isAdd: Boolean
    ) {
        val amountVal = record.amount.toDoubleOrNull() ?: return
        val sign = if (isAdd) 1.0 else -1.0
        val db = getDb(context).writableDatabase
        val now = System.currentTimeMillis()
        when (record.type) {
            "收入" -> {
                if (record.accountId == null) return
                db.execSQL("UPDATE accounts SET current_balance = current_balance + ?, updated_at = ? WHERE id = ?",
                    arrayOf(amountVal * sign, now, record.accountId))
            }
            "支出" -> {
                if (record.accountId == null) return
                // 优先用 reimburseAfterAmount（报销后实际净支出）
                val effectiveAmount = record.reimburseAfterAmount?.toDoubleOrNull() ?: amountVal
                db.execSQL("UPDATE accounts SET current_balance = current_balance - ?, updated_at = ? WHERE id = ?",
                    arrayOf(effectiveAmount * sign, now, record.accountId))
            }
            "转账" -> {
                // 转出账户减少，转入账户增加
                if (record.accountId != null) {
                    db.execSQL("UPDATE accounts SET current_balance = current_balance - ?, updated_at = ? WHERE id = ?",
                        arrayOf(amountVal * sign, now, record.accountId))
                }
                if (record.targetAccountId != null) {
                    db.execSQL("UPDATE accounts SET current_balance = current_balance + ?, updated_at = ? WHERE id = ?",
                        arrayOf(amountVal * sign, now, record.targetAccountId))
                }
            }
            "存款" -> {
                // 存款：资金从账户流出（类似支出）
                if (record.accountId == null) return
                db.execSQL("UPDATE accounts SET current_balance = current_balance - ?, updated_at = ? WHERE id = ?",
                    arrayOf(amountVal * sign, now, record.accountId))
            }
            "调整" -> {
                // 手动调整：amount 存储的是差值（可正可负），直接加减
                if (record.accountId == null) return
                db.execSQL("UPDATE accounts SET current_balance = current_balance + ?, updated_at = ? WHERE id = ?",
                    arrayOf(amountVal * sign, now, record.accountId))
            }
        }
    }

    /** 按 ID 查单条记录 */
    fun getRecordById(context: Context, id: String): AccountingRecord? {
        val sqlDb = getDb(context).readableDatabase
        val cursor = sqlDb.rawQuery(
            "SELECT id, book_name, type, amount, category_id, subcategory_id, note, happened_at, year, month, day, account_id, target_account_id, discount_before, discount_off, discount_after, reimbursement_account_id, attachments, exclude_from_stats, exclude_from_budget, reimburse_status, reimburse_amount, reimburse_after_amount, refund_amount, address, created_at, updated_at, category_name, subcategory_name, transaction_id, merchant_order_id, balance FROM records WHERE id = ?",
            arrayOf(id)
        )
        return try {
            if (cursor.moveToFirst()) {
                val attachmentsJson = cursor.getString(16)
                val attachments = if (!attachmentsJson.isNullOrEmpty()) {
                    try { Json.decodeFromString<List<AttachmentInfo>>(attachmentsJson) } catch (_: Exception) { emptyList() }
                } else emptyList()
                AccountingRecord(
                    id = cursor.getString(0),
                    bookName = cursor.getString(1),
                    type = cursor.getString(2),
                    amount = cursor.getString(3),
                    categoryId = cursor.getString(4),
                    subcategoryId = cursor.getString(5),
                    note = cursor.getString(6),
                    happenedAt = cursor.getLong(7),
                    year = cursor.getInt(8),
                    month = cursor.getInt(9),
                    day = cursor.getInt(10),
                    accountId = cursor.getString(11),
                    targetAccountId = cursor.getString(12),
                    discountBefore = cursor.getString(13),
                    discountOff = cursor.getString(14),
                    discountAfter = cursor.getString(15),
                    reimbursementAccountId = cursor.getString(16),
                    attachments = attachments,
                    excludeFromStats = cursor.getInt(18) == 1,
                    excludeFromBudget = cursor.getInt(19) == 1,
                    reimburseStatus = cursor.getInt(20) == 1,
                    reimburseAmount = cursor.getDouble(21),
                    reimburseAfterAmount = cursor.getString(22),
                    refundAmount = cursor.getDouble(23),
                    address = cursor.getString(24) ?: "",
                    createdAt = if (cursor.isNull(25)) null else cursor.getLong(25),
                    updatedAt = if (cursor.isNull(26)) null else cursor.getLong(26),
                    categoryName = cursor.getString(27) ?: "",
                    subcategoryName = cursor.getString(28),
                    transactionId = cursor.getString(29) ?: "",
                    merchantOrderId = cursor.getString(30) ?: "",
                    balance = if (cursor.columnCount > 31 && !cursor.isNull(31)) cursor.getDouble(31) else 0.0
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
                // 从 happenedAt 提取 year/month/day
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = record.happenedAt }
                val y = cal.get(java.util.Calendar.YEAR)
                val m = cal.get(java.util.Calendar.MONTH) + 1
                val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
                val cv = ContentValues().apply {
                    put("id", record.id)
                    put("book_name", record.bookName)
                    put("type", record.type)
                    put("amount", record.amount)
                    put("category_id", record.categoryId)
                    if (record.subcategoryId != null) put("subcategory_id", record.subcategoryId) else putNull("subcategory_id")
                    put("note", record.note)
                    put("happened_at", record.happenedAt)
                    put("year", y)
                    put("month", m)
                    put("day", d)
                    if (record.accountId != null) put("account_id", record.accountId) else putNull("account_id")
                    if (record.discountBefore != null) put("discount_before", record.discountBefore) else putNull("discount_before")
                    if (record.discountOff != null) put("discount_off", record.discountOff) else putNull("discount_off")
                    if (record.discountAfter != null) put("discount_after", record.discountAfter) else putNull("discount_after")
                    if (record.reimbursementAccountId != null) put("reimbursement_account_id", record.reimbursementAccountId) else putNull("reimbursement_account_id")
                    if (record.attachments.isNotEmpty()) {
                        put("attachments", Json.encodeToString(record.attachments))
                    } else {
                        putNull("attachments")
                    }
                    put("exclude_from_stats", if (record.excludeFromStats) 1 else 0)
                    put("exclude_from_budget", if (record.excludeFromBudget) 1 else 0)
                    put("reimburse_status", if (record.reimburseStatus) 1 else 0)
                    put("reimburse_amount", record.reimburseAmount)
                    if (record.reimburseAfterAmount != null) put("reimburse_after_amount", record.reimburseAfterAmount) else putNull("reimburse_after_amount")
                    put("refund_amount", record.refundAmount)
                    put("address", record.address)
                    if (record.createdAt != null) put("created_at", record.createdAt) else putNull("created_at")
                    if (record.updatedAt != null) put("updated_at", record.updatedAt) else putNull("updated_at")
                }
                sqlDb.insertWithOnConflict("records", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            sqlDb.setTransactionSuccessful()
        } finally {
            sqlDb.endTransaction()
        }
        // 重算所有涉及月份的收支汇总
        val affectedMonths = records.map { Triple(it.bookName, it.year, it.month) }.distinct()
        for ((book, y, m) in affectedMonths) {
            db.recalculateMonthSummary(book, y, m)
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
        val cursor = sqlDb.rawQuery("SELECT COUNT(DISTINCT year * 10000 + month * 100 + day) FROM records WHERE year > 0", null)
        return try {
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        } finally {
            cursor.close()
        }
    }

    /** 指定账本指定日期是否已有记录 */
    /** 任意账本在指定日期是否存在记录 */
    private fun isDateHasRecords(context: Context, year: Int, month: Int, day: Int): Boolean {
        val sqlDb = getDb(context).readableDatabase
        val cursor = sqlDb.rawQuery(
            "SELECT 1 FROM records WHERE year = ? AND month = ? AND day = ? LIMIT 1",
            arrayOf(year.toString(), month.toString(), day.toString())
        )
        return try { cursor.moveToFirst() } finally { cursor.close() }
    }

    /** 任意账本在指定日期是否存在除 excludeId 以外的记录 */
    private fun isDateHasOtherRecords(context: Context, year: Int, month: Int, day: Int, excludeId: String): Boolean {
        val sqlDb = getDb(context).readableDatabase
        val cursor = sqlDb.rawQuery(
            "SELECT 1 FROM records WHERE year = ? AND month = ? AND day = ? AND id != ? LIMIT 1",
            arrayOf(year.toString(), month.toString(), day.toString(), excludeId)
        )
        return try { cursor.moveToFirst() } finally { cursor.close() }
    }

    // ─────────────────────────────────────────────
    // 月度收支汇总（自动维护，业务代码只读）
    // ─────────────────────────────────────────────

    /** 获取指定账本指定月的收支汇总 */
    fun getMonthSummary(context: Context, bookName: String, year: Int, month: Int): MonthlySummary {
        return getDb(context).getMonthSummary(bookName, year, month)
    }

    /** 获取指定账本所有月度汇总（按年月降序） */
    fun getAllMonthSummaries(context: Context, bookName: String): List<MonthlySummary> {
        return getDb(context).getAllMonthSummaries(bookName)
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
    // 数据导出
    // ─────────────────────────────────────────────

    /** 导出全部记账数据为 JSON 文件到 Downloads 目录，返回文件路径 */
    fun exportData(context: Context): String {
        val json = getDb(context).exportToJson()
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val fileName = "记账本备份_${timestamp}.json"
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val file = java.io.File(dir, fileName)
        file.writeText(json, Charsets.UTF_8)
        return file.absolutePath
    }

    /** 从 JSON 文件导入全部记账数据（替换现有数据） */
    fun importData(context: Context, uri: Uri) {
        val json = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalArgumentException("无法读取文件。")
        getDb(context).importFromJson(json)
    }

    /** 校验 JSON 文件格式是否正确（不执行导入） */
    fun validateImportData(context: Context, uri: Uri) {
        val json = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalArgumentException("无法读取文件。")
        getDb(context).validateImportData(json)
    }

    /** 导出全部记账记录为 CSV 文件到 Downloads 目录，返回文件路径 */
    fun exportCsv(context: Context): String {
        val csv = getDb(context).exportToCsv()
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val fileName = "记账本备份_${timestamp}.csv"
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val file = java.io.File(dir, fileName)
        file.writeText(csv, Charsets.UTF_8)
        return file.absolutePath
    }

    /** 从 CSV 文件导入记账记录 */
    fun importCsv(context: Context, uri: Uri, appendMode: Boolean = false) {
        val csv = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalArgumentException("无法读取文件。")
        getDb(context).importFromCsv(csv, appendMode)
    }

    /** 导入后重算账户余额 */
    fun recalculateBalances(context: Context, replaceMode: Boolean) {
        getDb(context).recalculateBalances(replaceMode)
    }

    /** 全量重算所有账单的 balance 字段（滚动余额） */
    fun recalculateAllBalances(context: Context) {
        val db = getDb(context)
        db.recalculateAllBalances(db.writableDatabase)
    }

    /** 获取指定账户最近一笔账单的余额（账户详情卡片显示用），无记录时返回 null */
    fun getLatestBalance(context: Context, accountId: String): Double? {
        return getDb(context).getLatestBalance(accountId)
    }

    /** 导入后全量重算报销统计 */
    fun recalculateReimburseTotals(context: Context) {
        getDb(context).recalculateReimburseTotals()
    }

    /** 获取报销统计：Pair(可报销总额, 已报销总额) */
    fun getReimburseTotals(context: Context): Pair<Double, Double> {
        val pending = getSetting(context, "reimburse_pending_total")?.toDoubleOrNull() ?: 0.0
        val done = getSetting(context, "reimburse_done_total")?.toDoubleOrNull() ?: 0.0
        return pending to done
    }

    /** 修复含 AUTO 的分类标签，返回修复条数 */
    fun repairCategoryLabels(context: Context): Int {
        val db = getDb(context).writableDatabase
        val cursor = db.rawQuery(
            "SELECT id, category_name, subcategory_name FROM records WHERE category_name LIKE '%AUTO%' OR subcategory_name LIKE '%AUTO%'",
            null
        )
        val records = mutableListOf<Triple<String, String?, String?>>()
        try {
            while (cursor.moveToNext()) {
                records.add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
            }
        } finally {
            cursor.close()
        }
        if (records.isEmpty()) return 0

        // 收集有效的 (categoryName, subcategoryName) 配对
        val validPairs = mutableMapOf<String, String>()       // subCatName -> catName
        val validPairsRev = mutableMapOf<String, String>()    // catName -> subCatName
        val allCursor = db.rawQuery("SELECT category_name, subcategory_name FROM records", null)
        try {
            while (allCursor.moveToNext()) {
                val cName = allCursor.getString(0) ?: continue
                val sName = allCursor.getString(1) ?: continue
                if (!cName.contains("AUTO", ignoreCase = true) && !sName.contains("AUTO", ignoreCase = true)) {
                    validPairs.getOrPut(sName) { cName }
                    validPairsRev.getOrPut(cName) { sName }
                }
            }
        } finally {
            allCursor.close()
        }

        var fixed = 0
        for ((id, catName, subCatName) in records) {
            val catBad = catName != null && catName.contains("AUTO", ignoreCase = true)
            val subBad = subCatName != null && subCatName.contains("AUTO", ignoreCase = true)
            val newCatName: String? = when {
                catBad && !subBad -> subCatName?.let { validPairs[it] }
                else -> null
            }
            val newSubName: String? = when {
                subBad && !catBad -> catName?.let { validPairsRev[it] }
                else -> null
            }
            if (newCatName != null || newSubName != null) {
                db.execSQL(
                    "UPDATE records SET category_name = COALESCE(?, category_name), subcategory_name = ? WHERE id = ?",
                    arrayOf(newCatName, if (newSubName != null) newSubName else subCatName, id)
                )
                fixed++
            }
        }
        return fixed
    }

    /** 校验 CSV 文件格式是否正确（不执行导入） */
    fun validateImportCsv(context: Context, uri: Uri) {
        val csv = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalArgumentException("无法读取文件。")
        getDb(context).validateImportCsv(csv)
    }

    /** 从 CSV 文本导入记录（带列映射和分类映射），返回成功条数 */
    fun importCsvWithMapping(
        context: Context,
        csvText: String,
        columnMapping: Map<String, Int?>,
        categoryMapping: Map<String, String?>,
        replaceMode: Boolean
    ): Int {
        return getDb(context).importFromCsvWithMapping(csvText, columnMapping, categoryMapping, replaceMode)
    }

    // ─────────────────────────────────────────────
    // ─────────────────────────────────────────────
    // 附件 (Attachments)
    // ─────────────────────────────────────────────

    /** 从 URI 复制文件到附件目录，返回元信息 */
    fun storeAttachment(context: Context, uri: Uri, originalName: String?): AttachmentInfo {
        val id = java.util.UUID.randomUUID().toString()
        val ext = originalName?.substringAfterLast('.', "")?.let { if (it.isNotEmpty()) ".$it" else "" } ?: ""
        val storedFileName = "${id}$ext"
        val attachDir = AppDataPaths.accountingAttachments(context)
        val target = File(attachDir, storedFileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val fileName = originalName ?: storedFileName
        return AttachmentInfo(
            id = id,
            fileName = fileName,
            mimeType = mimeType,
            storedFileName = storedFileName
        )
    }

    /** 拍照专用：从 FileProvider URI 复制到附件目录 */
    fun storeAttachmentFromCamera(context: Context, photoUri: Uri): AttachmentInfo {
        val id = java.util.UUID.randomUUID().toString()
        val storedFileName = "${id}.jpg"
        val attachDir = AppDataPaths.accountingAttachments(context)
        val target = File(attachDir, storedFileName)
        context.contentResolver.openInputStream(photoUri)?.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        return AttachmentInfo(
            id = id,
            fileName = "拍照_$storedFileName",
            mimeType = "image/jpeg",
            storedFileName = storedFileName
        )
    }

    /** 删除磁盘上的附件文件 */
    fun deleteAttachmentFile(context: Context, storedFileName: String) {
        val file = File(AppDataPaths.accountingAttachments(context), storedFileName)
        if (file.exists()) file.delete()
    }

    /** 获取附件 File 对象 */
    fun getAttachmentFile(context: Context, storedFileName: String): File {
        return File(AppDataPaths.accountingAttachments(context), storedFileName)
    }

    // ── 附件回收站 ──────────────────────────────────

    /** 将附件移入回收站（软删除）：文件保留在磁盘，元信息写入 attachment_trash 表 */
    fun moveToTrash(context: Context, attachment: AttachmentInfo, recordId: String) {
        val entry = AttachmentTrashEntry(
            attachment = attachment,
            originalRecordId = recordId,
            originalRecordStatus = "active"
        )
        getDb(context).insertTrashEntry(entry)
    }

    /** 批量移入回收站 */
    fun moveToTrashBatch(context: Context, attachments: List<AttachmentInfo>, recordId: String) {
        for (att in attachments) {
            moveToTrash(context, att, recordId)
        }
    }

    /** 获取所有回收站条目 */
    fun getAllTrashEntries(context: Context): List<AttachmentTrashEntry> {
        return getDb(context).getAllTrashEntries()
    }

    /** 获取指定账单关联的回收站条目 */
    fun getTrashEntriesByRecord(context: Context, recordId: String): List<AttachmentTrashEntry> {
        return getDb(context).getTrashEntriesByRecord(recordId)
    }

    /** 从回收站恢复附件：删除 trash 记录，返回附件元信息（调用方负责将其重新加回记录） */
    fun restoreFromTrash(context: Context, trashEntryId: String): AttachmentInfo? {
        val db = getDb(context)
        val entries = db.getAllTrashEntries()
        val entry = entries.find { it.id == trashEntryId } ?: return null
        db.deleteTrashEntry(trashEntryId)
        return entry.attachment
    }

    /** 永久删除回收站条目：同时删除磁盘文件和 DB 记录 */
    fun permanentlyDelete(context: Context, trashEntryId: String) {
        val db = getDb(context)
        val entries = db.getAllTrashEntries()
        val entry = entries.find { it.id == trashEntryId } ?: return
        deleteAttachmentFile(context, entry.attachment.storedFileName)
        db.deleteTrashEntry(trashEntryId)
    }

    /** 永久删除指定账单关联的所有回收站条目 */
    fun permanentlyDeleteByRecord(context: Context, recordId: String) {
        val db = getDb(context)
        val entries = db.getTrashEntriesByRecord(recordId)
        for (entry in entries) {
            deleteAttachmentFile(context, entry.attachment.storedFileName)
        }
        db.deleteTrashEntriesByRecord(recordId)
    }

    /** 级联更新：当账单被删除时，将关联回收站条目的 originalRecordStatus 改为 "deleted" */
    fun cascadeUpdateTrashStatus(context: Context, recordId: String, newStatus: String) {
        getDb(context).updateTrashEntryRecordStatus(recordId, newStatus)
    }

    // ─────────────────────────────────────────────
    // 云同步（WebDAV）
    // ─────────────────────────────────────────────

    private const val KEY_WEBDAV_URL = "webdav_url"
    private const val KEY_WEBDAV_USERNAME = "webdav_username"
    private const val KEY_WEBDAV_PASSWORD = "webdav_password"
    private const val KEY_WEBDAV_AUTO_SYNC = "webdav_auto_sync"
    private const val KEY_WEBDAV_LAST_SYNC = "webdav_last_sync"
    private const val KEY_WEBDAV_REMOTE_DIR = "webdav_remote_dir"

    fun getWebDavConfig(context: Context): Triple<String, String, String> {
        val url = getSetting(context, KEY_WEBDAV_URL) ?: ""
        val username = getSetting(context, KEY_WEBDAV_USERNAME) ?: ""
        val password = getSetting(context, KEY_WEBDAV_PASSWORD) ?: ""
        return Triple(url, username, password)
    }

    fun setWebDavConfig(context: Context, url: String, username: String, password: String) {
        setSetting(context, KEY_WEBDAV_URL, url)
        setSetting(context, KEY_WEBDAV_USERNAME, username)
        setSetting(context, KEY_WEBDAV_PASSWORD, password)
    }

    fun getWebDavAutoSync(context: Context): Boolean {
        return getSetting(context, KEY_WEBDAV_AUTO_SYNC) == "true"
    }

    fun setWebDavAutoSync(context: Context, enabled: Boolean) {
        setSetting(context, KEY_WEBDAV_AUTO_SYNC, if (enabled) "true" else "false")
    }

    fun getWebDavLastSync(context: Context): String? {
        return getSetting(context, KEY_WEBDAV_LAST_SYNC)
    }

    fun setWebDavLastSync(context: Context, timestamp: String) {
        setSetting(context, KEY_WEBDAV_LAST_SYNC, timestamp)
    }

    fun getWebDavRemoteDir(context: Context): String {
        return getSetting(context, KEY_WEBDAV_REMOTE_DIR) ?: "记账本备份"
    }

    fun setWebDavRemoteDir(context: Context, dir: String) {
        setSetting(context, KEY_WEBDAV_REMOTE_DIR, dir)
    }

    /** 构建 WebDavServerConfig 并注册到全局 Authenticator */
    private fun buildWebDavClient(context: Context): com.whmdg.mczj.tools.fileop.webdav.WebDavFileClient? {
        val (url, username, password) = getWebDavConfig(context)
        if (url.isBlank() || username.isBlank()) return null
        // 解析 URL：https://host:port/path
        val uri = java.net.URI(url)
        val scheme = uri.scheme ?: "https"
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        val protocol = if (scheme == "https") "davs" else "dav"
        val relativePath = uri.path?.trimStart('/')?.trimEnd('/') ?: ""
        val config = com.whmdg.mczj.tools.fileop.webdav.WebDavServerConfig(
            name = "记账云同步",
            protocol = protocol,
            host = host,
            port = port,
            username = username,
            password = password,
            authType = "password",
            relativePath = relativePath
        )
        // 注册到全局 Authenticator
        com.whmdg.mczj.tools.fileop.webdav.WebDavAuthenticator.addTransientServer(config)
        return com.whmdg.mczj.tools.fileop.webdav.WebDavFileClient(config)
    }

    /** 测试 WebDAV 连接 */
    fun testWebDavConnection(context: Context): String {
        return try {
            val client = buildWebDavClient(context) ?: return "请先填写服务器地址和账号"
            client.testConnection()
            "连接成功"
        } catch (e: Exception) {
            "连接失败：${e.message}"
        }
    }

    /** 上传记账数据到 WebDAV */
    fun uploadToWebDav(context: Context): String {
        return try {
            val client = buildWebDavClient(context) ?: return "请先配置 WebDAV"
            // 导出 JSON
            val json = getDb(context).exportToJson()
            // 写入临时文件
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val fileName = "记账本备份_${timestamp}.json"
            val tempFile = File(context.cacheDir, fileName)
            tempFile.writeText(json, Charsets.UTF_8)
            try {
                // 确保远程目录存在
                val remoteDir = getWebDavRemoteDir(context)
                try { client.mkdir(remoteDir) } catch (_: Exception) {}
                // 上传
                val remotePath = "$remoteDir/$fileName"
                client.uploadFile(tempFile, remotePath) {}
                // 记录同步时间
                val syncTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                setWebDavLastSync(context, syncTime)
                "上传成功：$fileName"
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            "上传失败：${e.message}"
        }
    }

    /** 从 WebDAV 下载并导入记账数据 */
    fun downloadFromWebDav(context: Context): String {
        return try {
            val client = buildWebDavClient(context) ?: return "请先配置 WebDAV"
            val remoteDir = getWebDavRemoteDir(context)
            // 列出远程文件
            val files = try {
                client.listChildren(remoteDir)
            } catch (_: Exception) {
                return "远程目录不存在或无法访问"
            }
            if (files.isNullOrEmpty()) return "云端没有备份文件"
            // 找最新的 json 文件
            val latest = files.filter { it.name.endsWith(".json") && !it.isDirectory }
                .maxByOrNull { it.lastModified } ?: return "云端没有备份文件"
            // 下载到临时文件
            val tempFile = File(context.cacheDir, latest.name)
            try {
                client.downloadFile(latest.remotePath, tempFile) {}
                // 导入
                val json = tempFile.readText(Charsets.UTF_8)
                getDb(context).importFromJson(json)
                val syncTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                setWebDavLastSync(context, syncTime)
                "下载成功：${latest.name}"
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            "下载失败：${e.message}"
        }
    }

    /** 自动同步（供记录变更后调用） */
    fun autoSyncIfNeeded(context: Context) {
        if (!getWebDavAutoSync(context)) return
        val (url, username, _) = getWebDavConfig(context)
        if (url.isBlank() || username.isBlank()) return
        Thread {
            uploadToWebDav(context)
        }.start()
    }

    // ─────────────────────────────────────────────
    // 定期存款 (Fixed Deposits)
    // ─────────────────────────────────────────────

    fun insertFixedDeposit(context: Context, deposit: FixedDeposit) {
        val db = getDb(context).writableDatabase
        val cv = ContentValues().apply {
            put("id", deposit.id)
            put("record_id", deposit.recordId)
            put("principal", deposit.principal)
            put("interest_rate", deposit.interestRate)
            put("term_value", deposit.termValue)
            put("term_unit", deposit.termUnit)
            put("start_date", deposit.startDate)
            put("maturity_date", deposit.maturityDate)
            put("status", deposit.status)
            if (deposit.incomeBillId != null) put("income_bill_id", deposit.incomeBillId) else putNull("income_bill_id")
            put("note", deposit.note)
            put("created_at", deposit.createdAt)
        }
        db.insertWithOnConflict("fixed_deposits", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getFixedDepositsByAccount(context: Context, accountId: String): List<FixedDeposit> {
        val db = getDb(context).readableDatabase
        val cursor = db.rawQuery(
            """SELECT fd.id, fd.record_id, fd.principal, fd.interest_rate, fd.term_value,
                      fd.term_unit, fd.start_date, fd.maturity_date, fd.status,
                      fd.income_bill_id, fd.note, fd.created_at
               FROM fixed_deposits fd
               JOIN records r ON fd.record_id = r.id
               WHERE r.account_id = ? AND r.type = '存款'
               ORDER BY fd.created_at DESC""",
            arrayOf(accountId)
        )
        val list = mutableListOf<FixedDeposit>()
        try {
            while (cursor.moveToNext()) {
                list.add(FixedDeposit(
                    id = cursor.getString(0),
                    recordId = cursor.getString(1),
                    principal = cursor.getDouble(2),
                    interestRate = cursor.getDouble(3),
                    termValue = cursor.getInt(4),
                    termUnit = cursor.getString(5),
                    startDate = cursor.getLong(6),
                    maturityDate = cursor.getLong(7),
                    status = cursor.getString(8),
                    incomeBillId = cursor.getString(9),
                    note = cursor.getString(10) ?: "",
                    createdAt = cursor.getLong(11)
                ))
            }
        } finally {
            cursor.close()
        }
        return list
    }

    fun updateFixedDeposit(context: Context, deposit: FixedDeposit) {
        val db = getDb(context).writableDatabase
        val cv = ContentValues().apply {
            put("principal", deposit.principal)
            put("interest_rate", deposit.interestRate)
            put("term_value", deposit.termValue)
            put("term_unit", deposit.termUnit)
            put("start_date", deposit.startDate)
            put("maturity_date", deposit.maturityDate)
            put("status", deposit.status)
            if (deposit.incomeBillId != null) put("income_bill_id", deposit.incomeBillId) else putNull("income_bill_id")
            put("note", deposit.note)
        }
        db.update("fixed_deposits", cv, "id = ?", arrayOf(deposit.id))
    }

    fun deleteFixedDeposit(context: Context, id: String) {
        val db = getDb(context).writableDatabase
        // 先查出关联的 record_id
        val cursor = db.rawQuery("SELECT record_id FROM fixed_deposits WHERE id = ?", arrayOf(id))
        val recordId = try { if (cursor.moveToFirst()) cursor.getString(0) else null } finally { cursor.close() }
        db.delete("fixed_deposits", "id = ?", arrayOf(id))
        if (recordId != null) {
            db.delete("records", "id = ?", arrayOf(recordId))
        }
    }

    fun getMaturedDeposits(context: Context, accountId: String): List<FixedDeposit> {
        val db = getDb(context).readableDatabase
        val now = System.currentTimeMillis()
        val cursor = db.rawQuery(
            """SELECT fd.id, fd.record_id, fd.principal, fd.interest_rate, fd.term_value,
                      fd.term_unit, fd.start_date, fd.maturity_date, fd.status,
                      fd.income_bill_id, fd.note, fd.created_at
               FROM fixed_deposits fd
               JOIN records r ON fd.record_id = r.id
               WHERE r.account_id = ? AND r.type = '存款' AND fd.status = 'active' AND fd.maturity_date <= ?
               ORDER BY fd.maturity_date ASC""",
            arrayOf(accountId, now.toString())
        )
        val list = mutableListOf<FixedDeposit>()
        try {
            while (cursor.moveToNext()) {
                list.add(FixedDeposit(
                    id = cursor.getString(0),
                    recordId = cursor.getString(1),
                    principal = cursor.getDouble(2),
                    interestRate = cursor.getDouble(3),
                    termValue = cursor.getInt(4),
                    termUnit = cursor.getString(5),
                    startDate = cursor.getLong(6),
                    maturityDate = cursor.getLong(7),
                    status = cursor.getString(8),
                    incomeBillId = cursor.getString(9),
                    note = cursor.getString(10) ?: "",
                    createdAt = cursor.getLong(11)
                ))
            }
        } finally {
            cursor.close()
        }
        return list
    }

    // ─────────────────────────────────────────────
    // 转账记录查询 (Transfer Records)
    // ─────────────────────────────────────────────

    fun getRecordsByAccount(context: Context, accountId: String): List<AccountingRecord> {
        return getDb(context).getRecordsByAccount(accountId)
    }

    fun getTransfersByAccount(context: Context, accountId: String): List<AccountingRecord> {
        return getDb(context).getTransfersByAccount(accountId)
    }

    // 内部工具
    // ─────────────────────────────────────────────

    internal fun getDb(context: Context): AccountingDatabase {
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
