package com.whmdg.mczj.tools.ui.accounting

import android.content.Context
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 单个分类。
 * children 包含二级子分类列表，参考 BeeCount 二级分类模式。
 */
@Serializable
data class AccountingCategory(
    val id: String,
    val name: String,
    val icon: String,
    val children: List<AccountingCategory> = emptyList()
)

/**
 * 分类数据库（内存表示，底层存储已迁移至 SQLite）。
 * 结构：pages → 页面名 → 记账类型 → 分类列表。
 */
@Serializable
data class AccountingCategoryDb(
    val version: Int = 1,
    val pages: Map<String, Map<String, List<AccountingCategory>>> = emptyMap()
) {
    companion object {
        /** 当前默认数据版本，递增此值触发重新释放 */
        private const val CURRENT_VERSION = 2

        fun empty() = AccountingCategoryDb()

        /** 内置默认分类模板（参考 BeeCount 二级分类模式） */
        fun defaultCategories(): AccountingCategoryDb {
            val expenseCategories = listOf(
                AccountingCategory("dining", "餐饮", "restaurant", listOf(
                    AccountingCategory("dining_breakfast", "早餐", "free_breakfast"),
                    AccountingCategory("dining_lunch", "午餐", "lunch_dining"),
                    AccountingCategory("dining_dinner", "晚餐", "dinner_dining"),
                    AccountingCategory("dining_meituan", "美团", "delivery_dining"),
                    AccountingCategory("dining_eleme", "饿了么", "delivery_dining"),
                    AccountingCategory("dining_jd", "京东", "delivery_dining"),
                    AccountingCategory("dining_restaurant", "餐厅", "restaurant"),
                    AccountingCategory("dining_food", "食堂", "fastfood")
                )),
                AccountingCategory("snacks", "零食", "fastfood", listOf(
                    AccountingCategory("snacks_biscuit", "饼干", "cookie"),
                    AccountingCategory("snacks_chips", "薯片", "ramen_dining"),
                    AccountingCategory("snacks_candy", "糖果", "candy"),
                    AccountingCategory("snacks_chocolate", "巧克力", "chocolate"),
                    AccountingCategory("snacks_nuts", "坚果", "grain")
                )),
                AccountingCategory("fruit", "水果", "eco", listOf(
                    AccountingCategory("fruit_apple", "苹果", "apple"),
                    AccountingCategory("fruit_banana", "香蕉", "sports_cricket"),
                    AccountingCategory("fruit_orange", "橙子", "circle"),
                    AccountingCategory("fruit_grape", "葡萄", "bubble_chart"),
                    AccountingCategory("fruit_watermelon", "西瓜", "pie_chart"),
                    AccountingCategory("fruit_other", "其他", "eco")
                )),
                AccountingCategory("beverage", "饮品", "local_cafe", listOf(
                    AccountingCategory("beverage_milk_tea", "奶茶", "local_cafe"),
                    AccountingCategory("beverage_coffee", "咖啡", "coffee"),
                    AccountingCategory("beverage_juice", "果汁", "juice"),
                    AccountingCategory("beverage_soda", "汽水", "liquor"),
                    AccountingCategory("beverage_water", "矿泉水", "water_drop")
                )),
                AccountingCategory("pastry", "糕点", "cake", listOf(
                    AccountingCategory("pastry_cake", "蛋糕", "cake"),
                    AccountingCategory("pastry_bread", "面包", "bakery_dining"),
                    AccountingCategory("pastry_dessert", "甜点", "icecream"),
                    AccountingCategory("pastry_biscuit", "饼干", "cookie")
                )),
                AccountingCategory("cooking", "做饭食材", "kitchen", listOf(
                    AccountingCategory("cooking_vegetable", "蔬菜", "yard"),
                    AccountingCategory("cooking_meat", "肉类", "lunch_dining"),
                    AccountingCategory("cooking_seafood", "海鲜", "set_meal"),
                    AccountingCategory("cooking_seasoning", "调料", "blender"),
                    AccountingCategory("cooking_grain", "粮油", "grain")
                )),
                AccountingCategory("shopping", "购物", "shopping_cart", listOf(
                    AccountingCategory("shopping_clothing", "衣服", "checkroom"),
                    AccountingCategory("shopping_shoes", "鞋子", "accessibility"),
                    AccountingCategory("shopping_bag", "包包", "shopping_bag"),
                    AccountingCategory("shopping_accessory", "配饰", "watch"),
                    AccountingCategory("shopping_daily", "日用", "shopping_cart")
                )),
                AccountingCategory("pets", "宠物", "pets", listOf(
                    AccountingCategory("pets_food", "粮食", "pet_supplies"),
                    AccountingCategory("pets_supplies", "用品", "inventory_2"),
                    AccountingCategory("pets_medical", "医疗", "medical_services"),
                    AccountingCategory("pets_grooming", "美容", "shower")
                )),
                AccountingCategory("transport", "交通", "directions_car", listOf(
                    AccountingCategory("transport_subway", "地铁", "directions_subway"),
                    AccountingCategory("transport_bus", "公交", "directions_bus"),
                    AccountingCategory("transport_taxi", "出租车", "local_taxi"),
                    AccountingCategory("transport_ride", "骑行", "directions_bike"),
                    AccountingCategory("transport_parking", "停车", "local_parking"),
                    AccountingCategory("transport_fuel", "加油", "local_gas_station")
                )),
                AccountingCategory("car", "汽车", "directions_car", listOf(
                    AccountingCategory("car_maintenance", "保养", "build"),
                    AccountingCategory("car_repair", "维修", "handyman"),
                    AccountingCategory("car_insurance", "保险", "security"),
                    AccountingCategory("car_wash", "洗车", "local_car_wash"),
                    AccountingCategory("car_fine", "罚款", "report_problem")
                )),
                AccountingCategory("clothing", "服饰", "checkroom", listOf(
                    AccountingCategory("clothing_top", "上衣", "checkroom"),
                    AccountingCategory("clothing_pants", "裤子", "diamond"),
                    AccountingCategory("clothing_skirt", "裙子", "auto_awesome"),
                    AccountingCategory("clothing_shoes", "鞋子", "hiking"),
                    AccountingCategory("clothing_accessory", "配饰", "watch")
                )),
                AccountingCategory("daily_goods", "日用品", "local_laundry_service", listOf(
                    AccountingCategory("daily_toiletries", "洗护", "shower"),
                    AccountingCategory("daily_paper", "纸品", "receipt"),
                    AccountingCategory("daily_cleaning", "清洁", "cleaning_services"),
                    AccountingCategory("daily_kitchen", "厨具", "kitchen")
                )),
                AccountingCategory("education", "教育", "school", listOf(
                    AccountingCategory("education_tuition", "学费", "school"),
                    AccountingCategory("education_training", "培训", "model_training"),
                    AccountingCategory("education_books", "书籍", "menu_book"),
                    AccountingCategory("education_stationery", "文具", "edit"),
                    AccountingCategory("education_office", "办公", "business_center")
                )),
                AccountingCategory("invest_loss", "投资亏损", "trending_down", listOf(
                    AccountingCategory("invest_loss_stock", "股票", "trending_down"),
                    AccountingCategory("invest_loss_fund", "基金", "show_chart"),
                    AccountingCategory("invest_loss_other", "其他", "money_off")
                )),
                AccountingCategory("entertainment", "娱乐", "movie", listOf(
                    AccountingCategory("entertainment_movie", "电影", "movie"),
                    AccountingCategory("entertainment_ktv", "KTV", "mic"),
                    AccountingCategory("entertainment_amusement", "游乐", "attractions"),
                    AccountingCategory("entertainment_bar", "酒吧", "local_bar"),
                    AccountingCategory("entertainment_other", "其他", "celebration")
                )),
                AccountingCategory("game", "游戏", "sports_esports", listOf(
                    AccountingCategory("game_recharge", "充值", "payments"),
                    AccountingCategory("game_equipment", "装备", "sports_esports"),
                    AccountingCategory("game_membership", "会员", "workspace_premium")
                )),
                AccountingCategory("health_products", "保健品", "medication", listOf(
                    AccountingCategory("health_vitamin", "维生素", "medication"),
                    AccountingCategory("health_food", "食品", "biotech"),
                    AccountingCategory("health_nutrition", "营养品", "health_and_safety")
                )),
                AccountingCategory("subscription", "订阅服务", "subscriptions", listOf(
                    AccountingCategory("subscription_video", "视频", "play_circle"),
                    AccountingCategory("subscription_music", "音乐", "music_note"),
                    AccountingCategory("subscription_cloud", "云盘", "cloud"),
                    AccountingCategory("subscription_other", "其他", "subscriptions")
                )),
                AccountingCategory("sports", "运动", "fitness_center", listOf(
                    AccountingCategory("sports_gym", "健身", "fitness_center"),
                    AccountingCategory("sports_equipment", "器材", "sports"),
                    AccountingCategory("sports_course", "课程", "sports_martial_arts"),
                    AccountingCategory("sports_outdoor", "户外", "hiking")
                )),
                AccountingCategory("housing", "住房", "home_work", listOf(
                    AccountingCategory("housing_rent", "房租", "home"),
                    AccountingCategory("housing_property", "物业", "home_work"),
                    AccountingCategory("housing_mortgage", "房贷", "account_balance"),
                    AccountingCategory("housing_decoration", "装修", "construction")
                )),
                AccountingCategory("home", "居家", "home", listOf(
                    AccountingCategory("home_furniture", "家具", "weekend"),
                    AccountingCategory("home_appliance", "家电", "devices"),
                    AccountingCategory("home_decor", "装饰", "palette"),
                    AccountingCategory("home_bedding", "床品", "bed")
                )),
                AccountingCategory("beauty", "美容", "face", listOf(
                    AccountingCategory("beauty_skincare", "护肤", "face"),
                    AccountingCategory("beauty_cosmetics", "彩妆", "face_retouching_natural"),
                    AccountingCategory("beauty_salon", "美发", "content_cut"),
                    AccountingCategory("beauty_nail", "美甲", "back_hand")
                ))
            )
            val incomeCategories = listOf(
                AccountingCategory("salary", "工资", "work", listOf(
                    AccountingCategory("salary_basic", "基本工资", "payments"),
                    AccountingCategory("salary_performance", "绩效", "star"),
                    AccountingCategory("salary_year_end", "年终奖", "card_giftcard"),
                    AccountingCategory("salary_overtime", "加班费", "access_time")
                )),
                AccountingCategory("investment", "理财", "account_balance", listOf(
                    AccountingCategory("investment_fund", "基金", "account_balance"),
                    AccountingCategory("investment_dividend", "分红", "trending_up"),
                    AccountingCategory("investment_product", "理财产品", "savings"),
                    AccountingCategory("investment_other", "其他", "monetization_on")
                )),
                AccountingCategory("red_packet", "红包", "card_giftcard", listOf(
                    AccountingCategory("red_packet_festival", "节日", "celebration"),
                    AccountingCategory("red_packet_birthday", "生日", "cake"),
                    AccountingCategory("red_packet_return", "回礼", "card_giftcard")
                )),
                AccountingCategory("bonus", "奖金", "emoji_events", listOf(
                    AccountingCategory("bonus_year_end", "年终", "emoji_events"),
                    AccountingCategory("bonus_quarterly", "季度", "star"),
                    AccountingCategory("bonus_project", "项目", "workspace_premium"),
                    AccountingCategory("bonus_other", "其他", "military_tech")
                )),
                AccountingCategory("reimbursement", "报销", "receipt", listOf(
                    AccountingCategory("reimbursement_travel", "差旅", "flight"),
                    AccountingCategory("reimbursement_meal", "餐饮", "restaurant"),
                    AccountingCategory("reimbursement_other", "其他", "receipt")
                )),
                AccountingCategory("part_time", "兼职", "schedule", listOf(
                    AccountingCategory("part_time_income", "收入", "schedule"),
                    AccountingCategory("part_time_extra", "外快", "attach_money")
                )),
                AccountingCategory("gift", "礼物", "card_giftcard", listOf(
                    AccountingCategory("gift_wedding", "婚礼", "favorite"),
                    AccountingCategory("gift_birthday", "生日", "cake"),
                    AccountingCategory("gift_other", "其他", "card_giftcard")
                )),
                AccountingCategory("interest", "利息", "monetization_on", listOf(
                    AccountingCategory("interest_bank", "银行", "account_balance"),
                    AccountingCategory("interest_other", "其他", "monetization_on")
                )),
                AccountingCategory("refund", "退款", "undo", listOf(
                    AccountingCategory("refund_shopping", "购物", "shopping_cart"),
                    AccountingCategory("refund_service", "服务", "build"),
                    AccountingCategory("refund_other", "其他", "undo")
                )),
                AccountingCategory("invest_income", "投资收益", "trending_up", listOf(
                    AccountingCategory("invest_income_stock", "股票", "trending_up"),
                    AccountingCategory("invest_income_fund", "基金", "account_balance"),
                    AccountingCategory("invest_income_other", "其他", "attach_money")
                )),
                AccountingCategory("second_hand", "二手交易", "sell", listOf(
                    AccountingCategory("second_hand_idle", "闲置", "sell"),
                    AccountingCategory("second_hand_goods", "商品", "storefront")
                )),
                AccountingCategory("social_benefit", "社会福利", "health_and_safety", listOf(
                    AccountingCategory("social_benefit_unemployment", "失业金", "health_and_safety"),
                    AccountingCategory("social_benefit_maternity", "生育金", "child_care"),
                    AccountingCategory("social_benefit_other", "其他", "favorite")
                )),
                AccountingCategory("tax_refund", "退税", "receipt_long", listOf(
                    AccountingCategory("tax_refund_personal", "个人", "receipt_long"),
                    AccountingCategory("tax_refund_other", "其他", "description")
                )),
                AccountingCategory("provident_fund", "公积金", "account_balance_wallet", listOf(
                    AccountingCategory("provident_fund_withdrawal", "提取", "account_balance_wallet"),
                    AccountingCategory("provident_fund_interest", "利息", "savings")
                ))
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

        /** 从 SQLite 加载分类数据 */
        fun load(context: Context): AccountingCategoryDb {
            val db = AccountingDatabase.getInstance(context)
            val page = "记账页"
            val types = listOf("支出", "收入", "转账", "债务")
            val pages = mutableMapOf<String, Map<String, List<AccountingCategory>>>()
            val typeMap = mutableMapOf<String, List<AccountingCategory>>()
            for (type in types) {
                typeMap[type] = db.getCategories(page, type)
            }
            pages[page] = typeMap
            return AccountingCategoryDb(version = db.getCategoryVersion(), pages = pages)
        }

        /**
         * 首次释放：检查 SQLite 中的版本号，版本不足则重新写入默认分类。
         */
        fun ensureDefault(context: Context): AccountingCategoryDb {
            val db = AccountingDatabase.getInstance(context)
            // 执行数据迁移（JSON + SharedPreferences → SQLite）
            db.migrateFromLegacy(context)
            val savedVersion = db.getCategoryVersion()
            if (savedVersion < CURRENT_VERSION) {
                db.insertDefaultCategories()
                db.setCategoryVersion(CURRENT_VERSION)
            }
            return load(context)
        }
    }

    /** 获取指定页面、指定类型的分类列表 */
    fun getCategories(page: String, type: String): List<AccountingCategory> {
        return pages[page]?.get(type) ?: emptyList()
    }
}

/** 读取分类图标主题色（十六进制颜色值，默认靛蓝 #5C6BC0） */
fun getCategoryIconColor(context: Context): String {
    val db = AccountingDatabase.getInstance(context)
    return db.getSetting("category_icon_color") ?: "#5C6BC0"
}

/** 写入分类图标主题色 */
fun setCategoryIconColor(context: Context, colorHex: String) {
    val db = AccountingDatabase.getInstance(context)
    db.setSetting("category_icon_color", colorHex)
}

// ── 记账记录数据模型 ──

/**
 * 单条记账记录。
 * amount 保留字符串精度，happenedAt 为毫秒时间戳。
 */
@Serializable
data class AccountingRecord(
    val id: String = UUID.randomUUID().toString(),
    val bookName: String,
    val type: String,            // "支出"/"收入"/"转账"/"债务"
    val amount: String,
    val categoryId: String,      // 一级分类 id
    val subcategoryId: String?,  // 二级分类 id（可选）
    val note: String = "",
    val happenedAt: Long = System.currentTimeMillis()
)

/**
 * 记账记录数据库（内存表示，底层存储已迁移至 SQLite）。
 * 保持不可变副本模式：add/update/remove 返回新副本，save() 写入 SQLite。
 */
data class AccountingRecordDb(
    val records: List<AccountingRecord> = emptyList()
) {
    companion object {
        fun empty() = AccountingRecordDb()

        /** 从 SQLite 加载所有记录 */
        fun load(context: Context): AccountingRecordDb {
            val db = AccountingDatabase.getInstance(context)
            return AccountingRecordDb(records = db.getAllRecords())
        }
    }

    /** 添加一条记录 */
    fun add(record: AccountingRecord): AccountingRecordDb {
        return copy(records = records + record)
    }

    /** 删除指定 id 的记录 */
    fun remove(id: String): AccountingRecordDb {
        return copy(records = records.filter { it.id != id })
    }

    /** 更新指定 id 的记录 */
    fun update(record: AccountingRecord): AccountingRecordDb {
        return copy(records = records.map { if (it.id == record.id) record else it })
    }

    /** 保存到 SQLite：REPLACE 存在的 + DELETE 多余的 */
    fun save(context: Context) {
        val db = AccountingDatabase.getInstance(context)
        val sqlDb = db.writableDatabase
        sqlDb.beginTransaction()
        try {
            val currentIds = records.map { it.id }.toSet()
            // 删除当前列表中不存在的记录
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
                val cv = android.content.ContentValues().apply {
                    put("id", record.id)
                    put("book_name", record.bookName)
                    put("type", record.type)
                    put("amount", record.amount)
                    put("category_id", record.categoryId)
                    if (record.subcategoryId != null) put("subcategory_id", record.subcategoryId) else putNull("subcategory_id")
                    put("note", record.note)
                    put("happened_at", record.happenedAt)
                }
                sqlDb.insertWithOnConflict("records", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            }
            sqlDb.setTransactionSuccessful()
        } finally {
            sqlDb.endTransaction()
        }
    }
}

// ── 账户数据模型 ──

/**
 * 资产账户。
 * category: "tradable"（资金账户）或 "valuation"（估值账户）
 * type: 账户类型标识（cash/alipay/wechat/bank_card/real_estate 等）
 */
@Serializable
data class AccountingAccount(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String,
    val category: String,        // "tradable" 或 "valuation"
    val initialAmount: Double = 0.0,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
