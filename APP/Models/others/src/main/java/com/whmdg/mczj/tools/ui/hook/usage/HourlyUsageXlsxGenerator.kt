package com.whmdg.mczj.tools.ui.hook.usage

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.xssf.usermodel.XSSFWorkbook
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
        val workbook = XSSFWorkbook()

        // ── 样式定义 ──
        val headerStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            val font = workbook.createFont()
            font.bold = true
            setFont(font)
        }

        val cellStyle = workbook.createCellStyle().apply {
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
        }

        val packageNameStyle = workbook.createCellStyle().apply {
            alignment = HorizontalAlignment.LEFT
            verticalAlignment = VerticalAlignment.CENTER
        }

        val summaryStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.LIGHT_CORNFLOWER_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            val font = workbook.createFont()
            font.bold = true
            setFont(font)
        }

        val summaryNameStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.LIGHT_CORNFLOWER_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.LEFT
            verticalAlignment = VerticalAlignment.CENTER
            val font = workbook.createFont()
            font.bold = true
            setFont(font)
        }

        // ── 创建工作表 ──
        val sheet = workbook.createSheet("使用时长")

        // ── 表头行 ──
        val headerRow = sheet.createRow(0)
        headerRow.createCell(0).apply {
            setCellValue("包名")
            cellStyle = headerStyle
        }
        for (i in 0 until 24) {
            headerRow.createCell(i + 1).apply {
                setCellValue(String.format("%02d:00-%02d:00", i, i + 1))
                cellStyle = headerStyle
            }
        }
        headerRow.createCell(25).apply {
            setCellValue("总时长")
            cellStyle = headerStyle
        }

        // ── 数据行 ──
        for ((rowIndex, row) in table.rows.withIndex()) {
            val excelRow = sheet.createRow(rowIndex + 1)
            excelRow.createCell(0).apply {
                setCellValue(row.packageName)
                cellStyle = packageNameStyle
            }
            for (hour in 0 until 24) {
                excelRow.createCell(hour + 1).apply {
                    setCellValue(formatMillisForCell(row.hourlyMillis[hour]))
                    cellStyle = cellStyle
                }
            }
            excelRow.createCell(25).apply {
                setCellValue(formatMillisForCell(row.totalMillis))
                cellStyle = cellStyle
            }
        }

        // ── 汇总行 ──
        val summaryRowIndex = table.rows.size + 1
        val summaryRow = sheet.createRow(summaryRowIndex)
        summaryRow.createCell(0).apply {
            setCellValue("单小时总时长")
            cellStyle = summaryNameStyle
        }
        for (hour in 0 until 24) {
            summaryRow.createCell(hour + 1).apply {
                setCellValue(formatMillisForCell(table.summaryRow.hourlyMillis[hour]))
                cellStyle = summaryStyle
            }
        }
        summaryRow.createCell(25).apply {
            setCellValue(formatMillisForCell(table.summaryRow.totalMillis))
            cellStyle = summaryStyle
        }

        // ── 列宽自适应 ──
        sheet.setColumnWidth(0, 6000) // 包名列宽一些
        for (i in 1..25) {
            sheet.setColumnWidth(i, 3000)
        }

        // ── 写入文件 ──
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "使用时长_$timestamp.xlsx"
        val file = File(context.cacheDir, fileName)
        file.outputStream().use { workbook.write(it) }
        workbook.close()

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
