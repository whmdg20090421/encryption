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
    /** 递归获取子树中所有文件/子目录条目（单次 shell 调用）。返回 null 表示无法访问。 */
    fun listChildrenRecursive(path: String): List<DirEntry>?

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

    override fun listChildrenRecursive(path: String): List<DirEntry>? {
        val root = File(path)
        if (!root.isDirectory || !root.canRead()) return null
        val entries = mutableListOf<DirEntry>()
        fun scan(dir: File) {
            val children = dir.listFiles() ?: return
            for (f in children) {
                val isDir = f.isDirectory
                entries.add(DirEntry(f.name, f.absolutePath, isDir, if (isDir) 0L else f.length(), f.lastModified()))
                if (isDir) scan(f)
            }
        }
        scan(root)
        return entries
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
        val escaped = normalized.replace("'", "'\\''")
        // -l 长格式；-A 列隐藏文件但排除 . 和 ..；-p 目录加 / 后缀
        val command = "ls -lAp '$escaped'"
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
        val escaped = path.replace("'", "'\\''")
        // -d 显示目录自身条目（而非目录内容）
        val (stdout, _, exit) = try {
            exec("ls -lapd '$escaped'")
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

    override fun listChildrenRecursive(path: String): List<DirEntry>? {
        val escaped = path.replace("'", "'\\''")
        // stat -c 格式: %F=文件类型 %s=大小 %Y=mtime(epoch秒) %n=完整路径
        // 用 find + stat 一次性获取整个子树，避免逐目录 fork
        val (stdout, stderr, exitCode) = try {
            exec("find '$escaped' -mindepth 1 \\( -type f -o -type d \\) -exec stat -c '%F\\t%s\\t%Y\\t%n' {} +")
        } catch (_: Throwable) { return null }
        if (exitCode != 0) {
            // stat 不可用时回退到 find -printf（部分 toybox 支持）
            val (out2, _, exit2) = try {
                exec("find '$escaped' -mindepth 1 \\( -type f -o -type d \\) -printf '%y\\t%s\\tT@\\t%p\\n'")
            } catch (_: Throwable) { return null }
            if (exit2 != 0) return null
            return parseFindPrintfOutput(out2, path)
        }
        return parseStatOutput(stdout, path)
    }

    /** 解析 stat -c '%F\t%s\t%Y\t%n' 输出 */
    private fun parseStatOutput(output: String, rootPath: String): List<DirEntry>? {
        val entries = mutableListOf<DirEntry>()
        val rootNorm = rootPath.trimEnd('/').ifEmpty { "/" }
        for (line in output.lines()) {
            if (line.isBlank()) continue
            // 用 tab 分割前 3 个字段，第 4 个是路径（可能含空格）
            val t1 = line.indexOf('\t')
            if (t1 < 0) continue
            val t2 = line.indexOf('\t', t1 + 1)
            if (t2 < 0) continue
            val t3 = line.indexOf('\t', t2 + 1)
            if (t3 < 0) continue

            val typeStr = line.substring(0, t1)
            val sizeStr = line.substring(t1 + 1, t2)
            val mtimeStr = line.substring(t2 + 1, t3)
            val fullPath = line.substring(t3 + 1)

            val isDir = typeStr == "directory"
            val size = sizeStr.toLongOrNull() ?: 0L
            val mtime = (mtimeStr.toDoubleOrNull() ?: 0.0).toLong() * 1000L // 秒→毫秒
            val name = fullPath.substringAfterLast('/').ifEmpty { fullPath }
            entries.add(DirEntry(name, fullPath, isDir, if (isDir) 0L else size, mtime))
        }
        return entries
    }

    /** 解析 find -printf '%y\t%s\tT@\t%p\n' 输出（备用） */
    private fun parseFindPrintfOutput(output: String, rootPath: String): List<DirEntry>? {
        val entries = mutableListOf<DirEntry>()
        for (line in output.lines()) {
            if (line.isBlank()) continue
            val t1 = line.indexOf('\t')
            if (t1 < 0) continue
            val t2 = line.indexOf('\t', t1 + 1)
            if (t2 < 0) continue
            val t3 = line.indexOf('\t', t2 + 1)
            if (t3 < 0) continue

            val typeChar = line.substring(0, t1)
            val sizeStr = line.substring(t1 + 1, t2)
            val mtimeStr = line.substring(t2 + 1, t3)
            val fullPath = line.substring(t3 + 1)

            val isDir = typeChar == "d"
            val size = sizeStr.toLongOrNull() ?: 0L
            val mtime = (mtimeStr.toDoubleOrNull() ?: 0.0).toLong() * 1000L
            val name = fullPath.substringAfterLast('/').ifEmpty { fullPath }
            entries.add(DirEntry(name, fullPath, isDir, if (isDir) 0L else size, mtime))
        }
        return entries
    }
}
