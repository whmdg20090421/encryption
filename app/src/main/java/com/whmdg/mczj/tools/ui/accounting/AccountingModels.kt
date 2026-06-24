package com.whmdg.mczj.tools.ui.accounting

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 单个分类。
 * children 预留二级分类扩展空间，当前均为空列表。
 */
@Serializable
data class AccountingCategory(
    val id: String,
    val name: String,
    val icon: String,
    val children: List<AccountingCategory> = emptyList()
)

/**
 * 分类数据库。
 * 结构：pages → 页面名 → 记账类型 → 分类列表。
 * 首次安装时释放默认模板到 JSON 文件，后续 UI 动态读取该文件。
 * 用户修改 JSON 后，UI 自动反映变更。
 */
@Serializable
data class AccountingCategoryDb(
    val version: Int = 1,
    val pages: Map<String, Map<String, List<AccountingCategory>>> = emptyMap()
) {
    companion object {
        private const val FILE_NAME = "accounting_categories.json"
        private const val BACKUP_NAME = "accounting_categories.backup.json"
        private const val PREF_KEY_RELEASED = "categories_released"

        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            prettyPrintIndent = "    "
            encodeDefaults = true
        }

        fun empty() = AccountingCategoryDb()

        /** 内置默认分类模板 */
        fun defaultCategories(): AccountingCategoryDb {
            val expenseCategories = listOf(
                AccountingCategory("shopping", "购物消费", "shopping_bag"),
                AccountingCategory("food", "食品餐饮", "utensils"),
                AccountingCategory("transport", "交通出行", "car"),
                AccountingCategory("leisure", "休闲娱乐", "gamepad"),
                AccountingCategory("home", "居家生活", "house")
            )
            val pages = mapOf(
                "记账页" to mapOf(
                    "支出" to expenseCategories,
                    "收入" to emptyList(),
                    "转账" to emptyList(),
                    "债务" to emptyList()
                )
            )
            return AccountingCategoryDb(version = 1, pages = pages)
        }

        /** 加载：主配置 → 备份 → 空 */
        fun load(context: Context): AccountingCategoryDb {
            val dir = AppDataPaths.accounting(context)
            val primary = File(dir, FILE_NAME)
            if (primary.exists()) {
                try {
                    return json.decodeFromString<AccountingCategoryDb>(primary.readText())
                } catch (_: Exception) { }
            }
            val backup = File(dir, BACKUP_NAME)
            if (backup.exists()) {
                try {
                    return json.decodeFromString<AccountingCategoryDb>(backup.readText())
                } catch (_: Exception) { }
            }
            return empty()
        }

        /**
         * 首次释放：检查 SharedPreferences 标记，未释放则写入默认数据。
         * 后续调用直接 load()，用户修改 JSON 后 UI 自动反映。
         */
        fun ensureDefault(context: Context): AccountingCategoryDb {
            val prefs = context.getSharedPreferences(AppDataPaths.PREFS_ACCOUNTING, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_KEY_RELEASED, false)) {
                val db = defaultCategories()
                db.save(context)
                prefs.edit().putBoolean(PREF_KEY_RELEASED, true).apply()
                return db
            }
            return load(context)
        }
    }

    /** 双副本保存：主配置 + 备份 */
    fun save(context: Context) {
        val dir = AppDataPaths.accounting(context)
        val text = json.encodeToString(serializer(), this)
        File(dir, FILE_NAME).writeText(text)
        try {
            File(dir, BACKUP_NAME).writeText(text)
        } catch (_: Exception) { }
    }

    /** 获取指定页面、指定类型的分类列表 */
    fun getCategories(page: String, type: String): List<AccountingCategory> {
        return pages[page]?.get(type) ?: emptyList()
    }
}
