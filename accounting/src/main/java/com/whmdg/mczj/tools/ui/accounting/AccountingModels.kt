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
    val children: List<AccountingCategory> = emptyList(),
    val overlay: String? = null
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
        private const val CURRENT_VERSION = 5

        fun empty() = AccountingCategoryDb()

        /** 内置默认分类模板（来源于用户账单 CSV 提取） */
        fun defaultCategories(): AccountingCategoryDb {
            val expenseCategories = listOf(
                AccountingCategory("A001", "食品餐饮", "build_in_0052", listOf(
                    AccountingCategory("A001_01", "早餐", "build_in_0036"),
                    AccountingCategory("A001_02", "午餐", "build_in_0047"),
                    AccountingCategory("A001_03", "晚餐", "build_in_0039"),
                    AccountingCategory("A001_04", "休闲零食", "build_in_0057"),
                    AccountingCategory("A001_05", "饮料酒水", "build_in_0035"),
                    AccountingCategory("A001_06", "冰糕", "build_in_0045")
                )),
                AccountingCategory("A002", "购物消费", "build_in_0066", listOf(
                    AccountingCategory("A002_01", "日常家居", "build_in_0088"),
                    AccountingCategory("A002_02", "手机数码", "build_in_0124"),
                    AccountingCategory("A002_03", "虚拟充值", "build_in_0104"),
                    AccountingCategory("A002_04", "宠物用品", "build_in_0248"),
                    AccountingCategory("A002_05", "装修装饰", "build_in_0109"),
                    AccountingCategory("A002_06", "VIP", "build_in_0123")
                )),
                AccountingCategory("A003", "出行交通", "build_in_0279", listOf(
                    AccountingCategory("A003_01", "公共交通", "build_in_0274"),
                    AccountingCategory("A003_02", "打车", "build_in_0277"),
                    AccountingCategory("A003_03", "运费", "build_in_0080")
                )),
                AccountingCategory("A004", "健康医疗", "build_in_0224", listOf(
                    AccountingCategory("A004_01", "买药", "build_in_0230"),
                    AccountingCategory("A004_02", "医院", "build_in_0225")
                )),
                AccountingCategory("A005", "居家生活", "build_in_0205", listOf(
                    AccountingCategory("A005_01", "话费宽带", "build_in_0096")
                )),
                AccountingCategory("A006", "文化教育", "build_in_0016", listOf(
                    AccountingCategory("A006_01", "学费", "build_in_0024"),
                    AccountingCategory("A006_02", "培训考试", "build_in_0023"),
                    AccountingCategory("A006_03", "书报杂志", "build_in_0019"),
                    AccountingCategory("A006_04", "文具", "build_in_0022")
                )),
                AccountingCategory("A007", "休闲娱乐", "build_in_0127", listOf(
                    AccountingCategory("A007_01", "棋牌桌游", "build_in_0130")
                )),
                AccountingCategory("A008", "送礼人情", "build_in_0196", listOf(
                    AccountingCategory("A008_01", "孝敬长辈", "build_in_0194"),
                    AccountingCategory("A008_02", "红包", "build_in_0199"),
                    AccountingCategory("A008_03", "借出", "build_in_0198")
                )),
                AccountingCategory("A009", "其他", "build_in_0240", listOf(
                    AccountingCategory("A009_01", "丢失", "build_in_0233"),
                    AccountingCategory("A009_02", "代买", "build_in_0236")
                ))
            )
            val incomeCategories = listOf(
                AccountingCategory("B001", "收入", "build_in_0180", listOf(
                    AccountingCategory("B001_01", "中奖", "build_in_0181"),
                    AccountingCategory("B001_02", "二手闲置", "build_in_0191"),
                    AccountingCategory("B001_03", "其他", "build_in_0184"),
                    AccountingCategory("B001_04", "兼职外快", "build_in_0185"),
                    AccountingCategory("B001_05", "奖金", "build_in_0174"),
                    AccountingCategory("B001_06", "报销", "build_in_0187"),
                    AccountingCategory("B001_07", "生活费", "build_in_0186"),
                    AccountingCategory("B001_08", "礼金人情", "build_in_0178"),
                    AccountingCategory("B001_09", "补贴", "build_in_0192")
                )),
                AccountingCategory("B002", "生活费", "build_in_0152", listOf(
                    AccountingCategory("B002_01", "婆婆", "build_in_0150"),
                    AccountingCategory("B002_02", "爷爷", "build_in_0150"),
                    AccountingCategory("B002_03", "爸爸", "build_in_0149")
                )),
                AccountingCategory("B003", "工资", "build_in_0189", listOf(
                    AccountingCategory("B003_01", "学校工资", "build_in_0016")
                )),
                AccountingCategory("B004", "奖金", "build_in_0177", listOf(
                    AccountingCategory("B004_01", "快手极速版", "build_in_0174"),
                    AccountingCategory("B004_02", "抖音提现", "build_in_0176"),
                    AccountingCategory("B004_03", "拼多多", "build_in_0187")
                )),
                AccountingCategory("B005", "中奖", "build_in_0181", listOf(
                    AccountingCategory("B005_01", "捡钱", "build_in_0182")
                )),
                AccountingCategory("B006", "礼金人情", "build_in_0199", listOf(
                    AccountingCategory("B006_01", "亲人红包", "build_in_0199"),
                    AccountingCategory("B006_02", "外公红包", "build_in_0199"),
                    AccountingCategory("B006_03", "妈妈红包", "build_in_0199"),
                    AccountingCategory("B006_04", "婆婆红包", "build_in_0199"),
                    AccountingCategory("B006_05", "朋友红包", "build_in_0199"),
                    AccountingCategory("B006_06", "未知红包", "build_in_0199"),
                    AccountingCategory("B006_07", "爷爷红包", "build_in_0199"),
                    AccountingCategory("B006_08", "爸爸红包", "build_in_0199")
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
            val page = "记账页"
            val types = listOf("支出", "收入", "转账", "债务")
            val pages = mutableMapOf<String, Map<String, List<AccountingCategory>>>()
            val typeMap = mutableMapOf<String, List<AccountingCategory>>()
            for (type in types) {
                typeMap[type] = AccountingRepository.loadCategories(context, page, type)
            }
            pages[page] = typeMap
            return AccountingCategoryDb(version = AccountingRepository.getCategoryVersion(context), pages = pages)
        }

        /**
         * 首次释放：检查 SQLite 中的版本号，版本不足则重新写入默认分类。
         */
        fun ensureDefault(context: Context): AccountingCategoryDb {
            // 执行数据迁移（JSON + SharedPreferences → SQLite）
            AccountingRepository.migrateFromLegacy(context)
            val savedVersion = AccountingRepository.getCategoryVersion(context)
            if (savedVersion < CURRENT_VERSION) {
                AccountingRepository.insertDefaultCategories(context)
                AccountingRepository.setCategoryVersion(context, CURRENT_VERSION)
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
    return AccountingRepository.getCategoryIconColor(context)
}

/** 写入分类图标主题色 */
fun setCategoryIconColor(context: Context, colorHex: String) {
    AccountingRepository.setCategoryIconColor(context, colorHex)
}

// ── 附件数据模型 ──

/**
 * 附件元信息。DB 只存此信息，文件本体存储在独立目录。
 */
@Serializable
data class AttachmentInfo(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,       // 原始文件名
    val mimeType: String,       // "image/jpeg" / "application/pdf" 等
    val storedFileName: String, // 存储到磁盘的文件名（避免重名冲突）
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * 附件回收站记录。附件被移除时进入此表，支持恢复和永久清理。
 */
@Serializable
data class AttachmentTrashEntry(
    val id: String = UUID.randomUUID().toString(),
    val attachment: AttachmentInfo,            // 附件元信息
    val originalRecordId: String,             // 原所属账单 ID
    val originalRecordStatus: String,         // "active"（账单仍存在）或 "deleted"（账单已移除）
    val deletedAt: Long = System.currentTimeMillis()
)

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
    val categoryName: String = "",       // 一级分类中文名
    val subcategoryName: String? = null, // 二级分类中文名
    val note: String = "",
    val happenedAt: Long = System.currentTimeMillis(),
    val year: Int = 0,              // happenedAt 对应的年份
    val month: Int = 0,             // happenedAt 对应的月份（1-12）
    val day: Int = 0,               // happenedAt 对应的日（1-31）
    val accountId: String? = null,  // 关联账户 id（可选）
    val discountBefore: String? = null,  // 优惠前金额（可选）
    val discountOff: String? = null,     // 优惠金额（可选）
    val discountAfter: String? = null,   // 优惠后金额（可选）
    val reimbursementAccountId: String? = null,  // 关联报销账户 id（可选），null = 不报销
    val attachments: List<AttachmentInfo> = emptyList(),  // 附件列表（可选）
    val excludeFromStats: Boolean = false,   // 不计入收支统计
    val excludeFromBudget: Boolean = false,  // 不计入预算
    val reimburseStatus: Boolean = false,    // 报销状态
    val reimburseAmount: Double = 0.0,       // 报销金额
    val reimburseAfterAmount: String? = null, // 报销后金额（用于余额计算）
    val refundAmount: Double = 0.0,          // 退款金额
    val address: String = "",                // 地址（可选）
    val transactionId: String = "",          // 交易单号（自动记账专用，微信/支付宝）
    val merchantOrderId: String = "",        // 商户单号（自动记账专用，微信/支付宝）
    val createdAt: Long? = null,             // 账单创建时间（首次创建后锁定）
    val updatedAt: Long? = null              // 最后修改时间（编辑/退款/报销保存时更新）
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
            return AccountingRecordDb(records = AccountingRepository.getAllRecords(context))
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
        AccountingRepository.replaceAllRecords(context, records)
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
    val income: Double = 0.0,    // 累计收入
    val expense: Double = 0.0,   // 累计支出
    val currentBalance: Double = 0.0, // 当前余额（直接存储，显示用）
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// ── 账户类型配置 ──

data class AccountTypeConfig(
    val svgPath: String,
    val label: String,
    val category: String
)

val accountTypeConfigs = mapOf(
    "cash" to AccountTypeConfig("file:///android_asset/icons/cash.svg", "现金", "tradable"),
    "alipay" to AccountTypeConfig("file:///android_asset/icons/alipay.svg", "支付宝", "tradable"),
    "wechat" to AccountTypeConfig("file:///android_asset/icons/wechat.svg", "微信钱包", "tradable"),
    "bank_card" to AccountTypeConfig("file:///android_asset/icons/bank_card.svg", "银行卡", "tradable"),
    "custom" to AccountTypeConfig("file:///android_asset/icons/other_account.svg", "自定义", "tradable"),
    "real_estate" to AccountTypeConfig("file:///android_asset/icons/real_estate.svg", "不动产", "valuation"),
    "vehicle" to AccountTypeConfig("file:///android_asset/icons/vehicle.svg", "车辆", "valuation"),
    "investment" to AccountTypeConfig("file:///android_asset/icons/investment.svg", "投资", "valuation"),
    "insurance" to AccountTypeConfig("file:///android_asset/icons/insurance.svg", "保险", "valuation"),
    "provident_fund" to AccountTypeConfig("file:///android_asset/icons/social_fund.svg", "公积金", "valuation"),
    "loan" to AccountTypeConfig("file:///android_asset/icons/loan.svg", "贷款", "valuation"),
)

// ── 月度汇总数据模型 ──

/**
 * 每个账本每月的收支汇总。
 * 由 Repository 层在记录变更时自动维护，业务代码无需手动调用。
 * 过滤条件：reimbursementAccountId IS NULL 且 excludeFromStats = false。
 */
data class MonthlySummary(
    val bookName: String,
    val year: Int,
    val month: Int,
    val income: Double = 0.0,
    val expense: Double = 0.0
)
