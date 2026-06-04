package com.whmdg.mczj.tools.ui

import android.graphics.Typeface
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.whmdg.mczj.tools.util.DiagnosticLog
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.subscribeAlways
import io.github.rosemoe.sora.langs.java.JavaLanguage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(filePath: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val file = remember { File(filePath) }
    var hasChanges by remember { mutableStateOf(false) }
    var editorRef by remember { mutableStateOf<CodeEditor?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }

    // 未保存时返回确认
    BackHandler {
        if (hasChanges) showSaveDialog = true else onBack()
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("未保存的修改") },
            text = { Text("文件已修改但尚未保存，是否保存？") },
            confirmButton = {
                TextButton(onClick = {
                    editorRef?.text?.toString()?.let { content ->
                        try {
                            file.writeText(content)
                            hasChanges = false
                            DiagnosticLog.log("TextEditor", "保存成功: $filePath")
                        } catch (e: Exception) {
                            Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    showSaveDialog = false
                    onBack()
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    onBack()
                }) { Text("不保存") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = file.name,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasChanges) showSaveDialog = true else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            editorRef?.text?.toString()?.let { content ->
                                try {
                                    file.writeText(content)
                                    hasChanges = false
                                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                                    DiagnosticLog.log("TextEditor", "保存成功: $filePath")
                                } catch (e: Exception) {
                                    Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = hasChanges
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "保存",
                            tint = if (hasChanges) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            )
        }
    ) { padding ->
        val fileContent = remember {
            try {
                file.readText()
            } catch (e: Exception) {
                DiagnosticLog.log("TextEditor", "读取失败: $filePath ${e.message}")
                ""
            }
        }
        val langExt = remember { file.extension.lowercase() }

        AndroidView(
            factory = { ctx ->
                CodeEditor(ctx).apply {
                    setText(fileContent)
                    typefaceText = Typeface.MONOSPACE
                    // 根据后缀设置语言
                    setEditorLanguage(
                        if (langExt in listOf("java", "kt", "kts")) JavaLanguage()
                        else null
                    )
                    // 监听文本变化
                    subscribeAlways<ContentChangeEvent> {
                        if (!hasChanges) hasChanges = true
                    }
                    editorRef = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }

    // 释放编辑器资源
    DisposableEffect(Unit) {
        onDispose {
            editorRef?.release()
            editorRef = null
        }
    }
}
