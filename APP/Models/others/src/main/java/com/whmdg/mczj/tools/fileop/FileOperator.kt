package com.whmdg.mczj.tools.fileop

import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.util.DirEntry
import com.whmdg.mczj.tools.util.FileAccessLevel

/**
 * 文件操作抽象层。
 *
 * APPLICANT 走 Java Stream 直接复制，ROOT/ADB 通过 ShellExecutor 路由到 Shell dd 脚本。
 * 由 [ShellFileOperator] 统一实现，字节驱动进度。
 */
interface FileOperator {

    /**
     * 复制文件。进度通过 [onProgress] 回调已复制字节数（增量）。
     * @throws IOException 复制失败
     */
    fun copyFile(src: String, dst: String, onProgress: (Long) -> Unit, job: FileOperationJob? = null)

    /**
     * 尝试原子移动（renameTo）。成功返回 true，失败返回 false（调用方回退到 copy+delete）。
     */
    fun moveFile(src: String, dst: String, job: FileOperationJob? = null): Boolean

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

    /** 获取文件所在分区的设备号（用于判断是否在同一分区）。 */
    fun deviceId(path: String): Long

    companion object {
        /**
         * 根据访问级别创建合适的 FileOperator。
         * NORMAL 走 Java Stream，SHIZUKU/ROOT 走 Shell dd 脚本。
         * Shell 权限统一使用 MAX（自动取最高已授权权限）。
         */
        fun create(level: FileAccessLevel): FileOperator =
            ShellFileOperator(Permission.MAX, level)
    }
}
