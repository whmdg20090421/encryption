package com.whmdg.mczj.tools.ui.hook.usage

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 排除时间段
 *
 * 在统计使用时长时，落在该时间段内的所有应用使用事件将被完全过滤。
 *
 * 一次性排除：[startMillis], [endMillis] 为绝对时间戳，[repeatDays] 为 null。
 * 周期性排除：[startMinuteOfDay], [endMinuteOfDay] 为当天内的时间（分钟），
 *   [repeatDays] 为生效的星期集合（1=周一 ... 7=周日），[startMillis]/[endMillis] 不使用。
 */
data class ExcludedTimeRange(
    val id: Long = System.currentTimeMillis(),
    // 一次性排除用
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    // 周期性排除用
    val startMinuteOfDay: Int = 0,  // 从 0 点起的分钟数
    val endMinuteOfDay: Int = 0,
    val repeatDays: Set<Int>? = null  // null = 一次性，非 null = 周期性
) {
    /** 是否为周期性排除 */
    val isRecurring: Boolean get() = repeatDays != null

    companion object {
        // 星期显示名
        private val DAY_NAMES = arrayOf("一", "二", "三", "四", "五", "六", "日")
        private val WORKDAYS = setOf(1, 2, 3, 4, 5)
        private val WEEKEND = setOf(6, 7)
        private val ALL_DAYS = setOf(1, 2, 3, 4, 5, 6, 7)

        /**
         * 格式化周期性排除的时分部分：HH:mm ~ HH:mm
         */
        fun formatRecurringTime(startMinuteOfDay: Int, endMinuteOfDay: Int): String {
            val sh = startMinuteOfDay / 60
            val sm = startMinuteOfDay % 60
            val eh = endMinuteOfDay / 60
            val em = endMinuteOfDay % 60
            return String.format("%02d:%02d ~ %02d:%02d", sh, sm, eh, em)
        }

        /**
         * 格式化周期性排除的星期部分：工作日 / 每天 / 周末 / 一、三、五
         */
        fun formatRepeatDays(days: Set<Int>): String {
            if (days == ALL_DAYS) return "每天"
            if (days == WORKDAYS) return "工作日"
            if (days == WEEKEND) return "周末"
            return days.sorted().joinToString("、") { DAY_NAMES[it - 1] }
        }

        /**
         * 格式化完整的一次性排除显示文本
         */
        fun formatOneTime(startMillis: Long, endMillis: Long): String {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            return "${sdf.format(java.util.Date(startMillis))} ~ ${sdf.format(java.util.Date(endMillis))}"
        }

        /**
         * 格式化完整的周期性排除显示文本
         */
        fun formatRecurring(startMinuteOfDay: Int, endMinuteOfDay: Int, days: Set<Int>): String {
            return "${formatRecurringTime(startMinuteOfDay, endMinuteOfDay)} (${formatRepeatDays(days)})"
        }

        /**
         * 将排除时间段列表序列化为 JSON 字符串
         */
        fun toJson(ranges: List<ExcludedTimeRange>): String {
            val arr = JSONArray()
            for (range in ranges) {
                val obj = JSONObject()
                obj.put("id", range.id)
                if (range.isRecurring) {
                    obj.put("type", "recurring")
                    obj.put("startMod", range.startMinuteOfDay)
                    obj.put("endMod", range.endMinuteOfDay)
                    val daysArr = JSONArray()
                    for (d in range.repeatDays!!.sorted()) daysArr.put(d)
                    obj.put("days", daysArr)
                } else {
                    obj.put("type", "onetime")
                    obj.put("start", range.startMillis)
                    obj.put("end", range.endMillis)
                }
                arr.put(obj)
            }
            return arr.toString()
        }

        /**
         * 从 JSON 字符串反序列化排除时间段列表
         */
        fun fromJson(json: String?): List<ExcludedTimeRange> {
            if (json.isNullOrEmpty()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.getJSONObject(i)
                    val type = obj.optString("type", "onetime")
                    if (type == "recurring") {
                        val daysArr = obj.getJSONArray("days")
                        val days = (0 until daysArr.length()).map { daysArr.getInt(it) }.toSet()
                        ExcludedTimeRange(
                            id = obj.getLong("id"),
                            startMinuteOfDay = obj.getInt("startMod"),
                            endMinuteOfDay = obj.getInt("endMod"),
                            repeatDays = days
                        )
                    } else {
                        ExcludedTimeRange(
                            id = obj.getLong("id"),
                            startMillis = obj.getLong("start"),
                            endMillis = obj.getLong("end")
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
