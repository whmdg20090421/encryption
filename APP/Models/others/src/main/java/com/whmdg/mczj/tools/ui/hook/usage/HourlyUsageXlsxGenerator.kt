package com.whmdg.mczj.tools.ui.hook.usage

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 将 HourlyUsageTable 生成 XLSX 文件并通过系统分享
 *
 * XLSX = ZIP 包含多个 XML 文件，不依赖 Apache POI。
 */
object HourlyUsageXlsxGenerator {

    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_PKG = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val NS_CT = "http://schemas.openxmlformats.org/package/2006/content-types"

    fun generate(context: Context, table: HourlyUsageTable): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(context.cacheDir, "使用时长_$timestamp.xlsx")

        // 收集所有共享字符串
        val sharedStrings = mutableListOf<String>()
        val ssIndex = mutableMapOf<String, Int>()

        fun ssIndex(value: String): Int {
            return ssIndex.getOrPut(value) {
                sharedStrings.add(value)
                sharedStrings.size - 1
            }
        }

        // 预注册所有字符串
        ssIndex("包名")
        for (i in 0 until 24) ssIndex(String.format("%02d:00-%02d:00", i, i + 1))
        ssIndex("总时长")
        ssIndex("单小时总时长")
        for (row in table.rows) {
            ssIndex(row.packageName)
            for (hour in 0 until 24) ssIndex(formatMillis(row.hourlyMillis[hour]))
            ssIndex(formatMillis(row.totalMillis))
        }
        for (hour in 0 until 24) ssIndex(formatMillis(table.summaryRow.hourlyMillis[hour]))
        ssIndex(formatMillis(table.summaryRow.totalMillis))

        ZipOutputStream(file.outputStream()).use { zos ->
            // [Content_Types].xml
            zos.putEntry("[Content_Types].xml") {
                writeXmlDecl()
                tag("Types", "xmlns" to NS_CT) {
                    ext("rels", "application/vnd.openxmlformats-package.relationships+xml")
                    ext("xml", "application/xml")
                    ext("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml")
                    override("/xl/worksheets/sheet1.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml")
                    override("/xl/styles.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml")
                    override("/xl/sharedStrings.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml")
                }
            }

            // _rels/.rels
            zos.putEntry("_rels/.rels") {
                writeXmlDecl()
                tag("Relationships", "xmlns" to NS_PKG) {
                    rel("rId1", NS_R, "/xl/workbook.xml")
                }
            }

            // xl/_rels/workbook.xml.rels
            zos.putEntry("xl/_rels/workbook.xml.rels") {
                writeXmlDecl()
                tag("Relationships", "xmlns" to NS_PKG) {
                    rel("rId1", NS_R + "/worksheet", "worksheets/sheet1.xml")
                    rel("rId2", NS_R + "/styles", "styles.xml")
                    rel("rId3", NS_R + "/sharedStrings", "sharedStrings.xml")
                }
            }

            // xl/workbook.xml
            zos.putEntry("xl/workbook.xml") {
                writeXmlDecl()
                tag("workbook", "xmlns" to NS, "xmlns:r" to NS_R) {
                    tag("sheets") {
                        selfClosing("sheet", "name" to "使用时长", "sheetId" to "1", "r:id" to "rId1")
                    }
                }
            }

            // xl/styles.xml（最小化样式：粗体 + 居中）
            zos.putEntry("xl/styles.xml") {
                writeXmlDecl()
                tag("styleSheet", "xmlns" to NS) {
                    tag("fonts", "count" to "1") {
                        tag("font") { selfClosing("b") }
                    }
                    tag("fills", "count" to "2") {
                        tag("fill") { selfClosing("patternFill", "patternType" to "none") }
                        tag("fill") { tag("patternFill", "patternType" to "solid") { selfClosing("fgColor", "rgb" to "FFD9E1F2") } }
                    }
                    tag("cellStyleXfs", "count" to "1") { selfClosing("xf") }
                    tag("cellXfs", "count" to "3") {
                        selfClosing("xf", "numFmtId" to "0") // 0=默认
                        selfClosing("xf", "numFmtId" to "0", "fontId" to "0", "applyFont" to "1") // 1=粗体
                        selfClosing("xf", "numFmtId" to "0", "fontId" to "0", "fillId" to "1", "applyFont" to "1", "applyFill" to "1") // 2=粗体+背景
                    }
                }
            }

            // xl/sharedStrings.xml
            zos.putEntry("xl/sharedStrings.xml") {
                writeXmlDecl()
                tag("sst", "xmlns" to NS, "count" to sharedStrings.size.toString(), "uniqueCount" to sharedStrings.size.toString()) {
                    for (s in sharedStrings) {
                        tag("si") { tag("t") { text(s) } }
                    }
                }
            }

            // xl/worksheets/sheet1.xml
            zos.putEntry("xl/worksheets/sheet1.xml") {
                writeXmlDecl()
                tag("worksheet", "xmlns" to NS) {
                    tag("cols") {
                        col(1, 1, 22)   // 包名列宽
                        for (i in 2..25) col(i, i, 14) // 时间列宽
                        col(26, 26, 14) // 总时长列宽
                    }
                    tag("sheetData") {
                        // 表头行
                        row(1) {
                            cell("A1", ssIndex("包名"), 1)
                            for (i in 0 until 24) {
                                cell("${'B' + i}1", ssIndex(String.format("%02d:00-%02d:00", i, i + 1)), 1)
                            }
                            cell("Z1", ssIndex("总时长"), 1)
                        }
                        // 数据行
                        for ((rowIdx, row) in table.rows.withIndex()) {
                            val r = rowIdx + 2
                            row(r) {
                                cell("A$r", ssIndex(row.packageName), 0)
                                for (hour in 0 until 24) {
                                    cell("${'B' + hour}$r", ssIndex(formatMillis(row.hourlyMillis[hour])), 0)
                                }
                                cell("Z$r", ssIndex(formatMillis(row.totalMillis)), 0)
                            }
                        }
                        // 汇总行
                        val sr = table.rows.size + 2
                        row(sr) {
                            cell("A$sr", ssIndex("单小时总时长"), 2)
                            for (hour in 0 until 24) {
                                cell("${'B' + hour}$sr", ssIndex(formatMillis(table.summaryRow.hourlyMillis[hour])), 2)
                            }
                            cell("Z$sr", ssIndex(formatMillis(table.summaryRow.totalMillis)), 2)
                        }
                    }
                }
            }
        }

        return file
    }

    fun createShareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "使用时长统计")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun formatMillis(millis: Long): String {
        if (millis <= 0) return ""
        val h = millis / 3600000
        val m = (millis % 3600000) / 60000
        val s = (millis % 60000) / 1000
        return when {
            h > 0 -> "${h}h${m}m${s}s"
            m > 0 -> "${m}m${s}s"
            else -> "${s}s"
        }
    }

    // ── XML 构建工具 ──

    private fun OutputStream.writeXmlDecl() {
        write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>".toByteArray())
    }

    private inline fun ZipOutputStream.putEntry(name: String, block: OutputStream.() -> Unit) {
        putNextEntry(ZipEntry(name))
        block(this)
        closeEntry()
    }

    private inline fun OutputStream.tag(name: String, vararg attrs: Pair<String, String>, block: OutputStream.() -> Unit) {
        val attrStr = if (attrs.isEmpty()) "" else " " + attrs.joinToString(" ") { "${it.first}=\"${it.second}\"" }
        write("<$name$attrStr>".toByteArray())
        block()
        write("</$name>".toByteArray())
    }

    private fun OutputStream.selfClosing(name: String, vararg attrs: Pair<String, String>) {
        val attrStr = if (attrs.isEmpty()) "" else " " + attrs.joinToString(" ") { "${it.first}=\"${it.second}\"" }
        write("<$name$attrStr/>".toByteArray())
    }

    private fun OutputStream.text(value: String) {
        write(value.xmlEscape().toByteArray())
    }

    private fun OutputStream.rel(id: String, type: String, target: String) {
        selfClosing("Relationship", "Id" to id, "Type" to type, "Target" to target)
    }

    private fun OutputStream.ext(ext: String, contentType: String) {
        selfClosing("Default", "Extension" to ext, "ContentType" to contentType)
    }

    private fun OutputStream.override(partName: String, contentType: String) {
        selfClosing("Override", "PartName" to partName, "ContentType" to contentType)
    }

    private fun OutputStream.col(min: Int, max: Int, width: Int) {
        selfClosing("col", "min" to min.toString(), "max" to max.toString(), "width" to width.toString(), "customWidth" to "1")
    }

    private inline fun OutputStream.row(r: Int, block: OutputStream.() -> Unit) {
        tag("row", "r" to r.toString()) { block() }
    }

    private fun OutputStream.cell(ref: String, ssIndex: Int, styleIndex: Int) {
        selfClosing("c", "r" to ref, "t" to "s", "s" to styleIndex.toString(), "v" to ssIndex.toString())
    }

    private fun String.xmlEscape(): String {
        return replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
