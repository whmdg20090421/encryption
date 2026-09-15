package com.whmdg.mczj.tools.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 格式化工具类
 */
object FormatUtils {
    private val dateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * 将 UTC ISO8601 时间字符串按设备本地时区格式化为 "yyyy-MM-dd HH:mm"。
     * 解析失败时原样返回。
     */
    fun formatIsoLocal(iso: String): String {
        return try {
            Instant.parse(iso)
                .atZone(ZoneId.systemDefault())
                .format(dateTimeFormatter)
        } catch (_: Exception) {
            iso
        }
    }

    /**
     * 格式化字节数为人类可读格式
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"

        val suffixes = arrayOf("KB", "MB", "GB", "TB")
        var size = bytes / 1024.0
        var i = 0

        while (size >= 1024 && i < suffixes.size - 1) {
            size /= 1024.0
            i++
        }

        return "%.1f %s".format(size, suffixes[i])
    }

    /**
     * 格式化速度
     */
    fun formatSpeed(bytesPerSecond: Long): String {
        return "${formatBytes(bytesPerSecond)}/s"
    }

    /**
     * 格式化剩余时间
     */
    fun formatTimeRemaining(seconds: Long): String {
        if (seconds <= 0) return ""

        val minutes = seconds / 60
        val secs = seconds % 60

        return if (minutes > 0) {
            "${minutes}分${secs}秒"
        } else {
            "${secs}秒"
        }
    }
}
