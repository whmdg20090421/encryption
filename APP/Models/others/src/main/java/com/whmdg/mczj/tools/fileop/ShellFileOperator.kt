package com.whmdg.mczj.tools.fileop

import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.security.ShellExecutor
import com.whmdg.mczj.tools.util.DirEntry
import com.whmdg.mczj.tools.util.SevenZipCommand
import java.io.IOException

/**
 * 文件操作实现，统一通过 ShellExecutor 执行 shell 命令。
 * 复制使用 pv 获取实时进度，移动使用 pv+rm，删除使用 rm -rf。
 * 所有权限级别（NORMAL/SHIZUKU/ROOT）都通过 ShellExecutor 路由。
 */
class ShellFileOperator(
    private val permission: Permission,
    private val pvPath: String
) : FileOperator {

    /** 兼容旧调用：useRoot=true → ROOT, useRoot=false → ADB */
    constructor(useRoot: Boolean, pvPath: String) : this(
        if (useRoot) Permission.ROOT else Permission.ADB,
        pvPath
    )

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

    /**
     * 使用 PV 复制文件，通过 ShellExecutor 路由到正确 UID，实时读取 stderr 获取进度。
     * PV -n 输出百分比到 stderr，每行一个数字（0.0 到 100.0）。
     * --force 强制输出进度，即使 stderr 不是终端（解决缓冲问题）。
     */
    private fun copyWithPv(src: String, dst: String, onProgress: (Long) -> Unit) {
        val size = fileSize(src)
        val command = "$pvPath --force -n ${escape(src)} > ${escape(dst)}"

        try {
            ShellExecutor.executeWithStderr(
                permission = permission,
                command = command,
                onStderrLine = { line ->
                    line.trim().toDoubleOrNull()?.let { percent ->
                        onProgress((size * percent / 100).toLong())
                    }
                }
            )
        } catch (e: Exception) {
            throw IOException("PV 复制失败: ${e.message}")
        }

        onProgress(size)
    }

    /**
     * 使用 PV 移动文件（复制+删除源文件），通过 ShellExecutor 路由到正确 UID。
     */
    private fun moveWithPv(src: String, dst: String, onProgress: (Long) -> Unit) {
        val size = fileSize(src)
        val escapedSrc = escape(src)
        val command = "$pvPath --force -n $escapedSrc > ${escape(dst)}"

        try {
            ShellExecutor.executeWithStderr(
                permission = permission,
                command = command,
                onStderrLine = { line ->
                    line.trim().toDoubleOrNull()?.let { percent ->
                        onProgress((size * percent / 100).toLong())
                    }
                }
            )
        } catch (e: Exception) {
            throw IOException("PV 移动失败: ${e.message}")
        }

        // 复制成功，删除源文件（通过 ShellExecutor，保持权限一致）
        exec("rm -rf $escapedSrc")

        onProgress(size)
    }

    override fun copyFile(src: String, dst: String, onProgress: (Long) -> Unit) {
        copyWithPv(src, dst, onProgress)
    }

    override fun moveFile(src: String, dst: String): Boolean {
        return try {
            moveWithPv(src, dst) { /* moveFile 不需要进度回调，MoveJob 使用 copyFile+deleteFile */ }
            true
        } catch (e: Exception) {
            false
        }
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
