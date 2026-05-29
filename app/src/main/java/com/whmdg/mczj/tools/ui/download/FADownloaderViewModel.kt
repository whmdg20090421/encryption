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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class DownloadLog(val message: String, val timestamp: Long = System.currentTimeMillis())

/** 预览列表中的单个下载任务 */
data class PreviewItem(
    val seq: Int,
    val fileName: String,
    val imageUrl: String
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
    val namingMode: String = "original",
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
    val downloadedCount: Int = 0,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    val currentProgress: Float = 0f,
    val statusMessage: String = "准备就绪"
)

class FADownloaderViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val _uiState = MutableStateFlow(FAUiState())
    val uiState: StateFlow<FAUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null
    private var collectJob: Job? = null
    private var isStopped = false

    /** 收集→下载的生产者-消费者通道 */
    private var downloadChannel: Channel<PreviewItem>? = null

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
        _uiState.update {
            it.copy(
                isLoggedIn = cookie.isNotEmpty() && cookie.contains("a=") && cookie.contains("b="),
                username = username,
                saveDir = savedDirUri?.let { uri -> Uri.parse(uri) },
                saveDirPath = savedDirPath
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
    fun updateSkipExisting(skip: Boolean) = _uiState.update { it.copy(skipExisting = skip) }
    fun updateUseCache(use: Boolean) = _uiState.update { it.copy(useCache = use) }
    fun updateNamingMode(mode: String) = _uiState.update { it.copy(namingMode = mode) }
    fun updateDownloadThreads(threads: Int) = _uiState.update { it.copy(downloadThreads = threads.coerceIn(1, 4)) }

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
        val channel = Channel<PreviewItem>(Channel.UNLIMITED)
        downloadChannel = channel

        _uiState.update {
            it.copy(
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
            var currentPage = startPage
            var consecutiveEmpty = 0
            var globalSeq = 0
            var firstPageCount = 0

            try {
                while (!isStopped) {
                    if (maxDownload > 0 && allTasks.size >= maxDownload) break

                    val url = "$FA_BASE/${state.downloadType}/${state.author}/$currentPage"
                    val html = fetchHtml(url, cookie)
                    if (html == null) {
                        consecutiveEmpty++
                        if (consecutiveEmpty >= 3) {
                            addLog("连续 3 次失败，停止收集")
                            break
                        }
                        currentPage++
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
                    if (html.contains("id=\"login-form\"") && state.isLoggedIn) {
                        addLog("Cookie 已失效")
                        markCookieExpired()
                        _uiState.update { it.copy(isCollecting = false, statusMessage = "Cookie 失效") }
                        channel.close()
                        return@launch
                    }

                    val pages = parseGalleryPages(html)
                    if (pages.isEmpty()) {
                        consecutiveEmpty++
                        if (consecutiveEmpty >= 2) break
                        currentPage++
                        continue
                    }
                    consecutiveEmpty = 0

                    // First page done → 用分页链接估算总页数
                    if (currentPage == startPage) {
                        firstPageCount = pages.size
                        val maxPageNum = parseMaxPage(html)
                        val estimatedTotal = if (maxDownload > 0) {
                            minOf(firstPageCount * maxPageNum, maxDownload)
                        } else {
                            firstPageCount * maxPageNum
                        }
                        _uiState.update { it.copy(collectionTotal = estimatedTotal) }
                        addLog("每页 ~$firstPageCount 个，共 $maxPageNum 页，预估 $estimatedTotal 个任务")
                    }

                    // 并行抓取详情页，复用 downloadThreads 作为并发数
                    val semaphore = Semaphore(state.downloadThreads)
                    val deferreds = mutableListOf<kotlinx.coroutines.Deferred<Triple<String, String, String>?>>()

                    for (pageUrl in pages) {
                        if (isStopped) break
                        if (maxDownload > 0 && allTasks.size >= maxDownload) break

                        val deferred = async {
                            semaphore.withPermit {
                                val pageId = extractPageId(pageUrl)
                                val cachedUrl = if (state.useCache) getCachedUrl(pageId) else null
                                if (cachedUrl != null) {
                                    Triple(pageId, cachedUrl, extractFileName(cachedUrl))
                                } else {
                                    val detailHtml = fetchHtml(pageUrl, cookie)
                                    if (detailHtml == null) return@async null
                                    val parsedUrl = parseImageUrl(detailHtml)
                                    if (parsedUrl == null) return@async null
                                    if (state.useCache) cacheUrl(pageId, parsedUrl)
                                    Triple(pageId, parsedUrl, extractFileName(parsedUrl))
                                }
                            }
                        }
                        deferreds.add(deferred)
                    }

                    // 收集结果并更新进度
                    for (deferred in deferreds) {
                        if (isStopped) break
                        val result = deferred.await() ?: continue

                        val (_, imageUrl, fileName) = result
                        val saveFileName = when (state.namingMode) {
                            "sequential" -> {
                                globalSeq++
                                val ext = getFileExtension(imageUrl)
                                "${String.format("%04d", globalSeq)}.$ext"
                            }
                            else -> fileName
                        }

                        val task = PreviewItem(allTasks.size + 1, saveFileName, imageUrl)
                        allTasks.add(task)
                        channel.send(task)

                        _uiState.update {
                            it.copy(
                                pendingTasks = it.pendingTasks + task,
                                collectionLoaded = allTasks.size,
                                // 动态修正估算：实际值不低于已收集数
                                collectionTotal = it.collectionTotal.coerceAtLeast(allTasks.size)
                            )
                        }
                    }

                    addLog("第 $currentPage 页: 已收集 ${allTasks.size} 个任务")
                    currentPage++
                }
            } catch (e: Exception) {
                addLog("收集任务异常: ${e.message}")
            }

            channel.close()
            _uiState.update {
                it.copy(
                    isCollecting = false,
                    collectionComplete = true,
                    collectionTotal = allTasks.size,
                    statusMessage = if (allTasks.isEmpty()) "无图片" else "收集完成 ${allTasks.size} 个任务"
                )
            }
            if (allTasks.isEmpty()) {
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

            val authorDir = createSubdirectory(state.saveDir!!, state.author)
            val targetDir = if (state.downloadType == "scraps") {
                createSubdirectory(authorDir, "scraps")
            } else {
                authorDir
            }
            addLog("保存目录: ${state.author}/${if (state.downloadType == "scraps") "scraps/" else ""}")

            // Producer-consumer: read from channel, download in parallel, save in order
            val buffer = ConcurrentHashMap<Int, ByteArray?>()
            val fileNameMap = ConcurrentHashMap<Int, String>()
            val semaphore = Semaphore(threadCount)
            var nextSeq = 1
            var totalDownloaded = 0
            var totalSkipped = 0
            var totalFailed = 0
            var totalReceived = 0

            // Consume from channel: for each task, spawn a download coroutine
            val dlJob = coroutineScope {
                val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

                for (task in ch) {
                    if (isStopped) break
                    totalReceived++
                    fileNameMap[task.seq] = task.fileName

                    val deferred = async {
                        semaphore.withPermit {
                            if (isStopped) return@async
                            if (state.skipExisting && state.namingMode == "original") {
                                if (checkFileExists(targetDir, task.fileName)) {
                                    buffer[task.seq] = null // skipped
                                    return@async
                                }
                            }
                            val data = downloadToBytes(task.imageUrl)
                            buffer[task.seq] = data
                        }
                    }
                    jobs.add(deferred)
                }

                jobs
            }

            // Wait for all downloads to finish
            dlJob.awaitAll()

            // Ordered save: drain buffer in sequence order
            while (nextSeq <= totalReceived && !isStopped) {
                if (buffer.containsKey(nextSeq)) {
                    val data = buffer.remove(nextSeq)
                    val fileName = fileNameMap[nextSeq] ?: "unknown"
                    if (data == null) {
                        totalSkipped++
                        _uiState.update { it.copy(skippedCount = totalSkipped) }
                    } else if (saveToFile(targetDir, fileName, data)) {
                        totalDownloaded++
                        addLog("  ✓ $fileName")
                        _uiState.update { it.copy(downloadedCount = totalDownloaded) }
                    } else {
                        totalFailed++
                        addLog("  ✗ 保存失败: $fileName")
                        _uiState.update { it.copy(failedCount = totalFailed) }
                    }
                    _uiState.update { it.copy(currentProgress = nextSeq.toFloat() / totalReceived.coerceAtLeast(1)) }
                    nextSeq++
                } else {
                    delay(50)
                }
            }

            addLog("下载完成 — 已下载: $totalDownloaded, 跳过: $totalSkipped, 失败: $totalFailed")
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
                pendingTasks = emptyList(),
                statusMessage = "已停止"
            )
        }
        addLog("用户停止下载")
    }

    private fun addLog(message: String) {
        _uiState.update { it.copy(logs = it.logs + DownloadLog(message)) }
    }

    // ── HTTP ──

    private suspend fun fetchHtml(url: String, cookie: String, silent: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", REFERER)
            conn.setRequestProperty("Accept", "*/*")
            if (cookie.isNotEmpty()) {
                conn.setRequestProperty("Cookie", cookie)
            }
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT

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
        val pattern = Pattern.compile("""<a href="/view/(\d+)/">""")
        val matcher = pattern.matcher(html)
        while (matcher.find()) {
            pages.add("$FA_BASE/view/${matcher.group(1)}/")
        }
        return pages.distinct()
    }

    /** 从画廊页面解析最大页码（如存在分页导航） */
    private fun parseMaxPage(html: String): Int {
        // 匹配 /gallery/username/123/ 或 /scraps/username/123/ 格式的分页链接
        val pattern = Pattern.compile("""href="/(?:gallery|scraps)/[^/]+/(\d+)/"""")
        val matcher = pattern.matcher(html)
        var maxPage = 1
        while (matcher.find()) {
            val page = matcher.group(1)?.toIntOrNull() ?: continue
            if (page > maxPage) maxPage = page
        }
        return maxPage
    }

    private fun parseImageUrl(html: String): String? {
        val pattern = Pattern.compile("""href="(//d\.facdn\.net/[^"]+)"[^>]*>\s*Download""")
        val matcher = pattern.matcher(html)
        return if (matcher.find()) "https:${matcher.group(1)}" else null
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

    private fun getCachedUrl(pageId: String): String? {
        val url = cachePrefs.getString(pageId, null)
        return if (url.isNullOrEmpty()) null else url
    }

    private fun cacheUrl(pageId: String, imageUrl: String) {
        cachePrefs.edit().putString(pageId, imageUrl).apply()
    }

    // ── File Operations ──

    private fun createSubdirectory(parentUri: Uri, name: String): Uri {
        val app = getApplication<Application>()
        val parentDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, parentUri)
            ?: return parentUri
        val existing = parentDoc.findFile(name)
        if (existing != null && existing.exists() && existing.isDirectory) {
            return existing.uri
        }
        val newDir = parentDoc.createDirectory(name)
        return newDir?.uri ?: parentUri
    }

    private fun checkFileExists(dirUri: Uri, fileName: String): Boolean {
        return try {
            val app = getApplication<Application>()
            val dirDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, dirUri)
            val file = dirDoc?.findFile(fileName)
            file != null && file.exists()
        } catch (_: Exception) {
            false
        }
    }

    private fun getFileExtension(url: String): String {
        val name = url.substringAfterLast("/").substringBefore("?")
        return if (name.contains(".")) name.substringAfterLast(".") else "jpg"
    }

    private suspend fun downloadToBytes(imageUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(imageUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", REFERER)
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT

            if (conn.responseCode != 200) {
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
        } catch (_: Exception) {
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
