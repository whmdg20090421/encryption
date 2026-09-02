package com.whmdg.mczj.tools.util

import android.content.Context
import android.util.Log
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.ui.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 压缩包浏览工具类。
 * 通过 7-Zip JBinding 读取压缩包目录结构，构建内存目录树，支持只读浏览。
 */
object ArchiveBrowser {

    private const val TAG = "ArchiveBrowser"

    /** 支持的压缩包扩展名 */
    private val ARCHIVE_EXTENSIONS = setOf(
        "zip", "7z", "rar", "tar", "gz", "bz2", "xz",
        "lz4", "zst", "lzma", "cab", "iso", "dmg", "ar", "cpio", "wim", "xar"
    )

    /** 判断文件名是否为压缩包 */
    fun isArchiveFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        // 复合扩展名：tar.gz, tar.bz2, tar.xz
        val secondExt = name.substringBeforeLast('.', "").substringAfterLast('.', "").lowercase()
        if (secondExt == "tar" && ext in setOf("gz", "bz2", "xz")) return true
        return ext in ARCHIVE_EXTENSIONS
    }

    /** 去掉压缩包扩展名，用于解压目标路径计算 */
    fun stripArchiveExtension(name: String): String {
        val lower = name.lowercase()
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tar.bz2") || lower.endsWith(".tar.xz")) {
            return name.substringBeforeLast('.').substringBeforeLast('.')
        }
        return name.substringBeforeLast('.')
    }

    /**
     * 密码检测结果
     */
    sealed class PasswordCheckResult {
        /** 无加密，可直接打开 */
        data object NoPassword : PasswordCheckResult()
        /** 仅内容加密（文件名可见），可展开目录树，提取时才需要密码 */
        data object ContentEncrypted : PasswordCheckResult()
        /** 头部加密（文件名也加密），必须先输入密码 */
        data object HeaderEncrypted : PasswordCheckResult()
        /** 检测失败 */
        data class Error(
            val errorMessage: String,
            val command: String = "",
            val output: String = ""
        ) : PasswordCheckResult()
    }

    /**
     * 探测压缩包加密类型。
     *
     * 检测策略：
     * - 非7z格式 → 直接 NoPassword（文件名列表始终可见）
     * - 7z格式 → 读文件头判断头部是否加密（无需打开压缩包）
     *   - 头部加密 → HeaderEncrypted（必须先输入密码）
     *   - 头部未加密 → NoPassword（直接展开目录树，提取时再按条目 ENCRYPTED 判断）
     */
    suspend fun checkPasswordRequired(
        context: Context,
        archivePath: String,
        permissionLevel: String
    ): PasswordCheckResult = withContext(Dispatchers.IO) {
        // ZIP 等非 7z 格式同样可能只加密内容。文件名可见不代表后续读取
        // 文件数据无需密码，因此不能在这里跳过检测。
        if (!archivePath.endsWith(".7z", ignoreCase = true)) {
            val encryption = JBindingClient.detectEncryption(archivePath).getOrElse { e ->
                return@withContext PasswordCheckResult.Error(
                    errorMessage = e.message ?: "无法检测压缩包加密状态",
                    command = "detectEncryption($archivePath)",
                    output = e.stackTraceToString()
                )
            }
            return@withContext when (encryption) {
                JBindingClient.EncryptionType.None -> PasswordCheckResult.NoPassword
                JBindingClient.EncryptionType.ContentOnly -> PasswordCheckResult.ContentEncrypted
                JBindingClient.EncryptionType.Header -> PasswordCheckResult.HeaderEncrypted
            }
        }

        // 7z格式：读文件头判断头部加密
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "checkPasswordRequired 开始: archivePath=$archivePath")
        val result = JBindingClient.detect7zHeaderEncryption(archivePath)
        val elapsed = System.currentTimeMillis() - t0
        Log.d(TAG, "checkPasswordRequired 完成 (${elapsed}ms): success=${result.isSuccess}")
        val headerCheck = result.fold(
            onSuccess = { headerEncrypted ->
                if (headerEncrypted) PasswordCheckResult.HeaderEncrypted
                else null // 头部未加密，继续检测内容加密
            },
            onFailure = { e ->
                val errMsg = when {
                    e.message?.contains("Permission denied", ignoreCase = true) == true ->
                        "权限不足，无法读取文件"
                    e.message?.contains("不是有效的7z文件", ignoreCase = true) == true ->
                        "文件损坏，不是有效的7z格式"
                    e.message?.contains("7z下一个头大小异常", ignoreCase = true) == true ->
                        "文件损坏，7z头信息异常"
                    else -> e.message ?: "未知错误"
                }
                Log.e(TAG, "checkPasswordRequired 失败", e)
                PasswordCheckResult.Error(
                    errorMessage = errMsg,
                    command = "detect7zHeaderEncryption($archivePath)",
                    output = e.stackTraceToString()
                )
            }
        )

        // 头部加密或检测失败，直接返回
        if (headerCheck != null) return@withContext headerCheck

        // 头部未加密，检测内容是否加密（用 dummy 密码尝试打开）
        val info = analyze7z(context, archivePath, permissionLevel)
        if (info.contentEncrypted) {
            Log.d(TAG, "checkPasswordRequired: 内容加密，需要密码")
            PasswordCheckResult.ContentEncrypted
        } else {
            PasswordCheckResult.NoPassword
        }
    }

    /** 7z 文件分析结果 */
    data class SevenZipInfo(
        val fileName: String,
        val fileSize: Long,
        val headerEncrypted: Boolean,   // 头部加密（文件名不可见）
        val contentEncrypted: Boolean,  // 内容加密
        val isCorrupted: Boolean,       // 文件损坏
        val errorMessage: String? = null,
        val diagnosticInfo: String = "" // 检测失败时的原始诊断数据
    )

    /**
     * 分析 7z 文件的加密状态。
     * 通过假密码探测，不会阻塞。
     *
     * 四种情况：
     * 1. exitCode=0 + Encrypted=+/7zAES → 仅内容加密（文件名可见）
     * 2. exitCode=0 + 无加密标志 → 正常无加密
     * 3. exitCode=2 + "Cannot open encrypted archive" → 头部加密（文件名也加密）
     * 4. exitCode=2 + "Cannot open the file as" → 文件损坏
     */
    suspend fun analyze7z(
        context: Context,
        archivePath: String,
        permissionLevel: String
    ): SevenZipInfo {
        val fileName = File(archivePath).name
        val fileSize = File(archivePath).length()
        return try {
            val result = JBindingClient.listArchiveDetail(archivePath, password = "dummy")
            result.fold(
                onSuccess = { output ->
                    when {
                        output.contains("7zAES", ignoreCase = true) || output.contains("Encrypted = +") ->
                            SevenZipInfo(fileName, fileSize, headerEncrypted = false, contentEncrypted = true, isCorrupted = false)
                        else ->
                            SevenZipInfo(fileName, fileSize, headerEncrypted = false, contentEncrypted = false, isCorrupted = false)
                    }
                },
                onFailure = { e ->
                    val errMsg = e.message ?: ""
                    when {
                        errMsg.contains("Cannot open encrypted archive", ignoreCase = true) ->
                            SevenZipInfo(fileName, fileSize, headerEncrypted = true, contentEncrypted = true, isCorrupted = false, errorMessage = "头部加密，需要密码才能查看文件列表")
                        errMsg.contains("Cannot open the file as", ignoreCase = true) ->
                            SevenZipInfo(fileName, fileSize, headerEncrypted = false, contentEncrypted = false, isCorrupted = true, errorMessage = "文件损坏，无法识别为 7z 格式")
                        else ->
                            SevenZipInfo(fileName, fileSize, headerEncrypted = false, contentEncrypted = false, isCorrupted = true, errorMessage = errMsg)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "7z 分析失败", e)
            SevenZipInfo(fileName, fileSize, headerEncrypted = false, contentEncrypted = false, isCorrupted = true, errorMessage = e.message ?: "分析失败")
        }
    }

    /** 压缩包目录树节点 */
    data class ArchiveNode(
        val name: String,
        var isDirectory: Boolean,
        var size: Long = 0,            // 原始大小
        var compressedSize: Long = 0,  // 压缩后大小
        val children: MutableList<ArchiveNode> = mutableListOf()
    )

    /** 压缩包浏览会话 */
    data class ArchiveSession(
        val archivePath: String,
        val archiveName: String,
        val root: ArchiveNode,
        val currentPath: String,
        val currentEntries: List<FileEntry>,
        val originalPath: String,
        val originalEntries: List<FileEntry>,
        /** 已通过目录读取验证的密码；仅保存在当前内存会话中。 */
        val password: String = ""
    )

    /** 压缩包 Debug 解析信息 */
    data class ArchiveDebugInfo(
        val archivePath: String,
        val archiveName: String,
        val passwordRequired: Boolean,
        val listCommand: String,
        val listExitCode: Int,
        val listStdout: String,
        val listStderr: String,
        val parsedEntryCount: Int,
        val rootEntries: List<FileEntry>,
        val error: String? = null,
        val session: ArchiveSession? = null,
        val sourceEntry: FileEntry? = null
    )

    // ── 缓存序列化 ──

    @Serializable
    data class CacheArchiveNode(
        val name: String,
        val isDirectory: Boolean,
        val size: Long = 0,
        val compressedSize: Long = 0,
        val children: List<CacheArchiveNode> = emptyList()
    )

    @Serializable
    data class ArchiveSessionCache(
        val archivePath: String,
        val archiveName: String,
        val root: CacheArchiveNode,
        val currentPath: String,
        val originalPath: String,
        val sourcePanel: String
    )

    private val cacheJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val CACHE_FILE_NAME = "archive_session_cache.json"

    private fun toCacheNode(node: ArchiveNode): CacheArchiveNode = CacheArchiveNode(
        name = node.name, isDirectory = node.isDirectory, size = node.size, compressedSize = node.compressedSize,
        children = node.children.map { toCacheNode(it) }
    )

    private fun fromCacheNode(cache: CacheArchiveNode): ArchiveNode = ArchiveNode(
        name = cache.name, isDirectory = cache.isDirectory, size = cache.size, compressedSize = cache.compressedSize,
        children = cache.children.map { fromCacheNode(it) }.toMutableList()
    )

    fun saveSessionCache(context: Context, session: ArchiveSession, sourcePanel: String) {
        try {
            val cache = ArchiveSessionCache(
                archivePath = session.archivePath,
                archiveName = session.archiveName,
                root = toCacheNode(session.root),
                currentPath = session.currentPath,
                originalPath = session.originalPath,
                sourcePanel = sourcePanel
            )
            File(AppDataPaths.fileManager(context), CACHE_FILE_NAME).writeText(cacheJson.encodeToString(cache))
        } catch (e: Exception) {
            Log.e(TAG, "保存压缩包会话缓存失败", e)
        }
    }

    fun loadSessionCache(context: Context): Pair<ArchiveSessionCache, String>? {
        val file = File(AppDataPaths.fileManager(context), CACHE_FILE_NAME)
        if (!file.exists()) return null
        return try {
            val cache = cacheJson.decodeFromString<ArchiveSessionCache>(file.readText())
            if (!File(cache.archivePath).exists() || cache.root.children.isEmpty()) {
                file.delete(); return null
            }
            Pair(cache, cache.sourcePanel)
        } catch (e: Exception) {
            Log.e(TAG, "读取压缩包会话缓存失败", e)
            file.delete(); null
        }
    }

    fun clearSessionCache(context: Context) {
        try { File(AppDataPaths.fileManager(context), CACHE_FILE_NAME).delete() } catch (_: Exception) {}
    }

    fun restoreSession(cache: ArchiveSessionCache): ArchiveSession {
        val root = fromCacheNode(cache.root)
        val node = if (cache.currentPath == cache.archivePath) root
            else findNode(root, cache.currentPath, cache.archivePath) ?: root
        val internalPath = cache.currentPath.removePrefix(cache.archivePath).trimStart('/')
        return ArchiveSession(
            archivePath = cache.archivePath,
            archiveName = cache.archiveName,
            root = root,
            currentPath = cache.currentPath,
            currentEntries = nodeChildrenToEntries(node, internalPath),
            originalPath = cache.originalPath,
            originalEntries = emptyList()
        )
    }

    /**
     * 打开压缩包，构建目录树。
     * @return ArchiveSession 成功，或 Pair(null, errorMessage) 失败
     */
    suspend fun openArchive(
        context: Context,
        archivePath: String,
        archiveName: String,
        permissionLevel: String,
        password: String = "",
        originalPath: String = "",
        originalEntries: List<FileEntry> = emptyList()
    ): Result<ArchiveSession> = withContext(Dispatchers.IO) {
        try {
            val jbEntries = JBindingClient.listArchiveEntries(archivePath, password).getOrElse { e ->
                return@withContext Result.failure(Exception(e.message ?: "列出压缩包失败"))
            }

            Log.d(TAG, "JBinding 解析到 ${jbEntries.size} 个条目")

            if (jbEntries.isEmpty()) {
                Log.w(TAG, "压缩包内容为空（可能是加密或格式不支持）")
                return@withContext Result.failure(Exception("压缩包内容为空，可能是加密文件或格式不受支持"))
            }

            val root = buildTree(jbEntries)
            val rootEntries = nodeChildrenToEntries(root)

            Result.success(
                ArchiveSession(
                    archivePath = archivePath,
                    archiveName = archiveName,
                    root = root,
                    currentPath = archivePath,
                    currentEntries = rootEntries,
                    originalPath = originalPath,
                    originalEntries = originalEntries,
                    password = password
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "打开压缩包失败", e)
            Result.failure(e)
        }
    }

    /**
     * 用 ZipRawReader 打开 zip 文件，自适应编码检测文件名。
     */
    private fun openZipArchive(
        archivePath: String,
        archiveName: String,
        originalPath: String,
        originalEntries: List<FileEntry>
    ): Result<ArchiveSession> {
        return try {
            val rawEntries = ZipRawReader.readRawEntries(File(archivePath))
            Log.d(TAG, "ZipRawReader 解析到 ${rawEntries.size} 个条目")

            if (rawEntries.isEmpty()) {
                return Result.failure(Exception("压缩包内容为空，可能是加密文件或格式不受支持"))
            }

            val entries = rawEntries.map { raw ->
                val decodedName = ZipEncodingDetector.decodeFilename(
                    raw.rawName,
                    raw.generalFlag and 0x800 != 0
                )
                JBindingClient.ArchiveEntry(
                    path = decodedName,
                    isDirectory = raw.isDirectory,
                    size = raw.size,
                    compressedSize = raw.compressedSize
                )
            }

            val root = buildTree(entries)
            val rootEntries = nodeChildrenToEntries(root)

            Result.success(
                ArchiveSession(
                    archivePath = archivePath,
                    archiveName = archiveName,
                    root = root,
                    currentPath = archivePath,
                    currentEntries = rootEntries,
                    originalPath = originalPath,
                    originalEntries = originalEntries
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "打开 zip 压缩包失败", e)
            Result.failure(e)
        }
    }

    /**
     * zip 格式的 Debug 模式：用 ZipRawReader 解析，自适应编码检测。
     * 注意：密码检测由外层 parseArchiveDebug 负责。
     */
    private fun parseZipDebug(
        archivePath: String,
        archiveName: String,
        permissionLevel: String,
        originalPath: String,
        originalEntries: List<FileEntry>
    ): ArchiveDebugInfo {
        return try {
            val rawEntries = ZipRawReader.readRawEntries(File(archivePath))
            Log.d(TAG, "ZipRawReader Debug: 解析到 ${rawEntries.size} 个条目")

            val entries = rawEntries.map { raw ->
                val decodedName = ZipEncodingDetector.decodeFilename(
                    raw.rawName,
                    raw.generalFlag and 0x800 != 0
                )
                JBindingClient.ArchiveEntry(
                    path = decodedName,
                    isDirectory = raw.isDirectory,
                    size = raw.size,
                    compressedSize = raw.compressedSize
                )
            }

            if (entries.isEmpty()) {
                return ArchiveDebugInfo(
                    archivePath = archivePath, archiveName = archiveName,
                    passwordRequired = false, listCommand = "", listExitCode = 0,
                    listStdout = "", listStderr = "",
                    parsedEntryCount = 0, rootEntries = emptyList(),
                    error = "压缩包内容为空，可能是加密文件或格式不受支持"
                )
            }

            val root = buildTree(entries)
            val rootEntries = nodeChildrenToEntries(root)
            val session = ArchiveSession(
                archivePath = archivePath, archiveName = archiveName,
                root = root, currentPath = archivePath, currentEntries = rootEntries,
                originalPath = originalPath, originalEntries = originalEntries
            )

            ArchiveDebugInfo(
                archivePath = archivePath, archiveName = archiveName,
                passwordRequired = false, listCommand = "ZipRawReader", listExitCode = 0,
                listStdout = "", listStderr = "",
                parsedEntryCount = entries.size, rootEntries = rootEntries,
                session = session
            )
        } catch (e: Exception) {
            Log.e(TAG, "zip Debug 解析失败", e)
            ArchiveDebugInfo(
                archivePath = archivePath, archiveName = archiveName,
                passwordRequired = false, listCommand = "", listExitCode = -1,
                listStdout = "", listStderr = "",
                parsedEntryCount = 0, rootEntries = emptyList(),
                error = e.message ?: "zip 解析异常"
            )
        }
    }

    /** Debug 模式：解析压缩包信息但不进入浏览模式 */
    suspend fun parseArchiveDebug(
        context: Context,
        archivePath: String,
        archiveName: String,
        permissionLevel: String,
        originalPath: String = "",
        originalEntries: List<FileEntry> = emptyList()
    ): ArchiveDebugInfo = withContext(Dispatchers.IO) {
        try {
            // 1. 密码检测（所有格式都需要）
            val passwordCheckResult = checkPasswordRequired(context, archivePath, permissionLevel)

            // 头部加密：文件名也加密，必须先输入密码
            if (passwordCheckResult is PasswordCheckResult.HeaderEncrypted) {
                return@withContext ArchiveDebugInfo(
                    archivePath = archivePath, archiveName = archiveName,
                    passwordRequired = true,
                    listCommand = "", listExitCode = 0,
                    listStdout = "", listStderr = "",
                    parsedEntryCount = 0, rootEntries = emptyList(),
                    error = "需要密码"
                )
            }

            // 2. zip 格式：直接解析 zip 结构，自适应编码检测
            if (archivePath.endsWith(".zip", ignoreCase = true)) {
                return@withContext parseZipDebug(archivePath, archiveName, permissionLevel, originalPath, originalEntries)
            }

            val jbEntries = JBindingClient.listArchiveEntries(archivePath).getOrElse { e ->
                return@withContext ArchiveDebugInfo(
                    archivePath = archivePath, archiveName = archiveName,
                    passwordRequired = false,
                    listCommand = "JBindingClient.listArchiveEntries", listExitCode = -1,
                    listStdout = "", listStderr = "",
                    parsedEntryCount = 0, rootEntries = emptyList(),
                    error = e.message ?: "列出压缩包失败"
                )
            }

            if (jbEntries.isEmpty()) {
                return@withContext ArchiveDebugInfo(
                    archivePath = archivePath, archiveName = archiveName,
                    passwordRequired = false,
                    listCommand = "JBindingClient.listArchiveEntries", listExitCode = 0,
                    listStdout = "", listStderr = "",
                    parsedEntryCount = 0, rootEntries = emptyList(),
                    error = "压缩包内容为空，可能是加密文件或格式不受支持"
                )
            }

            val root = buildTree(jbEntries)
            val rootEntries = nodeChildrenToEntries(root)
            val session = ArchiveSession(
                archivePath = archivePath, archiveName = archiveName,
                root = root, currentPath = archivePath, currentEntries = rootEntries,
                originalPath = originalPath, originalEntries = originalEntries
            )

            ArchiveDebugInfo(
                archivePath = archivePath, archiveName = archiveName,
                passwordRequired = false,
                listCommand = "JBindingClient.listArchiveEntries", listExitCode = 0,
                listStdout = "", listStderr = "",
                parsedEntryCount = jbEntries.size, rootEntries = rootEntries,
                session = session
            )
        } catch (e: Exception) {
            Log.e(TAG, "Debug 解析压缩包失败", e)
            ArchiveDebugInfo(
                archivePath = archivePath, archiveName = archiveName,
                passwordRequired = false, listCommand = "", listExitCode = -1,
                listStdout = "", listStderr = "",
                parsedEntryCount = 0, rootEntries = emptyList(),
                error = e.message ?: "未知异常"
            )
        }
    }

    /**
     * 在压缩包内导航到子目录。
     * @return 新的 ArchiveSession（currentPath 和 currentEntries 更新），或 null（找不到子目录）
     */
    fun navigateTo(session: ArchiveSession, dirName: String): ArchiveSession? {
        val currentPath = session.currentPath
        val currentNode = findNode(session.root, currentPath, session.archivePath)
            ?: return null
        val child = currentNode.children.find { it.name == dirName && it.isDirectory }
            ?: return null

        val newPath = "$currentPath/$dirName"
        val entries = nodeChildrenToEntries(child, newPath.removePrefix(session.archivePath).trimStart('/'))

        return session.copy(
            currentPath = newPath,
            currentEntries = entries
        )
    }

    /**
     * 在压缩包内返回上一级。
     * @return 新的 ArchiveSession，或 null（已在根目录）
     */
    fun navigateUp(session: ArchiveSession): ArchiveSession? {
        if (session.currentPath == session.archivePath) return null

        val parentPath = session.currentPath.substringBeforeLast('/')
        val parentNode = findNode(session.root, parentPath, session.archivePath)
            ?: return null
        val parentInternalPath = parentPath.removePrefix(session.archivePath).trimStart('/')
        val entries = nodeChildrenToEntries(parentNode, parentInternalPath)

        return session.copy(
            currentPath = parentPath,
            currentEntries = entries
        )
    }

    /** 当前是否在压缩包根目录 */
    fun isAtRoot(session: ArchiveSession): Boolean {
        return session.currentPath == session.archivePath
    }

    // ── 内部实现 ──

    /** 从扁平路径列表构建目录树 */
    private fun buildTree(entries: List<JBindingClient.ArchiveEntry>): ArchiveNode {
        val root = ArchiveNode(name = "", isDirectory = true)

        for (entry in entries) {
            val parts = entry.path.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue

            var current = root
            for (i in parts.indices) {
                val part = parts[i]
                val isLast = i == parts.size - 1

                val existing = current.children.find { it.name == part }
                if (existing != null) {
                    if (isLast && !entry.isDirectory) {
                        // 叶子文件节点，就地更新大小（不用 copy，避免节点脱离树）
                        existing.size = entry.size
                        existing.compressedSize = entry.compressedSize
                    } else if (!isLast && !existing.isDirectory) {
                        // 非叶子路径段必须是目录（7z 某些情况下目录条目不带 D 属性）
                        existing.isDirectory = true
                    }
                    current = existing
                } else {
                    val node = if (isLast && !entry.isDirectory) {
                        ArchiveNode(name = part, isDirectory = false, size = entry.size, compressedSize = entry.compressedSize)
                    } else {
                        ArchiveNode(name = part, isDirectory = true)
                    }
                    current.children.add(node)
                    current = node
                }
            }
        }

        // 排序：目录在前，文件在后，各自按名称排序
        sortTree(root)
        // 递归计算目录的累计大小
        calculateDirectorySizes(root)
        return root
    }

    /** 递归计算每个目录节点的原始大小和压缩大小总和 */
    private fun calculateDirectorySizes(node: ArchiveNode): Pair<Long, Long> {
        if (!node.isDirectory) return Pair(node.size, node.compressedSize)
        var totalSize = 0L
        var totalCompressed = 0L
        for (child in node.children) {
            val (s, c) = calculateDirectorySizes(child)
            totalSize += s
            totalCompressed += c
        }
        node.size = totalSize
        node.compressedSize = totalCompressed
        return Pair(totalSize, totalCompressed)
    }

    /** 自然排序比较器：文本段按字典序，数字段按数值 */
    private val NATURAL_ORDER = Comparator<String> { a, b ->
        val regex = Regex("(\\d+)")
        val aParts = regex.split(a)
        val aNums = regex.findAll(a).map { it.value }.toList()
        val bParts = regex.split(b)
        val bNums = regex.findAll(b).map { it.value }.toList()
        val len = minOf(aParts.size, bParts.size)
        for (i in 0 until len) {
            val cmp = aParts[i].compareTo(bParts[i], ignoreCase = true)
            if (cmp != 0) return@Comparator cmp
            if (i < aNums.size && i < bNums.size) {
                val numCmp = aNums[i].toLong().compareTo(bNums[i].toLong())
                if (numCmp != 0) return@Comparator numCmp
            }
        }
        aParts.size.compareTo(bParts.size)
    }

    /** 递归排序目录树（目录在前，文件在后，自然排序） */
    private fun sortTree(node: ArchiveNode) {
        node.children.sortWith(compareBy<ArchiveNode> { !it.isDirectory }.thenBy(NATURAL_ORDER) { it.name })
        for (child in node.children) {
            if (child.isDirectory) sortTree(child)
        }
    }

    /** 根据虚拟路径在树中查找节点 */
    private fun findNode(root: ArchiveNode, virtualPath: String, archivePath: String): ArchiveNode? {
        if (virtualPath == archivePath) return root

        val relativePath = virtualPath.removePrefix(archivePath).trimStart('/')
        if (relativePath.isBlank()) return root

        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        var current = root
        for (part in parts) {
            val child = current.children.find { it.name == part && it.isDirectory }
                ?: return null
            current = child
        }
        return current
    }

    /** 将节点的子节点转换为 FileEntry 列表（供 UI 使用）。internalPath 为当前目录在压缩包内的相对路径。 */
    private fun nodeChildrenToEntries(node: ArchiveNode, internalPath: String = ""): List<FileEntry> {
        val prefix = if (internalPath.isEmpty()) "" else "$internalPath/"
        return node.children.map { child ->
            FileEntry(
                path = if (child.name.isEmpty()) "" else "$prefix${child.name}",
                name = child.name,
                isDirectory = child.isDirectory,
                size = child.size,
                compressedSize = child.compressedSize
            )
        }
    }

    /**
     * 提取结果数据类
     */
    data class ExtractResult(
        val success: Boolean,
        val file: File? = null,
        val command: String = "",
        val output: String = "",
        val errorMessage: String = ""
    )

    /**
     * 从压缩包中提取单个文件。
     * @param archivePath 压缩包路径
     * @param entryPath 压缩包内相对路径，如 "docs/readme.txt"
     * @param destFile 目标文件路径
     * @param password 密码（空=不带密码）
     * @param permissionLevel 执行权限级别
     * @return 提取结果
     */
    suspend fun extractSingleFile(
        context: Context,
        archivePath: String,
        entryPath: String,
        destFile: File,
        password: String = "",
        permissionLevel: String = "NORMAL",
        cancelFlag: java.util.concurrent.atomic.AtomicBoolean? = null
    ): ExtractResult = withContext(Dispatchers.IO) {
        try {
            if (password.isEmpty()) {
                when (val passwordCheck = checkPasswordRequired(context, archivePath, permissionLevel)) {
                    is PasswordCheckResult.HeaderEncrypted,
                    is PasswordCheckResult.ContentEncrypted -> {
                        return@withContext ExtractResult(
                            success = false,
                            command = "ArchiveBrowser.checkPasswordRequired",
                            errorMessage = "压缩包需要密码"
                        )
                    }
                    is PasswordCheckResult.Error -> {
                        return@withContext ExtractResult(
                            success = false,
                            command = passwordCheck.command,
                            errorMessage = "无法检测压缩包密码: ${passwordCheck.errorMessage}"
                        )
                    }
                    else -> Unit
                }
            }
            destFile.parentFile?.mkdirs()

            Log.d(TAG, "提取单文件: $entryPath")
            val result = JBindingClient.extractSingleFile(archivePath, entryPath, destFile, password, cancelFlag)
            result.fold(
                onSuccess = {
                    if (destFile.exists()) {
                        ExtractResult(success = true, file = destFile, command = "JBindingClient.extractSingleFile")
                    } else {
                        ExtractResult(
                            success = false,
                            command = "JBindingClient.extractSingleFile",
                            errorMessage = "文件提取后未找到: ${destFile.absolutePath}"
                        )
                    }
                },
                onFailure = { e ->
                    ExtractResult(
                        success = false,
                        command = "JBindingClient.extractSingleFile",
                        errorMessage = e.message ?: "提取失败"
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "提取单文件异常", e)
            ExtractResult(
                success = false,
                errorMessage = "异常: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /**
     * 用 java.util.zip.ZipFile 从 zip 中提取单个文件。
     * ZipFile 内部自动处理文件名编码匹配，不受 -sccUTF-8 影响。
     */
    private fun extractFromZip(
        archivePath: String,
        entryPath: String,
        destFile: File
    ): ExtractResult {
        return try {
            val zipFile = java.util.zip.ZipFile(archivePath, Charsets.UTF_8)
            zipFile.use { zf ->
                val entry = zf.entries().asSequence().find { it.name == entryPath }

                if (entry == null) {
                    val rawEntry = zf.entries().asSequence().find { e ->
                        val rawBytes = e.name.toByteArray(Charsets.ISO_8859_1)
                        val decoded = ZipEncodingDetector.decodeFilename(rawBytes, false)
                        decoded == entryPath
                    }
                    if (rawEntry != null) {
                        extractZipEntry(zf, rawEntry, destFile)
                    } else {
                        ExtractResult(
                            success = false,
                            command = "ZipFile",
                            errorMessage = "在 zip 中未找到: $entryPath"
                        )
                    }
                } else {
                    extractZipEntry(zf, entry, destFile)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ZipFile 提取失败", e)
            ExtractResult(
                success = false,
                command = "ZipFile",
                errorMessage = "异常: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun extractZipEntry(
        zipFile: java.util.zip.ZipFile,
        entry: java.util.zip.ZipEntry,
        destFile: File
    ): ExtractResult {
        destFile.parentFile?.mkdirs()
        zipFile.getInputStream(entry).use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return if (destFile.exists()) {
            ExtractResult(success = true, file = destFile, command = "ZipFile")
        } else {
            ExtractResult(
                success = false,
                command = "ZipFile",
                errorMessage = "文件写入失败: ${destFile.absolutePath}"
            )
        }
    }
}
