package com.whmdg.mczj.tools.ui.hook.usage

import android.graphics.drawable.Drawable

/**
 * 应用使用时长数据模型
 */
data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val usageTimeMillis: Long,
    val appIcon: Drawable? = null,
    val usagePercentage: Float = 0f,
    val rank: Int = 0
) {
    /** 格式化使用时长，如 "2h 30m" / "45m 12s" / "小于 1 分钟" */
    val formattedTime: String
        get() {
            if (usageTimeMillis < 60_000L) return "小于 1 分钟"
            val hours = usageTimeMillis / (1000 * 60 * 60)
            val minutes = (usageTimeMillis / (1000 * 60)) % 60
            val seconds = (usageTimeMillis / 1000) % 60
            return when {
                hours > 0 -> "${hours}h ${minutes}m"
                minutes > 0 -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
        }

    val formattedPercentage: String
        get() = String.format("%.1f%%", usagePercentage)
}
