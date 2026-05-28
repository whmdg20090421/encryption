package com.whmdg.mczj.tools.ui.download

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

data class FAUiState(
    val author: String = "",
    val downloadType: String = "gallery", // gallery or scraps
    val saveDir: Uri? = null,
    val saveDirPath: String = "",
    val startPage: String = "1",
    val maxDownload: String = "0",
    val skipExisting: Boolean = true,
    val useCache: Boolean = true,
    val namingMode: String = "original", // "original" or "sequential"
    val downloadThreads: Int = 1, // 1~4
    val isLoggedIn: Boolean = false,
    val cookieExpired: Boolean = false,
    val isDownloading: Boolean = false,
    val logs: List<DownloadLog> = emptyList(),
    val downloadedCount: Int = 0,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    val currentProgress: Float = 0f,
    val statusMessage: String = "准备就绪"
)

class FADownloaderViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(FAUiState())
    val uiState: StateFlow<FAUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null
    private var isStopped = false

    private val prefs = application.getSharedPreferences("fa_download_auth", Context.MODE_PRIVATE)
    private val cachePrefs = application.getSharedPreferences("fa_download_cache", Context.MODE_PRIVATE)

    companion object {
        private const val FA_BASE = "https://www.furaffinity.net"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        private const val REFERER = "https://www.furaffinity.net"
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 30000

        fun saveCookieStatic(context: Context, cookie: String) {
            context.getSharedPreferences("fa_download_auth", Context.MODE_PRIVATE)
                .edit().putString("cookie", cookie).apply()
        }
    }

    init {
        // Load saved cookie
        val cookie = prefs.getString("cookie", "") ?: ""
        _uiState.update { it.copy(isLoggedIn = cookie.isNotEmpty() && cookie.contains("a=")) }
    }

    fun updateAuthor(author: String) = _uiState.update { it.copy(author = author) }
    fun updateType(type: String) = _uiState.update { it.copy(downloadType = type) }
    fun updateSaveDir(uri: Uri, path: String) = _uiState.update { it.copy(saveDir = uri, saveDirPath = path) }
    fun updateStartPage(page: String) = _uiState.update { it.copy(startPage = page) }
    fun updateMaxDownload(max: String) = _uiState.update { it.copy(maxDownload = max) }
    fun updateSkipExisting(skip: Boolean) = _uiState.update { it.copy(skipExisting = skip) }
    fun updateUseCache(use: Boolean) = _uiState.update { it.copy(useCache = use) }
    fun updateNamingMode(mode: String) = _uiState.update { it.copy(namingMode = mode) }
    fun updateDownloadThreads(threads: Int) = _uiState.update { it.copy(downloadThreads = threads.coerceIn(1, 4)) }

    fun saveCookie(cookie: String) {
        prefs.edit().putString("cookie", cookie).apply()
        _uiState.update { it.copy(isLoggedIn = cookie.contains("a="), cookieExpired = false) }
    }

    fun clearCookie() {
        prefs.edit().remove("cookie").apply()
        _uiState.update { it.copy(isLoggedIn = false, cookieExpired = false) }
    }

    fun loadCookie(): String = prefs.getString("cookie", "") ?: ""

    fun startDownload() {
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
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isDownloading = true, downloadedCount = 0, skippedCount = 0, failedCount = 0, statusMessage = "正在下载...") }
            addLog("开始下载 ${state.author} 的 ${state.downloadType}")

            val startPage = state.startPage.toIntOrNull() ?: 1
            val maxDownload = state.maxDownload.toIntOrNull() ?: 0
            val cookie = loadCookie()
            val threadCount = state.downloadThreads

            // Create author subdirectory
            val authorDir = createSubdirectory(state.saveDir, state.author)
            val targetDir = if (state.downloadType == "scraps") {
                createSubdirectory(authorDir, "scraps")
            } else {
                authorDir
            }
            addLog("保存目录: ${state.author}/${if (state.downloadType == "scraps") "scraps/" else ""}")

            // ── Phase 1: Collect all download tasks across pages ──
            data class DownloadTask(
                val seq: Int,
                val imageUrl: String,
                val fileName: String,
                val pageId: String
            )

            val allTasks = mutableListOf<DownloadTask>()
            var currentPage = startPage
            var consecutiveEmpty = 0
            var globalSeq = 0

            addLog("正在收集下载任务...")
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
                        _uiState.update { it.copy(isDownloading = false, statusMessage = "作者不存在") }
                        return@launch
                    }
                    if (html.contains("available to registered users only")) {
                        addLog("该作者的作品仅对注册用户可见，请先登录")
                        _uiState.update { it.copy(cookieExpired = true, isDownloading = false, statusMessage = "需要登录") }
                        return@launch
                    }
                    if (html.contains("id=\"login-form\"") && state.isLoggedIn) {
                        addLog("Cookie 已失效，请重新登录")
                        _uiState.update { it.copy(cookieExpired = true, isDownloading = false, statusMessage = "Cookie 失效") }
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

                    for (pageUrl in pages) {
                        if (isStopped) break
                        if (maxDownload > 0 && allTasks.size >= maxDownload) break

                        val pageId = extractPageId(pageUrl)
                        val imageUrl: String
                        val fileName: String

                        val cachedUrl = if (state.useCache) getCachedUrl(pageId) else null
                        if (cachedUrl != null) {
                            imageUrl = cachedUrl
                            fileName = extractFileName(cachedUrl)
                        } else {
                            val detailHtml = fetchHtml(pageUrl, cookie)
                            if (detailHtml == null) {
                                addLog("  获取详情页失败: $pageUrl")
                                continue
                            }
                            val parsedUrl = parseImageUrl(detailHtml)
                            if (parsedUrl == null) {
                                addLog("  无法解析图片地址: $pageUrl")
                                continue
                            }
                            imageUrl = parsedUrl
                            fileName = extractFileName(parsedUrl)
                            if (state.useCache) cacheUrl(pageId, imageUrl)
                        }

                        // Determine save file name
                        val saveFileName = when (state.namingMode) {
                            "sequential" -> {
                                globalSeq++
                                val ext = getFileExtension(imageUrl)
                                "${String.format("%04d", globalSeq)}.$ext"
                            }
                            else -> fileName
                        }

                        allTasks.add(DownloadTask(allTasks.size + 1, imageUrl, saveFileName, pageId))
                    }

                    addLog("第 $currentPage 页: 已收集 ${allTasks.size} 个任务")
                    currentPage++
                }
            } catch (e: Exception) {
                addLog("收集任务异常: ${e.message}")
            }

            if (allTasks.isEmpty()) {
                addLog("没有找到可下载的图片")
                _uiState.update { it.copy(isDownloading = false, statusMessage = "无图片") }
                return@launch
            }

            addLog("共 ${allTasks.size} 个下载任务，${threadCount} 线程下载")

            // ── Phase 2: Multi-thread download + ordered save ──
            var totalDownloaded = 0
            var totalSkipped = 0
            var totalFailed = 0
            val totalTasks = allTasks.size
            val buffer = ConcurrentHashMap<Int, ByteArray?>()
            val fileNameMap = ConcurrentHashMap<Int, String>()
            val semaphore = Semaphore(threadCount)

            // Save original names for skip check
            for (task in allTasks) {
                fileNameMap[task.seq] = task.fileName
            }

            // Launch download coroutines
            val downloadJob = coroutineScope {
                allTasks.map { task ->
                    async {
                        semaphore.withPermit {
                            if (isStopped) return@async
                            // Skip check: only in original naming mode
                            if (state.skipExisting && state.namingMode == "original") {
                                if (checkFileExists(targetDir, task.fileName)) {
                                    buffer[task.seq] = null // null = skipped
                                    return@async
                                }
                            }
                            val data = downloadToBytes(task.imageUrl)
                            buffer[task.seq] = data
                        }
                    }
                }
            }

            // Ordered save loop
            var saveSeq = 1
            while (saveSeq <= totalTasks && !isStopped) {
                if (buffer.containsKey(saveSeq)) {
                    val data = buffer.remove(saveSeq)
                    val fileName = fileNameMap[saveSeq] ?: "unknown"
                    if (data == null) {
                        // Skipped
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
                    _uiState.update {
                        it.copy(currentProgress = saveSeq.toFloat() / totalTasks)
                    }
                    saveSeq++
                } else {
                    delay(50)
                }
            }

            // Wait for any remaining downloads
            downloadJob.awaitAll()

            addLog("下载完成 — 已下载: $totalDownloaded, 跳过: $totalSkipped, 失败: $totalFailed")
            _uiState.update {
                it.copy(
                    isDownloading = false,
                    statusMessage = "下载完成",
                    currentProgress = 1f
                )
            }
        }
    }

    fun stopDownload() {
        isStopped = true
        downloadJob?.cancel()
        _uiState.update { it.copy(isDownloading = false, statusMessage = "已停止") }
        addLog("用户停止下载")
    }

    private fun addLog(message: String) {
        _uiState.update { it.copy(logs = it.logs + DownloadLog(message)) }
    }

    // ── HTTP ──

    private suspend fun fetchHtml(url: String, cookie: String): String? = withContext(Dispatchers.IO) {
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

            if (conn.responseCode == 503) {
                return@withContext null
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val html = reader.readText()
            reader.close()
            conn.disconnect()
            html
        } catch (e: Exception) {
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

    private fun parseImageUrl(html: String): String? {
        // Match: href="...d.facdn.net/..."...>Download
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
        // Check if subdirectory already exists
        val existing = parentDoc.findFile(name)
        if (existing != null && existing.exists() && existing.isDirectory) {
            return existing.uri
        }
        // Create new subdirectory
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

            // Delete existing file if present (for overwrite mode)
            val existing = dirDoc?.findFile(fileName)
            existing?.delete()

            // Create new file
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
