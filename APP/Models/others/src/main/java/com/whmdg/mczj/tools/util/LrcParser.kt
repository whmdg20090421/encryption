package com.whmdg.mczj.tools.util

/**
 * LRC 歌词解析：将 `[mm:ss.xx]歌词` 文本解析为按时间排序的歌词行。
 *
 * 兼容两种常见格式：
 * - 行首单时间戳：`[00:12.34]这是一句歌词`
 * - 逐字时间戳（卡拉OK）：`[00:12.34]这[00:12.50]是[00:12.66]一句`
 *
 * 无时间戳的纯文本歌词会被当作无时间行处理（时间戳为 -1）。
 */
object LrcParser {

    data class LyricLine(val timeMs: Long, val text: String) {
        val isTimed: Boolean get() = timeMs >= 0
    }

    private val TAG_REGEX = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val META_REGEX = Regex("\\[(ti|ar|al|by|offset|re|ve|length):[^]]*]")

    fun parse(raw: String?): List<LyricLine> {
        if (raw.isNullOrBlank()) return emptyList()

        val lines = mutableListOf<LyricLine>()
        for (rawLine in raw.split('\n')) {
            val line = META_REGEX.replace(rawLine.trimEnd('\r'), "").trim()
            if (line.isEmpty()) continue

            val matches = TAG_REGEX.findAll(line).toList()
            if (matches.isEmpty()) continue

            // 去掉所有时间戳后即为整行文本（兼容逐字时间戳）
            val text = TAG_REGEX.replace(line, "").trim()
            if (text.isEmpty()) continue

            // 以行内首个时间戳作为该行时间
            lines += LyricLine(timeToMs(matches.first()), text)
        }

        if (lines.isEmpty()) {
            // 无时间戳：纯文本整体展示
            return raw.split('\n')
                .map { META_REGEX.replace(it.trim(), "").trim() }
                .filter { it.isNotEmpty() && !TAG_REGEX.containsMatchIn(it) }
                .map { LyricLine(-1, it) }
        }

        return lines.sortedBy { it.timeMs }
    }

    private fun timeToMs(match: MatchResult): Long {
        val min = match.groupValues[1].toLong()
        val sec = match.groupValues[2].toLong()
        val fracRaw = match.groupValues[3]
        val fracMs = when (fracRaw.length) {
            0 -> 0L
            1 -> fracRaw.toLong() * 100
            2 -> fracRaw.toLong() * 10
            else -> fracRaw.substring(0, 3).toLong()
        }
        return min * 60_000 + sec * 1_000 + fracMs
    }

    /** 返回当前播放位置对应的歌词行索引，无匹配返回 -1。 */
    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var idx = -1
        for (i in lines.indices) {
            if (!lines[i].isTimed) continue
            if (lines[i].timeMs <= positionMs) idx = i else break
        }
        return idx
    }
}
