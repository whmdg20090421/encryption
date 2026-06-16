package com.whmdg.mczj.tools.fileop

import android.content.Context
import com.whmdg.mczj.tools.util.FileAccessLevel
import java.io.File

/**
 * 文件操作抽象层，屏蔽 Java File API / Shell 两种通道差异。
 *
 * 复制/移动/删除等操作统一通过此接口执行，
 * 由 [JavaFileOperator]（普通权限）和 [ShellFileOperator]（Root/Shizuku）分别实现。
 */
interface FileOperator {

    /**
     * 复制文件。进度通过 [onProgress] 回调已复制字节数（增量）。
     * @throws IOException 复制失败
     */
    fun copyFile(src: String, dst: String, onProgress: (Long) -> Unit)

    /**
     * 尝试原子移动（renameTo）。成功返回 true，失败返回 false（调用方回退到 copy+delete）。
     */
    fun moveFile(src: String, dst: String): Boolean

    /** 删除文件或目录。 */
    fun deleteFile(path: String)

    /** 创建目录。 */
    fun mkdir(path: String)

    /** 判断路径是否存在。 */
    fun exists(path: String): Boolean

    /** 判断路径是否为目录。 */
    fun isDirectory(path: String): Boolean

    /** 列出目录子项。返回 null 表示无法访问。 */
    fun listChildren(path: String): List<FileChildInfo>?

    /** 获取文件大小（字节）。目录返回 0。 */
    fun fileSize(path: String): Long

    /** 获取文件修改时间（epoch millis）。 */
    fun lastModified(path: String): Long

    companion object {
        /**
         * 根据权限级别创建合适的 FileOperator。
         * 有 Shell 引擎时优先使用 ShellFileOperator。
         */
        fun create(level: FileAccessLevel, context: Context): FileOperator = when (level) {
            FileAccessLevel.NORMAL -> JavaFileOperator()
            FileAccessLevel.SHIZUKU -> ShellFileOperator(context, useRoot = false)
            FileAccessLevel.ROOT -> ShellFileOperator(context, useRoot = true)
        }
    }
}

/**
 * 统一的子项信息，供 walkFileTree 使用。
 */
data class FileChildInfo(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long
)
