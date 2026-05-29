package com.whmdg.mczj.tools.ui.download

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.auth.Feature
import com.whmdg.mczj.tools.auth.SecurityEnforcer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class DownloadLog(val message: String, val timestamp: Long = System.currentTimeMillis())

enum class DirConflictAction { RENAME, MERGE, DELETE }

/** 作品元数据（参考 furaffinity-dl） */
data class SubmissionMeta(
    val faId: String = "",
    val imageUrl: String = "",
    val fileName: String = "",
    val title: String = "",
    val author: String = "",
    val date: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val category: String = "",
    val theme: String = "",
    val species: String = "",
    val rating: String = "",
    val views: Int = 0,
    val favorites: Int = 0
)

/** 预览列表中的单个下载任务 */
data class PreviewItem(
    val seq: Int,
    val fileName: String,
    val imageUrl: String,
    val title: String = "",
    val faId: String = "",
    val author: String = "",
    val meta: SubmissionMeta = SubmissionMeta()
)

data class FAUiState(
    val author: String = "",
    val downloadType: String = "gallery",
    val saveDir: Uri? = null,
    val saveDirPath: String = "",
    val startPage: String = "1",
    val maxDownload: String = "0",
    val skipExisting: Boolean = true,
    val useCache: Boolean = true,
    val downloadThreads: Int = 1,
    val isLoggedIn: Boolean = false,
    val username: String = "",
    val cookieExpired: Boolean = false,
    val cookieRefreshAttempts: Int = 0,
    val isDownloading: Boolean = false,
    val isCollecting: Boolean = false,
    val showPreview: Boolean = false,
    val pendingTasks: List<PreviewItem> = emptyList(),
    val collectionComplete: Boolean = false,
    val collectionLoaded: Int = 0,
    val collectionTotal: Int = 0,
    val logs: List<DownloadLog> = emptyList(),
    val pageSummaries: List<String> = emptyList(),
    val downloadedCount: Int = 0,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    val currentProgress: Float = 0f,
    val statusMessage: String = "准备就绪",
    val errorMessage: String? = null,
    val isPaused: Boolean = false,
    // 失败重试
    val failedTasks: List<PreviewItem> = emptyList(),
    val hasFailedTasks: Boolean = false,
    // 目录冲突对话框
    val showDirConflict: Boolean = false,
    val conflictDirName: String = "",
    // 二次删除确认
    val showDeleteConfirm: Boolean = false,
    // 作者历史
    val authorHistory: List<AuthorHistoryEntry> = emptyList(),
    val showAuthorHistory: Boolean = false,
    // 缓存查看器
    val showCacheViewer: Boolean = false,
    val cachedAuthors: List<CachedAuthorInfo> = emptyList(),
    val selectedCachedAuthor: String? = null,
    val cachedLinks: List<CachedLinkInfo> = emptyList(),
    val selectedCacheLinks: Set<String> = emptySet()
)

data class AuthorHistoryEntry(val author: String, val timestamp: Long)
data class CachedAuthorInfo(val author: String, val count: Int, val timestamp: Long)
data class CachedLinkInfo(val pageId: String, val imageUrl: String)

class FADownloaderViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val _uiState = MutableStateFlow(FAUiState())
    val uiState: StateFlow<FAUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null
    private var collectJob: Job? = null
    private var isStopped = false

    /** 收集→下载的生产者-消费者通道 */
    private var downloadChannel: Channel<PreviewItem>? = null
    private var dirConflictDeferred: CompletableDeferred<DirConflictAction>? = null

    private val prefs = application.getSharedPreferences(AppDataPaths.PREFS_BATCH_DOWNLOADER, Context.MODE_PRIVATE)
    private val cachePrefs = application.getSharedPreferences("fa_download_cache", Context.MODE_PRIVATE)

    companion object {
        private const val FA_BASE = "https://www.furaffinity.net"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        private const val REFERER = "https://www.furaffinity.net"
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 30000
        private const val MAX_COOKIE_REFRESH_ATTEMPTS = 3

        fun saveCookieStatic(context: Context, cookie: String, username: String = "") {
            context.getSharedPreferences(AppDataPaths.PREFS_BATCH_DOWNLOADER, Context.MODE_PRIVATE)
                .edit()
                .putString("cookie", cookie)
                .putString("username", username)
                .apply()
        }
    }

    init {
        val cookie = prefs.getString("cookie", "") ?: ""
        val username = prefs.getString("username", "") ?: ""
        val savedDirUri = prefs.getString("save_dir_uri", null)
        val savedDirPath = prefs.getString("save_dir_path", "") ?: ""
        val savedSkipExisting = prefs.getBoolean("skip_existing", true)
        val savedThreads = prefs.getInt("download_threads", 1)
        _uiState.update {
            it.copy(
                isLoggedIn = cookie.isNotEmpty() && cookie.contains("a=") && cookie.contains("b="),
                username = username,
                saveDir = savedDirUri?.let { uri -> Uri.parse(uri) },
                saveDirPath = savedDirPath,
                skipExisting = savedSkipExisting,
                downloadThreads = savedThreads
            )
        }
    }

    fun updateAuthor(author: String) = _uiState.update { it.copy(author = author) }
    fun updateType(type: String) = _uiState.update { it.copy(downloadType = type) }
    fun updateSaveDir(uri: Uri, path: String) {
        prefs.edit()
            .putString("save_dir_uri", uri.toString())
            .putString("save_dir_path", path)
            .apply()
        _uiState.update { it.copy(saveDir = uri, saveDirPath = path) }
    }
    fun updateStartPage(page: String) = _uiState.update { it.copy(startPage = page) }
    fun updateMaxDownload(max: String) = _uiState.update { it.copy(maxDownload = max) }
    fun updateSkipExisting(skip: Boolean) {
        prefs.edit().putBoolean("skip_existing", skip).apply()
        _uiState.update { it.copy(skipExisting = skip) }
    }
    fun updateUseCache(use: Boolean) = _uiState.update { it.copy(useCache = use) }
    fun updateDownloadThreads(threads: Int) {
        val t = threads.coerceIn(1, 4)
        prefs.edit().putInt("download_threads", t).apply()
        _uiState.update { it.copy(downloadThreads = t) }
    }

    // ── 作者历史管理 ──

    private val historyPrefs = application.getSharedPreferences("fa_author_history", Context.MODE_PRIVATE)

    init {
        // 加载作者历史
        _uiState.update { it.copy(authorHistory = loadAuthorHistory()) }
    }

    private fun loadAuthorHistory(): List<AuthorHistoryEntry> {
        val raw = historyPrefs.getString("history", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("|||").mapNotNull { entry ->
            val parts = entry.split(":::", limit = 2)
            if (parts.size == 2) {
                AuthorHistoryEntry(parts[0], parts[1].toLongOrNull() ?: 0L)
            } else null
        }.sortedByDescending { it.timestamp }
    }

    private fun saveAuthorHistory(list: List<AuthorHistoryEntry>) {
        val raw = list.joinToString("|||") { "${it.author}:::${it.timestamp}" }
        historyPrefs.edit().putString("history", raw).apply()
    }

    /** 将作者添加到历史记录（如果已存在则移到最前） */
    fun addAuthorToHistory(author: String) {
        if (author.isBlank()) return
        val current = loadAuthorHistory().toMutableList()
        current.removeAll { it.author.equals(author, ignoreCase = true) }
        current.add(0, AuthorHistoryEntry(author, System.currentTimeMillis()))
        saveAuthorHistory(current)
        _uiState.update { it.copy(authorHistory = current) }
    }

    /** 从历史记录中删除作者 */
    fun removeAuthorFromHistory(author: String) {
        val current = loadAuthorHistory().toMutableList()
        current.removeAll { it.author == author }
        saveAuthorHistory(current)
        _uiState.update { it.copy(authorHistory = current) }
    }

    fun toggleAuthorHistory() {
        _uiState.update { it.copy(showAuthorHistory = !it.showAuthorHistory) }
    }

    // ── 缓存查看器 ──

    fun toggleCacheViewer() {
        val show = !_uiState.value.showCacheViewer
        if (show) {
            loadCachedAuthors()
        }
        _uiState.update { it.copy(showCacheViewer = show, selectedCachedAuthor = null, cachedLinks = emptyList(), selectedCacheLinks = emptySet()) }
    }

    private fun loadCachedAuthors() {
        val allEntries = cachePrefs.all
        // 按 author 分组: key 格式为 "author:pageId" 或纯 pageId
        // 为兼容旧格式，先加载所有条目
        val authorMap = mutableMapOf<String, MutableList<Pair<String, String>>>()
        for ((key, value) in allEntries) {
            if (value is String && key != "author_links") {
                // 尝试从 key 中提取 author（新格式: author/pageId）
                val parts = key.split("/", limit = 2)
                if (parts.size == 2) {
                    authorMap.getOrPut(parts[0]) { mutableListOf() }.add(parts[1] to value)
                } else {
                    // 旧格式：无 author 信息，归入 "未知作者"
                    authorMap.getOrPut("未知作者") { mutableListOf() }.add(key to value)
                }
            }
        }
        val authors = authorMap.map { (author, links) ->
            CachedAuthorInfo(author, links.size, System.currentTimeMillis())
        }.sortedByDescending { it.timestamp }
        _uiState.update { it.copy(cachedAuthors = authors) }
    }

    fun resetCacheViewerSelection() {
        _uiState.update { it.copy(selectedCachedAuthor = null, cachedLinks = emptyList(), selectedCacheLinks = emptySet()) }
    }

    fun selectCachedAuthor(author: String) {
        val allEntries = cachePrefs.all
        val links = mutableListOf<CachedLinkInfo>()
        for ((key, value) in allEntries) {
            if (value is String && key.startsWith("$author/")) {
                val pageId = key.removePrefix("$author/")
                links.add(CachedLinkInfo(pageId, value))
            }
        }
        _uiState.update { it.copy(selectedCachedAuthor = author, cachedLinks = links, selectedCacheLinks = emptySet()) }
    }

    fun toggleCacheLinkSelection(pageId: String) {
        _uiState.update { state ->
            val newSet = if (pageId in state.selectedCacheLinks) {
                state.selectedCacheLinks - pageId
            } else {
                state.selectedCacheLinks + pageId
            }
            state.copy(selectedCacheLinks = newSet)
        }
    }

    fun deleteSelectedCacheLinks() {
        val state = _uiState.value
        val author = state.selectedCachedAuthor ?: return
        val editor = cachePrefs.edit()
        for (pageId in state.selectedCacheLinks) {
            editor.remove("$author/$pageId")
        }
        editor.apply()
        // 刷新
        selectCachedAuthor(author)
        loadCachedAuthors()
    }

    fun deleteCachedAuthor(author: String) {
        val allEntries = cachePrefs.all
        val editor = cachePrefs.edit()
        for ((key, _) in allEntries) {
            if (key.startsWith("$author/")) {
                editor.remove(key)
            }
        }
        editor.apply()
        loadCachedAuthors()
        _uiState.update { it.copy(selectedCachedAuthor = null, cachedLinks = emptyList(), selectedCacheLinks = emptySet()) }
    }

    fun saveCookie(cookie: String, username: String = "") {
        prefs.edit()
            .putString("cookie", cookie)
            .putString("username", username.ifEmpty { prefs.getString("username", "") ?: "" })
            .apply()
        _uiState.update {
            it.copy(
                isLoggedIn = cookie.contains("a=") && cookie.contains("b="),
                cookieExpired = false,
                cookieRefreshAttempts = 0,
                username = username.ifEmpty { it.username }
            )
        }
    }

    fun clearCookie() {
        prefs.edit().remove("cookie").apply()
        _uiState.update {
            it.copy(
                isLoggedIn = false,
                cookieExpired = false,
                cookieRefreshAttempts = 0,
                username = ""
            )
        }
    }

    fun loadCookie(): String = prefs.getString("cookie", "") ?: ""

    /** 从 SharedPreferences 刷新认证状态（从登录页返回时调用） */
    fun refreshAuth() {
        val cookie = prefs.getString("cookie", "") ?: ""
        val username = prefs.getString("username", "") ?: ""
        _uiState.update {
            it.copy(
                isLoggedIn = cookie.isNotEmpty() && cookie.contains("a=") && cookie.contains("b="),
                username = username.ifEmpty { it.username }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun markCookieExpired() {
        val currentAttempts = _uiState.value.cookieRefreshAttempts
        _uiState.update {
            it.copy(
                cookieExpired = true,
                cookieRefreshAttempts = currentAttempts + 1
            )
        }
    }

    fun onCookieRefreshSuccess(cookie: String) {
        saveCookie(cookie)
        addLog("Cookie 已自动刷新")
    }

    fun onCookieRefreshFailed() {
        val newAttempts = _uiState.value.cookieRefreshAttempts + 1
        _uiState.update { it.copy(cookieRefreshAttempts = newAttempts) }
        if (newAttempts >= MAX_COOKIE_REFRESH_ATTEMPTS) {
            _uiState.update { it.copy(cookieExpired = true) }
            addLog("Cookie 自动刷新失败 ${MAX_COOKIE_REFRESH_ATTEMPTS} 次，请重新登录")
        } else {
            addLog("Cookie 刷新失败，重试 ($newAttempts/$MAX_COOKIE_REFRESH_ATTEMPTS)")
        }
    }

    fun shouldAttemptCookieRefresh(): Boolean {
        return _uiState.value.cookieRefreshAttempts < MAX_COOKIE_REFRESH_ATTEMPTS
    }

    fun resetRefreshAttempts() {
        _uiState.update { it.copy(cookieRefreshAttempts = 0) }
    }

    // ── Phase 1: Start collection, show preview immediately ──

    fun startDownload() {
        // 业务层权限检查（第二道防线）
        if (!SecurityEnforcer.checkOrDie(context, Feature.BATCH_DOWNLOADER, "FADownloaderViewModel.startDownload")) {
            addLog("权限不足：无法启动下载")
            return
        }

        clearLogs()
        val state = _uiState.value
        if (state.author.isBlank()) {
            addLog("请输入作者名")
            return
        }
        if (state.saveDir == null) {
            addLog("请选择保存目录")
            return
        }

        isStopped = false
        // 记录作者到历史
        addAuthorToHistory(state.author)
        val channel = Channel<PreviewItem>(Channel.UNLIMITED)
        downloadChannel = channel

        _uiState.update {
            it.copy(
                isPaused = false,
                isCollecting = true,
                showPreview = true,
                collectionComplete = false,
                collectionLoaded = 0,
                collectionTotal = 0,
                pendingTasks = emptyList(),
                statusMessage = "正在收集任务..."
            )
        }
        addLog("正在收集 ${state.author} 的 ${state.downloadType}...")

        collectJob = viewModelScope.launch(Dispatchers.IO) {
            val startPage = state.startPage.toIntOrNull() ?: 1
            val maxDownload = state.maxDownload.toIntOrNull() ?: 0
            val cookie = loadCookie()

            val allTasks = mutableListOf<PreviewItem>()
            var currentUrl = "$FA_BASE/${state.downloadType}/${state.author}/${startPage}"
            var consecutiveEmpty = 0
            var pageNum = startPage

            try {
                while (!isStopped) {
                    if (maxDownload > 0 && allTasks.size >= maxDownload) break

                    val html = fetchHtml(currentUrl, cookie)
                    if (html == null) {
                        consecutiveEmpty++
                        if (consecutiveEmpty >= 3) {
                            addLog("连续 3 次失败，停止收集")
                            break
                        }
                        // 无法从空 HTML 解析下一页，回退到页码递增
                        pageNum++
                        currentUrl = "$FA_BASE/${state.downloadType}/${state.author}/$pageNum"
                        continue
                    }

                    if (html.contains("could not be found")) {
                        addLog("未找到作者「${state.author}」，请检查名称")
                        _uiState.update { it.copy(isCollecting = false, statusMessage = "作者不存在") }
                        channel.close()
                        return@launch
                    }
                    if (html.contains("available to registered users only")) {
                        addLog("该作者的作品仅对注册用户可见，请先登录")
                        markCookieExpired()
                        _uiState.update { it.copy(isCollecting = false, statusMessage = "需要登录") }
                        channel.close()
                        return@launch
                    }

                    // 检测画廊结束（参考 furaffinity-dl: id="no-images"）
                    if (isGalleryEnd(html)) {
                        addLog("已到画廊末尾 (第 $pageNum 页)")
                        break
                    }

                    val pages = parseGalleryPages(html)
                    if (pages.isEmpty()) {
                        consecutiveEmpty++
                        addLog("第 $pageNum 页: 未发现作品链接 (连续空页 $consecutiveEmpty)")
                        // 仅在连续空页 + 登录表单存在时才判定 Cookie 失效
                        if (consecutiveEmpty >= 2 && html.contains("id=\"login-form\"") && state.isLoggedIn) {
                            addLog("Cookie 可能已失效（连续空页且含登录表单）")
                            markCookieExpired()
                            _uiState.update { it.copy(isCollecting = false, statusMessage = "Cookie 失效") }
                            channel.close()
                            return@launch
                        }
                        if (consecutiveEmpty >= 4) {
                            addLog("连续 4 页无内容，停止收集")
                            break
                        }
                        // 尝试下一页（优先从 Next 按钮解析，回退到页码递增）
                        val nextUrl = parseNextPageUrl(html, currentUrl)
                        if (nextUrl != null) {
                            currentUrl = nextUrl; pageNum++
                        } else {
                            pageNum++
                            currentUrl = "$FA_BASE/${state.downloadType}/${state.author}/$pageNum"
                        }
                        continue
                    }
                    consecutiveEmpty = 0

                    addLog("第 $pageNum 页: 发现 ${pages.size} 个链接，开始抓取详情...")

                    // 并行抓取详情页，复用 downloadThreads 作为并发数
                    data class DetailResult(val pageId: String, val imageUrl: String, val fileName: String, val title: String, val meta: SubmissionMeta = SubmissionMeta())
                    val semaphore = Semaphore(state.downloadThreads)
                    val deferreds = mutableListOf<kotlinx.coroutines.Deferred<DetailResult?>>()
                    val seenPageIds = mutableSetOf<String>()  // 去重

                    for (pageUrl in pages) {
                        if (isStopped) break
                        if (maxDownload > 0 && allTasks.size >= maxDownload) break

                        val pageId = extractPageId(pageUrl)
                        if (!seenPageIds.add(pageId)) continue  // 跳过重复

                        val deferred = async {
                            semaphore.withPermit {
                                val cachedUrl = if (state.useCache) getCachedUrl(state.author, pageId) else null
                                if (cachedUrl != null) {
                                    // 缓存命中：仍需获取元数据（命名格式依赖标题）
                                    val meta = run {
                                        val detailHtml = fetchHtml(pageUrl, cookie)
                                        if (detailHtml != null) parseSubmissionInfo(detailHtml) else null
                                    }
                                    val m = meta ?: SubmissionMeta(imageUrl = cachedUrl, faId = pageId)
                                    // 校验缓存的图片 URL 必须包含作者名称
                                    val checkAuthor = m.author.ifEmpty { state.author }.lowercase()
                                    if (checkAuthor.isNotEmpty() && !cachedUrl.lowercase().contains(checkAuthor)) {
                                        addLog("  ⚠ 缓存URL不含作者「$checkAuthor」，跳过: $cachedUrl")
                                        return@async null
                                    }
                                    DetailResult(pageId, cachedUrl, extractFileName(cachedUrl), m.title, m)
                                } else {
                                    val detailHtml = fetchHtml(pageUrl, cookie)
                                    if (detailHtml == null) return@async null
                                    val info = parseSubmissionInfo(detailHtml) ?: return@async null
                                    // 校验图片 URL 必须包含作者名称（防止翻页错误导致抓到其他作者的作品）
                                    val checkAuthor = info.author.ifEmpty { state.author }.lowercase()
                                    if (checkAuthor.isNotEmpty() && !info.imageUrl.lowercase().contains(checkAuthor)) {
                                        addLog("  ⚠ 跳过: 图片URL不含作者「$checkAuthor」: ${info.imageUrl}")
                                        return@async null
                                    }
                                    if (state.useCache) cacheUrl(state.author, pageId, info.imageUrl)
                                    DetailResult(pageId, info.imageUrl, extractFileName(info.imageUrl), info.title, info)
                                }
                            }
                        }
                        deferreds.add(deferred)
                    }

                    // 收集结果并更新进度
                    val countBeforePage = allTasks.size
                    for (deferred in deferreds) {
                        if (isStopped) break
                        val result = deferred.await() ?: continue

                        val (pageId, imageUrl, _, title, meta) = result
                        val ext = getFileExtension(imageUrl)
                        val safeTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(50)
                        val authorName = meta.author.ifEmpty { state.author }
                        val safeAuthor = authorName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                        val saveFileName = "${safeAuthor}_${safeTitle}_${pageId}.$ext"

                        val task = PreviewItem(allTasks.size + 1, saveFileName, imageUrl, title, pageId, state.author, meta)
                        allTasks.add(task)
                        channel.send(task)

                        _uiState.update {
                            it.copy(
                                pendingTasks = it.pendingTasks + task,
                                collectionLoaded = allTasks.size,
                                collectionTotal = it.collectionTotal.coerceAtLeast(allTasks.size)
                            )
                        }
                    }

                    val pageCollected = allTasks.size - countBeforePage
                    addPageSummary("第${pageNum}页（${pages.size}个链接），本页${pageCollected}个，累计${allTasks.size}个")
                    // 从页面解析下一页 URL（参考 furaffinity-dl: 从 Next 按钮提取）
                    val nextUrl = parseNextPageUrl(html, currentUrl)
                    if (nextUrl != null) {
                        currentUrl = nextUrl
                        pageNum++
                    } else {
                        addLog("无更多页面，收集结束")
                        break
                    }
                }
            } catch (e: Exception) {
                addLog("收集任务异常: ${e.message}")
            }

            channel.close()
            val isEmpty = allTasks.isEmpty()
            _uiState.update {
                it.copy(
                    isCollecting = false,
                    collectionComplete = true,
                    collectionTotal = allTasks.size,
                    statusMessage = if (isEmpty) "无图片" else "收集完成 ${allTasks.size} 个任务",
                    errorMessage = if (isEmpty) "未找到可下载的图片。可能原因：\n• Cookie 已失效或未登录\n• 作者名拼写错误\n• FA 页面结构已变化导致解析失败\n\n请检查日志中的错误信息。" else null
                )
            }
            if (isEmpty) {
                addLog("没有找到可下载的图片")
            } else {
                addLog("收集完成，共 ${allTasks.size} 个任务")
            }
        }
    }

    // ── Phase 2: Confirm and start download (can run parallel with collection) ──

    fun confirmDownload() {
        val state = _uiState.value
        val ch = downloadChannel ?: return

        _uiState.update {
            it.copy(
                showPreview = false,
                isDownloading = true,
                downloadedCount = 0,
                skippedCount = 0,
                failedCount = 0,
                statusMessage = "正在下载..."
            )
        }
        addLog("开始下载，${state.downloadThreads} 线程")

        isStopped = false
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            val cookie = loadCookie()
            val threadCount = state.downloadThreads

            val targetDir = resolveTargetDir(state)
            if (targetDir == null) {
                _uiState.update { it.copy(isDownloading = false, statusMessage = "已取消") }
                return@launch
            }
            addLog("保存目录: ${state.saveDirPath}/${state.author}/${if (state.downloadType == "scraps") "scraps/" else ""}")

            // 扫描已有文件，构建 FAID 索引用于增量跳过
            val existingFiles = buildExistingFileIndex(targetDir)
            if (existingFiles.isNotEmpty()) {
                addLog("发现 ${existingFiles.size} 个已有文件，将按 FAID+作者+标题匹配跳过")
            }

            // 每个任务独立：下载→保存→立即输出日志
            val semaphore = Semaphore(threadCount)
            val saveMutex = Mutex()
            var nextSeq = 1
            var totalDownloaded = 0
            var totalSkipped = 0
            var totalFailed = 0
            var totalTasks = 0
            val failedTasksList = mutableListOf<PreviewItem>()

            val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

            for (task in ch) {
                if (isStopped) break
                totalTasks++

                val deferred = async {
                    semaphore.withPermit {
                        if (isStopped) return@async
                        // 暂停等待
                        while (_uiState.value.isPaused && !isStopped) {
                            delay(200)
                        }
                        if (isStopped) return@async
                        // 增量跳过：按 FAID + 作者 + 标题匹配已有文件
                        if (state.skipExisting) {
                            val existing = existingFiles[task.faId]
                            if (existing != null &&
                                existing.author.equals(task.meta.author.ifEmpty { state.author }, ignoreCase = true) &&
                                existing.title.equals(task.title, ignoreCase = true)
                            ) {
                                saveMutex.lock()
                                val cur = totalDownloaded + totalSkipped + totalFailed + 1
                                totalSkipped++
                                addLog("  ($cur/$totalTasks) ⏭ 已存在，跳过: ${existing.fileName}")
                                _uiState.update { it.copy(skippedCount = totalSkipped) }
                                nextSeq++
                                saveMutex.unlock()
                                return@async
                            }
                        }
                        // 下载，失败后自动重试一次
                        var data = downloadToBytes(task.imageUrl, cookie)
                        if (data == null) {
                            delay(200)
                            data = downloadToBytes(task.imageUrl, cookie)
                        }
                        if (data == null) {
                            saveMutex.lock()
                            totalFailed++
                            failedTasksList.add(task)
                            addLog("  (${totalDownloaded + totalSkipped + totalFailed}/$totalTasks) ✗ 失败（已加入重试队列）: ${task.fileName}")
                            _uiState.update { it.copy(failedCount = totalFailed, failedTasks = failedTasksList.toList(), hasFailedTasks = true) }
                            nextSeq++
                            saveMutex.unlock()
                            return@async
                        }
                        // 保存并立即输出日志
                        saveMutex.lock()
                        if (saveToFile(targetDir, task.fileName, data)) {
                            totalDownloaded++
                            addLog("  (${totalDownloaded + totalSkipped + totalFailed}/$totalTasks) ✓ ${task.fileName}")
                            _uiState.update { it.copy(downloadedCount = totalDownloaded) }
                        } else {
                            totalFailed++
                            addLog("  (${totalDownloaded + totalSkipped + totalFailed}/$totalTasks) ✗ 保存失败: ${task.fileName}")
                            _uiState.update { it.copy(failedCount = totalFailed) }
                        }
                        _uiState.update { it.copy(currentProgress = nextSeq.toFloat() / totalTasks.coerceAtLeast(1)) }
                        nextSeq++
                        saveMutex.unlock()
                    }
                }
                jobs.add(deferred)
            }

            jobs.awaitAll()

            addLog("下载完成 — 已下载: $totalDownloaded, 跳过: $totalSkipped, 失败: $totalFailed")

            // 自定义编号模式：按 FA ID 匹配重排序
            if (!isStopped) {
                renumberFiles(targetDir, state.author)
            }

            _uiState.update {
                it.copy(
                    isDownloading = false,
                    pendingTasks = emptyList(),
                    statusMessage = "下载完成",
                    currentProgress = 1f
                )
            }
        }
    }

    fun cancelPreview() {
        isStopped = true
        collectJob?.cancel()
        downloadJob?.cancel()
        downloadChannel?.close()
        downloadChannel = null
        _uiState.update {
            it.copy(
                showPreview = false,
                isCollecting = false,
                isDownloading = false,
                pendingTasks = emptyList(),
                collectionComplete = false,
                collectionLoaded = 0,
                collectionTotal = 0,
                statusMessage = "准备就绪"
            )
        }
    }

    fun stopDownload() {
        isStopped = true
        downloadJob?.cancel()
        collectJob?.cancel()
        downloadChannel?.close()
        _uiState.update {
            it.copy(
                isDownloading = false,
                isCollecting = false,
                isPaused = false,
                pendingTasks = emptyList(),
                statusMessage = "已停止"
            )
        }
        addLog("用户停止下载")
    }

    fun togglePause() {
        val wasPaused = _uiState.value.isPaused
        _uiState.update { it.copy(isPaused = !wasPaused) }
        addLog(if (wasPaused) "下载已继续" else "下载已暂停")
    }

    fun retryFailedTasks() {
        val failed = _uiState.value.failedTasks
        if (failed.isEmpty()) return
        _uiState.update { it.copy(failedTasks = emptyList(), hasFailedTasks = false) }
        addLog("重试 ${failed.size} 个失败任务...")

        val state = _uiState.value
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            val cookie = loadCookie()
            val saveMutex = Mutex()
            var retried = 0
            var success = 0
            var stillFailed = 0
            val stillFailedList = mutableListOf<PreviewItem>()
            val targetDir = resolveTargetDir(state) ?: return@launch

            val semaphore = Semaphore(state.downloadThreads)
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

            for (task in failed) {
                retried++
                val deferred = async {
                    semaphore.withPermit {
                        if (isStopped) return@async
                        var data = downloadToBytes(task.imageUrl, cookie)
                        if (data == null) {
                            delay(200)
                            data = downloadToBytes(task.imageUrl, cookie)
                        }
                        if (data == null) {
                            saveMutex.lock()
                            stillFailed++
                            stillFailedList.add(task)
                            addLog("  ✗ 重试仍失败: ${task.fileName}")
                            _uiState.update { it.copy(failedCount = it.failedCount + stillFailed, failedTasks = stillFailedList.toList(), hasFailedTasks = stillFailedList.isNotEmpty()) }
                            saveMutex.unlock()
                            return@async
                        }
                        saveMutex.lock()
                        if (saveToFile(targetDir, task.fileName, data)) {
                            success++
                            addLog("  ✓ 重试成功: ${task.fileName}")
                            _uiState.update { it.copy(downloadedCount = it.downloadedCount + 1) }
                        } else {
                            stillFailed++
                            stillFailedList.add(task)
                            addLog("  ✗ 重试保存失败: ${task.fileName}")
                        }
                        saveMutex.unlock()
                    }
                }
                jobs.add(deferred)
            }
            jobs.awaitAll()

            addLog("重试完成 — 成功: $success, 仍失败: $stillFailed")

            // 重排序
            if (!isStopped) {
                renumberFiles(targetDir, state.author)
            }

            if (stillFailedList.isEmpty()) {
                _uiState.update { it.copy(isDownloading = false, statusMessage = "下载完成") }
            }
        }
    }

    /** 解析目标目录 URI（复用 confirmDownload 的目录创建逻辑） */
    private suspend fun resolveTargetDir(state: FAUiState): Uri? {
        val authorDir = checkAndCreateDirectory(state.saveDir!!, state.author) ?: return null
        return if (state.downloadType == "scraps") {
            checkAndCreateDirectory(authorDir, "scraps")
        } else {
            authorDir
        }
    }

    private fun addLog(message: String) {
        _uiState.update { it.copy(logs = listOf(DownloadLog(message)) + it.logs) }
    }

    private fun addPageSummary(summary: String) {
        _uiState.update { it.copy(pageSummaries = it.pageSummaries + summary) }
    }

    private fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList(), pageSummaries = emptyList()) }
    }

    // ── HTTP（参考 furaffinity-dl: 统一 session，自动带 Cookie） ──

    private val cookieManager = CookieManager()

    /** 统一 HTTP GET —— 所有请求都通过这里，自动带 Cookie（类似 furaffinity-dl 的 session.get） */
    private fun httpGet(url: String, cookie: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Referer", REFERER)
        conn.setRequestProperty("Accept", "*/*")
        // 自动携带 Cookie（参考 furaffinity-dl: session.cookies 自动附加）
        if (cookie.isNotEmpty()) {
            conn.setRequestProperty("Cookie", cookie)
        }
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        return conn
    }

    private suspend fun fetchHtml(url: String, cookie: String, silent: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            val conn = httpGet(url, cookie)
            val code = conn.responseCode
            if (code !in 200..299) {
                if (!silent) addLog("  HTTP $code: $url")
                conn.disconnect()
                return@withContext null
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val html = reader.readText()
            reader.close()
            conn.disconnect()
            html
        } catch (e: Exception) {
            if (!silent) addLog("  请求异常: ${e.message}")
            null
        }
    }

    // ── HTML Parsing ──

    private fun parseGalleryPages(html: String): List<String> {
        val pages = mutableListOf<String>()
        // 方式1: 匹配 <figure> 内的 <a href="/view/ID/...">（参考 furaffinity-dl 的 figure 标签方式）
        val figurePattern = Pattern.compile("""<figure[^>]*>.*?<a\s+href="(/view/(\d+)(?:/[^"]*)?)".*?</figure>""", Pattern.DOTALL)
        val figureMatcher = figurePattern.matcher(html)
        while (figureMatcher.find()) {
            pages.add("$FA_BASE${figureMatcher.group(1)}")
        }
        // 方式2: 直接匹配 <a href="/view/ID/"> 作为后备
        if (pages.isEmpty()) {
            val linkPattern = Pattern.compile("""<a\s+href="(/view/(\d+)(?:/[^"]*)?)"[^>]*>""")
            val linkMatcher = linkPattern.matcher(html)
            while (linkMatcher.find()) {
                pages.add("$FA_BASE${linkMatcher.group(1)}")
            }
        }
        return pages.distinct()
    }

    /** 检测画廊是否已结束（参考 furaffinity-dl: id="no-images"） */
    private fun isGalleryEnd(html: String): Boolean {
        return html.contains("id=\"no-images\"")
    }

    /** 从页面解析下一页 URL（参考 furaffinity-dl: 从 Next 按钮的 form action 提取） */
    private fun parseNextPageUrl(html: String, currentUrl: String): String? {
        // 方式1: 从 Next 按钮的 form action 提取（限定 class="button standard" + 精确匹配 "Next"，与 Python 版对齐）
        val nextPattern = Pattern.compile("""<form[^>]+action="([^"]*)"[^>]*>.*?<button[^>]*class="button standard"[^>]*>[^<]*\s*Next\s*<""", Pattern.DOTALL)
        val nextMatcher = nextPattern.matcher(html)
        if (nextMatcher.find()) {
            val action = nextMatcher.group(1) ?: ""
            if (action.isNotEmpty()) {
                return if (action.startsWith("http")) action else "$FA_BASE$action"
            }
        }
        // 方式2: 从分页链接提取下一页
        val pagePattern = Pattern.compile("""href="(/(?:gallery|scraps)/[^/]+/(\d+)/[^"]*)"[^>]*>\s*(?:»|Next|下一页)""")
        val pageMatcher = pagePattern.matcher(html)
        if (pageMatcher.find()) {
            return "$FA_BASE${pageMatcher.group(1)}"
        }
        return null
    }

    private fun parseImageUrl(html: String): String? {
        // 方式1: 查找文本为 "Download" 的 <a> 标签（参考 furaffinity-dl 项目，不硬编码域名）
        val downloadLinkPattern = Pattern.compile("""<a[^>]+href="([^"]+)"[^>]*>\s*[Dd]ownload""")
        val downloadMatcher = downloadLinkPattern.matcher(html)
        if (downloadMatcher.find()) {
            val href = downloadMatcher.group(1) ?: ""
            val url = if (href.startsWith("//")) "https:$href" else href
            if (url.startsWith("http")) return url
        }
        // 方式2: 带 download 属性的链接
        val downloadAttrPattern = Pattern.compile("""<a[^>]+href="([^"]+)"[^>]*download[^>]*>""")
        val downloadAttrMatcher = downloadAttrPattern.matcher(html)
        if (downloadAttrMatcher.find()) {
            val href = downloadAttrMatcher.group(1) ?: ""
            val url = if (href.startsWith("//")) "https:$href" else href
            if (url.startsWith("http")) return url
        }
        // 方式3: 回退到 submission 图片 src
        val imgPattern = Pattern.compile("""<img[^>]+id="submissionImg"[^>]+src="(https?://[^"]+)"""")
        val imgMatcher = imgPattern.matcher(html)
        return if (imgMatcher.find()) imgMatcher.group(1) else null
    }

    /** 从详情页解析完整元数据（参考 furaffinity-dl） */
    private fun parseSubmissionInfo(html: String): SubmissionMeta? {
        val imageUrl = parseImageUrl(html) ?: return null
        fun regex(pattern: String): String {
            val m = Pattern.compile(pattern, Pattern.DOTALL).matcher(html)
            return if (m.find()) (m.group(1) ?: "").trim() else ""
        }
        fun regexInt(pattern: String): Int = regex(pattern).replace(",", "").toIntOrNull() ?: 0

        val title = regex("""submission-title[^>]*>\s*<h2[^>]*>([^<]+)</h2>""")
        val author = regex("""c-usernameBlockSimple__displayName[^>]*title="([^"]+)"""")
        val date = regex("""popup_date[^>]*title="([^"]+)"""")
        val description = regex("""submission-description[^>]*>(.*?)</div>""").replace("\r\n", "\n").trim()
        val rating = regex("""c-contentRating--([a-z]+)""")

        // 统计信息: Category, Theme, Species
        val category = regex("""Category\s*</span>\s*<span[^>]*>([^<]+)</span>""")
        val theme = regex("""Theme\s*</span>\s*<span[^>]*>([^<]+)</span>""")
        val species = regex("""Species\s*</span>\s*<span[^>]*>([^<]+)</span>""")

        // 浏览数 / 收藏数
        val views = regexInt("""title="Views"[^>]*>\s*<div[^>]*>([\d,]+)</div>""")
        val favorites = regexInt("""title="Favorites"[^>]*>\s*<div[^>]*>([\d,]+)</div>""")

        // 标签
        val tags = mutableListOf<String>()
        val tagMatcher = Pattern.compile("""/search/[^"]*"[^>]*>([^<]+)</a>""").matcher(html)
        while (tagMatcher.find()) {
            tagMatcher.group(1)?.trim()?.let { tags.add(it) }
        }

        // 从 URL 提取 FA ID
        val idMatcher = Pattern.compile("""/view/(\d+)/""").matcher(html)
        val faId = if (idMatcher.find()) idMatcher.group(1) ?: "" else ""

        return SubmissionMeta(
            faId = faId, imageUrl = imageUrl, fileName = extractFileName(imageUrl),
            title = title, author = author, date = date, description = description,
            tags = tags, category = category, theme = theme, species = species,
            rating = rating, views = views, favorites = favorites
        )
    }

    private fun extractFileName(url: String): String {
        return url.substringAfterLast("/").substringBefore("?")
    }

    private fun extractPageId(pageUrl: String): String {
        val pattern = Pattern.compile("""/view/(\d+)/""")
        val matcher = pattern.matcher(pageUrl)
        return if (matcher.find()) matcher.group(1) ?: "" else ""
    }

    // ── Cache ──

    private fun getCachedUrl(author: String, pageId: String): String? {
        // 新格式: author/pageId
        val newKey = "$author/$pageId"
        val url = cachePrefs.getString(newKey, null)
        if (!url.isNullOrEmpty()) return url
        // 兼容旧格式: pageId（自动迁移）
        val oldUrl = cachePrefs.getString(pageId, null)
        if (!oldUrl.isNullOrEmpty()) {
            cachePrefs.edit().putString(newKey, oldUrl).remove(pageId).apply()
            return oldUrl
        }
        return null
    }

    private fun cacheUrl(author: String, pageId: String, imageUrl: String) {
        // 只缓存 d.furaffinity.net 格式的链接
        if (imageUrl.matches(Regex("""https://d\.furaffinity\.net/art/[^/]+/\d+.*"""))) {
            cachePrefs.edit().putString("$author/$pageId", imageUrl).apply()
        }
    }

    // ── File Operations ──

    private fun createSubdirectory(parentUri: Uri, name: String): Uri {
        val app = getApplication<Application>()
        val parentDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, parentUri)
            ?: return parentUri
        for (file in parentDoc.listFiles()) {
            if (file.isDirectory && file.name == name) {
                return file.uri
            }
        }
        val newDir = parentDoc.createDirectory(name)
        return newDir?.uri ?: parentUri
    }

    /** 检查目录是否存在（大小写敏感），存在则弹出冲突对话框，返回最终目录 URI */
    private suspend fun checkAndCreateDirectory(parentUri: Uri, name: String): Uri? {
        val app = getApplication<Application>()
        val parentDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, parentUri)
            ?: return parentUri

        // 检查是否存在同名目录（精确匹配 or 大小写不同）
        val exactMatch = parentDoc.listFiles().firstOrNull {
            it.isDirectory && it.name == name
        }
        val caseVariant = if (exactMatch == null) {
            parentDoc.listFiles().firstOrNull {
                it.isDirectory && it.name != null &&
                    it.name!!.equals(name, ignoreCase = true) && it.name != name
            }
        } else null

        // 完全不存在 → 创建并验证
        if (exactMatch == null && caseVariant == null) {
            val newDir = parentDoc.createDirectory(name)
            val createdName = newDir?.name
            if (createdName != null && createdName != name) {
                // 底层存储大小写不敏感：createDirectory("Gryf") 返回了已有的 "gryf"
                // 删除这个误创建的引用，当作冲突处理
                return handleDirConflict(parentDoc, name, newDir)
            }
            return newDir?.uri ?: parentUri
        }

        // 精确匹配 → 直接合并
        if (exactMatch != null) {
            return exactMatch.uri
        }

        // 大小写变体 → 冲突对话框
        return handleDirConflict(parentDoc, name, caseVariant!!)
    }

    private suspend fun handleDirConflict(
        parentDoc: androidx.documentfile.provider.DocumentFile,
        name: String,
        existingDir: androidx.documentfile.provider.DocumentFile
    ): Uri? {
        val deferred = CompletableDeferred<DirConflictAction>()
        dirConflictDeferred = deferred
        _uiState.update { it.copy(showDirConflict = true, conflictDirName = "$name（已存在: ${existingDir.name}）") }
        val action = deferred.await()
        dirConflictDeferred = null
        return when (action) {
            DirConflictAction.MERGE -> existingDir.uri
            DirConflictAction.RENAME -> {
                var seq = 1
                var newName: String
                var conflict: Boolean
                do {
                    newName = "$name($seq)"
                    conflict = parentDoc.listFiles().any {
                        it.isDirectory && it.name?.equals(newName, ignoreCase = true) == true
                    }
                    seq++
                } while (conflict)
                val newDir = parentDoc.createDirectory(newName)
                addLog("目录已重命名: $newName")
                newDir?.uri ?: parentDoc.uri
            }
            DirConflictAction.DELETE -> {
                existingDir.delete()
                addLog("已删除旧目录: ${existingDir.name}")
                val newDir = parentDoc.createDirectory(name)
                // 删除后重新创建，再次验证大小写
                if (newDir?.name != name) {
                    addLog("警告: 目录名大小写不一致: 期望 $name, 实际 ${newDir?.name}")
                }
                newDir?.uri ?: parentDoc.uri
            }
        }
    }

    fun resolveDirConflict(action: DirConflictAction) {
        if (action == DirConflictAction.DELETE) {
            _uiState.update { it.copy(showDirConflict = false, showDeleteConfirm = true) }
        } else {
            _uiState.update { it.copy(showDirConflict = false) }
            dirConflictDeferred?.complete(action)
        }
    }

    fun confirmDeleteDir() {
        _uiState.update { it.copy(showDeleteConfirm = false) }
        dirConflictDeferred?.complete(DirConflictAction.DELETE)
    }

    fun cancelDeleteDir() {
        _uiState.update { it.copy(showDeleteConfirm = false, showDirConflict = true) }
    }

    fun dismissDirConflict() {
        _uiState.update { it.copy(showDirConflict = false, showDeleteConfirm = false) }
        dirConflictDeferred?.complete(DirConflictAction.MERGE)
    }

    /** 已有文件解析结果 */
    private data class ExistingFileInfo(val faId: String, val author: String, val title: String, val fileName: String)

    /** 扫描目录中所有符合命名格式的文件，构建 FAID 索引（兼容带序号和无序号） */
    private fun buildExistingFileIndex(dirUri: Uri): Map<String, ExistingFileInfo> {
        val result = mutableMapOf<String, ExistingFileInfo>()
        try {
            val app = getApplication<Application>()
            val dirDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, dirUri)
                ?: return result
            // 带序号: 0001_author_title_FAID.ext
            val numberedPattern = Regex("""^\d{4}_([^_]+)_(.+)_(\d+)\.\w+$""")
            // 无序号: author_title_FAID.ext
            val unnumberedPattern = Regex("""^([^_]+)_(.+)_(\d+)\.\w+$""")
            for (file in dirDoc.listFiles()) {
                if (!file.isFile) continue
                val name = file.name ?: continue
                // 跳过 .tmp 文件
                if (name.endsWith(".tmp")) continue
                val numbered = numberedPattern.matchEntire(name)
                if (numbered != null) {
                    val author = numbered.groupValues[1]
                    val title = numbered.groupValues[2]
                    val faId = numbered.groupValues[3]
                    result[faId] = ExistingFileInfo(faId, author, title, name)
                    continue
                }
                val unnumbered = unnumberedPattern.matchEntire(name)
                if (unnumbered != null) {
                    val author = unnumbered.groupValues[1]
                    val title = unnumbered.groupValues[2]
                    val faId = unnumbered.groupValues[3]
                    result[faId] = ExistingFileInfo(faId, author, title, name)
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun getFileExtension(url: String): String {
        val name = url.substringAfterLast("/").substringBefore("?")
        return if (name.contains(".")) name.substringAfterLast(".") else "jpg"
    }

    /**
     * 智能差量重命名：
     * 1. 扫描带序号旧文件 + 无序号新文件
     * 2. 合并所有标识，按标题分组排序
     * 3. 差量比对旧编号与新编号，找到第一个差异位置
     * 4. 只重命名从该位置开始的文件（两步法避免冲突）
     */
    private suspend fun renumberFiles(dirUri: Uri, author: String) = withContext(Dispatchers.IO) {
        try {
            val app = getApplication<Application>()
            val dirDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, dirUri) ?: return@withContext
            val safeAuthor = author.replace(Regex("[^a-zA-Z0-9_-]"), "_")

            // 匹配模式
            val numberedPattern = Regex("""^\d{4}_([^_]+)_(.+)_(\d+)\.(\w+)$""")
            val unnumberedPattern = Regex("""^([^_]+)_(.+)_(\d+)\.(\w+)$""")

            data class FileIdentity(val title: String, val faId: Long, val ext: String)
            data class OldEntry(val seq: Int, val identity: FileIdentity, val docFile: androidx.documentfile.provider.DocumentFile, val name: String)

            val oldEntries = mutableListOf<OldEntry>()
            val newDownloads = mutableListOf<androidx.documentfile.provider.DocumentFile>()
            val allIdentities = mutableSetOf<FileIdentity>()

            // ── Step 1: 扫描目录 ──
            for (file in dirDoc.listFiles()) {
                if (!file.isFile) continue
                val name = file.name ?: continue
                if (name.endsWith(".tmp")) continue

                val numbered = numberedPattern.matchEntire(name)
                if (numbered != null) {
                    val title = numbered.groupValues[1]
                    val faId = numbered.groupValues[2].toLongOrNull() ?: continue
                    val ext = numbered.groupValues[3]
                    val seq = name.substringBefore("_").toIntOrNull() ?: 0
                    val identity = FileIdentity(title, faId, ext)
                    oldEntries.add(OldEntry(seq, identity, file, name))
                    allIdentities.add(identity)
                    continue
                }
                val unnumbered = unnumberedPattern.matchEntire(name)
                if (unnumbered != null) {
                    val title = unnumbered.groupValues[1]
                    val faId = unnumbered.groupValues[2].toLongOrNull() ?: continue
                    val ext = unnumbered.groupValues[3]
                    allIdentities.add(FileIdentity(title, faId, ext))
                    newDownloads.add(file)
                }
            }

            if (allIdentities.isEmpty()) return@withContext

            // ── Step 2: 排序（标题分组 → 组间最早FAID → 组内FAID） ──
            val grouped = allIdentities.groupBy { it.title }
            val sorted = grouped.entries
                .sortedBy { (_, group) -> group.minOf { it.faId } }
                .flatMap { (_, group) -> group.sortedBy { it.faId } }

            // ── Step 3: 新编号映射 ──
            val newNumbering = sorted.mapIndexed { idx, identity -> (idx + 1) to identity }

            // ── Step 4: 旧编号映射 ──
            val oldNumbering = oldEntries.associate { it.seq to it.identity }

            // ── Step 5: 差量比对 ──
            val maxOld = oldNumbering.keys.maxOrNull() ?: 0
            val maxNew = newNumbering.size
            val maxSize = maxOf(maxOld, maxNew)
            var firstDiff = maxSize + 1
            for (i in 1..maxSize) {
                val oldId = oldNumbering[i]
                val newId = newNumbering.getOrNull(i - 1)?.second
                if (oldId != newId) {
                    firstDiff = i
                    break
                }
            }

            if (firstDiff > maxSize) {
                addLog("重排序: 无变化")
                return@withContext
            }

            addLog("重排序: ${allIdentities.size} 个文件，${grouped.size} 组，从第 $firstDiff 个开始重命名...")

            // ── Step 6: 构建重命名操作列表 ──
            data class RenameOp(val docFile: androidx.documentfile.provider.DocumentFile, val from: String, val to: String)
            val ops = mutableListOf<RenameOp>()

            val oldByIdentity = oldEntries.associateBy { it.identity }
            val newFileByIdentity = mutableMapOf<FileIdentity, androidx.documentfile.provider.DocumentFile>()
            for (file in newDownloads) {
                val name = file.name ?: continue
                val match = unnumberedPattern.matchEntire(name) ?: continue
                val title = match.groupValues[1]
                val faId = match.groupValues[2].toLongOrNull() ?: continue
                val ext = match.groupValues[3]
                newFileByIdentity[FileIdentity(title, faId, ext)] = file
            }

            for (i in firstDiff..maxNew) {
                val identity = newNumbering.getOrNull(i - 1)?.second ?: continue
                val newName = "${String.format("%04d", i)}_${safeAuthor}_${identity.title}_${identity.faId}.${identity.ext}"

                val oldEntry = oldByIdentity[identity]
                if (oldEntry != null) {
                    if (oldEntry.name != newName) {
                        ops.add(RenameOp(oldEntry.docFile, oldEntry.name, newName))
                    }
                } else {
                    val newFile = newFileByIdentity[identity]
                    if (newFile != null) {
                        val currentName = newFile.name ?: continue
                        ops.add(RenameOp(newFile, currentName, newName))
                    }
                }
            }

            if (ops.isEmpty()) {
                addLog("重排序: 无需重命名")
                return@withContext
            }

            // ── Step 7: 两步重命名（先 → .tmp，再 → 最终名） ──
            var renamed = 0
            for (op in ops) {
                try {
                    op.docFile.renameTo("${op.to}.tmp")
                    renamed++
                } catch (e: Exception) {
                    addLog("  重命名失败: ${op.from}")
                }
            }
            for (op in ops) {
                try {
                    dirDoc.listFiles()
                        .firstOrNull { it.name == "${op.to}.tmp" }
                        ?.renameTo(op.to)
                } catch (e: Exception) {
                    addLog("  最终重命名失败: ${op.to}.tmp")
                }
            }

            addLog("重排序完成: 重命名 $renamed 个文件（从第 $firstDiff 个开始）")
        } catch (e: Exception) {
            addLog("重排序异常: ${e.message}")
        }
    }

    /** 下载图片（参考 furaffinity-dl 的 download_file: 用同一个 session.get） */
    private suspend fun downloadToBytes(imageUrl: String, cookie: String = ""): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val conn = httpGet(imageUrl, cookie)
            val code = conn.responseCode
            if (code != 200) {
                addLog("  HTTP $code: $imageUrl")
                conn.disconnect()
                return@withContext null
            }
            val buffer = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                buffer.use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()
            buffer.toByteArray()
        } catch (e: Exception) {
            addLog("  下载异常: ${e.message}")
            null
        }
    }

    private fun saveToFile(dirUri: Uri, fileName: String, data: ByteArray): Boolean {
        return try {
            val app = getApplication<Application>()
            val resolver = app.contentResolver
            val dirDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, dirUri)

            val existing = dirDoc?.findFile(fileName)
            existing?.delete()

            val mimeType = when {
                fileName.endsWith(".png", true) -> "image/png"
                fileName.endsWith(".gif", true) -> "image/gif"
                fileName.endsWith(".webp", true) -> "image/webp"
                else -> "image/jpeg"
            }
            val newFile = dirDoc?.createFile(mimeType, fileName)
            if (newFile != null) {
                resolver.openOutputStream(newFile.uri)?.use { output ->
                    output.write(data)
                }
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
