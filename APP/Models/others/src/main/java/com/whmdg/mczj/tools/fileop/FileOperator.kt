package com.whmdg.mczj.tools.fileop

import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.util.DirEntry
import com.whmdg.mczj.tools.util.FileAccessLevel
import java.io.File

/**
 * 文件操作抽象层，统一通过 ShellExecutor 执行。
 *
 * 所有权限级别（NORMAL/SHIZUKU/ROOT）都通过 ShellExecutor 路由，
 * 由 [ShellFileOperator] 统一实现。
 * 复制使用 pv 获取实时进度。
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
    fun listChildren(path: String): List<DirEntry>?

    /** 获取文件大小（字节）。目录返回 0。 */
    fun fileSize(path: String): Long

    /** 获取文件修改时间（epoch millis）。 */
    fun lastModified(path: String): Long

    companion object {
        /**
         * 根据权限级别创建合适的 FileOperator。
         * 统一通过 ShellExecutor 执行，由 ShellExecutor 内部路由到对应权限。
         * @param pvPath pv 二进制路径，用于复制时获取实时进度
         */
        fun create(level: FileAccessLevel, pvPath: String): FileOperator = when (level) {
            FileAccessLevel.NORMAL -> ShellFileOperator(Permission.APPLICANT, pvPath)
            FileAccessLevel.SHIZUKU -> ShellFileOperator(Permission.ADB, pvPath)
            FileAccessLevel.ROOT -> ShellFileOperator(Permission.ROOT, pvPath)
        }
    }
}
