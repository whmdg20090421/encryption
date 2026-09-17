package com.whmdg.mczj.tools.ui.viewer

import android.graphics.Typeface
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.whmdg.mczj.tools.encryption.core.FileCodec
import com.whmdg.mczj.tools.encryption.services.VaultKeyHolder
import com.whmdg.mczj.tools.util.DiagnosticLog
import com.whmdg.mczj.tools.util.TextEncodingDetector
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.subscribeAlways
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import java.io.File
import java.nio.charset.Charset

private const val TOP_BAR_HEIGHT_DP = 48
private const val FILE_INFO_BAR_HEIGHT_DP = 24

private data class FileState(
    val content: String,
    val encodingName: String,
    val charset: Charset,
    val bom: ByteArray
)

@Composable
fun TextEditorScreen(
    filePath: String,
    vaultSessionId: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val file = remember { File(filePath) }
    val wordwrapEnabled = remember { true }

    var hasChanges by remember { mutableStateOf(false) }
    var undoAvailable by remember { mutableStateOf(false) }
    var redoAvailable by remember { mutableStateOf(false) }
    var cursorLine by remember { mutableStateOf(1) }
    var cursorColumn by remember { mutableStateOf(1) }
    var editorRef by remember { mutableStateOf<CodeEditor?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }

    // 编辑器色板跟随当前主题亮度
    val isDarkMode = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // 同步撤销/重做可用态。
    // 注意：CodeEditor.undo()/redo() 内部会设置 ignoreModification，不派发 ContentChangeEvent，
    // 因此点击按钮后必须显式刷新，否则状态会滞后一拍。
    fun syncHistoryState(editor: CodeEditor?) {
        undoAvailable = editor?.canUndo() == true
        redoAvailable = editor?.canRedo() == true
    }

    // 读取文件并检测编码
    val fileState = remember {
        try {
            val bytes = file.readBytes()
            val detect = TextEncodingDetector.detect(bytes)
            val charset: Charset = detect.charset ?: Charsets.UTF_8
            // 剥离 BOM 后解码显示，保存时按 detect.bom 原样补回
            val contentBytes = if (detect.hasBom) bytes.copyOfRange(detect.bom.size, bytes.size) else bytes
            FileState(
                content = String(contentBytes, charset),
                encodingName = detect.displayName,
                charset = charset,
                bom = detect.bom
            )
        } catch (e: Exception) {
            DiagnosticLog.log("TextEditor", "读取失败: $filePath ${e.message}")
            FileState("", "未知", Charsets.UTF_8, ByteArray(0))
        }
    }
    val fileContent = fileState.content
    val encodingName = fileState.encodingName
    val encoding = fileState.charset

    fun saveContent() {
        editorRef?.text?.toString()?.let { content ->
            try {
                val output = fileState.bom + content.toByteArray(encoding)
                if (vaultSessionId != null) {
                    val ctx = VaultKeyHolder.get(vaultSessionId)
                    if (ctx != null) {
                        file.writeBytes(output)
                        FileCodec.encrypt(
                            src = file,
                            dst = File(ctx.originalEncryptedPath),
                            dek = ctx.dek,

                            customEncryption = ctx.customEncryption
                        )
                    } else {
                        Toast.makeText(context, "保险箱会话已过期", Toast.LENGTH_SHORT).show()
                        return
                    }
                } else {
                    file.writeBytes(output)
                }
                hasChanges = false
                DiagnosticLog.log("TextEditor", "保存成功: $filePath")
            } catch (e: Exception) {
                Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
                    saveContent()
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

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TOP_BAR_HEIGHT_DP.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (hasChanges) showSaveDialog = true else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        editorRef?.undo()
                        syncHistoryState(editorRef)
                    }, enabled = undoAvailable) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "撤销",
                            tint = if (undoAvailable) MaterialTheme.colorScheme.onSurface
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                    IconButton(onClick = {
                        editorRef?.redo()
                        syncHistoryState(editorRef)
                    }, enabled = redoAvailable) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "重做",
                            tint = if (redoAvailable) MaterialTheme.colorScheme.onSurface
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                    IconButton(onClick = { saveContent() }, enabled = hasChanges) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "保存",
                            tint = if (hasChanges) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                    IconButton(onClick = { /* 仅 UI，无功能 */ }) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                    IconButton(onClick = { /* 仅 UI，无功能 */ }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(FILE_INFO_BAR_HEIGHT_DP.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = file.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "$cursorLine:$cursorColumn  $encodingName",
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        AndroidView(
            factory = { ctx ->
                CodeEditor(ctx).apply {
                    setText(fileContent)
                    typefaceText = Typeface.MONOSPACE
                    setWordwrap(wordwrapEnabled)
                    if (isDarkMode) colorScheme = SchemeDarcula()
                    setEditorLanguage(
                        if (file.extension.lowercase() in listOf("java", "kt", "kts")) JavaLanguage()
                        else null
                    )
                    subscribeAlways<ContentChangeEvent> { _ ->
                        if (!hasChanges) hasChanges = true
                        syncHistoryState(this)
                    }
                    subscribeAlways<SelectionChangeEvent> { event ->
                        cursorLine = event.left.line + 1
                        cursorColumn = event.left.column + 1
                    }
                    editorRef = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
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
