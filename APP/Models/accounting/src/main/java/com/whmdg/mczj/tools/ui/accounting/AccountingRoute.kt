package com.whmdg.mczj.tools.ui.accounting

/**
 * 记账本模块内部路由。
 */
sealed class AccountingRoute {
    object Home : AccountingRoute()
    data class Detail(val bookName: String, val recordId: String) : AccountingRoute()
    data class AddRecord(val bookName: String, val recordId: String? = null) : AccountingRoute()
    object ReimbursementAccount : AccountingRoute()
    data class ReimbursementDetail(val accountId: String) : AccountingRoute()
    object AddReimbursementAccount : AccountingRoute()
    data class AssetDetail(val accountId: String) : AccountingRoute()
    data class CapitalFlow(val accountId: String) : AccountingRoute()
    data class AssetHistory(val accountId: String) : AccountingRoute()
    data class TransferList(val accountId: String) : AccountingRoute()
    data class FixedDepositManager(val accountId: String) : AccountingRoute()
}
