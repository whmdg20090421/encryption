package com.whmdg.mczj.tools.fileop

import com.whmdg.mczj.tools.util.DirEntry
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * 普通权限的文件操作实现，使用 Java File API。
 * 流式复制，8KB 缓冲区。
 */
class JavaFileOperator : FileOperator {

    companion object {
        private const val BUFFER_SIZE = 8 * 1024
    }

    override fun copyFile(src: String, dst: String, onProgress: (Long) -> Unit) {
        val srcFile = File(src)
        val dstFile = File(dst)
        FileInputStream(srcFile).use { input ->
            FileOutputStream(dstFile).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var totalCopied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    totalCopied += read
                    onProgress(read.toLong())
                }
                output.flush()
            }
        }
        // 复制权限
        dstFile.setExecutable(srcFile.canExecute(), false)
        dstFile.setReadable(srcFile.canRead(), false)
        dstFile.setWritable(srcFile.canWrite(), false)
    }

    override fun moveFile(src: String, dst: String): Boolean {
        return File(src).renameTo(File(dst))
    }

    override fun deleteFile(path: String) {
        val file = File(path)
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteFile(it.absolutePath) }
        }
        if (!file.delete()) {
            throw IOException("删除失败: $path")
        }
    }

    override fun mkdir(path: String) {
        if (!File(path).mkdirs()) {
            throw IOException("创建目录失败: $path")
        }
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun isDirectory(path: String): Boolean = File(path).isDirectory

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

    override fun fileSize(path: String): Long = File(path).length()

    override fun lastModified(path: String): Long = File(path).lastModified()
}
