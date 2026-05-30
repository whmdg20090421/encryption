package com.whmdg.mczj.tools.ui.download.Deviant

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.auth.Feature
import com.whmdg.mczj.tools.auth.SecurityEnforcer
import com.whmdg.mczj.tools.ui.download.DownloadLog
import com.whmdg.mczj.tools.ui.download.NetworkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

class DeviantDownloaderViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val _uiState = MutableStateFlow(DeviantUiState())
    val uiState: StateFlow<DeviantUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null
    private var collectJob: Job? = null
    private var isStopped = false

    private val prefs = application.getSharedPreferences(AppDataPaths.PREFS_BATCH_DOWNLOADER, Context.MODE_PRIVATE)

    companion object {
        private const val DA_BASE = "https://www.deviantart.com"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 30000
        private const val PAGE_SIZE = 24

        fun saveCookieStatic(context: Context, cookie: String) {
            context.getSharedPreferences(AppDataPaths.PREFS_BATCH_DOWNLOADER, Context.MODE_PRIVATE)
                .edit()
                .putString("deviant_cookie", cookie)
                .apply()
        }
    }

    init {
        val cookie = prefs.getString("deviant_cookie", "") ?: ""
        val savedDirUri = prefs.getString("deviant_save_dir_uri", null)
        val savedDirPath = prefs.getString("deviant_save_dir_path", "") ?: ""
        val savedSkipExisting = prefs.getBoolean("deviant_skip_existing", true)
        val savedThreads = prefs.getInt("deviant_download_threads", 2)
        _uiState.update {
            it.copy(
                isLoggedIn = cookie.isNotEmpty(),
                saveDir = savedDirUri?.let { uri -> Uri.parse(uri) },
                saveDirPath = savedDirPath,
                skipExisting = savedSkipExisting,
                downloadThreads = savedThreads
            )
        }
    }

    // ── UI 状态更新 ──

    fun updateUsername(username: String) = _uiState.update { it.copy(username = username) }
    fun updateGalleryType(type: String) = _uiState.update { it.copy(galleryType = type) }
    fun updateSaveDir(uri: Uri, path: String) {
        prefs.edit()
            .putString("deviant_save_dir_uri", uri.toString())
            .putString("deviant_save_dir_path", path)
            .apply()
        _uiState.update { it.copy(saveDir = uri, saveDirPath = path) }
    }
    fun updateSkipExisting(skip: Boolean) {
        prefs.edit().putBoolean("deviant_skip_existing", skip).apply()
        _uiState.update { it.copy(skipExisting = skip) }
    }
    fun updateDownloadThreads(threads: Int) {
        val t = threads.coerceIn(1, 4)
        prefs.edit().putInt("deviant_download_threads", t).apply()
        _uiState.update { it.copy(downloadThreads = t) }
    }

    fun saveCookie(cookie: String) {
        prefs.edit().putString("deviant_cookie", cookie).apply()
        _uiState.update { it.copy(isLoggedIn = cookie.isNotEmpty()) }
    }

    fun clearCookie() {
        prefs.edit().remove("deviant_cookie").apply()
        _uiState.update { it.copy(isLoggedIn = false) }
    }

    fun loadCookie(): String = prefs.getString("deviant_cookie", "") ?: ""

    fun refreshAuth() {
        val cookie = prefs.getString("deviant_cookie", "") ?: ""
        _uiState.update { it.copy(isLoggedIn = cookie.isNotEmpty()) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    private fun addLog(message: String) {
        _uiState.update { it.copy(logs = listOf(DownloadLog(message)) + it.logs) }
    }

    // ── 网络检测 ──

    fun checkNetworkStatus() {
        _uiState.update { it.copy(networkStatus = NetworkStatus.CHECKING) }
        viewModelScope.launch(Dispatchers.IO) {
            val cookie = loadCookie()
            if (cookie.isEmpty()) {
                _uiState.update { it.copy(networkStatus = NetworkStatus.NO_COOKIE) }
                return@launch
            }

            if (!pingHost("www.baidu.com")) {
                _uiState.update { it.copy(networkStatus = NetworkStatus.NETWORK_DOWN) }
                return@launch
            }

            if (!pingHost("www.deviantart.com")) {
                _uiState.update { it.copy(networkStatus = NetworkStatus.COOKIE_EXPIRED) }
                return@launch
            }

            _uiState.update { it.copy(networkStatus = NetworkStatus.CONNECTED) }
        }
    }

    private fun pingHost(host: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("/system/bin/ping -c 1 -W 3 $host")
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    // ── Phase 1: 收集作品列表 ──

    fun startCollect() {
        if (!SecurityEnforcer.checkOrDie(context, Feature.BATCH_DOWNLOADER, "DeviantDownloaderViewModel.startCollect")) {
            addLog("权限不足：无法启动收集")
            return
        }

        clearLogs()
        val state = _uiState.value
        if (state.username.isBlank()) {
            addLog("请输入用户名")
            return
        }
        if (state.saveDir == null) {
            addLog("请选择保存目录")
            return
        }

        isStopped = false

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
        addLog("开始收集 ${state.username} 的 ${if (state.galleryType == "gallery") "画廊" else "收藏"}...")

        collectJob = viewModelScope.launch(Dispatchers.IO) {
            val cookie = loadCookie()
            val allTasks = mutableListOf<DeviantPreviewItem>()
            var offset = 0
            var consecutiveEmpty = 0

            try {
                while (!isStopped) {
                    val galleryUrl = buildGalleryUrl(state.username, state.galleryType, offset)
                    addLog("扫描 offset=$offset ...")
                    val html = fetchHtml(galleryUrl, cookie)

                    if (html == null) {
                        consecutiveEmpty++
                        if (consecutiveEmpty >= 3) {
                            addLog("连续 3 次请求失败，停止收集")
                            break
                        }
                        offset += PAGE_SIZE
                        continue
                    }

                    // 解析作品链接
                    val deviationLinks = parseGalleryPage(html)
                    if (deviationLinks.isEmpty()) {
                        consecutiveEmpty++
                        if (consecutiveEmpty >= 2) {
                            addLog("连续空页，停止收集")
                            break
                        }
                        offset += PAGE_SIZE
                        continue
                    }

                    consecutiveEmpty = 0
                    addLog("offset=$offset: 发现 ${deviationLinks.size} 个作品链接，开始抓取详情...")

                    // 并行抓取详情页
                    val semaphore = Semaphore(state.downloadThreads)
                    val seenIds = mutableSetOf<String>()

                    for (link in deviationLinks) {
                        if (isStopped) break

                        val deviationId = extractDeviationId(link)
                        if (deviationId == null || !seenIds.add(deviationId)) continue

                        val deferred = async {
                            semaphore.withPermit {
                                fetchDeviationMeta(link, cookie, state.username)
                            }
                        }

                        val meta = deferred.await()
                        if (meta != null && meta.imageUrl.isNotEmpty()) {
                            val ext = getFileExtension(meta.imageUrl)
                            val safeAuthor = meta.author.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                            val fileName = "${safeAuthor}_${meta.deviationId}.$ext"

                            val task = DeviantPreviewItem(
                                seq = allTasks.size + 1,
                                fileName = fileName,
                                imageUrl = meta.imageUrl,
                                title = meta.title,
                                deviationId = meta.deviationId,
                                author = meta.author
                            )
                            allTasks.add(task)

                            _uiState.update {
                                it.copy(
                                    pendingTasks = it.pendingTasks + task,
                                    collectionLoaded = allTasks.size,
                                    collectionTotal = it.collectionTotal.coerceAtLeast(allTasks.size)
                                )
                            }
                        }
                    }

                    addLog("offset=$offset: 本页收集 ${deviationLinks.size} 个链接，累计 ${allTasks.size} 个作品")

                    // 检查是否还有更多页
                    if (!hasNextPage(html)) {
                        addLog("已到末页")
                        break
                    }

                    offset += PAGE_SIZE
                }
            } catch (e: Exception) {
                addLog("收集异常: ${e.message}")
            }

            val isEmpty = allTasks.isEmpty()
            _uiState.update {
                it.copy(
                    isCollecting = false,
                    collectionComplete = true,
                    collectionTotal = allTasks.size,
                    statusMessage = if (isEmpty) "无作品" else "收集完成 ${allTasks.size} 个任务",
                    errorMessage = if (isEmpty) "未找到可下载的作品。可能原因：\n• 用户名拼写错误\n• 画廊为空\n• Cookie 已失效\n\n请检查日志中的错误信息。" else null
                )
            }
            if (isEmpty) {
                addLog("没有找到可下载的作品")
            } else {
                addLog("收集完成，共 ${allTasks.size} 个任务")
            }
        }
    }

    fun cancelCollect() {
        isStopped = true
        collectJob?.cancel()
        _uiState.update {
            it.copy(
                isCollecting = false,
                showPreview = false,
                statusMessage = "已取消"
            )
        }
    }

    // ── Phase 2: 开始下载 ──

    fun startDownload() {
        val state = _uiState.value
        if (state.pendingTasks.isEmpty()) {
            addLog("没有待下载的任务")
            return
        }

        _uiState.update {
            it.copy(
                showPreview = false,
                isDownloading = true,
                isPaused = false,
                downloadedCount = 0,
                skippedCount = 0,
                failedCount = 0,
                currentProgress = 0f,
                statusMessage = "正在下载..."
            )
        }
        addLog("开始下载，${state.downloadThreads} 线程")
        isStopped = false

        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            val cookie = loadCookie()
            val tasks = state.pendingTasks
            val totalTasks = tasks.size
            val threadCount = state.downloadThreads

            val targetDir = resolveTargetDir(state)
            if (targetDir == null) {
                _uiState.update { it.copy(isDownloading = false, statusMessage = "目录无效") }
                return@launch
            }
            addLog("保存目录: ${state.saveDirPath}/${state.username}")

            val dirDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(getApplication(), targetDir)
            if (dirDoc == null) {
                _uiState.update { it.copy(isDownloading = false, statusMessage = "目录无效") }
                return@launch
            }

            // 扫描已有文件
            val existingFiles = if (state.skipExisting) buildExistingFileIndex(dirDoc) else emptyMap()
            if (existingFiles.isNotEmpty()) {
                addLog("发现 ${existingFiles.size} 个已有文件，将按 DeviationID 匹配跳过")
            }

            val semaphore = Semaphore(threadCount)
            var totalDownloaded = 0
            var totalSkipped = 0
            var totalFailed = 0
            var completedCount = 0

            val jobs = tasks.map { task ->
                async {
                    semaphore.withPermit {
                        if (isStopped) return@async

                        // 暂停等待
                        while (_uiState.value.isPaused && !isStopped) {
                            delay(200)
                        }
                        if (isStopped) return@async

                        // 增量跳过
                        if (state.skipExisting && existingFiles.containsKey(task.deviationId)) {
                            synchronized(this) {
                                totalSkipped++
                                completedCount++
                                addLog("  ($completedCount/$totalTasks) ⏭ 已存在，跳过: ${task.fileName}")
                                _uiState.update {
                                    it.copy(
                                        skippedCount = totalSkipped,
                                        currentProgress = completedCount.toFloat() / totalTasks
                                    )
                                }
                            }
                            return@async
                        }

                        // 下载，失败重试一次
                        var data = downloadToBytes(task.imageUrl, cookie)
                        if (data == null) {
                            delay(200)
                            data = downloadToBytes(task.imageUrl, cookie)
                        }

                        if (data == null) {
                            synchronized(this) {
                                totalFailed++
                                completedCount++
                                addLog("  ($completedCount/$totalTasks) ✗ 失败: ${task.fileName}")
                                _uiState.update {
                                    it.copy(
                                        failedCount = totalFailed,
                                        currentProgress = completedCount.toFloat() / totalTasks
                                    )
                                }
                            }
                            return@async
                        }

                        // 保存
                        val saved = saveToFile(dirDoc, task.fileName, data)
                        synchronized(this) {
                            completedCount++
                            if (saved) {
                                totalDownloaded++
                                addLog("  ($completedCount/$totalTasks) ✓ ${task.fileName}")
                            } else {
                                totalFailed++
                                addLog("  ($completedCount/$totalTasks) ✗ 保存失败: ${task.fileName}")
                            }
                            _uiState.update {
                                it.copy(
                                    downloadedCount = totalDownloaded,
                                    failedCount = totalFailed,
                                    currentProgress = completedCount.toFloat() / totalTasks
                                )
                            }
                        }
                    }
                }
            }

            jobs.awaitAll()

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

    fun pauseDownload() {
        _uiState.update { it.copy(isPaused = true, statusMessage = "已暂停") }
        addLog("下载已暂停")
    }

    fun resumeDownload() {
        _uiState.update { it.copy(isPaused = false, statusMessage = "正在下载...") }
        addLog("下载已继续")
    }

    fun stopDownload() {
        isStopped = true
        downloadJob?.cancel()
        _uiState.update {
            it.copy(
                isDownloading = false,
                isPaused = false,
                statusMessage = "已停止"
            )
        }
        addLog("下载已停止")
    }

    // ── URL 构建 ──

    private fun buildGalleryUrl(username: String, galleryType: String, offset: Int): String {
        val basePath = when (galleryType) {
            "favourites" -> "$DA_BASE/$username/favourites"
            else -> "$DA_BASE/$username/gallery"
        }
        return if (offset > 0) "$basePath?offset=$offset" else basePath
    }

    // ── HTML 解析 ──

    private fun parseGalleryPage(html: String): List<String> {
        val links = mutableListOf<String>()

        // 方式1: 匹配 thumb 类的链接 (data-hook="deviation_link" 或 class 含 thumb)
        val thumbPattern = Pattern.compile("""<a[^>]+href="(https://www\.deviantart\.com/[^/]+/art/[^"]+)"[^>]*>""")
        val thumbMatcher = thumbPattern.matcher(html)
        while (thumbMatcher.find()) {
            links.add(thumbMatcher.group(1)!!)
        }

        // 方式2: 匹配 deviation 链接 (更宽泛)
        if (links.isEmpty()) {
            val altPattern = Pattern.compile("""href="(/[^/]+/art/[^"]+)"[^>]*>""")
            val altMatcher = altPattern.matcher(html)
            while (altMatcher.find()) {
                val path = altMatcher.group(1)!!
                links.add("$DA_BASE$path")
            }
        }

        return links.distinct()
    }

    private fun hasNextPage(html: String): Boolean {
        // 检查是否有 "Next" 按钮或下一页链接
        return html.contains("Next") || html.contains("next-page") || html.contains("data-hook=\"pagination_next\"")
    }

    private fun extractDeviationId(url: String): String? {
        // URL 格式: https://www.deviantart.com/{author}/art/{slug}-{deviationId}
        // 或: /{author}/art/{slug}-{deviationId}
        val pattern = Pattern.compile("""/art/(?:[^-]*-)?(\d+)""")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    private suspend fun fetchDeviationMeta(url: String, cookie: String, expectedAuthor: String): DeviationMeta? = withContext(Dispatchers.IO) {
        try {
            val html = fetchHtml(url, cookie) ?: return@withContext null

            // 解析标题
            val titlePattern = Pattern.compile("""<h1[^>]*>([^<]+)</h1>""")
            val titleMatcher = titlePattern.matcher(html)
            val title = if (titleMatcher.find()) titleMatcher.group(1)?.trim() ?: "" else ""

            // 解析作者
            val authorPattern = Pattern.compile("""<a[^>]+href="https://www\.deviantart\.com/([^/]+)"[^>]*class="[^"]*username[^"]*"[^>]*>""")
            val authorMatcher = authorPattern.matcher(html)
            val author = if (authorMatcher.find()) authorMatcher.group(1)!! else expectedAuthor

            // 解析图片 URL (优先找 collect_rid 属性的 img)
            var imageUrl = ""
            val collectRidPattern = Pattern.compile("""<img[^>]+collect_rid="[^"]*"[^>]+src="([^"]+)"[^>]*>""")
            val collectRidMatcher = collectRidPattern.matcher(html)
            if (collectRidMatcher.find()) {
                imageUrl = collectRidMatcher.group(1)!!
            }

            // 后备: 找 deviantart-wixmp 或 images-wixmp 的图片
            if (imageUrl.isEmpty()) {
                val wixmpPattern = Pattern.compile("""src="(https?://(?:images-wixmp|deviantart-wixmp)[^"]+\.(?:jpg|png|gif|webp)[^"]*)" """)
                val wixmpMatcher = wixmpPattern.matcher(html)
                if (wixmpMatcher.find()) {
                    imageUrl = wixmpMatcher.group(1)!!
                }
            }

            // 后备: 找 og:image
            if (imageUrl.isEmpty()) {
                val ogPattern = Pattern.compile("""<meta[^>]+property="og:image"[^>]+content="([^"]+)"""")
                val ogMatcher = ogPattern.matcher(html)
                if (ogMatcher.find()) {
                    imageUrl = ogMatcher.group(1)!!
                }
            }

            val deviationId = extractDeviationId(url) ?: ""

            DeviationMeta(
                deviationId = deviationId,
                title = title,
                author = author,
                imageUrl = imageUrl
            )
        } catch (e: Exception) {
            addLog("  解析异常: ${e.message}")
            null
        }
    }

    // ── HTTP 请求 ──

    private fun httpGet(url: String, cookie: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Referer", DA_BASE)
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.5")
        if (cookie.isNotEmpty()) {
            conn.setRequestProperty("Cookie", cookie)
        }
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.instanceFollowRedirects = true
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

    // ── 文件操作 ──

    private fun getFileExtension(url: String): String {
        val name = url.substringAfterLast("/").substringBefore("?")
        return when {
            name.contains(".png", true) -> "png"
            name.contains(".gif", true) -> "gif"
            name.contains(".webp", true) -> "webp"
            name.contains(".") -> name.substringAfterLast(".").lowercase()
            else -> "jpg"
        }
    }

    private suspend fun resolveTargetDir(state: DeviantUiState): Uri? {
        val parentUri = state.saveDir ?: return null
        val parentDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(getApplication(), parentUri)
            ?: return parentUri

        val name = state.username
        val existing = parentDoc.listFiles().firstOrNull {
            it.isDirectory && it.name == name
        }
        if (existing != null) return existing.uri

        val newDir = parentDoc.createDirectory(name)
        return newDir?.uri ?: parentUri
    }

    private fun buildExistingFileIndex(dirDoc: androidx.documentfile.provider.DocumentFile): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            // 文件名格式: {author}_{deviationId}.{ext}
            val pattern = Regex("""^.+_(\d+)\.\w+$""")
            for (file in dirDoc.listFiles()) {
                if (!file.isFile) continue
                val name = file.name ?: continue
                val match = pattern.matchEntire(name)
                if (match != null) {
                    val deviationId = match.groupValues[1]
                    result[deviationId] = name
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun saveToFile(dirDoc: androidx.documentfile.provider.DocumentFile, fileName: String, data: ByteArray): Boolean {
        return try {
            val resolver = getApplication<Application>().contentResolver
            val mimeType = when {
                fileName.endsWith(".png", true) -> "image/png"
                fileName.endsWith(".gif", true) -> "image/gif"
                fileName.endsWith(".webp", true) -> "image/webp"
                else -> "image/jpeg"
            }
            val newFile = dirDoc.createFile(mimeType, fileName)
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
