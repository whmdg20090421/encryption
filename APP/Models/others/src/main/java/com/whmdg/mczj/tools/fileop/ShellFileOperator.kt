package com.whmdg.mczj.tools.fileop

import android.os.ParcelFileDescriptor
import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.security.ShellExecutor
import com.whmdg.mczj.tools.security.ShizukuAuthorizer
import com.whmdg.mczj.tools.util.DirEntry
import com.whmdg.mczj.tools.util.FileAccessLevel
import com.whmdg.mczj.tools.util.SevenZipCommand
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * 文件操作实现。
 *
 * - [FileAccessLevel.NORMAL]：Java Stream 直接复制，字节驱动进度
 * - [FileAccessLevel.SHIZUKU]：Shizuku UserService 打开文件返回 PFD，Java Stream 在 PFD 上复制
 * - [FileAccessLevel.ROOT]：Shizuku PFD（需 Shizuku 同时可用）
 *
 * PFD 路径：ShellService（uid 2000/0）打开文件 → PFD 通过 Binder 传回 → Java 读写 FD，无需权限校验。
 */
class ShellFileOperator(
    private val permission: Permission,
    private val accessLevel: FileAccessLevel
) : FileOperator {

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

    // ── 复制 ──

    override fun copyFile(src: String, dst: String, onProgress: (Long) -> Unit) {
        when (accessLevel) {
            FileAccessLevel.NORMAL -> copyWithJavaStream(src, dst, onProgress)
            FileAccessLevel.SHIZUKU, FileAccessLevel.ROOT -> copyWithPfd(src, dst, onProgress)
        }
    }

    /**
     * PFD 复制：Shizuku UserService 打开源文件和目标文件返回 PFD，
     * Java 在 PFD 的 FD 上做 8KB read/write 循环，字节驱动进度。
     *
     * 与 MT 管理器架构一致：提升权限打开文件获取 FD → Java 层直接读写 FD。
     * FD 一旦获取，后续 I/O 不再检查权限。
     */
    private fun copyWithPfd(src: String, dst: String, onProgress: (Long) -> Unit) {
        val srcPfd = ShizukuAuthorizer.openForRead(src)
            ?: throw IOException("Shizuku 打开源文件失败: $src")
        try {
            // 目标文件需要先通过 Shizuku 创建（可能在应用无写权限的目录）
            val dstPfd = ShizukuAuthorizer.openForWrite(dst)
                ?: throw IOException("Shizuku 创建目标文件失败: $dst")
            try {
                copyBetweenPfds(srcPfd, dstPfd, onProgress)
            } finally {
                dstPfd.close()
            }
        } finally {
            srcPfd.close()
        }
    }

    /**
     * 在两个 PFD 之间复制，8KB buffer，字节驱动进度。
     * 与 MT 管理器 C3317.transferTo() 的 buffer 大小一致。
     */
    private fun copyBetweenPfds(
        srcPfd: ParcelFileDescriptor,
        dstPfd: ParcelFileDescriptor,
        onProgress: (Long) -> Unit
    ) {
        val buf = ByteArray(BUFFER_SIZE)
        var copied = 0L
        FileInputStream(srcPfd.fileDescriptor).use { input ->
            FileOutputStream(dstPfd.fileDescriptor).use { output ->
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    copied += read
                    onProgress(copied)
                }
            }
        }
    }

    /**
     * Java Stream 直接复制，8KB buffer，字节驱动进度。
     * 用于 NORMAL 权限（应用自身 uid 可读写的文件）。
     */
    private fun copyWithJavaStream(src: String, dst: String, onProgress: (Long) -> Unit) {
        val buf = ByteArray(BUFFER_SIZE)
        var copied = 0L
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    copied += read
                    onProgress(copied)
                }
            }
        }
    }

    // ── 移动 ──

    override fun moveFile(src: String, dst: String): Boolean {
        return try {
            when (accessLevel) {
                FileAccessLevel.NORMAL -> moveWithJavaStream(src, dst)
                FileAccessLevel.SHIZUKU, FileAccessLevel.ROOT -> moveWithPfd(src, dst)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun moveWithJavaStream(src: String, dst: String) {
        copyWithJavaStream(src, dst) { /* moveFile 不需要进度 */ }
        if (!File(src).delete()) {
            throw IOException("删除源文件失败: $src")
        }
    }

    private fun moveWithPfd(src: String, dst: String) {
        copyWithPfd(src, dst) { /* moveFile 不需要进度 */ }
        // 移动完成后删除源文件（通过 Shell 删除，因为源可能在应用无权目录）
        deleteFile(src)
    }

    // ── 其他操作 ──

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

    companion object {
        /** 8KB buffer，与 MT 管理器 C3317.transferTo() 一致 */
        private const val BUFFER_SIZE = 8192
    }
}
