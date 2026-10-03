package com.whmdg.mczj.tools.util

/**
 * 文件访问通道枚举。
 * 由调用方根据当前可用的最高权限选择，传给统计/扫描算法。
 */
enum class FileAccessLevel { NORMAL, SHIZUKU, ROOT }

/**
 * 统一的目录条目（脱离 FileEntry 依赖，专供后台扫描使用）。
 * mtime 单位：毫秒。
 *   - NORMAL 通道：来自 File.lastModified()，原生毫秒精度
 *   - SHIZUKU / ROOT 通道：来自 `find -printf %T@` 的 Unix 时间戳，秒级精度
 *   差异统计的 mtime 对比要求同一路径始终走同一通道，否则不一致会被识别为"已变化"。
 */
data class DirEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long
)

/** 文件夹大小统计的结果。 */
sealed class SizeCalcResult {
    /**
     * 统计成功。
     * @param fullSnapshot true = 整棵子树扫描完整（可用于删除消失项）；
     *   false = 部分成功（存在不可读子目录，只能合并写入、不删旧记录）。
     */
    data class Success(
        val rootSize: Long,
        val tree: com.whmdg.mczj.tools.util.SizeTreeNode? = null,
        val fullSnapshot: Boolean = false
    ) : SizeCalcResult()
    object Cancelled : SizeCalcResult()
    data class Failed(val reason: String) : SizeCalcResult()
}
