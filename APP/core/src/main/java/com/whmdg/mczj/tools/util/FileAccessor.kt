package com.whmdg.mczj.tools.util

import android.content.Context
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

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
        return if (useRoot) {
            SpecialPermissionVerifier.executeRootCommandFull(command)
        } else {
            SpecialPermissionVerifier.executeShizukuCommand(command)
        }
    }

    private fun makeDateFmt(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun listChildren(path: String): List<DirEntry>? {
        val normalized = if (path == "/") "/" else path.trimEnd('/').ifEmpty { "/" }
        val escaped = SevenZipCommand.escape(normalized)
        // -l 长格式；-A 列隐藏文件但排除 . 和 ..；-p 目录加 / 后缀
        val command = "ls -lAp $escaped"
        val (stdout, _, exitCode) = try {
            exec(command)
        } catch (_: Throwable) {
            return null
        }
        if (exitCode != 0 && stdout.isBlank()) return null

        val fmt = makeDateFmt()
        val entries = mutableListOf<DirEntry>()
        for (raw in stdout.lines()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("total ")) continue
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 8) continue
            if (parts[0].length < 10) continue

            // 精确提取原始文件名（保留多空格），逐字符跳过前 7 个字段
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
            entries.add(DirEntry(name, childPath, isDir, if (isDir) 0L else size, mtime))
        }
        return entries
    }

    override fun statMtime(path: String): Long? {
        val escaped = SevenZipCommand.escape(path)
        // -d 显示目录自身条目（而非目录内容）
        val (stdout, _, exit) = try {
            exec("ls -lapd $escaped")
        } catch (_: Throwable) { return null }
        if (exit != 0 || stdout.isBlank()) return null
        val line = stdout.lines().firstOrNull { it.isNotBlank() && !it.startsWith("total ") }
            ?: return null
        val parts = line.split("\\s+".toRegex())
        if (parts.size < 8) return null
        return try {
            makeDateFmt().parse("${parts[5]} ${parts[6]}")?.time ?: 0L
        } catch (_: Exception) { 0L }
    }
}
