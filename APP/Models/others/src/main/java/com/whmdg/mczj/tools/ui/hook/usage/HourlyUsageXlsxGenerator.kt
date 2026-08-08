package com.whmdg.mczj.tools.ui.hook.usage

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 将 HourlyUsageTable 生成 XLSX 文件并通过系统分享
 */
object HourlyUsageXlsxGenerator {

    /**
     * 生成 XLSX 文件并返回 File
     *
     * @param context Android Context
     * @param table 表格数据
     * @return 生成的 XLSX 文件
     */
    fun generate(context: Context, table: HourlyUsageTable): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "使用时长_$timestamp.xlsx"
        val file = File(context.cacheDir, fileName)

        file.outputStream().use { os ->
            val wb = Workbook(os, "使用时长统计", "1.0")
            val ws = wb.newWorksheet("使用时长")

            // ── 列宽 ──
            ws.width(0, 25.0) // 包名列
            for (i in 1..25) {
                ws.width(i, 14.0)
            }

            // ── 表头行 ──
            ws.value(0, 0, "包名")
            for (i in 0 until 24) {
                ws.value(0, i + 1, String.format("%02d:00-%02d:00", i, i + 1))
            }
            ws.value(0, 25, "总时长")
            // 表头样式
            ws.range(0, 0, 0, 25).style()
                .bold()
                .fillColor("D9D9D9")
                .horizontalAlignment("center")
                .verticalAlignment("center")
                .set()

            // ── 数据行 ──
            for ((rowIndex, row) in table.rows.withIndex()) {
                val r = rowIndex + 1
                ws.value(r, 0, row.packageName)
                for (hour in 0 until 24) {
                    ws.value(r, hour + 1, formatMillisForCell(row.hourlyMillis[hour]))
                }
                ws.value(r, 25, formatMillisForCell(row.totalMillis))
                // 数据行样式
                ws.range(r, 1, r, 25).style()
                    .horizontalAlignment("center")
                    .verticalAlignment("center")
                    .set()
            }

            // ── 汇总行 ──
            val summaryR = table.rows.size + 1
            ws.value(summaryR, 0, "单小时总时长")
            for (hour in 0 until 24) {
                ws.value(summaryR, hour + 1, formatMillisForCell(table.summaryRow.hourlyMillis[hour]))
            }
            ws.value(summaryR, 25, formatMillisForCell(table.summaryRow.totalMillis))
            // 汇总行样式
            ws.range(summaryR, 0, summaryR, 25).style()
                .bold()
                .fillColor("B4C6E7")
                .horizontalAlignment("center")
                .verticalAlignment("center")
                .set()

            wb.finish()
        }

        return file
    }

    /**
     * 创建分享 Intent
     */
    fun createShareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "使用时长统计")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun formatMillisForCell(millis: Long): String {
        if (millis <= 0) return ""
        val hours = millis / (1000 * 60 * 60)
        val minutes = (millis / (1000 * 60)) % 60
        val seconds = (millis / 1000) % 60
        return when {
            hours > 0 -> "${hours}h${minutes}m${seconds}s"
            minutes > 0 -> "${minutes}m${seconds}s"
            else -> "${seconds}s"
        }
    }
}
