package com.whmdg.mczj.tools.ui.filemanager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.whmdg.mczj.tools.ui.FileEntry
import com.whmdg.mczj.tools.ui.theme.DialogWidthFraction

/**
 * 临时诊断弹窗：分析文件名换行行为。
 * 测试完毕后删除此文件 + 移除调用点引用。
 */
@Composable
fun TextWrapDiagnosticDialog(
    show: Boolean,
    currentPath: String,
    entries: List<FileEntry>,
    fileNameFontSize: Float,
    onDismiss: () -> Unit
) {
    if (!show) return

    val clipboardManager = LocalClipboardManager.current
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // 找出最长文件名
    val longestEntry = entries.maxByOrNull { it.name.length }
    val longestName = longestEntry?.name ?: "(空目录)"
    val longestLen = longestName.length

    // 模拟测量：用 TextMeasurer 在不同约束下测量文本
    val fontSize = fileNameFontSize.sp
    val textStyle = TextStyle(fontSize = fontSize, textAlign = TextAlign.Center)

    // 假设屏幕宽度 1080px，density 3x → 360dp
    // Row 中 weight(4f) 占 4/5，padding 16dp 每侧
    // 实际可用宽度 ≈ (screenWidth - 32dp) * 4/5
    val screenWidthDp = 360  // 默认估算
    val paddingDp = 16 * 2   // horizontal padding
    val iconWeight = 1f
    val nameWeight = 4f
    val sideSpacerWeight = 0.5f * 2
    val totalWeight = sideSpacerWeight + iconWeight + nameWeight  // 0.5+1+4 = 5.5
    val availableWidthDp = ((screenWidthDp - paddingDp) * nameWeight / totalWeight).toInt()

    // 无约束测量（文本固有宽度）
    val unconstrainedResult = textMeasurer.measure(
        text = longestName,
        style = textStyle,
        constraints = Constraints(maxWidth = Int.MAX_VALUE),
        maxLines = Int.MAX_VALUE
    )
    val intrinsicWidthDp = with(density) { unconstrainedResult.size.width.toDp().value.toInt() }
    val intrinsicLineCount = unconstrainedResult.lineCount

    // 模拟可用宽度约束下测量（maxLines=2）
    val constrainedWidthPx = with(density) { availableWidthDp.dp.roundToPx() }
    val constrainedResult = textMeasurer.measure(
        text = longestName,
        style = textStyle,
        constraints = Constraints(maxWidth = constrainedWidthPx),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
    val constrainedLineCount = constrainedResult.lineCount
    val didOverflowWidth = constrainedResult.didOverflowWidth
    val didOverflowHeight = constrainedResult.didOverflowHeight
    val firstLineWidth = with(density) { constrainedResult.getLineRight(0).toInt().toDp().value.toInt() }
    val firstLineEnd = constrainedResult.getLineEnd(0, visibleEnd = true)
    val isLine0Ellipsized = constrainedResult.isLineEllipsized(0)
    val isLine1Ellipsized = if (constrainedLineCount > 1) constrainedResult.isLineEllipsized(1) else false

    // 实际像素值
    val constrainedWidthDp = with(density) { constrainedWidthPx.toDp().value.toInt() }

    // 行高计算
    val lineHeightPx = unconstrainedResult.getLineBottom(0) - unconstrainedResult.getLineTop(0)
    val lineHeightDp = with(density) { lineHeightPx.toDp().value.toInt() }

    // 构建报告
    val report = buildString {
        appendLine("═══ 文件名换行诊断报告 ═══")
        appendLine()
        appendLine("【当前路径】$currentPath")
        appendLine("【目录条目数】${entries.size}")
        appendLine()
        appendLine("【最长文件名】")
        appendLine("  名称: $longestName")
        appendLine("  字符数: $longestLen")
        appendLine("  是否目录: ${longestEntry?.isDirectory}")
        appendLine("  完整路径: ${longestEntry?.path}")
        appendLine()
        appendLine("【字体设置】")
        appendLine("  fileNameFontSize: ${fileNameFontSize}sp")
        appendLine("  行高: ${lineHeightDp}dp (${lineHeightPx}px)")
        appendLine()
        appendLine("【宽度估算】")
        appendLine("  假设屏幕宽度: ${screenWidthDp}dp")
        appendLine("  padding: ${paddingDp}dp")
        appendLine("  图标 weight: ${iconWeight}f")
        appendLine("  文件名 weight: ${nameWeight}f")
        appendLine("  可用宽度(估算): ${availableWidthDp}dp")
        appendLine()
        appendLine("【无约束测量（文本固有宽度）】")
        appendLine("  固有宽度: ${intrinsicWidthDp}dp")
        appendLine("  固有行数: ${intrinsicLineCount}")
        appendLine("  宽度/可用宽度: ${intrinsicWidthDp}/${availableWidthDp}dp")
        appendLine("  需要换行: ${intrinsicWidthDp > availableWidthDp}")
        appendLine()
        appendLine("【约束测量（maxLines=2, overflow=Ellipsis）】")
        appendLine("  约束宽度: ${constrainedWidthDp}dp")
        appendLine("  实际行数: ${constrainedLineCount}")
        appendLine("  didOverflowWidth: ${didOverflowWidth}")
        appendLine("  didOverflowHeight: ${didOverflowHeight}")
        appendLine("  第一行宽度: ${firstLineWidth}dp")
        appendLine("  第一行结束位置(字符索引): ${firstLineEnd}")
        appendLine("  第0行是否省略: ${isLine0Ellipsized}")
        appendLine("  第1行是否省略: ${isLine1Ellipsized}")
        appendLine()
        appendLine("【第一行内容】")
        appendLine("  \"${longestName.take(firstLineEnd)}\"")
        if (firstLineEnd < longestLen) {
            appendLine("【第二行内容】")
            appendLine("  \"${longestName.substring(firstLineEnd)}\"")
        }
        appendLine()
        appendLine("【诊断结论】")
        if (intrinsicWidthDp <= availableWidthDp) {
            appendLine("  → 文本固有宽度 <= 可用宽度，不需要换行")
            appendLine("  → 这是正常的 1 行显示")
        } else if (constrainedLineCount <= 1) {
            appendLine("  → 文本需要换行但约束测量只有 1 行")
            appendLine("  → 可能原因：Row 中 weight(4f) 分配的实际宽度不足")
            appendLine("  → 或者 Text 的 minLines/固有尺寸影响了布局")
        } else if (constrainedLineCount >= 2 && !isLine1Ellipsized) {
            appendLine("  → 约束测量显示 2 行，无省略 → 应该能正常显示 2 行")
            appendLine("  → 如果 UI 上只显示 1 行，问题在实际布局约束与模拟不同")
        } else if (isLine0Ellipsized || isLine1Ellipsized) {
            appendLine("  → 文本被省略号截断")
            if (isLine0Ellipsized) appendLine("  → 第 0 行就被省略，宽度严重不足")
        }
        if (didOverflowHeight) {
            appendLine("  → didOverflowHeight=true，高度不够容纳所有行")
            appendLine("  → Column 的 height(80.dp) 可能不足")
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(DialogWidthFraction),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("文件名换行诊断", style = MaterialTheme.typography.titleLarge)

                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = report,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }

                // 实际 Text 组件预览（与 FileEntryRow 中完全相同的参数）
                Text("── 实际 Text 组件预览 ──", style = MaterialTheme.typography.labelMedium)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 2.dp,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    var actualLineCount by remember { mutableIntStateOf(0) }
                    var actualDidOverflow by remember { mutableStateOf(false) }
                    var actualIsEllipsized by remember { mutableStateOf(false) }

                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = longestName,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = fileNameFontSize.sp,
                            textAlign = TextAlign.Center,
                            onTextLayout = { result: TextLayoutResult ->
                                actualLineCount = result.lineCount
                                actualDidOverflow = result.didOverflowWidth || result.didOverflowHeight
                                actualIsEllipsized = (0 until result.lineCount).any { result.isLineEllipsized(it) }
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "实际行数: $actualLineCount | 溢出: $actualDidOverflow | 省略: $actualIsEllipsized",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(report))
                    }) { Text("复制") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}
