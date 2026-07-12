package com.whmdg.mczj.tools.fileop

import android.content.Context
import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.security.ShellExecutor
import com.whmdg.mczj.tools.util.DirEntry
import com.whmdg.mczj.tools.util.SevenZipCommand
import java.io.IOException

/**
 * Shell 权限的文件操作实现，通过 Root/Shizuku 执行 shell 命令。
 * 复制使用 cp，移动使用 mv，删除使用 rm -rf。
 */
class ShellFileOperator(
    private val context: Context,
    private val useRoot: Boolean
) : FileOperator {

    private val permission = if (useRoot) Permission.ROOT else Permission.ADB

    private fun escape(path: String): String = SevenZipCommand.escape(path)

    private fun exec(command: String): String {
        return ShellExecutor.execute(permission, command, debug = true)
    }

    private fun checkExit(command: String, errorMsg: String) {
        try {
            exec(command)
        } catch (e: Exception) {
            throw IOException("$errorMsg: ${e.message}")
        }
    }

    override fun copyFile(src: String, dst: String, onProgress: (Long) -> Unit) {
        val size = fileSize(src)
        val escapedSrc = escape(src)
        val escapedDst = escape(dst)
        checkExit("cp $escapedSrc $escapedDst", "复制失败")
        // Shell cp 无法获取实时进度，复制完成后回调总大小
        onProgress(size)
    }

    override fun moveFile(src: String, dst: String): Boolean {
        val escapedSrc = escape(src)
        val escapedDst = escape(dst)
        return try {
            exec("mv $escapedSrc $escapedDst")
            true
        } catch (_: Exception) { false }
    }

    override fun deleteFile(path: String) {
        val escaped = escape(path)
        checkExit("rm -rf $escaped", "删除失败")
    }

    override fun mkdir(path: String) {
        val escaped = escape(path)
        checkExit("mkdir -p $escaped", "创建目录失败")
    }

    override fun exists(path: String): Boolean {
        val escaped = escape(path)
        return try { exec("test -e $escaped"); true } catch (_: Exception) { false }
    }

    override fun isDirectory(path: String): Boolean {
        val escaped = escape(path)
        return try { exec("test -d $escaped"); true } catch (_: Exception) { false }
    }

    override fun listChildren(path: String): List<DirEntry>? {
        val normalized = if (path == "/") "/" else path.trimEnd('/').ifEmpty { "/" }
        val escaped = SevenZipCommand.escape(normalized)
        val command = "find $escaped -maxdepth 1 -mindepth 1 -printf '%f|%s|%T@|%m|%u|%g|%M\\n'"
        val stdout = try {
            exec(command)
        } catch (_: Throwable) {
            return null
        }
        if (stdout.isBlank()) return null

        val entries = mutableListOf<DirEntry>()
        for (raw in stdout.lines()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            val parts = line.split("|")
            if (parts.size < 7) continue
            val name = parts[0]
            if (name == "." || name == "..") continue
            val size = parts[1].toLongOrNull() ?: 0L
            val mtime = (parts[2].toDoubleOrNull()?.toLong() ?: 0L) * 1000
            val perms = parts[6]
            val isDir = perms.startsWith("d")
            val childPath = if (normalized == "/") "/$name" else "$normalized/$name"
            entries.add(DirEntry(name, childPath, isDir, if (isDir) 0L else size, mtime))
        }
        return entries
    }

    override fun fileSize(path: String): Long {
        val escaped = escape(path)
        val stdout = try { exec("stat -c %s $escaped") } catch (_: Exception) { return 0L }
        return stdout.trim().toLongOrNull() ?: 0L
    }

    override fun lastModified(path: String): Long {
        val escaped = escape(path)
        val stdout = try { exec("stat -c %Y $escaped") } catch (_: Exception) { return 0L }
        val epochSec = stdout.trim().toLongOrNull() ?: return 0L
        return epochSec * 1000
    }
}
