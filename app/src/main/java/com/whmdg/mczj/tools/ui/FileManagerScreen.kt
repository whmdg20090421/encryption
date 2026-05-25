package com.whmdg.mczj.tools.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.whmdg.mczj.tools.util.DiagnosticLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import java.io.File

enum class FocusedPanel { LEFT, RIGHT }

data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var hasStoragePermission by remember {
        mutableStateOf(Environment.isExternalStorageManager())
    }

    val defaultPath = "/storage/emulated/0"
    var leftPath by remember { mutableStateOf(defaultPath) }
    var rightPath by remember { mutableStateOf(defaultPath) }
    var leftEntries by remember { mutableStateOf(listOf<FileEntry>()) }
    var rightEntries by remember { mutableStateOf(listOf<FileEntry>()) }
    var focusedPanel by remember { mutableStateOf(FocusedPanel.LEFT) }
    var showHiddenFiles by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<Throwable?>(null) }

    // 引擎选择：Root or POSIX
    val sp = remember { context.getSharedPreferences("special_permissions", Context.MODE_PRIVATE) }
    val permissionLevel = sp.getString("target_permission_level", "NORMAL") ?: "NORMAL"
    val isRootEngine = remember {
        permissionLevel == "ROOT" && SpecialPermissionVerifier.isRootAvailable()
    }

    // ── 普通引擎：File.listFiles（公开 API，无 hidden API 限制） ──
    fun listWithFile(path: String, showHidden: Boolean): List<FileEntry> {
        DiagnosticLog.log("FileEngine", "listFiles($path) showHidden=$showHidden")
        val normalizedPath = path.trimEnd('/')
        val dir = File(normalizedPath)

        if (!dir.exists()) {
            DiagnosticLog.log("FileEngine", "目录不存在: $normalizedPath")
            loadError = RuntimeException("目录不存在: $normalizedPath")
            return emptyList()
        }
        if (!dir.isDirectory) {
            DiagnosticLog.log("FileEngine", "路径不是目录: $normalizedPath")
            loadError = RuntimeException("路径不是目录: $normalizedPath")
            return emptyList()
        }

        val children = try {
            dir.listFiles()
        } catch (e: SecurityException) {
            DiagnosticLog.log("FileEngine", "listFiles SecurityException: ${e.message}")
            loadError = e
            return emptyList()
        }
        if (children == null) {
            DiagnosticLog.log("FileEngine", "listFiles 返回 null（权限不足或 I/O 错误）")
            loadError = RuntimeException("无法列出目录（权限不足）: $normalizedPath")
            return emptyList()
        }
        DiagnosticLog.log("FileEngine", "listFiles 返回 ${children.size} 项")

        val entries = mutableListOf<FileEntry>()
        var dirCount = 0
        var fileCount = 0
        var skipHidden = 0
        for (child in children) {
            val name = child.name
            if (!showHidden && name.startsWith(".")) { skipHidden++; continue }
            val isDir = try {
                child.isDirectory
            } catch (e: Exception) {
                DiagnosticLog.log("FileEngine", "isDirectory 异常 $name: ${e.message}")
                false
            }
            if (isDir) dirCount++ else fileCount++
            entries.add(FileEntry(child.absolutePath, name, isDir))
        }
        DiagnosticLog.log("FileEngine", "统计: dirs=$dirCount, files=$fileCount, hidden 过滤=$skipHidden")

        // 添加 ".." 父目录（仅当父目录可访问）
        val parentPath = normalizedPath.substringBeforeLast('/')
        if (parentPath.isNotEmpty() && normalizedPath != parentPath) {
            val parentFile = File(parentPath)
            val parentAccessible = try {
                parentFile.canRead()
            } catch (_: Exception) { false }
            if (parentAccessible) {
                entries.add(0, FileEntry(parentPath, "..", true))
            } else {
                DiagnosticLog.log("FileEngine", "父目录不可读: $parentPath")
            }
        }

        entries.sortWith(
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
        return entries
    }

    // ── Root 引擎：su -c ls ──
    fun listWithRoot(path: String, showHidden: Boolean): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        val normalizedPath = path.trimEnd('/')
        val escapedPath = normalizedPath.replace("'", "'\\''")

        try {
            // 把路径赋给 shell 变量 P，再用双引号引用，确保 $f 能正常展开
            val command = """
P='${escapedPath}'
ls -1a "${'$'}P" | while IFS= read -r f; do
    if [ "${'$'}f" != "." ] && [ "${'$'}f" != ".." ]; then
        if [ -d "${'$'}P/${'$'}f" ]; then echo "DIR|${'$'}f"; else echo "FIL|${'$'}f"; fi
    fi
done
""".trimIndent()
            DiagnosticLog.log("RootEngine", "su 命令:\n$command")
            val output = SpecialPermissionVerifier.executeRootCommand(command)
            DiagnosticLog.log("RootEngine", "su 输出 ${output.length} 字符 (前 200): ${output.take(200)}")
            val lines = output.lines().filter { it.isNotBlank() }

            var dirCount = 0
            var fileCount = 0
            for (line in lines) {
                val sep = line.indexOf('|')
                if (sep < 0) continue
                val type = line.substring(0, sep)
                val name = line.substring(sep + 1)
                if (!showHidden && name.startsWith(".")) continue
                val isDir = type == "DIR"
                if (isDir) dirCount++ else fileCount++
                entries.add(FileEntry("$normalizedPath/$name", name, isDir))
            }
            DiagnosticLog.log("RootEngine", "解析结果: dirs=$dirCount, files=$fileCount")
        } catch (e: Exception) {
            DiagnosticLog.log("RootEngine", "异常: ${e.javaClass.simpleName}: ${e.message}")
            loadError = e
            return emptyList()
        }

        // 父目录（Root 模式不做边界限制）
        val parentPath = normalizedPath.substringBeforeLast('/')
        if (parentPath.isNotEmpty() && normalizedPath != parentPath) {
            entries.add(0, FileEntry(parentPath, "..", true))
        }

        entries.sortWith(
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
        return entries
    }

    // ── 统一入口 ──
    fun listDirectory(path: String): List<FileEntry> {
        DiagnosticLog.log("FileMgr", ">>> listDirectory START path=$path engine=${if (isRootEngine) "ROOT" else "FILE"}")
        loadError = null
        val t0 = System.currentTimeMillis()
        val entries = if (isRootEngine) {
            listWithRoot(path, showHiddenFiles)
        } else {
            listWithFile(path, showHiddenFiles)
        }
        val took = System.currentTimeMillis() - t0
        DiagnosticLog.log("FileMgr", "<<< listDirectory END path=$path entries=${entries.size} took=${took}ms err=${loadError?.javaClass?.simpleName}")
        // File 引擎失败时（典型：权限不足）自动尝试 Root 兜底
        if (entries.isEmpty() && loadError != null && !isRootEngine && SpecialPermissionVerifier.isRootAvailable()) {
            DiagnosticLog.log("FileMgr", "File 引擎失败，回退到 ROOT 引擎")
            loadError = null
            return listWithRoot(path, showHiddenFiles)
        }
        return entries
    }

    fun openFile(context: Context, entry: FileEntry) {
        DiagnosticLog.log("OpenFile", "请求打开: ${entry.path}")
        val file = File(entry.path)
        if (entry.name.endsWith(".apk", ignoreCase = true) ||
            entry.name.endsWith(".apex", ignoreCase = true)
        ) {
            DiagnosticLog.log("OpenFile", "拒绝打开 apk/apex: ${entry.name}")
            Toast.makeText(context, "APK 文件请在应用管理器中安装", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val extension = entry.name.substringAfterLast('.', "").lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "*/*"
            DiagnosticLog.log("OpenFile", "uri=$uri ext='$extension' mime=$mimeType")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val resolver = intent.resolveActivity(context.packageManager)
            DiagnosticLog.log("OpenFile", "resolveActivity=${resolver?.flattenToString() ?: "(null)"}")
            if (resolver != null) {
                context.startActivity(intent)
                DiagnosticLog.log("OpenFile", "startActivity 已调用")
            } else {
                Toast.makeText(context, "没有应用可以打开此文件", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            DiagnosticLog.log("OpenFile", "异常: ${e.javaClass.simpleName}: ${e.message}")
            Toast.makeText(context, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        hasStoragePermission = Environment.isExternalStorageManager()
        if (hasStoragePermission) {
            leftEntries = listDirectory(leftPath)
            rightEntries = listDirectory(rightPath)
        }
    }

    LaunchedEffect(leftPath, showHiddenFiles) {
        DiagnosticLog.log("FileMgr", "LaunchedEffect[LEFT] 触发 path=$leftPath showHidden=$showHiddenFiles")
        leftEntries = listDirectory(leftPath)
        DiagnosticLog.log("FileMgr", "LaunchedEffect[LEFT] 完成 entries=${leftEntries.size}")
    }
    LaunchedEffect(rightPath, showHiddenFiles) {
        DiagnosticLog.log("FileMgr", "LaunchedEffect[RIGHT] 触发 path=$rightPath showHidden=$showHiddenFiles")
        rightEntries = listDirectory(rightPath)
        DiagnosticLog.log("FileMgr", "LaunchedEffect[RIGHT] 完成 entries=${rightEntries.size}")
    }

    LaunchedEffect(Unit) {
        DiagnosticLog.beginSession("进入 FileManagerScreen")
        DiagnosticLog.log("FileMgr", "FileManagerScreen 启动 isRootEngine=$isRootEngine permissionLevel=$permissionLevel hasStoragePerm=$hasStoragePermission")
        if (!hasStoragePermission) {
            Toast.makeText(context, "需要存储权限才能浏览文件", Toast.LENGTH_LONG).show()
        }
    }

    val currentPath = when (focusedPanel) {
        FocusedPanel.LEFT -> leftPath
        FocusedPanel.RIGHT -> rightPath
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    StartEllipsisText(
                        text = currentPath,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Home, contentDescription = "返回主页")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSettingsMenu = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                        DropdownMenu(
                            expanded = showSettingsMenu,
                            onDismissRequest = { showSettingsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("显示隐藏文件") },
                                trailingIcon = {
                                    if (showHiddenFiles) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    showHiddenFiles = !showHiddenFiles
                                    showSettingsMenu = false
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasStoragePermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("需要存储权限", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "文件管理器需要「管理所有文件」权限才能浏览设备文件。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = {
                        val intent = android.content.Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                        ).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        }
                        permissionLauncher.launch(intent)
                    }) {
                        Text("授予权限")
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    FileBrowserPanel(
                        entries = leftEntries,
                        isFocused = focusedPanel == FocusedPanel.LEFT,
                        onFocus = { focusedPanel = FocusedPanel.LEFT },
                        onFolderClick = { entry ->
                            DiagnosticLog.beginSession("[LEFT] 点击文件夹 '${entry.name}'")
                            DiagnosticLog.log("FileMgr", "[LEFT] 点击文件夹 name='${entry.name}' path='${entry.path}' from=$leftPath")
                            focusedPanel = FocusedPanel.LEFT
                            leftPath = entry.path
                        },
                        onFileClick = { entry ->
                            DiagnosticLog.beginSession("[LEFT] 点击文件 '${entry.name}'")
                            DiagnosticLog.log("FileMgr", "[LEFT] 点击文件 name='${entry.name}' path='${entry.path}'")
                            focusedPanel = FocusedPanel.LEFT
                            openFile(context, entry)
                        },
                        modifier = Modifier.weight(1f)
                    )

                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        thickness = 1.dp
                    )

                    FileBrowserPanel(
                        entries = rightEntries,
                        isFocused = focusedPanel == FocusedPanel.RIGHT,
                        onFocus = { focusedPanel = FocusedPanel.RIGHT },
                        onFolderClick = { entry ->
                            DiagnosticLog.beginSession("[RIGHT] 点击文件夹 '${entry.name}'")
                            DiagnosticLog.log("FileMgr", "[RIGHT] 点击文件夹 name='${entry.name}' path='${entry.path}' from=$rightPath")
                            focusedPanel = FocusedPanel.RIGHT
                            rightPath = entry.path
                        },
                        onFileClick = { entry ->
                            DiagnosticLog.beginSession("[RIGHT] 点击文件 '${entry.name}'")
                            DiagnosticLog.log("FileMgr", "[RIGHT] 点击文件 name='${entry.name}' path='${entry.path}'")
                            focusedPanel = FocusedPanel.RIGHT
                            openFile(context, entry)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    ErrorDialog(error = loadError, onDismiss = {
        DiagnosticLog.log("FileMgr", "关闭错误对话框")
        loadError = null
    })
}

@Composable
private fun FileBrowserPanel(
    entries: List<FileEntry>,
    isFocused: Boolean,
    onFocus: () -> Unit,
    onFolderClick: (FileEntry) -> Unit,
    onFileClick: (FileEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onFocus)
    ) {
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "空目录",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(entries, key = { it.path }) { entry ->
                    FileEntryRow(
                        entry = entry,
                        isFocused = isFocused,
                        onClick = {
                            if (entry.isDirectory) onFolderClick(entry)
                            else onFileClick(entry)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FileEntryRow(
    entry: FileEntry,
    isFocused: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = if (isFocused) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun StartEllipsisText(
    text: String,
    modifier: Modifier = Modifier
) {
    var displayText by remember(text) { mutableStateOf(text) }

    Text(
        text = displayText,
        modifier = modifier,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        style = MaterialTheme.typography.titleMedium,
        onTextLayout = { result ->
            if (result.hasVisualOverflow) {
                val visible = result.getLineEnd(0)
                if (visible > 0 && visible <= text.length) {
                    displayText = "…${text.takeLast(visible - 1)}"
                }
            } else if (displayText != text) {
                displayText = text
            }
        }
    )
}
