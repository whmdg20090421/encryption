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

        /** 内置默认分类模板（参考 BeeCount 二级分类模式的父分类） */
        fun defaultCategories(): AccountingCategoryDb {
            val expenseCategories = listOf(
                AccountingCategory("dining", "餐饮", "restaurant"),
                AccountingCategory("snacks", "零食", "fastfood"),
                AccountingCategory("fruit", "水果", "eco"),
                AccountingCategory("beverage", "饮品", "local_cafe"),
                AccountingCategory("pastry", "糕点", "cake"),
                AccountingCategory("cooking", "做饭食材", "kitchen"),
                AccountingCategory("shopping", "购物", "shopping_cart"),
                AccountingCategory("pets", "宠物", "pets"),
                AccountingCategory("transport", "交通", "directions_car"),
                AccountingCategory("car", "汽车", "directions_car"),
                AccountingCategory("clothing", "服饰", "checkroom"),
                AccountingCategory("daily_goods", "日用品", "local_laundry_service"),
                AccountingCategory("education", "教育", "school"),
                AccountingCategory("invest_loss", "投资亏损", "trending_down"),
                AccountingCategory("entertainment", "娱乐", "movie"),
                AccountingCategory("game", "游戏", "sports_esports"),
                AccountingCategory("health_products", "保健品", "medication"),
                AccountingCategory("subscription", "订阅服务", "subscriptions"),
                AccountingCategory("sports", "运动", "fitness_center"),
                AccountingCategory("housing", "住房", "home_work"),
                AccountingCategory("home", "居家", "home"),
                AccountingCategory("beauty", "美容", "face")
            )
            val incomeCategories = listOf(
                AccountingCategory("salary", "工资", "work"),
                AccountingCategory("investment", "理财", "account_balance"),
                AccountingCategory("red_packet", "红包", "card_giftcard"),
                AccountingCategory("bonus", "奖金", "emoji_events"),
                AccountingCategory("reimbursement", "报销", "receipt"),
                AccountingCategory("part_time", "兼职", "schedule"),
                AccountingCategory("gift", "礼物", "card_giftcard"),
                AccountingCategory("interest", "利息", "monetization_on"),
                AccountingCategory("refund", "退款", "undo"),
                AccountingCategory("invest_income", "投资收益", "trending_up"),
                AccountingCategory("second_hand", "二手交易", "sell"),
                AccountingCategory("social_benefit", "社会福利", "health_and_safety"),
                AccountingCategory("tax_refund", "退税", "receipt_long"),
                AccountingCategory("provident_fund", "公积金", "account_balance_wallet")
            )
            val pages = mapOf(
                "记账页" to mapOf(
                    "支出" to expenseCategories,
                    "收入" to incomeCategories,
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
