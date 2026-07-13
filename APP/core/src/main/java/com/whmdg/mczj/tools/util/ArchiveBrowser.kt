package com.whmdg.mczj.tools.util

import android.content.Context
import android.util.Log
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.security.ShellException
import com.whmdg.mczj.tools.security.ShellExecutor
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
     * 探测压缩包是否需要密码。
     * 通过 `7zzs l -slt -p"dummy"` 假密码探测，不会阻塞在 stdin。
     *
     * 返回值：
     * - true  → 需要密码（仅内容加密或头部加密）
     * - false → 不需要密码（无加密）
     * - null  → 文件损坏
     */
    suspend fun checkPasswordRequired(
        context: Context,
        archivePath: String,
        permissionLevel: String
    ): Boolean? = withContext(Dispatchers.IO) {
        val binaryPath = BinaryExtractor.ensureExtracted(context).absolutePath
        val cmd = "${SevenZipCommand.buildListDetailCommand(binaryPath, archivePath, password = "dummy")} 2>&1"
        val (merged, _, exitCode) = executeCommand(cmd, permissionLevel, context)
        Log.d(TAG, "密码检测: exitCode=$exitCode, output=${merged.take(200)}")
        when {
            exitCode == 0 && (merged.contains("7zAES", ignoreCase = true) || merged.contains("Encrypted = +")) -> true
            exitCode == 0 -> false
            merged.contains("Cannot open encrypted archive", ignoreCase = true) -> true
            merged.contains("Cannot open the file as", ignoreCase = true) -> null
            else -> null
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
     * 通过 `7zzs l -slt -p"dummy"` 假密码探测，不会阻塞。
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
    ): SevenZipInfo = withContext(Dispatchers.IO) {
        val fileName = File(archivePath).name
        val fileSize = File(archivePath).length()
        try {
            val binaryPath = BinaryExtractor.ensureExtracted(context).absolutePath
            val cmd = "${SevenZipCommand.buildListDetailCommand(binaryPath, archivePath, password = "dummy")} 2>&1"

            // === 诊断：原始进程执行，绕过 ShellExecutor ===
            var rawStdout = ""
            var rawStderr = ""
            var rawExit = -1
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                rawStdout = process.inputStream.bufferedReader().readText().trim()
                rawStderr = process.errorStream.bufferedReader().readText().trim()
                process.waitFor()
                rawExit = process.exitValue()
            } catch (e: Exception) {
                rawStderr = "exec异常: ${e.message}"
            }
            Log.e(TAG, "诊断原始执行: exitCode=$rawExit, stdout=${rawStdout.take(300)}, stderr=${rawStderr.take(300)}")

            val (merged, _, exitCode) = executeCommand(cmd, permissionLevel, context)
            Log.e(TAG, "诊断ShellExecutor: exitCode=$exitCode, merged=${merged.take(300)}")

            // === 诊断信息，直接显示在弹窗里 ===
            val diagBlock = "=== 原始执行 ===\nexitCode=$rawExit\nstdout=${rawStdout.take(500)}\nstderr=${rawStderr.take(500)}\n=== ShellExecutor ===\nexitCode=$exitCode\nmerged=${merged.take(500)}"

            when {
                exitCode == 0 && (merged.contains("7zAES", ignoreCase = true) || merged.contains("Encrypted = +")) ->
                    SevenZipInfo(fileName, fileSize, headerEncrypted = false, contentEncrypted = true, isCorrupted = false, diagnosticInfo = diagBlock)
                exitCode == 0 ->
                    SevenZipInfo(fileName, fileSize, headerEncrypted = false, contentEncrypted = false, isCorrupted = false, diagnosticInfo = diagBlock)
                merged.contains("Cannot open encrypted archive", ignoreCase = true) ->
                    SevenZipInfo(fileName, fileSize, headerEncrypted = true, contentEncrypted = true, isCorrupted = false, errorMessage = "头部加密，需要密码才能查看文件列表", diagnosticInfo = diagBlock)
                merged.contains("Cannot open the file as", ignoreCase = true) ->
                    SevenZipInfo(fileName, fileSize, headerEncrypted = false, contentEncrypted = false, isCorrupted = true, errorMessage = "文件损坏，无法识别为 7z 格式", diagnosticInfo = diagBlock)
                else ->
                    SevenZipInfo(fileName, fileSize, headerEncrypted = false, contentEncrypted = false, isCorrupted = true, errorMessage = "未知错误 (exitCode=$exitCode)", diagnosticInfo = diagBlock)
            }
        } catch (e: Exception) {
            Log.e(TAG, "7z 分析失败", e)
            val diag = "path=$archivePath\npermission=$permissionLevel\nexception=${e.javaClass.simpleName}\n${e.stackTraceToString()}"
            SevenZipInfo(fileName, fileSize, headerEncrypted = false, contentEncrypted = false, isCorrupted = true, errorMessage = e.message ?: "分析失败", diagnosticInfo = diag)
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

            // 1. 密码检测：7zzs l -slt -p"dummy" 假密码探测
            val detailCmd = "${SevenZipCommand.buildListDetailCommand(binaryPath, archivePath, password = "dummy")} 2>&1"
            val (detMerged, _, detExit) = executeCommand(detailCmd, permissionLevel, context)
            val passwordRequired = when {
                detExit == 0 && (detMerged.contains("7zAES", ignoreCase = true) || detMerged.contains("Encrypted = +")) -> true
                detExit == 0 -> false
                detMerged.contains("Cannot open encrypted archive", ignoreCase = true) -> true
                else -> false
            }

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
     *  路径已由 SevenZipCommand.escape() 用单引号包裹，可安全传递给任何 shell。
     *  委托给 ShellExecutor 执行。 */
    private suspend fun executeCommand(
        cmd: String,
        permissionLevel: String,
        context: Context
    ): Triple<String, String, Int> = withContext(Dispatchers.IO) {
        val permission = when (permissionLevel) {
            "ROOT" -> Permission.ROOT
            "SHIZUKU" -> Permission.ADB
            else -> Permission.APPLICANT
        }
        try {
            val stdout = withTimeout(COMMAND_TIMEOUT_MS) {
                ShellExecutor.execute(permission, cmd, debug = true)
            }
            Triple(stdout, "", 0)
        } catch (e: ShellException) {
            Log.e(TAG, "ShellException: exitCode=${e.exitCode}, stderr=${e.stderr.take(300)}, message=${e.message?.take(200)}")
            Triple("", "${e.message}\n${e.stderr}", e.exitCode)
        } catch (e: Exception) {
            Triple("", e.message ?: "Shell 执行异常", -1)
        }
    }
}
