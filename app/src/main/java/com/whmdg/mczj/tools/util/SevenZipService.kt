package com.whmdg.mczj.tools.util

import android.content.Context
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import java.io.File

/**
 * 封装 7za (7zzs) 静态二进制的命令调用，支持 Root 和 Shizuku 两种 shell 通道。
 */
object SevenZipService {

    /** 压缩包列表条目 */
    data class ArchiveEntry(
        val name: String,
        val path: String,       // 压缩包内相对路径
        val size: Long,
        val compressedSize: Long,
        val isDir: Boolean
    )

    /** 错误分类 */
    sealed class SevenZipError(msg: String) : Exception(msg) {
        class RootNotGranted(msg: String = "未获取 root/shell 权限") : SevenZipError(msg)
        class FileNotFound(msg: String) : SevenZipError(msg)
        class UnsupportedFormat(msg: String) : SevenZipError(msg)
        class WrongPassword(msg: String = "密码错误") : SevenZipError(msg)
        class Other(msg: String) : SevenZipError(msg)
    }

    // ── Shell 转义 ────────────────────────────────────────

    /** 将字符串用单引号包裹，内部的单引号用 '\'' 转义 */
    private fun shellEscape(s: String): String {
        return "'" + s.replace("'", "'\\''") + "'"
    }

    // ── 命令执行 ──────────────────────────────────────────

    /** 获取二进制路径，未初始化时尝试懒初始化 */
    private fun getBinaryPathOrInit(): String {
        if (RootBinaryManager.isReady()) return RootBinaryManager.getBinaryPath()
        // 懒初始化：应用启动时可能 root 尚未就绪
        val ctx = com.whmdg.mczj.tools.ToolsApp.instance
        val path = RootBinaryManager.init(ctx)
        if (path == null) {
            DiagnosticLog.log("7za", "懒初始化失败: isRoot=${SpecialPermissionVerifier.isRootAvailable()} isShizuku=${SpecialPermissionVerifier.isShizukuAuthorized(ctx)}")
        }
        return RootBinaryManager.getBinaryPath()
    }

    private fun isRootMode(): Boolean =
        SpecialPermissionVerifier.isRootAvailable()

    /** 直接通过 su -c 执行命令，返回 (stdout, stderr, exitCode) */
    private fun execSu(command: String): Triple<String, String, Int> {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return Triple(stdout, stderr, exitCode)
    }

    /** 执行命令，自动选择 root 或 Shizuku 通道 */
    private fun exec(command: String): Triple<String, String, Int> {
        return if (isRootMode()) {
            execSu(command)
        } else {
            SpecialPermissionVerifier.executeShizukuCommand(command)
        }
    }

    /** 执行命令并返回 Process（仅 Root，用于流式读取 stdout） */
    private fun execStreaming(command: String): Process {
        if (!isRootMode()) throw UnsupportedOperationException("流式执行仅支持 Root 模式")
        return Runtime.getRuntime().exec(arrayOf("su", "-c", command))
    }

    // ── 格式判断 ──────────────────────────────────────────

    /** 复合格式（外层压缩 + 内层 tar）需要两步处理 */
    fun isCompoundFormat(format: String): Boolean {
        return format in listOf("tar.gz", "tar.bz2", "tar.xz", "tgz", "tbz2", "txz")
    }

    // ── 列出压缩包内容 ────────────────────────────────────

    /**
     * 列出压缩包内所有条目。
     * @param context Android Context
     * @param archivePath 压缩包路径（设备上的绝对路径）
     * @param format 格式字符串（如 "zip", "7z", "tar.gz" 等）
     * @param password 密码（空=无密码）
     */
    fun listArchive(context: Context, archivePath: String, format: String, password: String = ""): List<ArchiveEntry> {
        val bin = getBinaryPathOrInit()
        val pwArg = if (password.isNotEmpty()) " -p${shellEscape(password)}" else ""
        val pathArg = shellEscape(archivePath)

        return if (isCompoundFormat(format)) {
            listCompoundArchive(context, bin, pathArg, pwArg)
        } else {
            listSimpleArchive(bin, pathArg, pwArg)
        }
    }

    /** 简单格式（zip/7z/rar/tar）直接列出 */
    private fun listSimpleArchive(bin: String, pathArg: String, pwArg: String): List<ArchiveEntry> {
        val cmd = "$bin l -ba$pwArg $pathArg"
        val (stdout, stderr, exitCode) = exec(cmd)
        if (exitCode != 0) throw classifyError(stderr, exitCode)
        return parseListOutput(stdout)
    }

    /** 复合格式：先解压外层到临时 tar，再列出 tar 内容 */
    private fun listCompoundArchive(context: Context, bin: String, pathArg: String, pwArg: String): List<ArchiveEntry> {
        val tmpTar = File(context.cacheDir, "7za_inner_${pathArg.hashCode()}.tar")
        val tmpTarArg = shellEscape(tmpTar.absolutePath)
        try {
            // 步骤 1：解压外层到临时 tar
            val cmd1 = "$bin e -ba -so$pwArg $pathArg > $tmpTarArg"
            val (_, stderr1, code1) = exec(cmd1)
            if (code1 != 0) throw classifyError(stderr1, code1)
            // 步骤 2：列出 tar 内容
            val cmd2 = "$bin l -ba $tmpTarArg"
            val (stdout2, stderr2, code2) = exec(cmd2)
            if (code2 != 0) throw classifyError(stderr2, code2)
            return parseListOutput(stdout2)
        } finally {
            tmpTar.delete()
        }
    }

    /** 解析 l -ba 输出 */
    private fun parseListOutput(output: String): List<ArchiveEntry> {
        val entries = mutableListOf<ArchiveEntry>()
        // 匹配格式: "2026-06-18 05:56:28 D....            0            0  path/name"
        val regex = Regex("""(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})\s+(.{5})\s+(\d+)\s+(\d+)\s+(.+)""")
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val m = regex.matchEntire(trimmed) ?: continue
            val isDir = m.groupValues[2].startsWith("D")
            val path = m.groupValues[5]
            entries.add(
                ArchiveEntry(
                    name = path.substringAfterLast('/'),
                    path = path,
                    size = m.groupValues[3].toLong(),
                    compressedSize = m.groupValues[4].toLong(),
                    isDir = isDir
                )
            )
        }
        return entries
    }

    // ── 提取单文件 ────────────────────────────────────────

    /**
     * 提取单文件到临时文件，返回临时文件路径。
     * Root 和 Shizuku 均支持。
     */
    fun extractToFile(
        context: Context,
        archivePath: String,
        entryPath: String,
        format: String,
        password: String = ""
    ): File {
        val bin = getBinaryPathOrInit()
        val pwArg = if (password.isNotEmpty()) " -p${shellEscape(password)}" else ""
        val pathArg = shellEscape(archivePath)
        val entryArg = shellEscape(entryPath)
        val outFile = File(context.cacheDir, "7za_extract_${entryPath.hashCode()}")
        val outArg = shellEscape(outFile.absolutePath)

        val cmd = if (isCompoundFormat(format)) {
            "$bin e -so$pwArg $pathArg | $bin e -so -si $entryArg > $outArg"
        } else {
            "$bin e -so$pwArg $pathArg $entryArg > $outArg"
        }

        val (_, stderr, exitCode) = exec(cmd)
        if (exitCode != 0) {
            outFile.delete()
            throw classifyError(stderr, exitCode)
        }
        if (!outFile.exists() || outFile.length() == 0L) {
            outFile.delete()
            throw SevenZipError.Other("提取失败: 输出为空")
        }
        return outFile
    }

    /**
     * 提取单文件到 InputStream（仅 Root 模式）。
     * 适用于大文件流式读取，避免先写磁盘。
     */
    fun extractToStream(
        context: Context,
        archivePath: String,
        entryPath: String,
        format: String,
        password: String = ""
    ): Process {
        val bin = getBinaryPathOrInit()
        val pwArg = if (password.isNotEmpty()) " -p${shellEscape(password)}" else ""
        val pathArg = shellEscape(archivePath)
        val entryArg = shellEscape(entryPath)

        val cmd = if (isCompoundFormat(format)) {
            "$bin e -so$pwArg $pathArg | $bin e -so -si $entryArg"
        } else {
            "$bin e -so$pwArg $pathArg $entryArg"
        }

        return execStreaming(cmd)
    }

    // ── 错误分类 ──────────────────────────────────────────

    fun classifyError(stderr: String, exitCode: Int): SevenZipError {
        val msg = stderr.trim()
        return when {
            exitCode == 127 || msg.contains("not found") || msg.contains("Permission denied") ->
                SevenZipError.RootNotGranted(msg)
            msg.contains("No such file") || msg.contains("Cannot find file") ->
                SevenZipError.FileNotFound(msg)
            msg.contains("Wrong password") || msg.contains("Cannot open encrypted") ->
                SevenZipError.WrongPassword(msg)
            msg.contains("Cannot open") || msg.contains("E_NOTIMPL") ->
                SevenZipError.UnsupportedFormat(msg)
            else -> SevenZipError.Other(msg)
        }
    }
}
