package com.whmdg.mczj.tools.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.security.ShizukuAuthorizer
import com.whmdg.mczj.tools.ui.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 压缩包浏览工具类。
 * 通过 7zzs l -ba 命令读取压缩包目录结构，构建内存目录树，支持只读浏览。
 */
object ArchiveBrowser {

    private const val TAG = "ArchiveBrowser"

    /** 支持的压缩包扩展名 */
    private val ARCHIVE_EXTENSIONS = setOf(
        "zip", "7z", "rar", "tar", "gz", "bz2", "xz",
        "lz4", "zst", "lzma", "cab", "iso", "dmg"
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
     * 快速检测 7z 文件是否头部加密（读二进制头部，无需启动进程）。
     * 7z 格式：前 6 字节魔数 `37 7A BC AF 27 1C`，偏移 32 处为头部类型：
     * - 0x01 (kHeader) → 明文头部，无加密
     * - 0x17 (kEncodedHeader) → 头部被编码/加密，需要密码
     *
     * @return true=头部加密, false=无头部加密或非7z文件
     */
    private fun is7zHeaderEncrypted(archivePath: String): Boolean {
        if (!archivePath.endsWith(".7z", ignoreCase = true)) return false
        return try {
            val file = java.io.File(archivePath)
            if (file.length() < 33) return false
            file.inputStream().use { stream ->
                // 校验魔数
                val magic = ByteArray(6)
                if (stream.read(magic) != 6) return false
                if (magic[0] != 0x37.toByte() || magic[1] != 0x7A.toByte() ||
                    magic[2] != 0xBC.toByte() || magic[3] != 0xAF.toByte() ||
                    magic[4] != 0x27.toByte() || magic[5] != 0x1C.toByte()) return false
                // 跳过 version(2) + startHeaderCRC(4) + nextHeaderOffset(8) + nextHeaderSize(8) + nextHeaderCRC(4) = 26 字节
                val skipped = stream.skip(26)
                if (skipped < 26) return false
                // 读取头部类型字节
                val headerType = stream.read()
                if (headerType == -1) return false
                Log.d(TAG, "7z 头部类型: 0x${headerType.toString(16).uppercase()} (0x17=kEncodedHeader)")
                headerType == 0x17
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取 7z 二进制头部失败", e)
            false
        }
    }

    /**
     * 探测压缩包是否需要密码。
     * 检测策略（按优先级）：
     * 1. 7z 二进制头部快速检测：偏移 32 == 0x17 → 头部加密（无需启动进程）
     * 2. 7zzs l -slt 输出中 Method 字段含 `7zAES` → 内容加密（最可靠）
     * 3. 输出含 `Encrypted = +` → 加密
     * 4. exitCode=2 且为 7z 文件 → 头部加密（兜底）
     *
     * 返回值：
     * - true  → 需要密码
     * - false → 不需要密码（无加密，正常打开）
     * - null  → 档案本身有问题（exitCode≠0 且未检测到加密标志）
     */
    suspend fun checkPasswordRequired(
        context: Context,
        archivePath: String,
        permissionLevel: String
    ): Boolean? = withContext(Dispatchers.IO) {
        // 快速路径：读 7z 二进制头部，0x17 = kEncodedHeader = 头部加密
        if (is7zHeaderEncrypted(archivePath)) {
            Log.d(TAG, "7z 二进制头部检测: kEncodedHeader(0x17)，头部加密")
            return@withContext true
        }

        val binaryPath = BinaryExtractor.ensureExtracted(context).absolutePath
        val baseCmd = SevenZipCommand.buildListDetailCommand(binaryPath, archivePath, password = "dummy")
        // 用 2>&1 将 stderr 合并到 stdout，防止某些 su 实现吞掉 stderr
        val cmd = "$baseCmd 2>&1"
        val (merged, _, exitCode) = executeCommand(cmd, permissionLevel, context)
        Log.d(TAG, "密码检测: exitCode=$exitCode, output=${merged.length}字节, 前200字=${merged.take(200)}")
        // Method 字段含 7zAES → 加密（最可靠的命令行检测方式）
        if (merged.contains("7zAES", ignoreCase = true)) return@withContext true
        if (merged.contains("Encrypted = +")) return@withContext true
        if (exitCode == 0) return@withContext false
        // 头部加密的 7Z：exitCode≠0，输出包含密码相关提示
        if (merged.contains("password", ignoreCase = true)) return@withContext true
        if (merged.contains("密码")) return@withContext true
        // 7Z 格式：exitCode=2 且无 Encrypted 字段，很可能是头部加密
        if (archivePath.endsWith(".7z", ignoreCase = true) && exitCode == 2) return@withContext true
        null
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
        val originalEntries: List<FileEntry>
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
        return ArchiveSession(
            archivePath = cache.archivePath,
            archiveName = cache.archiveName,
            root = root,
            currentPath = cache.currentPath,
            currentEntries = nodeChildrenToEntries(node),
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
            val binaryPath = BinaryExtractor.ensureExtracted(context).absolutePath
            val cmd = SevenZipCommand.buildListCommand(binaryPath, archivePath, password)
            Log.d(TAG, "列表命令: $cmd")

            val (stdout, stderr, exitCode) = executeCommand(cmd, permissionLevel, context)
            Log.d(TAG, "执行完毕: exitCode=$exitCode, stdout=${stdout.length}字节, stderr=${stderr.take(200)}")

            if (exitCode != 0) {
                val errMsg = stderr.ifBlank { "7zzs 退出码: $exitCode" }
                Log.w(TAG, "非零退出: $errMsg")
                return@withContext Result.failure(Exception(errMsg))
            }

            val entries = parseListOutput(stdout)
            Log.d(TAG, "解析到 ${entries.size} 个条目")

            if (entries.isEmpty()) {
                Log.w(TAG, "压缩包内容为空（可能是加密或格式不支持）")
                return@withContext Result.failure(Exception("压缩包内容为空，可能是加密文件或格式不受支持"))
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
            Log.e(TAG, "打开压缩包失败", e)
            Result.failure(e)
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
            val binaryPath = BinaryExtractor.ensureExtracted(context).absolutePath

            // 1. 密码检测：二进制头部快速检测 + 命令行详细检测
            val headerEncrypted = is7zHeaderEncrypted(archivePath)
            val detailBaseCmd = SevenZipCommand.buildListDetailCommand(binaryPath, archivePath, password = "dummy")
            val detailCmd = "$detailBaseCmd 2>&1"
            val (detMerged, _, detExit) = executeCommand(detailCmd, permissionLevel, context)
            val passwordRequired = headerEncrypted
                    || detMerged.contains("7zAES", ignoreCase = true)
                    || detMerged.contains("Encrypted = +")
                    || detMerged.contains("password", ignoreCase = true)
                    || detMerged.contains("密码")
                    || (archivePath.endsWith(".7z", ignoreCase = true) && detExit == 2)

            // 2. 需要密码时跳过列表命令（无密码会导致 7zzs 阻塞在 stdin 等待输入）
            if (passwordRequired) {
                return@withContext ArchiveDebugInfo(
                    archivePath = archivePath, archiveName = archiveName,
                    passwordRequired = true,
                    listCommand = "", listExitCode = 0,
                    listStdout = "", listStderr = "",
                    parsedEntryCount = 0, rootEntries = emptyList(),
                    error = "需要密码"
                )
            }

            // 3. 列表命令
            val listCmd = SevenZipCommand.buildListCommand(binaryPath, archivePath)
            val (stdout, stderr, exitCode) = executeCommand(listCmd, permissionLevel, context)

            if (exitCode != 0) {
                val errMsg = stderr.ifBlank { "7zzs 退出码: $exitCode" }
                return@withContext ArchiveDebugInfo(
                    archivePath = archivePath, archiveName = archiveName,
                    passwordRequired = passwordRequired,
                    listCommand = listCmd, listExitCode = exitCode,
                    listStdout = stdout, listStderr = stderr,
                    parsedEntryCount = 0, rootEntries = emptyList(),
                    error = errMsg
                )
            }

            val entries = parseListOutput(stdout)
            if (entries.isEmpty()) {
                return@withContext ArchiveDebugInfo(
                    archivePath = archivePath, archiveName = archiveName,
                    passwordRequired = passwordRequired,
                    listCommand = listCmd, listExitCode = exitCode,
                    listStdout = stdout, listStderr = stderr,
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
                passwordRequired = passwordRequired,
                listCommand = listCmd, listExitCode = exitCode,
                listStdout = stdout, listStderr = stderr,
                parsedEntryCount = entries.size, rootEntries = rootEntries,
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
        val entries = nodeChildrenToEntries(child)

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
        val entries = nodeChildrenToEntries(parentNode)

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

    /** 解析 7zzs l -ba 输出，提取文件/目录条目 */
    private fun parseListOutput(output: String): List<ParsedEntry> {
        val entries = mutableListOf<ParsedEntry>()
        // 7zzs -ba 输出中，空行之前是表头，之后是文件列表
        // 也可能没有空行分隔，直接从第一个匹配行开始
        val lineRegex = Regex(
            """^\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\s+.{5}\s+[\d]+\s+[\d]+\s+.+$"""
        )
        val fieldRegex = Regex(
            """^(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2})\s+(\S{5})\s+(\d+)\s+(\d+)\s+(.+)$"""
        )

        for (line in output.lines()) {
            val trimmed = line.trimEnd()
            if (trimmed.isBlank()) continue
            if (!lineRegex.matches(trimmed)) continue

            val match = fieldRegex.matchEntire(trimmed) ?: continue
            val attrs = match.groupValues[3]
            val compressedSize = match.groupValues[4].toLongOrNull() ?: 0
            val size = match.groupValues[5].toLongOrNull() ?: 0
            var path = match.groupValues[6].trim()
            // 统一路径分隔符
            path = path.replace('\\', '/')

            val isDir = attrs.startsWith('D')
            entries.add(ParsedEntry(path = path, isDirectory = isDir, size = size, compressedSize = compressedSize))
        }

        return entries
    }

    /** 解析后的原始条目 */
    private data class ParsedEntry(
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val compressedSize: Long
    )

    /** 从扁平路径列表构建目录树 */
    private fun buildTree(entries: List<ParsedEntry>): ArchiveNode {
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

    /** 递归排序目录树（目录在前，文件在后） */
    private fun sortTree(node: ArchiveNode) {
        node.children.sortWith(compareBy<ArchiveNode> { !it.isDirectory }.thenBy { it.name })
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

    /** 将节点的子节点转换为 FileEntry 列表（供 UI 使用） */
    private fun nodeChildrenToEntries(node: ArchiveNode): List<FileEntry> {
        return node.children.map { child ->
            FileEntry(
                path = child.name,
                name = child.name,
                isDirectory = child.isDirectory,
                size = child.size,
                compressedSize = child.compressedSize
            )
        }
    }

    /**
     * 从压缩包中提取单个文件到指定目录。
     * @param archivePath 压缩包路径
     * @param fileName 压缩包内相对路径，如 "docs/readme.txt"
     * @param outputDir 输出目录
     * @param password 密码（空=不带密码）
     * @param permissionLevel 执行权限级别
     * @return 提取后的文件，失败返回 null
     */
    suspend fun extractSingleFile(
        context: Context,
        archivePath: String,
        fileName: String,
        outputDir: String,
        password: String = "",
        permissionLevel: String = "NORMAL"
    ): File? = withContext(Dispatchers.IO) {
        try {
            val binaryPath = BinaryExtractor.ensureExtracted(context).absolutePath
            val cmd = SevenZipCommand.buildExtractSingleCommand(
                binaryPath, archivePath, fileName, outputDir, password
            )
            Log.d(TAG, "提取单文件: $fileName")
            val (stdout, stderr, exitCode) = executeCommand(cmd, permissionLevel, context)
            if (exitCode != 0) {
                Log.w(TAG, "提取失败 exitCode=$exitCode stderr=$stderr")
                return@withContext null
            }
            // 保留目录结构：outputDir/fileName
            val outputFile = File(outputDir, fileName)
            if (outputFile.exists()) outputFile else null
        } catch (e: Exception) {
            Log.e(TAG, "提取单文件异常", e)
            null
        }
    }

    /** 命令执行超时时间（毫秒） */
    private const val COMMAND_TIMEOUT_MS = 30_000L

    /** 执行 shell 命令，返回 (stdout, stderr, exitCode)。
     *  路径已由 SevenZipCommand.escape() 用单引号包裹，可安全传递给任何 shell。 */
    private suspend fun executeCommand(
        cmd: String,
        permissionLevel: String,
        context: Context
    ): Triple<String, String, Int> = withContext(Dispatchers.IO) {
        val process = when (permissionLevel) {
            "ROOT" -> {
                Log.d(TAG, "ROOT 执行: $cmd")
                ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(false)
                    .start()
            }
            "SHIZUKU" -> {
                val service = ShizukuAuthorizer.getShellService()
                    ?: throw IllegalStateException("Shizuku UserService 未连接")
                Log.d(TAG, "SHIZUKU 执行: $cmd")
                val result = withTimeout(COMMAND_TIMEOUT_MS) { service.execute(cmd) }
                val parts = result.split("\n")
                val stdout = if (parts.size >= 1 && parts[0].isNotEmpty()) {
                    String(Base64.decode(parts[0], Base64.NO_WRAP), Charsets.UTF_8)
                } else ""
                val stderr = if (parts.size >= 2 && parts[1].isNotEmpty()) {
                    String(Base64.decode(parts[1], Base64.NO_WRAP), Charsets.UTF_8)
                } else ""
                val exitCode = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: -1
                return@withContext Triple(stdout, stderr, exitCode)
            }
            else -> {
                Log.d(TAG, "NORMAL 执行: $cmd")
                ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(false)
                    .start()
            }
        }
        // 并发读取 stdout/stderr，避免管道缓冲区满导致死锁
        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        val tOut = Thread {
            try {
                process.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    var n: Int
                    while (r.read(buf).also { n = it } != -1) stdoutBuf.append(buf, 0, n)
                }
            } catch (_: Exception) {}
        }
        val tErr = Thread {
            try {
                process.errorStream.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    var n: Int
                    while (r.read(buf).also { n = it } != -1) stderrBuf.append(buf, 0, n)
                }
            } catch (_: Exception) {}
        }
        tOut.start(); tErr.start()
        // 超时看门狗
        val watchdog = Thread {
            try {
                Thread.sleep(COMMAND_TIMEOUT_MS)
                process.destroyForcibly()
            } catch (_: Exception) {}
        }
        watchdog.isDaemon = true
        watchdog.start()
        val exitCode = process.waitFor()
        watchdog.interrupt()
        tOut.join(5000); tErr.join(5000)
        Triple(stdoutBuf.toString().trimEnd(), stderrBuf.toString().trimEnd(), exitCode)
    }
}
