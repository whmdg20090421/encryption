package com.whmdg.mczj.tools.ui.hook.usage

/**
 * 每小时使用时长表格
 *
 * 行：应用包名
 * 列：00-01, 01-02, ..., 23-24, 总时长
 * 最后一行：单小时总时长（每列求和）
 */
data class HourlyUsageTable(
    /** 每行数据：包名 → 24 小时桶（毫秒）+ 总时长 */
    val rows: List<HourlyRow>,
    /** 最后一行：每列求和 */
    val summaryRow: HourlySummaryRow
) {
    /** 单个应用的每小时数据 */
    data class HourlyRow(
        val packageName: String,
        /** 长度 24，索引 0=00-01，索引 23=23-24 */
        val hourlyMillis: LongArray
    ) {
        val totalMillis: Long get() = hourlyMillis.sum()
    }

    /** 汇总行 */
    data class HourlySummaryRow(
        /** 长度 24，每列所有应用求和 */
        val hourlyMillis: LongArray
    ) {
        val totalMillis: Long get() = hourlyMillis.sum()
    }
}

/**
 * 按应用类别分组的每小时数据
 *
 * 用于柱状图堆叠显示：游戏（紫）、视频/音频（橙）、其他（蓝）
 */
data class CategoryHourlyData(
    /** 游戏类每小时数据（长度 24） */
    val gameHourly: LongArray,
    /** 视频/音频类每小时数据（长度 24） */
    val mediaHourly: LongArray,
    /** 其他类每小时数据（长度 24） */
    val otherHourly: LongArray
) {
    val gameTotal: Long get() = gameHourly.sum()
    val mediaTotal: Long get() = mediaHourly.sum()
    val otherTotal: Long get() = otherHourly.sum()
}
