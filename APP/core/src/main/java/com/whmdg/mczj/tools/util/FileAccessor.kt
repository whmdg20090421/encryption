package com.whmdg.mczj.tools.util

import android.content.Context
import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.security.ShellExecutor
import java.io.File

/**
 * 文件访问抽象层：屏蔽普通/Shizuku/Root 三种通道差异。
 *
 * - `listChildren` 返回 null 表示无法访问（路径不存在或权限不足）
 * - `statMtime` 返回 null 表示无法访问
 *
 * 调用方负责按权限传入正确的 [FileAccessLevel]；本层不做权限回退。
 */
interface FileAccessor {
    fun listChildren(path: String): List<DirEntry>?
    fun statMtime(path: String): Long?
    /** 执行 shell 命令，返回 (stdout, stderr, exitCode)。NormalAccessor 返回失败。 */
    fun exec(command: String): Triple<String, String, Int>

    companion object {
        fun create(level: FileAccessLevel, context: Context): FileAccessor = when (level) {
            FileAccessLevel.NORMAL -> NormalAccessor()
            FileAccessLevel.SHIZUKU -> ShellAccessor(context, useRoot = false)
            FileAccessLevel.ROOT -> ShellAccessor(context, useRoot = true)
        }
    }
}

private class NormalAccessor : FileAccessor {
    override fun exec(command: String): Triple<String, String, Int> = Triple("", "无 shell 权限", 1)

    override fun listChildren(path: String): List<DirEntry>? {
        val dir = File(path)
        if (!dir.isDirectory || !dir.canRead()) return null
        val children = dir.listFiles() ?: return null
        return children.map { f ->
            val isDir = f.isDirectory
            DirEntry(
                name = f.name,
                path = f.absolutePath,
                isDir = isDir,
                size = if (isDir) 0L else f.length(),
                mtime = f.lastModified()
            )
        }
    }

    override fun statMtime(path: String): Long? {
        val f = File(path)
        if (!f.exists()) return null
        return f.lastModified()
    }
}

private class ShellAccessor(
    private val context: Context,
    private val useRoot: Boolean
) : FileAccessor {

    override fun exec(command: String): Triple<String, String, Int> {
        val perm = if (useRoot) Permission.ROOT else Permission.ADB
        return try {
            val stdout = ShellExecutor.execute(perm, command, debug = true)
            Triple(stdout, "", 0)
        } catch (e: com.whmdg.mczj.tools.security.ShellException) {
            Triple("", "${e.message}\n${e.stderr}", e.exitCode)
        } catch (e: Exception) {
            Triple("", e.message ?: "Shell 执行异常", -1)
        }
    }

    override fun listChildren(path: String): List<DirEntry>? {
        val normalized = if (path == "/") "/" else path.trimEnd('/').ifEmpty { "/" }
        val escaped = SevenZipCommand.escape(normalized)
        // find -printf 直接输出各字段，无列对齐问题，保留前导空格等特殊字符
        val command = "find $escaped -maxdepth 1 -mindepth 1 -printf '%f|%s|%T@|%m|%u|%g|%M\\n'"
        val (stdout, _, exitCode) = try {
            exec(command)
        } catch (_: Throwable) {
            return null
        }
        if (exitCode != 0 && stdout.isBlank()) return null

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

    override fun statMtime(path: String): Long? {
        val escaped = SevenZipCommand.escape(path)
        val (stdout, _, exit) = try {
            exec("stat -c %Y $escaped")
        } catch (_: Throwable) { return null }
        if (exit != 0 || stdout.isBlank()) return null
        val epochSec = stdout.trim().toLongOrNull() ?: return null
        return epochSec * 1000
    }
}
