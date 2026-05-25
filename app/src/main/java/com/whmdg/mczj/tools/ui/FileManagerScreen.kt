package com.whmdg.mczj.tools.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
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
import java.io.File

enum class FocusedPanel { LEFT, RIGHT }

data class FileEntry(
    val file: File,
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

    val defaultPath = "/storage/emulated/0/"
    var leftPath by remember { mutableStateOf(defaultPath) }
    var rightPath by remember { mutableStateOf(defaultPath) }
    var leftEntries by remember { mutableStateOf(listOf<FileEntry>()) }
    var rightEntries by remember { mutableStateOf(listOf<FileEntry>()) }
    var focusedPanel by remember { mutableStateOf(FocusedPanel.LEFT) }
    var showHiddenFiles by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }

    fun loadDirectory(path: String, showHidden: Boolean): List<FileEntry> {
        val dir = File(path)
        val rawFiles = try {
            dir.listFiles()?.filter { showHidden || !it.name.startsWith(".") }
        } catch (_: Exception) { null }
        if (rawFiles == null) return emptyList()
        return rawFiles
            .map { FileEntry(it, it.name, it.isDirectory) }
            .sortedWith(
                compareByDescending<FileEntry> { it.isDirectory }
                    .thenBy { it.name.lowercase() }
            ).let { entries ->
                val parent = dir.parentFile
                if (parent != null && path != "/") {
                    listOf(FileEntry(parent, "..", isDirectory = true)) + entries
                } else {
                    entries
                }
            }
    }

    fun openFile(context: Context, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val extension = file.extension.lowercase()
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
            leftEntries = loadDirectory(leftPath, showHiddenFiles)
            rightEntries = loadDirectory(rightPath, showHiddenFiles)
        }
    }

    LaunchedEffect(leftPath, showHiddenFiles) {
        leftEntries = loadDirectory(leftPath, showHiddenFiles)
    }
    LaunchedEffect(rightPath, showHiddenFiles) {
        rightEntries = loadDirectory(rightPath, showHiddenFiles)
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
                        onFolderClick = { folder ->
                            focusedPanel = FocusedPanel.LEFT
                            leftPath = folder.absolutePath
                        },
                        onFileClick = { file ->
                            focusedPanel = FocusedPanel.LEFT
                            openFile(context, file)
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
                        onFolderClick = { folder ->
                            focusedPanel = FocusedPanel.RIGHT
                            rightPath = folder.absolutePath
                        },
                        onFileClick = { file ->
                            focusedPanel = FocusedPanel.RIGHT
                            openFile(context, file)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun FileBrowserPanel(
    entries: List<FileEntry>,
    isFocused: Boolean,
    onFocus: () -> Unit,
    onFolderClick: (File) -> Unit,
    onFileClick: (File) -> Unit,
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
                items(entries, key = { it.file.absolutePath }) { entry ->
                    FileEntryRow(
                        entry = entry,
                        isFocused = isFocused,
                        onClick = {
                            if (entry.isDirectory) onFolderClick(entry.file)
                            else onFileClick(entry.file)
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
