package com.whmdg.mczj.tools.encryption.data

/**
 * 大小统计的内存暂存区。
 *
 * [com.whmdg.mczj.tools.util.calculateFolderSize] 在统计期间把结果写入本暂存区，
 * 不触碰持久化存储；统计完整成功或用户在错误弹窗选择「保存」时，由
 * [FolderSizeStore.commitStaging] 一次性提交；用户选择「丢弃」/取消时直接丢弃本对象，
 * 存储不受影响。以此保留「统计中不落盘、由用户确认」的原有语义。
 *
 * 暂存区记录本次统计的根路径，并保存「扫描产出的全部路径」，供提交层与库中旧记录
 * 做差集、删除已消失的条目。
 */
class FolderSizeStaging private constructor(
    val rootPath: String
) : FolderSizeCache {

    companion object {
        /** 创建暂存区。 */
        fun create(rootPath: String): FolderSizeStaging = FolderSizeStaging(rootPath)
    }

    private fun normalize(path: String): String = path.trimEnd('/')

    /** 暂存的写入（路径已归一化）。 */
    private val staged = LinkedHashMap<String, FolderSizeInfo>()

    private val lock = Any()

    override fun bulkPut(updates: Map<String, FolderSizeInfo>) {
        synchronized(lock) {
            for ((path, info) in updates) {
                staged[normalize(path)] = info
            }
        }
    }

    /** 提交用：暂存的写入快照（已归一化路径）。 */
    fun stagedSnapshot(): Map<String, FolderSizeInfo> = synchronized(lock) { LinkedHashMap(staged) }

    /** 提交用：本次扫描实际产出的全部路径集合。 */
    fun scannedPaths(): Set<String> = synchronized(lock) { staged.keys.toHashSet() }
}
