package com.whmdg.mczj.tools.ui

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.encryption.core.FileConstants
import com.whmdg.mczj.tools.encryption.core.FilenameCodec
import com.whmdg.mczj.tools.encryption.services.CryptoService
import com.whmdg.mczj.tools.encryption.services.EncryptionTaskManager
import com.whmdg.mczj.tools.encryption.services.VaultSession
import com.whmdg.mczj.tools.ui.encryption.EncryptionProgressIcon
import com.whmdg.mczj.tools.ui.encryption.EncryptionProgressPanel
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DisplayEntry(
    val file: File,
    val displayName: String,
    val isDirectory: Boolean
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultOpenScreen(
    session: VaultSession,
    onBack: () -> Unit,
    vaultService: com.whmdg.mczj.tools.encryption.services.VaultService? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentPath by remember { mutableStateOf(session.vaultDir.absolutePath) }
    var entries by remember { mutableStateOf(listOf<DisplayEntry>()) }
    var loading by remember { mutableStateOf(true) }
    var vaultOpenError by remember { mutableStateOf<Throwable?>(null) }

    val isRoot = currentPath == session.vaultDir.absolutePath

    fun refresh() {
        loading = true
        coroutineScope.launch(Dispatchers.Default) {
            try {
                session.loadNameMapping(context)
                val currentDir = File(currentPath)
                val rawFiles = (currentDir.listFiles() ?: emptyArray()).filter { f ->
                    val name = f.name
                    name != "vault_config.json" &&
                    name != "vault_config.backup.json" &&
                    name != "name_mappings.json"
                }.toTypedArray()

                // Sort directories first, then files alphabetically
                rawFiles.sortWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })

                val resolved = rawFiles.map { f ->
                    val displayName = if (f.isDirectory) {
                        f.name
                    } else {
                        var raw = f.name
                        if (raw.endsWith(".aes")) {
                            raw = raw.substring(0, raw.length - 4)
                        }
                        if (!session.record.encryptFilename) {
                            raw
                        } else {
                            try {
                                FilenameCodec.decrypt(
                                    encryptedName = "${raw}.aes",
                                    dek = session.dek,
                                    aad = if (session.record.customEncryption) FileConstants.aadCustomObf else null,
                                    lookupMapping = { session.nameMapping.get(it) }
                                )
                            } catch (e: Exception) {
                                raw
                            }
                        }
                    }
                    DisplayEntry(f, displayName, f.isDirectory)
                }

                withContext(Dispatchers.Main) {
                    entries = resolved
                    loading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    vaultOpenError = e
                    loading = false
                }
            }
        }
    }

    LaunchedEffect(currentPath) {
        refresh()
    }

    fun goUp() {
        val parent = File(currentPath).parentFile
        if (parent != null && parent.absolutePath.length >= session.vaultDir.absolutePath.length) {
            currentPath = parent.absolutePath
        }
    }

    fun onClose() {
        session.dispose()
        onBack()
    }

    // ── UI state ──
    var showFabMenu by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    var showDecryptDialog by remember { mutableStateOf<File?>(null) }
    var decryptOutputPath by remember { mutableStateOf(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
            ?: context.filesDir.absolutePath
    ) }

    var progressTitle by remember { mutableStateOf("") }
    var progressPercent by remember { mutableStateOf<Float?>(null) }

    // ── Encryption progress state ──
    var showProgressPanel by remember { mutableStateOf(false) }
    var showEncryptionSnackbar by remember { mutableStateOf(false) }

    // ── Long-press context menu state ──
    var contextMenuEntry by remember { mutableStateOf<DisplayEntry?>(null) }

    // ── Rename dialog ──
    var showRenameDialog by remember { mutableStateOf<DisplayEntry?>(null) }
    var renameNewName by remember { mutableStateOf("") }

    // ── Delete confirmation dialog ──
    var showDeleteConfirm by remember { mutableStateOf<DisplayEntry?>(null) }

    // ── Move / Copy mode ──
    // When the user selects "移动到" or "复制到", we enter a destination-picking mode.
    // The user can navigate folders and press "粘贴到此处".
    var moveOrCopySource by remember { mutableStateOf<File?>(null) }
    var moveOrCopyMode by remember { mutableStateOf<String?>(null) } // "MOVE" or "COPY"
    var moveOrCopyDisplayName by remember { mutableStateOf("") }

    // ── SAF Multi-File Picker Launcher ──
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch(Dispatchers.Default) {
                withContext(Dispatchers.Main) {
                    progressTitle = "正在准备导入文件..."
                    progressPercent = 0.0f
                }

                val total = uris.size
                var done = 0
                val relative = if (isRoot) "" else File(currentPath).relativeTo(session.vaultDir).path

                for (uri in uris) {
                    try {
                        val tempFile = uriToTempFile(context, uri)

                        // 使用 EncryptionTaskManager 创建后台加密任务
                        EncryptionTaskManager.createEncryptionTask(
                            file = tempFile,
                            session = session,
                            subDir = relative,
                            onComplete = {
                                tempFile.delete()
                            }
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    done++
                    withContext(Dispatchers.Main) {
                        progressPercent = done.toFloat() / total.toFloat()
                    }
                }

                withContext(Dispatchers.Main) {
                    progressPercent = null
                    progressTitle = ""
                    showEncryptionSnackbar = true
                    vaultService?.markModified(session.record.id)
                    refresh()
                }
            }
        }
    }

    // ── SAF Folder (DocumentTree) Picker Launcher ──
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            coroutineScope.launch(Dispatchers.Default) {
                withContext(Dispatchers.Main) {
                    progressTitle = "正在导入文件夹..."
                    progressPercent = null
                }

                try {
                    val relative = if (isRoot) "" else File(currentPath).relativeTo(session.vaultDir).path

                    // 获取文件夹名称并创建目标子目录
                    val docId = DocumentsContract.getTreeDocumentId(treeUri)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val folderName: String
                    val nameCursor = context.contentResolver.query(
                        docUri,
                        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null, null, null
                    )
                    folderName = nameCursor?.use {
                        if (it.moveToFirst()) it.getString(0) else "imported_folder"
                    } ?: "imported_folder"

                    val targetSubDir = if (relative.isEmpty()) folderName else "$relative/$folderName"
                    File(session.vaultDir, targetSubDir).mkdirs()

                    // 收集所有文件
                    val files = collectDocumentTreeFiles(context, treeUri, docId, targetSubDir)
                    val total = files.size
                    var done = 0

                    // 逐个创建加密任务（走 EncryptionTaskManager）
                    for ((uri, displayName, subDir) in files) {
                        try {
                            EncryptionTaskManager.createEncryptionTaskFromUri(
                                context = context,
                                uri = uri,
                                displayName = displayName,
                                session = session,
                                subDir = subDir
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        done++
                        withContext(Dispatchers.Main) {
                            progressPercent = done.toFloat() / total.toFloat()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        vaultOpenError = e
                    }
                }

                withContext(Dispatchers.Main) {
                    progressPercent = null
                    progressTitle = ""
                    showEncryptionSnackbar = true
                    vaultService?.markModified(session.record.id)
                    refresh()
                }
            }
        }
    }

    // ── Helper: collect all vault dirs for Move/Copy destination picker ──
    fun collectVaultDirs(root: File): List<File> {
        val result = mutableListOf(root)
        fun walkDirs(dir: File) {
            (dir.listFiles() ?: emptyArray()).filter { it.isDirectory }.forEach { sub ->
                result.add(sub)
                walkDirs(sub)
            }
        }
        walkDirs(root)
        return result
    }

    // ── Move / Copy destination picker dialog ──
    var showDestPicker by remember { mutableStateOf(false) }
    var destPickerPath by remember { mutableStateOf(session.vaultDir.absolutePath) }

    fun startMoveOrCopy(entry: DisplayEntry, mode: String) {
        moveOrCopySource = entry.file
        moveOrCopyMode = mode
        moveOrCopyDisplayName = entry.displayName
        destPickerPath = session.vaultDir.absolutePath
        showDestPicker = true
    }

    fun executeMoveOrCopy(destDir: File) {
        val srcFile = moveOrCopySource ?: return
        val mode = moveOrCopyMode ?: return

        // Prevent moving/copying into itself
        if (srcFile.isDirectory && destDir.absolutePath.startsWith(srcFile.absolutePath)) {
            vaultOpenError = Exception("无法将文件夹${if (mode == "MOVE") "移动" else "复制"}到自身内部")
            return
        }

        coroutineScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                progressTitle = if (mode == "MOVE") "正在移动..." else "正在复制..."
                progressPercent = null
            }

            try {
                val target = File(destDir, srcFile.name)
                if (mode == "MOVE") {
                    // Try rename first (same filesystem), fallback to copy+delete
                    if (!srcFile.renameTo(target)) {
                        srcFile.copyRecursively(target, overwrite = true)
                        srcFile.deleteRecursively()
                    }
                } else {
                    srcFile.copyRecursively(target, overwrite = true)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "${if (mode == "MOVE") "移动" else "复制"}成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    vaultOpenError = e
                }
            }

            withContext(Dispatchers.Main) {
                progressPercent = null
                progressTitle = ""
                moveOrCopySource = null
                moveOrCopyMode = null
                showDestPicker = false
                refresh()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (moveOrCopyMode != null && showDestPicker) {
                        Text("选择目标位置")
                    } else {
                        Text(if (isRoot) session.record.name else File(currentPath).name)
                    }
                },
                navigationIcon = {
                    if (moveOrCopyMode != null && showDestPicker) {
                        IconButton(onClick = {
                            moveOrCopySource = null
                            moveOrCopyMode = null
                            showDestPicker = false
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "取消")
                        }
                    } else {
                        IconButton(onClick = { onClose() }) {
                            Icon(Icons.Default.Lock, contentDescription = "退出并销毁密钥")
                        }
                    }
                },
                actions = {
                    if (moveOrCopyMode == null || !showDestPicker) {
                        val hasEncryptionTasks = EncryptionTaskManager.stateFlow.collectAsState().value.let {
                            EncryptionTaskManager.tasks.isNotEmpty()
                        }
                        if (hasEncryptionTasks) {
                            EncryptionProgressIcon(
                                onShowPanel = { showProgressPanel = true }
                            )
                        } else if (!isRoot) {
                            IconButton(onClick = { goUp() }) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "返回上级")
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (moveOrCopyMode != null && showDestPicker) {
                // "Paste here" FAB in destination picker mode
                ExtendedFloatingActionButton(
                    text = { Text("粘贴到此处") },
                    icon = { Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = { executeMoveOrCopy(File(destPickerPath)) }
                )
            } else {
                FloatingActionButton(onClick = { showFabMenu = true }) {
                    Icon(Icons.Default.Add, contentDescription = "添加")
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── Destination picker mode for Move/Copy ──
            if (moveOrCopyMode != null && showDestPicker) {
                val destIsRoot = destPickerPath == session.vaultDir.absolutePath
                val destDir = File(destPickerPath)
                val destSubDirs = remember(destPickerPath) {
                    (destDir.listFiles() ?: emptyArray())
                        .filter { it.isDirectory }
                        .sortedBy { it.name.lowercase() }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Breadcrumb / current info
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "${if (moveOrCopyMode == "MOVE") "移动" else "复制"}: $moveOrCopyDisplayName",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val displayPath = if (destIsRoot) "/" else "/" + destDir.relativeTo(session.vaultDir).path
                            Text(
                                "当前目录: $displayPath",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Up button (if not at vault root)
                    if (!destIsRoot) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .combinedClickable(
                                    onClick = {
                                        val parent = destDir.parentFile
                                        if (parent != null && parent.absolutePath.length >= session.vaultDir.absolutePath.length) {
                                            destPickerPath = parent.absolutePath
                                        }
                                    },
                                    onLongClick = {}
                                )
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null,
                                modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("..  返回上级",
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.fillMaxWidth())
                                }
                                Spacer(modifier = Modifier.weight(0.5f))
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }

                    if (destSubDirs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("此目录下没有子文件夹", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(destSubDirs) { dir ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(60.dp)
                                        .combinedClickable(
                                            onClick = { destPickerPath = dir.absolutePath },
                                            onLongClick = {}
                                        )
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.secondary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(dir.name,
                                                textAlign = TextAlign.Center,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.fillMaxWidth())
                                        }
                                        Spacer(modifier = Modifier.weight(0.5f))
                                    }
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }
            }
            // ── Normal file listing mode ──
            else if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("此目录为空", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("点击右下角 + 号导入文件或新建目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                PullToRefreshBox(
                    isRefreshing = loading,
                    onRefresh = { refresh() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries) { entry ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = entry.displayName,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = if (!entry.isDirectory) {
                                { Text(compactSize(entry.file.length())) }
                            } else null,
                            leadingContent = {
                                Icon(
                                    imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = if (entry.isDirectory) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                )
                            },
                            trailingContent = if (!entry.isDirectory) {
                                {
                                    IconButton(onClick = { showDecryptDialog = entry.file }) {
                                        Icon(Icons.Default.ArrowDownward, contentDescription = "解密到...")
                                    }
                                }
                            } else null,
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (entry.isDirectory) {
                                        currentPath = entry.file.absolutePath
                                    }
                                },
                                onLongClick = { contextMenuEntry = entry }
                            )
                        )
                        HorizontalDivider()
                    }
                }
            }
            }

            // ── Progress Dialog ──
            if (progressTitle.isNotEmpty()) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text(progressTitle) },
                    text = {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                            progressPercent?.let { pct ->
                                LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
                            } ?: run {
                                CircularProgressIndicator()
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            progressTitle = ""
                            progressPercent = null
                        }) {
                            Text("取消")
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            progressTitle = ""
                            progressPercent = null
                            showProgressPanel = true
                        }) {
                            Text("查看加密进度")
                        }
                    }
                )
            }

            // ── FAB Menu Dialog ──
            if (showFabMenu) {
                AlertDialog(
                    onDismissRequest = { showFabMenu = false },
                    title = { Text("新建或导入") },
                    text = {
                        Column {
                            ListItem(
                                headlineContent = { Text("导入文件") },
                                supportingContent = { Text("从手机中选择文件加密导入") },
                                leadingContent = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null) },
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        showFabMenu = false
                                        fileLauncher.launch("*/*")
                                    },
                                    onLongClick = {}
                                )
                            )
                            ListItem(
                                headlineContent = { Text("导入文件夹") },
                                supportingContent = { Text("选择整个文件夹递归加密导入") },
                                leadingContent = { Icon(Icons.Default.DriveFolderUpload, contentDescription = null) },
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        showFabMenu = false
                                        folderLauncher.launch(null)
                                    },
                                    onLongClick = {}
                                )
                            )
                            ListItem(
                                headlineContent = { Text("新建文件夹") },
                                supportingContent = { Text("在当前目录下创建空文件夹") },
                                leadingContent = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        showFabMenu = false
                                        showCreateFolderDialog = true
                                    },
                                    onLongClick = {}
                                )
                            )
                        }
                    },
                    confirmButton = {}
                )
            }

            // ── Create Folder Dialog ──
            if (showCreateFolderDialog) {
                AlertDialog(
                    onDismissRequest = { showCreateFolderDialog = false },
                    title = { Text("新建文件夹") },
                    text = {
                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            label = { Text("文件夹名称") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            val name = newFolderName.trim()
                            if (name.isNotEmpty()) {
                                val newDir = File(currentPath, name)
                                try {
                                    newDir.mkdirs()
                                    Toast.makeText(context, "创建成功", Toast.LENGTH_SHORT).show()
                                    showCreateFolderDialog = false
                                    newFolderName = ""
                                    vaultService?.markModified(session.record.id)
                                    refresh()
                                } catch (e: Exception) {
                                    vaultOpenError = e
                                }
                            }
                        }) {
                            Text("创建")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showCreateFolderDialog = false
                            newFolderName = ""
                        }) {
                            Text("取消")
                        }
                    }
                )
            }

            // ── Long-press Context Menu Dialog ──
            contextMenuEntry?.let { entry ->
                AlertDialog(
                    onDismissRequest = { contextMenuEntry = null },
                    title = {
                        Text(
                            entry.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    text = {
                        Column {
                            ListItem(
                                headlineContent = { Text("重命名") },
                                leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        contextMenuEntry = null
                                        renameNewName = entry.displayName
                                        showRenameDialog = entry
                                    },
                                    onLongClick = {}
                                )
                            )
                            ListItem(
                                headlineContent = { Text("移动到...") },
                                leadingContent = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) },
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        contextMenuEntry = null
                                        startMoveOrCopy(entry, "MOVE")
                                    },
                                    onLongClick = {}
                                )
                            )
                            ListItem(
                                headlineContent = { Text("复制到...") },
                                leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        contextMenuEntry = null
                                        startMoveOrCopy(entry, "COPY")
                                    },
                                    onLongClick = {}
                                )
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            ListItem(
                                headlineContent = {
                                    Text("删除", color = MaterialTheme.colorScheme.error)
                                },
                                leadingContent = {
                                    Icon(Icons.Default.Delete, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error)
                                },
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        contextMenuEntry = null
                                        showDeleteConfirm = entry
                                    },
                                    onLongClick = {}
                                )
                            )
                        }
                    },
                    confirmButton = {}
                )
            }

            // ── Rename Dialog ──
            showRenameDialog?.let { entry ->
                AlertDialog(
                    onDismissRequest = {
                        showRenameDialog = null
                        renameNewName = ""
                    },
                    title = { Text("重命名") },
                    text = {
                        OutlinedTextField(
                            value = renameNewName,
                            onValueChange = { renameNewName = it },
                            label = { Text("新名称") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            val newName = renameNewName.trim()
                            if (newName.isNotEmpty() && newName != entry.displayName) {
                                coroutineScope.launch(Dispatchers.Default) {
                                    try {
                                        if (entry.isDirectory) {
                                            // Directories are stored with plain names
                                            val newDir = File(entry.file.parentFile, newName)
                                            if (!entry.file.renameTo(newDir)) {
                                                throw Exception("重命名失败")
                                            }
                                        } else {
                                            // Files: need to re-encrypt filename
                                            // Step 1: decrypt to temp
                                            val tempFile = File(context.cacheDir, "rename_temp_${System.currentTimeMillis()}")
                                            com.whmdg.mczj.tools.encryption.core.FileCodec.decrypt(
                                                src = entry.file,
                                                dst = tempFile,
                                                dek = session.dek,
                                                customEncryption = session.record.customEncryption
                                            )
                                            // Step 2: rename temp to desired name
                                            val renamedTemp = File(context.cacheDir, newName)
                                            tempFile.renameTo(renamedTemp)
                                            // Step 3: re-encrypt into vault
                                            val relative = entry.file.parentFile?.let { p ->
                                                if (p.absolutePath == session.vaultDir.absolutePath) ""
                                                else p.relativeTo(session.vaultDir).path
                                            } ?: ""
                                            CryptoService.encryptIntoVault(
                                                context = context,
                                                session = session,
                                                srcFile = renamedTemp,
                                                subDir = relative,
                                                overwrite = false
                                            )
                                            // Step 4: delete original
                                            entry.file.delete()
                                            renamedTemp.delete()
                                        }
                                        withContext(Dispatchers.Main) {
                                            showRenameDialog = null
                                            renameNewName = ""
                                            Toast.makeText(context, "重命名成功", Toast.LENGTH_SHORT).show()
                                            vaultService?.markModified(session.record.id)
                                            refresh()
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) { vaultOpenError = e }
                                    }
                                }
                            }
                        }) {
                            Text("确定")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showRenameDialog = null
                            renameNewName = ""
                        }) {
                            Text("取消")
                        }
                    }
                )
            }

            // ── Delete Confirmation Dialog ──
            showDeleteConfirm?.let { entry ->
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = null },
                    title = { Text("确认删除") },
                    text = {
                        Text(
                            if (entry.isDirectory)
                                "确定要删除文件夹「${entry.displayName}」及其中所有内容吗？此操作不可恢复。"
                            else
                                "确定要删除「${entry.displayName}」吗？此操作不可恢复。"
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val target = entry.file
                                showDeleteConfirm = null
                                coroutineScope.launch(Dispatchers.Default) {
                                    try {
                                        if (target.isDirectory) {
                                            target.deleteRecursively()
                                        } else {
                                            target.delete()
                                        }
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "删除成功", Toast.LENGTH_SHORT).show()
                                            vaultService?.markModified(session.record.id)
                                            refresh()
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) { vaultOpenError = e }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("删除")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = null }) {
                            Text("取消")
                        }
                    }
                )
            }

            // ── Decrypt File Dialog ──
            showDecryptDialog?.let { file ->
                AlertDialog(
                    onDismissRequest = { showDecryptDialog = null },
                    title = { Text("解密输出目录") },
                    text = {
                        OutlinedTextField(
                            value = decryptOutputPath,
                            onValueChange = { decryptOutputPath = it },
                            label = { Text("输出文件夹路径") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            val outDir = File(decryptOutputPath)
                            showDecryptDialog = null
                            coroutineScope.launch(Dispatchers.Default) {
                                withContext(Dispatchers.Main) {
                                    progressTitle = "正在解密中..."
                                    progressPercent = null
                                }

                                try {
                                    CryptoService.decryptOutOfVault(
                                        session = session,
                                        encryptedFile = file,
                                        outputDir = outDir,
                                        overwrite = true
                                    )
                                    withContext(Dispatchers.Main) {
                                        progressTitle = ""
                                        Toast.makeText(context, "解密成功", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        progressTitle = ""
                                        vaultOpenError = e
                                    }
                                }
                            }
                        }) {
                            Text("解密")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDecryptDialog = null }) {
                            Text("取消")
                        }
                    }
                )
            }

            // ── Encryption Progress Panel ──
            if (showProgressPanel) {
                EncryptionProgressPanel(
                    onDismiss = { showProgressPanel = false }
                )
            }

            // ── Encryption Snackbar ──
            if (showEncryptionSnackbar) {
                Snackbar(
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.BottomCenter),
                    action = {
                        Row {
                            TextButton(onClick = { showEncryptionSnackbar = false }) {
                                Text("知道了")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = {
                                showEncryptionSnackbar = false
                                showProgressPanel = true
                            }) {
                                Text("查看加密进度")
                            }
                        }
                    },
                    dismissAction = {
                        IconButton(onClick = { showEncryptionSnackbar = false }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                ) {
                    Text("正在后台加密...")
                }
            }

            ErrorDialog(error = vaultOpenError, onDismiss = { vaultOpenError = null })
        }
    }
}

// ── Helper: copy URI to temp file ──
fun uriToTempFile(context: Context, uri: Uri): File {
    val inputStream = context.contentResolver.openInputStream(uri) ?: throw Exception("Failed to open input stream")
    // Try to get original filename from ContentResolver
    val cursor = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
    val displayName = cursor?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    val fileName = displayName ?: "import_${System.currentTimeMillis()}"
    val tempFile = File(context.cacheDir, fileName)
    tempFile.deleteOnExit()
    tempFile.outputStream().use { outputStream ->
        inputStream.copyTo(outputStream)
    }
    return tempFile
}

fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) {
        return "${"%.1f".format(bytes / 1024.0)} KiB"
    }
    if (bytes < 1024 * 1024 * 1024) {
        return "${"%.1f".format(bytes / (1024.0 * 1024.0))} MiB"
    }
    return "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GiB"
}

private fun compactSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val v = bytes.toDouble()
    return when {
        v < 1024 -> "%.0f B".format(v)
        v < 1024 * 1024 -> "%.1f K".format(v / 1024)
        v < 1024 * 1024 * 1024 -> "%.1f M".format(v / (1024 * 1024))
        else -> "%.1f G".format(v / (1024 * 1024 * 1024))
    }
}

/**
 * 递归收集 SAF DocumentTree 中所有文件的 URI 信息
 * 返回 List<Triple<Uri, 文件名, 相对子目录路径>>
 */
private fun collectDocumentTreeFiles(
    context: Context,
    treeUri: Uri,
    parentDocId: String,
    subDir: String
): List<Triple<Uri, String, String>> {
    val result = mutableListOf<Triple<Uri, String, String>>()
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val cursor = context.contentResolver.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ),
        null, null, null
    ) ?: return result

    cursor.use {
        while (it.moveToNext()) {
            val childDocId = it.getString(0)
            val childName = it.getString(1) ?: "file_${System.currentTimeMillis()}"
            val mimeType = it.getString(2)
            val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

            if (isDir) {
                val childSubDir = "$subDir/$childName"
                result.addAll(collectDocumentTreeFiles(context, treeUri, childDocId, childSubDir))
            } else {
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                result.add(Triple(childUri, childName, subDir))
            }
        }
    }
    return result
}

/**
 * 递归导入 SAF DocumentTree：遍历子文档，文件夹 → 创建对应子目录，文件 → 加密导入。
 */
fun importDocumentTree(
    context: Context,
    session: VaultSession,
    treeUri: Uri,
    baseSubDir: String
) {
    val docId = DocumentsContract.getTreeDocumentId(treeUri)
    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    // Get the folder display name for top-level
    val folderName: String
    val nameCursor = context.contentResolver.query(
        docUri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null, null, null
    )
    folderName = nameCursor?.use {
        if (it.moveToFirst()) it.getString(0) else "imported_folder"
    } ?: "imported_folder"

    val targetSubDir = if (baseSubDir.isEmpty()) folderName else "$baseSubDir/$folderName"
    File(session.vaultDir, targetSubDir).mkdirs()

    importDocumentChildren(context, session, treeUri, docId, targetSubDir)
}

private fun importDocumentChildren(
    context: Context,
    session: VaultSession,
    treeUri: Uri,
    parentDocId: String,
    subDir: String
) {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val cursor = context.contentResolver.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ),
        null, null, null
    ) ?: return

    cursor.use {
        while (it.moveToNext()) {
            val childDocId = it.getString(0)
            val childName = it.getString(1)
            val mimeType = it.getString(2)
            val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

            if (isDir) {
                val childSubDir = "$subDir/$childName"
                File(session.vaultDir, childSubDir).mkdirs()
                importDocumentChildren(context, session, treeUri, childDocId, childSubDir)
            } else {
                // File — copy to temp, then encrypt into vault
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                val inputStream = context.contentResolver.openInputStream(childUri) ?: continue
                val tempFile = File(context.cacheDir, childName ?: "file_${System.currentTimeMillis()}")
                tempFile.deleteOnExit()
                tempFile.outputStream().use { os -> inputStream.copyTo(os) }

                try {
                    CryptoService.encryptIntoVault(
                        context = context,
                        session = session,
                        srcFile = tempFile,
                        subDir = subDir,
                        overwrite = true
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                tempFile.delete()
            }
        }
    }
}
