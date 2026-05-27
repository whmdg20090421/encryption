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
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

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

            var currentPage = startPage
            var totalDownloaded = 0
            var totalSkipped = 0
            var totalFailed = 0
            var consecutiveEmpty = 0

            try {
                while (!isStopped) {
                    if (maxDownload > 0 && totalDownloaded >= maxDownload) {
                        addLog("已达到最大下载量 ($maxDownload)")
                        break
                    }

                    val url = "$FA_BASE/${state.downloadType}/${state.author}/$currentPage"
                    addLog("正在获取第 $currentPage 页...")

                    val html = fetchHtml(url, cookie)
                    if (html == null) {
                        addLog("网络错误，无法获取页面")
                        consecutiveEmpty++
                        if (consecutiveEmpty >= 3) {
                            addLog("连续 3 次失败，停止下载")
                            break
                        }
                        currentPage++
                        continue
                    }

                    // Check if user exists
                    if (html.contains("could not be found")) {
                        addLog("未找到作者「${state.author}」，请检查名称")
                        break
                    }

                    // Check if login required
                    if (html.contains("available to registered users only")) {
                        addLog("该作者的作品仅对注册用户可见，请先登录")
                        _uiState.update { it.copy(cookieExpired = true) }
                        break
                    }

                    // Check if cookie expired (redirected to login)
                    if (html.contains("id=\"login-form\"") && state.isLoggedIn) {
                        addLog("Cookie 已失效，请重新登录")
                        _uiState.update { it.copy(cookieExpired = true) }
                        break
                    }

                    val pages = parseGalleryPages(html)
                    if (pages.isEmpty()) {
                        addLog("第 $currentPage 页没有更多图片")
                        consecutiveEmpty++
                        if (consecutiveEmpty >= 2) break
                        currentPage++
                        continue
                    }
                    consecutiveEmpty = 0

                    addLog("第 $currentPage 页共 ${pages.size} 张图片")

                    for ((index, pageUrl) in pages.withIndex()) {
                        if (isStopped) break
                        if (maxDownload > 0 && totalDownloaded >= maxDownload) break

                        val pageId = extractPageId(pageUrl)
                        val fileName: String
                        val imageUrl: String?

                        // Check cache
                        val cachedUrl = if (state.useCache) getCachedUrl(pageId) else null
                        if (cachedUrl != null) {
                            imageUrl = cachedUrl
                            fileName = extractFileName(cachedUrl)
                        } else {
                            // Fetch detail page
                            val detailHtml = fetchHtml(pageUrl, cookie)
                            if (detailHtml == null) {
                                addLog("  获取详情页失败: $pageUrl")
                                totalFailed++
                                _uiState.update { it.copy(failedCount = totalFailed) }
                                continue
                            }

                            imageUrl = parseImageUrl(detailHtml)
                            if (imageUrl == null) {
                                addLog("  无法解析图片地址")
                                totalFailed++
                                _uiState.update { it.copy(failedCount = totalFailed) }
                                continue
                            }

                            fileName = extractFileName(imageUrl)
                            // Save to cache
                            if (state.useCache) {
                                cacheUrl(pageId, imageUrl)
                            }
                        }

                        // Check if file exists
                        if (state.skipExisting && checkFileExists(state.saveDir, fileName, state.downloadType)) {
                            totalSkipped++
                            _uiState.update { it.copy(skippedCount = totalSkipped) }
                            continue
                        }

                        // Download image
                        val success = downloadImage(imageUrl, state.saveDir, fileName, state.downloadType)
                        if (success) {
                            totalDownloaded++
                            addLog("  ✓ $fileName")
                        } else {
                            totalFailed++
                            addLog("  ✗ 下载失败: $fileName")
                        }

                        _uiState.update {
                            it.copy(
                                downloadedCount = totalDownloaded,
                                failedCount = totalFailed
                            )
                        }
                    }

                    _uiState.update { it.copy(currentProgress = if (maxDownload > 0) totalDownloaded.toFloat() / maxDownload else 0f) }
                    currentPage++
                }
            } catch (e: Exception) {
                addLog("下载异常: ${e.message}")
            }

            addLog("下载完成 — 已下载: $totalDownloaded, 跳过: $totalSkipped, 失败: $totalFailed")
            _uiState.update {
                it.copy(
                    isDownloading = false,
                    statusMessage = "下载完成",
                    currentProgress = if (maxDownload > 0) totalDownloaded.toFloat() / maxDownload else 1f
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

    private fun checkFileExists(saveDir: Uri, fileName: String, type: String): Boolean {
        return try {
            val resolver = getApplication<Application>().contentResolver
            val dirUri = if (type == "scraps") {
                Uri.parse("$saveDir/scraps")
            } else {
                saveDir
            }
            // Try to find the file
            val fileUri = Uri.parse("$dirUri/$fileName")
            try {
                resolver.openInputStream(fileUri)?.close()
                true
            } catch (_: Exception) {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun downloadImage(
        imageUrl: String,
        saveDir: Uri,
        fileName: String,
        type: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL(imageUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", REFERER)
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT

            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext false
            }

            val app = getApplication<Application>()
            val resolver = app.contentResolver

            // Create scraps subdirectory if needed
            val targetDir = if (type == "scraps") {
                try {
                    val scrapsDir = Uri.parse("$saveDir/scraps")
                    // Try to create directory via DocumentFile
                    val documentFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, scrapsDir)
                    if (documentFile == null || !documentFile.exists()) {
                        // Create via parent
                        val parentDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, saveDir)
                        parentDoc?.createDirectory("scraps")
                    }
                    scrapsDir
                } catch (_: Exception) {
                    saveDir
                }
            } else {
                saveDir
            }

            val fileUri = Uri.parse("$targetDir/$fileName")
            val outputStream = try {
                resolver.openOutputStream(fileUri)
            } catch (_: Exception) {
                // Try creating the file
                try {
                    val parentDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, targetDir)
                    val newFile = parentDoc?.createFile("image/*", fileName)
                    newFile?.uri?.let { resolver.openOutputStream(it) }
                } catch (_: Exception) {
                    null
                }
            }

            if (outputStream == null) {
                conn.disconnect()
                return@withContext false
            }

            conn.inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()
            true
        } catch (e: Exception) {
            false
        }
    }
}
