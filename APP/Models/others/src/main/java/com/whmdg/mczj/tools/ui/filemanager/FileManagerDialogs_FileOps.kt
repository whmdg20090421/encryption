package com.whmdg.mczj.tools.ui.filemanager

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.whmdg.mczj.tools.ui.theme.DialogWidthFraction
import com.whmdg.mczj.tools.fileop.FileOperationManager
import com.whmdg.mczj.tools.util.FormatUtils
import androidx.compose.foundation.shape.RoundedCornerShape

// ── 通用弹窗模板 ──
@Composable
internal fun StandardDialog(
    onDismissRequest: () -> Unit,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.fillMaxWidth(DialogWidthFraction),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                title?.let { Box { it() } }
                text?.let { Box(modifier = Modifier.weight(1f, fill = false)) { it() } }
                if (confirmButton != null || dismissButton != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        dismissButton?.invoke()
                        Spacer(Modifier.width(8.dp))
                        confirmButton?.invoke()
                    }
                }
            }
        }
    }
}

// ── 新建类型选择对话框 ──
@Composable
internal fun CreateTypeDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onSelect: (CreateMode) -> Unit
) {
    if (!show) return
    StandardDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建") },
        text = {
            Column {
                TextButton(
                    onClick = { onSelect(CreateMode.FILE) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("创建文件") }
                TextButton(
                    onClick = { onSelect(CreateMode.FOLDER) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("创建文件夹") }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ── 名称输入对话框 ──
@Composable
internal fun NameInputDialog(
    show: Boolean,
    createMode: CreateMode,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    if (!show) return
    var name by remember { mutableStateOf("") }
    StandardDialog(
        onDismissRequest = { name = ""; onDismiss() },
        title = { Text(if (createMode == CreateMode.FILE) "创建文件" else "创建文件夹") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()); name = "" },
                enabled = name.isNotBlank()
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = { name = ""; onDismiss() }) { Text("取消") }
        }
    )
}

// ── 重命名对话框 ──
@Composable
internal fun RenameDialog(
    show: Boolean,
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    if (!show) return
    var text by remember { mutableStateOf(currentName) }
    LaunchedEffect(currentName) { text = currentName }
    StandardDialog(
        onDismissRequest = { text = ""; onDismiss() },
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val newName = text.trim()
                if (newName.isNotBlank() && newName != currentName) onConfirm(newName)
                else onDismiss()
                text = ""
            }) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = { text = ""; onDismiss() }) { Text("取消") }
        }
    )
}

// ── 删除确认对话框 ──
@Composable
internal fun DeleteConfirmDialog(
    show: Boolean,
    isMultiDel: Boolean,
    delCount: Int,
    entryName: String,
    onDismiss: () -> Unit,
    onConfirm: (recycleBinEnabled: Boolean) -> Unit
) {
    if (!show) return
    var recycleBinEnabled by remember { mutableStateOf(true) }
    StandardDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除") },
        text = {
            Column {
                if (isMultiDel) Text("确定要删除选中的 $delCount 个项目吗？")
                else Text("确定要删除「$entryName」吗？")
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Checkbox(
                        checked = recycleBinEnabled,
                        onCheckedChange = { recycleBinEnabled = it }
                    )
                    Text("移动到回收站")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(recycleBinEnabled) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ── 删除进度对话框 ──
@Composable
internal fun DeleteProgressDialog(
    show: Boolean,
    isMultiDel: Boolean,
    entryName: String,
    onAutoDismiss: () -> Unit
) {
    if (!show) return
    StandardDialog(
        onDismissRequest = { /* 不可手动关闭 */ },
        title = { Text("删除") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(if (isMultiDel) "正在删除..." else "正在删除「$entryName」")
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
    val progress by FileOperationManager.progress.collectAsState()
    LaunchedEffect(progress) {
        if (progress == null) onAutoDismiss()
    }
}

// ── 复制/移动确认对话框 ──
@Composable
internal fun CopyMoveConfirmDialog(
    show: Boolean,
    isCopy: Boolean,
    sourcePaths: List<String>,
    targetDir: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (!show) return
    val sourceNames = sourcePaths.map { it.substringAfterLast('/') }
    val sourceDisplay = if (sourceNames.size == 1) sourceNames[0]
    else "${sourceNames[0]} 等 ${sourceNames.size} 个文件"
    val sourceDir = sourcePaths.firstOrNull()?.substringBeforeLast('/') ?: ""
    StandardDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCopy) "确认复制" else "确认移动") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (isCopy) "复制到以下目录：" else "移动到以下目录：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = targetDir,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Text("源文件：", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = sourceDisplay,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                Text(
                    text = sourceDir,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ── 复制/移动进度对话框 ──
@Composable
internal fun CopyMoveProgressDialog(
    show: Boolean,
    isDebugMode: Boolean,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onExtractReport: () -> Unit
) {
    if (!show) return
    val progress by FileOperationManager.progress.collectAsState()
    val lastSummary by FileOperationManager.lastSummary.collectAsState()
    var isCancelling by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }

    // 操作完成时显示摘要
    LaunchedEffect(progress) {
        if (progress == null && lastSummary != null) {
            showSummary = true
        }
    }

    if (showSummary && lastSummary != null) {
        val summary = lastSummary!!
        val sign = if (summary.phase.contains("删除")) "-" else "+"
        StandardDialog(
            onDismissRequest = { showSummary = false; onDismiss() },
            title = { Text("操作完成") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("${summary.phase.replace("正在", "")}完成")
                    Spacer(Modifier.height(8.dp))
                    Text("文件数：${summary.fileCount}")
                    Text("大小变化：$sign${FormatUtils.formatBytes(summary.totalBytes)}")
                }
            },
            confirmButton = {
                Button(onClick = { showSummary = false; onDismiss() }) {
                    Text("确定")
                }
            }
        )
        return
    }

    StandardDialog(
        onDismissRequest = { /* 不可手动关闭 */ },
        title = { Text(if (isCancelling) "正在取消" else progress?.phase ?: "处理中") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isCancelling) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "正在取消...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (progress != null) {
                    val p = progress!!
                    if (p.currentFileName.isNotEmpty()) {
                        Text(
                            text = p.currentFileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (p.isScanning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else if (p.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { p.fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (p.isScanning) {
                            if (p.totalBytes > 0) "${FormatUtils.formatBytes(p.totalBytes)} (正在统计)"
                            else "正在统计..."
                        } else {
                            "${FormatUtils.formatBytes(p.currentBytes)} / ${FormatUtils.formatBytes(p.totalBytes)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        },
        confirmButton = {
            if (!isCancelling) {
                TextButton(onClick = { isCancelling = true; onCancel() }) {
                    Text("取消", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            if (isDebugMode) {
                TextButton(onClick = onExtractReport) { Text("提取报告") }
            }
        }
    )
    LaunchedEffect(progress) {
        if (progress == null && lastSummary == null) onDismiss()
    }
}

// ── 强制删除确认对话框 ──
@Composable
internal fun ForceDeleteDialog(
    show: Boolean,
    entryName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (!show) return
    StandardDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除") },
        text = { Text("无法移动到回收站，是否永久删除？") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("是") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("否") }
        }
    )
}

// ── 回收站永久删除确认对话框 ──
@Composable
internal fun PermanentDeleteDialog(
    show: Boolean,
    isMultiDelete: Boolean,
    count: Int,
    targetName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (!show) return
    StandardDialog(
        onDismissRequest = onDismiss,
        title = { Text("永久删除") },
        text = {
            if (isMultiDelete) Text("确定要永久删除选中的 $count 个项目吗？此操作不可撤销。")
            else Text("确定要永久删除「$targetName」吗？此操作不可撤销。")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确定", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ── 添加快捷访问对话框 ──
@Composable
internal fun AddQuickAccessDialog(
    show: Boolean,
    existingNames: List<String>,
    isPathValid: (String) -> Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, path: String) -> Unit
) {
    if (!show) return
    var nameInput by remember { mutableStateOf("") }
    var pathInput by remember { mutableStateOf("") }

    fun normalizeQaPath(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val full = when {
            trimmed.startsWith("/storage/emulated/") ||
            trimmed.startsWith("/data/") ||
            trimmed.startsWith("/sdcard/") -> trimmed
            trimmed.startsWith("/") -> "/storage/emulated/0$trimmed"
            else -> "/storage/emulated/0/${trimmed.trimStart('/')}"
        }
        return if (full.endsWith("/") || full.endsWith("\\")) full.dropLast(1) else full
    }

    val name = nameInput.trim()
    val isDuplicate = name.isNotEmpty() && existingNames.any { it == name }
    val normalizedPath = normalizeQaPath(pathInput)
    val pathInvalid = pathInput.trim().isNotEmpty() && normalizedPath.isNotEmpty() && !isPathValid(normalizedPath)
    val canSubmit = name.isNotEmpty() && pathInput.trim().isNotEmpty() && !isDuplicate && !pathInvalid

    StandardDialog(
        onDismissRequest = { nameInput = ""; pathInput = ""; onDismiss() },
        title = { Text("添加快捷访问") },
        text = {
            Column {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("命名") },
                    singleLine = true,
                    isError = isDuplicate,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isDuplicate) {
                    Text(
                        "该名称已存在",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    label = { Text("绝对路径") },
                    singleLine = true,
                    isError = pathInvalid,
                    modifier = Modifier.fillMaxWidth()
                )
                if (pathInvalid) {
                    Text(
                        "当前文件夹路径似乎无效",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalPath = normalizeQaPath(pathInput)
                    if (name.isNotEmpty() && finalPath.isNotEmpty()) {
                        onConfirm(name, finalPath)
                        nameInput = ""; pathInput = ""
                    }
                },
                enabled = canSubmit
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = { nameInput = ""; pathInput = ""; onDismiss() }) { Text("取消") }
        }
    )
}
