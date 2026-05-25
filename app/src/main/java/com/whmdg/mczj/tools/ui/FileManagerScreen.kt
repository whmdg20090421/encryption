package com.whmdg.mczj.tools.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.system.ErrnoException
import android.system.Os  // 公开 API：stat() 等
import android.webkit.MimeTypeMap
import android.widget.Toast
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

/**
 * 通过反射调用被 @hide 标记的 POSIX 目录遍历 API（Os.opendir/readdir/closedir）。
 * 这些 API 在 Android 运行时中实际存在（libcore），但被隐藏在 SDK 桩中。
 */
private object PosixDir {
    private val osClass = Os::class.java
    private val opendir = osClass.getMethod("opendir", String::class.java)
    private val readdir = osClass.getMethod("readdir", java.io.FileDescriptor::class.java)
    private val closedir = osClass.getMethod("closedir", java.io.FileDescriptor::class.java)

    private val direntClass = Class.forName("android.system.StructDirent")
    private val dNameField = direntClass.getField("d_name")
    private val dTypeField = direntClass.getField("d_type")

    val DT_DIR: Int
    val DT_LNK: Int
    val DT_UNKNOWN: Int

    init {
        val oc = Class.forName("android.system.OsConstants")
        DT_DIR = oc.getField("DT_DIR").getInt(null)
        DT_LNK = oc.getField("DT_LNK").getInt(null)
        DT_UNKNOWN = oc.getField("DT_UNKNOWN").getInt(null)
    }

    fun opendir(path: String): java.io.FileDescriptor =
        opendir.invoke(null, path) as java.io.FileDescriptor

    fun readdir(fd: java.io.FileDescriptor): Any? {
        val result = readdir.invoke(null, fd)
        return result  // null = 目录遍历完毕
    }

    fun closedir(fd: java.io.FileDescriptor) =
        closedir.invoke(null, fd)

    fun dName(dirent: Any): String = dNameField.get(dirent) as String
    fun dType(dirent: Any): Int = dTypeField.getInt(dirent)
}

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

    // ── POSIX 引擎：Op.opendir/readdir（通过反射调用）逐项读取 ──
    fun listWithPosix(path: String, showHidden: Boolean): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        val normalizedPath = path.trimEnd('/')

        val dirStream = try {
            PosixDir.opendir(normalizedPath)
        } catch (e: ErrnoException) {
            loadError = RuntimeException("无法打开目录: ${e.message}")
            return emptyList()
        }

        try {
            while (true) {
                val dirent = try {
                    PosixDir.readdir(dirStream)
                } catch (e: ErrnoException) {
                    if (e.errno == android.system.OsConstants.EACCES) continue
                    else throw e
                }
                if (dirent == null) break

                val name = PosixDir.dName(dirent)
                if (name == "." || name == "..") continue
                if (!showHidden && name.startsWith(".")) continue

                val isDir = when (PosixDir.dType(dirent)) {
                    PosixDir.DT_DIR -> true
                    PosixDir.DT_LNK, PosixDir.DT_UNKNOWN -> {
                        // .apk 文件跳过 Os.stat() 回退——部分系统会触发 APK 扫描
                        if (name.endsWith(".apk", ignoreCase = true) || name.endsWith(".apex", ignoreCase = true)) {
                            false
                        } else try {
                            android.system.OsConstants.S_ISDIR(
                                Os.stat("$normalizedPath/$name").st_mode
                            )
                        } catch (_: ErrnoException) { false }
                    }
                    else -> false
                }

                entries.add(FileEntry("$normalizedPath/$name", name, isDir))
            }
        } finally {
            try { PosixDir.closedir(dirStream) } catch (_: Exception) {}
        }

        // 添加 ".." 父目录（仅当父目录可访问）
        val parentPath = normalizedPath.substringBeforeLast('/')
        if (parentPath.isNotEmpty() && normalizedPath != parentPath) {
            val parentAccessible = try {
                PosixDir.closedir(PosixDir.opendir(parentPath))
                true
            } catch (_: ErrnoException) { false }
            if (parentAccessible) {
                entries.add(0, FileEntry(parentPath, "..", true))
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
            val command = """
ls -1a '${escapedPath}' | while IFS= read -r f; do
    if [ "${'$'}f" != "." ] && [ "${'$'}f" != ".." ]; then
        if [ -d '${escapedPath}/${'$'}f' ]; then echo "DIR|${'$'}f"; else echo "FIL|${'$'}f"; fi
    fi
done
""".trimIndent()
            val output = SpecialPermissionVerifier.executeRootCommand(command)
            val lines = output.lines().filter { it.isNotBlank() }

            for (line in lines) {
                val sep = line.indexOf('|')
                if (sep < 0) continue
                val type = line.substring(0, sep)
                val name = line.substring(sep + 1)
                if (!showHidden && name.startsWith(".")) continue
                entries.add(FileEntry("$normalizedPath/$name", name, type == "DIR"))
            }
        } catch (e: Exception) {
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
        loadError = null
        val entries = if (isRootEngine) {
            listWithRoot(path, showHiddenFiles)
        } else {
            listWithPosix(path, showHiddenFiles)
        }
        // POSIX 失败时自动尝试 Root 兜底
        if (entries.isEmpty() && loadError != null && !isRootEngine && SpecialPermissionVerifier.isRootAvailable()) {
            loadError = null
            return listWithRoot(path, showHiddenFiles)
        }
        return entries
    }

    fun openFile(context: Context, entry: FileEntry) {
        val file = File(entry.path)
        // 阻止直接打开 .apk/.apex——系统会尝试加载为 APK 资源导致崩溃
        if (entry.name.endsWith(".apk", ignoreCase = true) ||
            entry.name.endsWith(".apex", ignoreCase = true)
        ) {
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
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "没有应用可以打开此文件", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
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
        leftEntries = listDirectory(leftPath)
    }
    LaunchedEffect(rightPath, showHiddenFiles) {
        rightEntries = listDirectory(rightPath)
    }

    LaunchedEffect(Unit) {
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
                            focusedPanel = FocusedPanel.LEFT
                            leftPath = entry.path
                        },
                        onFileClick = { entry ->
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
                            focusedPanel = FocusedPanel.RIGHT
                            rightPath = entry.path
                        },
                        onFileClick = { entry ->
                            focusedPanel = FocusedPanel.RIGHT
                            openFile(context, entry)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    ErrorDialog(error = loadError, onDismiss = { loadError = null })
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
