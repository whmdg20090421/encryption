package com.whmdg.mczj.tools.fileop

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.whmdg.mczj.tools.security.FdProvider
import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.security.ShellExecutor
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
 * - [FileAccessLevel.SHIZUKU] / [FileAccessLevel.ROOT]：通过 FdProvider 获取 PFD，Java 在 PFD 上复制
 *
 * FdProvider 委托 ShellExecutor 按 Permission.MAX 路由到 ROOT/ADB/APPLICANT 获取 FD。
 * FD 一旦获取，后续 I/O 不再检查权限。
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

    override fun copyFile(src: String, dst: String, onProgress: (Long) -> Unit, job: FileOperationJob?) {
        when (accessLevel) {
            FileAccessLevel.NORMAL -> copyWithJavaStream(src, dst, onProgress)
            FileAccessLevel.SHIZUKU, FileAccessLevel.ROOT -> copyWithPfd(src, dst, onProgress, job)
        }
    }

    /**
     * PFD 复制：FdProvider 获取源/目标 PFD，Java 在 PFD 的 FD 上做 read/write 循环。
     *
     * 与 MT 管理器架构一致：提升权限打开文件获取 FD → Java 层直接读写 FD。
     * FD 一旦获取，后续 I/O 不再检查权限。
     */
    private fun copyWithPfd(src: String, dst: String, onProgress: (Long) -> Unit, job: FileOperationJob?) {
        val diag = FileOpDiagnostics.isEnabled()
        val t0 = if (diag) System.nanoTime() else 0L

        val srcPfd = FdProvider.openForRead(src)
        if (diag) {
            val elapsed = (System.nanoTime() - t0) / 1_000_000
            FileOpDiagnostics.logPfdOpen("READ", src, elapsed)
        }

        try {
            val t1 = if (diag) System.nanoTime() else 0L
            val dstPfd = FdProvider.openForWrite(dst)
            if (diag) {
                val elapsed = (System.nanoTime() - t1) / 1_000_000
                FileOpDiagnostics.logPfdOpen("WRITE", dst, elapsed)
            }

            try {
                job?.setCurrentPfds(srcPfd, dstPfd)
                copyBetweenPfds(srcPfd, dstPfd, onProgress)
            } finally {
                job?.setCurrentPfds(null, null)
                dstPfd.close()
            }
        } finally {
            srcPfd.close()
        }
    }

    /**
     * 在两个 PFD 之间复制，buffer 驱动进度。
     * 与 MT 管理器 Features3.read/write(fd, buf, off, len) 对齐：
     * 直接用 Os.read/write 操作 fd，绕过 FileInputStream 缓冲层。
     */
    private fun copyBetweenPfds(
        srcPfd: ParcelFileDescriptor,
        dstPfd: ParcelFileDescriptor,
        onProgress: (Long) -> Unit
    ) {
        val diag = FileOpDiagnostics.isEnabled()
        val buf = ByteArray(BUFFER_SIZE)
        val srcFd = srcPfd.fileDescriptor
        val dstFd = dstPfd.fileDescriptor
        var copied = 0L
        var chunkIndex = 0
        val transferStart = if (diag) System.nanoTime() else 0L

        while (true) {
            val t0 = if (diag) System.nanoTime() else 0L
            val n = try {
                Os.read(srcFd, buf, 0, BUFFER_SIZE)
            } catch (e: ErrnoException) {
                throw IOException("读取失败: ${describeErrno(e)}", e)
            }
            if (n == 0) break
            try {
                Os.write(dstFd, buf, 0, n)
            } catch (e: ErrnoException) {
                throw IOException("写入失败: ${describeErrno(e)}", e)
            }
            copied += n
            chunkIndex++

            if (diag) {
                val elapsed = (System.nanoTime() - t0) / 1_000_000
                FileOpDiagnostics.logTransfer(chunkIndex, n.toLong(), copied, elapsed)
            }

            onProgress(copied)
        }

        if (diag) {
            val totalElapsed = (System.nanoTime() - transferStart) / 1_000_000
            FileOpDiagnostics.logPhaseComplete("TRANSFER", copied, totalElapsed)
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

    override fun moveFile(src: String, dst: String, job: FileOperationJob?): Boolean {
        return try {
            when (accessLevel) {
                FileAccessLevel.NORMAL -> moveWithJavaStream(src, dst)
                FileAccessLevel.SHIZUKU, FileAccessLevel.ROOT -> moveWithPfd(src, dst, job)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun moveWithJavaStream(src: String, dst: String) {
        try {
            copyWithJavaStream(src, dst) { /* moveFile 不需要进度 */ }
        } catch (e: Exception) {
            try { File(dst).delete() } catch (_: Exception) {}
            throw e
        }
        if (!File(src).delete()) {
            throw IOException("删除源文件失败: $src")
        }
    }

    private fun moveWithPfd(src: String, dst: String, job: FileOperationJob?) {
        try {
            copyWithPfd(src, dst, onProgress = {}, job = job)
        } catch (e: Exception) {
            try { if (exists(dst)) deleteFile(dst) } catch (_: Exception) {}
            throw e
        }
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
        /** 128KB buffer，大文件复制性能优化 */
        private const val BUFFER_SIZE = 128 * 1024
    }

    private fun describeErrno(e: ErrnoException): String {
        val msg = when (e.errno) {
            OsConstants.EPERM -> "操作不允许（需要更高权限）"
            OsConstants.EACCES -> "权限不足"
            OsConstants.ENOENT -> "文件不存在"
            OsConstants.EIO -> "I/O 硬件错误"
            OsConstants.EBADF -> "无效文件描述符"
            OsConstants.ENOMEM -> "内存不足"
            OsConstants.ENOSPC -> "磁盘空间不足"
            OsConstants.EDQUOT -> "磁盘配额超限"
            OsConstants.EINTR -> "操作被信号中断"
            OsConstants.EFBIG -> "文件过大"
            OsConstants.EROFS -> "只读文件系统"
            else -> null
        }
        return msg ?: "errno=${e.errno}: ${e.message}"
    }
}
