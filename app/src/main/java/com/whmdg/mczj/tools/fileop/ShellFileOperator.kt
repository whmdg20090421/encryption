package com.whmdg.mczj.tools.fileop

import android.content.Context
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Shell 权限的文件操作实现，通过 Root/Shizuku 执行 shell 命令。
 * 复制使用 cp，移动使用 mv，删除使用 rm -rf。
 */
class ShellFileOperator(
    private val context: Context,
    private val useRoot: Boolean
) : FileOperator {

    private fun exec(command: String): Triple<String, String, Int> {
        return if (useRoot) {
            SpecialPermissionVerifier.executeRootCommandFull(command)
        } else {
            SpecialPermissionVerifier.executeShizukuCommand(command)
        }
    }

    private fun escape(path: String): String {
        return path.replace("'", "'\\''")
    }

    private fun checkExit(command: String, errorMsg: String) {
        val (_, stderr, exitCode) = exec(command)
        if (exitCode != 0) {
            throw IOException("$errorMsg: ${stderr.ifBlank { "exit code $exitCode" }}")
        }
    }

    override fun copyFile(src: String, dst: String, onProgress: (Long) -> Unit) {
        val size = fileSize(src)
        val escapedSrc = escape(src)
        val escapedDst = escape(dst)
        checkExit("cp '$escapedSrc' '$escapedDst'", "复制失败")
        // Shell cp 无法获取实时进度，复制完成后回调总大小
        onProgress(size)
    }

    override fun moveFile(src: String, dst: String): Boolean {
        val escapedSrc = escape(src)
        val escapedDst = escape(dst)
        val (_, _, exitCode) = exec("mv '$escapedSrc' '$escapedDst'")
        return exitCode == 0
    }

    override fun deleteFile(path: String) {
        val escaped = escape(path)
        checkExit("rm -rf '$escaped'", "删除失败")
    }

    override fun mkdir(path: String) {
        val escaped = escape(path)
        checkExit("mkdir -p '$escaped'", "创建目录失败")
    }

    override fun exists(path: String): Boolean {
        val escaped = escape(path)
        val (_, _, exitCode) = exec("test -e '$escaped'")
        return exitCode == 0
    }

    override fun isDirectory(path: String): Boolean {
        val escaped = escape(path)
        val (_, _, exitCode) = exec("test -d '$escaped'")
        return exitCode == 0
    }

    override fun listChildren(path: String): List<FileChildInfo>? {
        val normalized = if (path == "/") "/" else path.trimEnd('/').ifEmpty { "/" }
        val escaped = normalized.replace("'", "'\\''")
        val command = "ls -lAp '$escaped'"
        val (stdout, _, exitCode) = try {
            exec(command)
        } catch (_: Throwable) {
            return null
        }
        if (exitCode != 0 && stdout.isBlank()) return null

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val entries = mutableListOf<FileChildInfo>()
        for (raw in stdout.lines()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("total ")) continue
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 8) continue
            if (parts[0].length < 10) continue

            // 精确提取原始文件名（保留多空格）
            var namePos = 0
            repeat(7) {
                while (namePos < line.length && line[namePos].isWhitespace()) namePos++
                if (namePos >= line.length) return@repeat
                while (namePos < line.length && !line[namePos].isWhitespace()) namePos++
            }
            while (namePos < line.length && line[namePos].isWhitespace()) namePos++
            val nameWithSlash = if (namePos < line.length) line.substring(namePos) else continue
            val isDir = nameWithSlash.endsWith("/")
            val name = if (isDir) nameWithSlash.dropLast(1) else nameWithSlash
            if (name == "." || name == "..") continue

            val size = parts[4].toLongOrNull() ?: 0L
            val childPath = if (normalized == "/") "/$name" else "$normalized/$name"
            val mtime = try {
                fmt.parse("${parts[5]} ${parts[6]}")?.time ?: 0L
            } catch (_: Exception) { 0L }
            entries.add(FileChildInfo(name, childPath, isDir, if (isDir) 0L else size, mtime))
        }
        return entries
    }

    override fun fileSize(path: String): Long {
        val escaped = escape(path)
        val (stdout, _, exitCode) = exec("stat -c %s '$escaped'")
        if (exitCode != 0) return 0L
        return stdout.trim().toLongOrNull() ?: 0L
    }

    override fun lastModified(path: String): Long {
        val escaped = escape(path)
        val (stdout, _, exitCode) = exec("stat -c %Y '$escaped'")
        if (exitCode != 0) return 0L
        val epochSec = stdout.trim().toLongOrNull() ?: return 0L
        return epochSec * 1000
    }
}
