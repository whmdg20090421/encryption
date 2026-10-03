package com.whmdg.mczj.tools.encryption.data

/**
 * 大小统计的内存暂存区。
 *
 * [com.whmdg.mczj.tools.util.calculateFolderSize] 在统计期间把结果写入本暂存区，
 * 不触碰持久化存储；统计完整成功或用户在错误弹窗选择「保存」时，由
 * [FolderSizeStore.commitStaging] 一次性提交；用户选择「丢弃」/取消时直接丢弃本对象，
 * 存储不受影响。以此保留「统计中不落盘、由用户确认」的原有语义。
 *
 * 读取时先看暂存、再看底层存储，保证统计过程中的读-改-写一致。
 */
class FolderSizeStaging private constructor(
    private val readBase: (String) -> FolderSizeInfo?
) : FolderSizeCache {

    companion object {
        /** 基于存储门面创建暂存区。 */
        fun create(): FolderSizeStaging = FolderSizeStaging { path -> FolderSizeStore.peek(path) }
    }

    private fun normalize(path: String): String = path.trimEnd('/')

    /** 暂存的写入（路径已归一化）。 */
    private val staged = LinkedHashMap<String, FolderSizeInfo>()

    /** 暂存的子树删除请求。 */
    private val removed = LinkedHashSet<String>()

    private val lock = Any()

    override fun get(path: String): FolderSizeInfo? {
        val key = normalize(path)
        synchronized(lock) {
            staged[key]?.let { return it }
        }
        return readBase(key)
    }

    override fun bulkPut(updates: Map<String, FolderSizeInfo>) {
        synchronized(lock) {
            for ((path, info) in updates) {
                val key = normalize(path)
                staged[key] = info
                removed.remove(key)
            }
        }
    }

    override fun removeDescendants(path: String) {
        val key = normalize(path)
        val prefix = "$key/"
        synchronized(lock) {
            staged.keys.removeAll { it == key || it.startsWith(prefix) }
            removed.add(key)
        }
    }

    /** 提交用：暂存的写入快照。 */
    fun stagedSnapshot(): Map<String, FolderSizeInfo> = synchronized(lock) { LinkedHashMap(staged) }

    /** 提交用：暂存的删除快照。 */
    fun removedSnapshot(): List<String> = synchronized(lock) { ArrayList(removed) }
}
