package com.whmdg.mczj.tools.ui.download

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FADownloaderScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    viewModel: FADownloaderViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val logListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Silent cookie refresh WebView state
    var showSilentRefresh by remember { mutableStateOf(false) }
    var silentRefreshWebView by remember { mutableStateOf<WebView?>(null) }

    // Auto-scroll to latest log
    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) {
            logListState.animateScrollToItem(state.logs.size - 1)
        }
    }

    // 刷新认证状态（从登录页返回时同步 Cookie 和用户名）
    LaunchedEffect(Unit) {
        viewModel.refreshAuth()
    }

    // Trigger silent cookie refresh when cookie is expired and attempts < 3
    LaunchedEffect(state.cookieExpired, state.cookieRefreshAttempts) {
        if (state.cookieExpired && viewModel.shouldAttemptCookieRefresh()) {
            showSilentRefresh = true
        }
    }

    // Hidden WebView for silent cookie refresh
    if (showSilentRefresh) {
        Box(modifier = Modifier.size(0.dp)) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val currentUrl = url ?: ""

                                // If redirected to login page, session is dead
                                if (currentUrl.contains("/login")) {
                                    showSilentRefresh = false
                                    viewModel.onCookieRefreshFailed()
                                    return
                                }

                                // Check for valid cookies
                                val cookieManager = CookieManager.getInstance()
                                val cookies = cookieManager.getCookie("https://www.furaffinity.net")
                                if (cookies != null && cookies.contains("a=") && cookies.contains("b=")) {
                                    // Cookie refreshed successfully
                                    showSilentRefresh = false
                                    viewModel.onCookieRefreshSuccess(cookies)
                                } else {
                                    // Cookies not yet available, wait and check again
                                    // The page might still be loading/settling
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                return false
                            }
                        }

                        silentRefreshWebView = this
                        // Load FA homepage to trigger cookie refresh
                        loadUrl("https://www.furaffinity.net")
                    }
                },
                modifier = Modifier.size(0.dp)
            )
        }
    }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val path = it.path ?: it.toString()
            viewModel.updateSaveDir(it, path)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FA 下载器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // Login status — show username when logged in, login button when not
                    if (state.isLoggedIn) {
                        TextButton(onClick = onLogin) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                state.username.ifEmpty { "已登录" },
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        TextButton(onClick = onLogin) {
                            Icon(
                                Icons.AutoMirrored.Filled.Login,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("登录")
                        }
                    }

                    // Cookie expired dialog — only show when refresh attempts exhausted
                    if (state.cookieExpired && !viewModel.shouldAttemptCookieRefresh()) {
                        AlertDialog(
                            onDismissRequest = { viewModel.clearCookie() },
                            icon = {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            title = { Text("登录已失效") },
                            text = {
                                Text("Cookie 自动刷新失败 ${state.cookieRefreshAttempts} 次，FA 登录凭证已过期，请重新登录以继续使用。")
                            },
                            confirmButton = {
                                Button(onClick = {
                                    viewModel.clearCookie()
                                    viewModel.resetRefreshAttempts()
                                    onLogin()
                                }) { Text("重新登录") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    viewModel.clearCookie()
                                    viewModel.resetRefreshAttempts()
                                }) { Text("取消") }
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Header Card ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "Fur Affinity 批量下载器",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "输入作者名，批量下载 Gallery / Scraps 作品",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // ── Author & Type ──
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionLabel("作者设置", Icons.Default.Person)
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = state.author,
                            onValueChange = { viewModel.updateAuthor(it) },
                            label = { Text("作者名") },
                            placeholder = { Text("例如: username") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { viewModel.toggleAuthorHistory() }) {
                                    Icon(Icons.Default.History, contentDescription = "历史记录")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isDownloading
                        )

                        // 作者历史下拉列表
                        if (state.showAuthorHistory && state.authorHistory.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text("历史作者", style = MaterialTheme.typography.labelMedium)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    state.authorHistory.take(10).forEach { entry ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    viewModel.updateAuthor(entry.author)
                                                    viewModel.toggleAuthorHistory()
                                                }
                                                .padding(vertical = 4.dp, horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                entry.author,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.weight(1f)
                                            )
                                            IconButton(
                                                onClick = { viewModel.removeAuthorFromHistory(entry.author) },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "删除",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            "下载类型",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = state.downloadType == "gallery",
                                onClick = { if (!state.isDownloading) viewModel.updateType("gallery") },
                                label = { Text("Gallery") },
                                leadingIcon = if (state.downloadType == "gallery") {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                            FilterChip(
                                selected = state.downloadType == "scraps",
                                onClick = { if (!state.isDownloading) viewModel.updateType("scraps") },
                                label = { Text("Scraps") },
                                leadingIcon = if (state.downloadType == "scraps") {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }
                }
            }

            // ── Download Options ──
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionLabel("下载选项", Icons.Default.Tune)
                        Spacer(modifier = Modifier.height(12.dp))

                        // Save directory
                        OutlinedCard(
                            onClick = { if (!state.isDownloading) dirPickerLauncher.launch(null) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isDownloading
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "保存目录",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        if (state.saveDirPath.isNotEmpty()) state.saveDirPath else "点击选择目录",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (state.saveDirPath.isNotEmpty())
                                            MaterialTheme.colorScheme.onSurface
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Start page & max download
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = state.startPage,
                                onValueChange = { viewModel.updateStartPage(it) },
                                label = { Text("起始页") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                enabled = !state.isDownloading
                            )
                            OutlinedTextField(
                                value = state.maxDownload,
                                onValueChange = { viewModel.updateMaxDownload(it) },
                                label = { Text("最大下载量") },
                                placeholder = { Text("0 = 不限") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                enabled = !state.isDownloading
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Switches
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Switch(
                                    checked = state.skipExisting,
                                    onCheckedChange = { viewModel.updateSkipExisting(it) },
                                    enabled = !state.isDownloading
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("跳过已存在", style = MaterialTheme.typography.bodySmall)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Switch(
                                    checked = state.useCache,
                                    onCheckedChange = { viewModel.updateUseCache(it) },
                                    enabled = !state.isDownloading
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("使用缓存", style = MaterialTheme.typography.bodySmall)
                                if (state.useCache) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { viewModel.toggleCacheViewer() },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.List,
                                            contentDescription = "查看缓存",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 文件命名格式说明
                        Text(
                            "文件命名",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "序号_作者_标题_FAID.ext（按FAID跳过已有）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Thread count
                        Text(
                            "下载线程: ${state.downloadThreads}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = state.downloadThreads.toFloat(),
                            onValueChange = { viewModel.updateDownloadThreads(it.toInt()) },
                            valueRange = 1f..4f,
                            steps = 2,
                            enabled = !state.isDownloading
                        )
                    }
                }
            }

            // ── Control Buttons ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { viewModel.startDownload() },
                        modifier = Modifier.weight(1f),
                        enabled = !state.isDownloading && !state.isCollecting
                    ) {
                        if (state.isCollecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("收集...")
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("开始下载")
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.stopDownload() },
                        modifier = Modifier.weight(1f),
                        enabled = state.isDownloading || state.isCollecting
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("停止")
                    }
                }
            }

            // ── Progress & Stats ──
            item {
                AnimatedVisibility(visible = state.isDownloading || state.downloadedCount > 0 || state.statusMessage != "准备就绪") {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Progress bar
                            if (state.isDownloading) {
                                LinearProgressIndicator(
                                    progress = { state.currentProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // Status
                            Text(
                                state.statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Stats row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                StatBadge("已下载", state.downloadedCount, MaterialTheme.colorScheme.primary)
                                StatBadge("跳过", state.skippedCount, MaterialTheme.colorScheme.tertiary)
                                StatBadge("失败", state.failedCount, MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // ── Log Section ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel("下载日志", Icons.Default.Terminal)
                    if (state.isDownloading) {
                        IconButton(onClick = { viewModel.togglePause() }) {
                            Icon(
                                if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (state.isPaused) "继续" else "暂停",
                                tint = if (state.isPaused) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 400.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    if (state.logs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "准备就绪",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            state = logListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(state.logs) { log ->
                                LogLine(log.message)
                            }
                        }
                    }
                }
            }

            // Bottom spacer
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    // ── Fatal error dialog ──
    if (state.errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("收集失败") },
            text = { Text(state.errorMessage!!) },
            confirmButton = {
                Button(onClick = { viewModel.clearError() }) {
                    Text("知道了")
                }
            }
        )
    }

    // ── Directory conflict dialog ──
    if (state.showDirConflict) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDirConflict() },
            icon = {
                Icon(
                    Icons.Default.CreateNewFolder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("目录已存在") },
            text = { Text("文件夹「${state.conflictDirName}」已存在，请选择处理方式：") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.resolveDirConflict(DirConflictAction.RENAME) }) {
                        Text("重命名")
                    }
                    Button(onClick = { viewModel.resolveDirConflict(DirConflictAction.MERGE) }) {
                        Text("合并")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveDirConflict(DirConflictAction.DELETE) }) {
                    Text("删除重建", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    // ── Delete confirmation dialog ──
    if (state.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelDeleteDir() },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("确认删除") },
            text = { Text("将删除文件夹「${state.conflictDirName}」及其所有内容，此操作不可撤销！") },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDeleteDir() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDeleteDir() }) {
                    Text("取消")
                }
            }
        )
    }

    // ── Early confirm warning dialog ──
    var showEarlyConfirmWarning by remember { mutableStateOf(false) }
    if (showEarlyConfirmWarning) {
        AlertDialog(
            onDismissRequest = { showEarlyConfirmWarning = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("图片未加载完毕") },
            text = { Text("当前已加载 ${state.collectionLoaded} 个任务。是否等待加载完毕后继续下载？") },
            confirmButton = {
                Button(onClick = {
                    showEarlyConfirmWarning = false
                    viewModel.confirmDownload()
                }) { Text("立即下载") }
            },
            dismissButton = {
                TextButton(onClick = { showEarlyConfirmWarning = false }) {
                    Text("等待加载")
                }
            }
        )
    }

    // ── Download Preview Dialog ──
    if (state.showPreview) {
        val progressValue = if (state.collectionComplete) {
            1f
        } else {
            // 收集阶段无预估，用不确定进度
            0f
        }

        AlertDialog(
            onDismissRequest = { viewModel.cancelPreview() },
            icon = {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                if (state.collectionComplete) {
                    Text("下载预览 (${state.collectionLoaded} 个文件)")
                } else {
                    Text("正在收集 ${state.collectionLoaded} 个")
                }
            },
            text = {
                Column {
                    // Task list
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (state.pendingTasks.isEmpty() && !state.collectionComplete) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            "正在搜索作品...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        items(state.pendingTasks) { task ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        "#${task.seq}  ${task.fileName}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        task.imageUrl,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Progress bar
                    Column {
                        if (state.collectionComplete) {
                            LinearProgressIndicator(
                                progress = { progressValue },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (state.collectionComplete) {
                                Text(
                                    "${(progressValue * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    "爬取中...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "${state.collectionLoaded} 个",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!state.collectionComplete) {
                            showEarlyConfirmWarning = true
                        } else {
                            viewModel.confirmDownload()
                        }
                    },
                    enabled = state.pendingTasks.isNotEmpty() || state.collectionComplete
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("确认下载")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelPreview() }) {
                    Text("取消")
                }
            }
        )
    }

    // ── Cache Viewer Dialog ──
    if (state.showCacheViewer) {
        AlertDialog(
            onDismissRequest = { viewModel.toggleCacheViewer() },
            icon = {
                Icon(
                    Icons.Default.List,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("缓存管理") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (state.cachedAuthors.isEmpty()) {
                        Text("暂无缓存数据", style = MaterialTheme.typography.bodyMedium)
                    } else if (state.selectedCachedAuthor == null) {
                        // 作者列表
                        Text("已缓存的作者 (${state.cachedAuthors.size})", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        state.cachedAuthors.forEach { authorInfo ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectCachedAuthor(authorInfo.author) }
                                    .padding(vertical = 6.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(authorInfo.author, style = MaterialTheme.typography.bodyMedium)
                                    Text("${authorInfo.count} 个链接", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { viewModel.deleteCachedAuthor(authorInfo.author) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除作者缓存", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    } else {
                        // 链接列表
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                viewModel.resetCacheViewerSelection()
                            }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(state.selectedCachedAuthor!!, style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.weight(1f))
                            Text("${state.cachedLinks.size} 个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        if (state.selectedCacheLinks.isNotEmpty()) {
                            Button(
                                onClick = { viewModel.deleteSelectedCacheLinks() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("删除选中 (${state.selectedCacheLinks.size})")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        state.cachedLinks.forEach { link ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleCacheLinkSelection(link.pageId) }
                                    .padding(vertical = 4.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = link.pageId in state.selectedCacheLinks,
                                    onCheckedChange = { viewModel.toggleCacheLinkSelection(link.pageId) },
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(link.pageId, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                    Text(
                                        link.imageUrl.take(60) + if (link.imageUrl.length > 60) "..." else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.toggleCacheViewer() }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
private fun SectionLabel(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun StatBadge(label: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$count",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LogLine(message: String) {
    val color = when {
        message.startsWith("  ✓") -> MaterialTheme.colorScheme.primary
        message.startsWith("  ✗") -> MaterialTheme.colorScheme.error
        message.contains("错误") || message.contains("失败") || message.contains("异常") -> MaterialTheme.colorScheme.error
        message.contains("完成") -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 长文本自动换行，续行前加 ↳ 箭头标识
    val displayText = if (message.length > 80) {
        val firstLine = message.take(80)
        val rest = message.drop(80).chunked(80).joinToString("\n") { "↳ $it" }
        "$firstLine\n$rest"
    } else {
        message
    }
    Text(
        text = displayText,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        ),
        color = color
    )
}
