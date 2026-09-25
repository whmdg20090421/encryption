package com.whmdg.mczj.tools.util

import android.content.Context
import android.util.Log
import com.whmdg.mczj.tools.AppDataPaths
import java.io.File

/**
 * 压缩包密码本。
 *
 * 磁盘格式：UTF-8 文本，每行一个密码（空行/首尾空白忽略）。
 *
 * 生命周期按「一次密码尝试回合」管理，尽量减少磁盘 IO：
 * 1. [begin] 一次性把整个密码本读入内存快照（回合开始）；
 * 2. 调用方用 [candidates] 逐个尝试；
 * 3. 全部未命中、用户手动输入正确密码后 [add] 追加到内存；
 * 4. [commit] 把内存快照一次性写回磁盘（回合成功结束）；
 * 5. 用户取消或失败放弃时 [release] 丢弃内存快照，不产生写入。
 *
 * 同一时刻只应存在一个回合（由 FileManager 的密码弹窗串行化保证）。
 */
object ArchivePasswordBook {

    private const val TAG = "ArchivePasswordBook"

    /** 内存快照；null 表示当前没有活跃回合。 */
    private var snapshot: LinkedHashSet<String>? = null

    /** 快照中是否包含尚未写回磁盘的新增项。 */
    private var dirty = false

    /**
     * 开始一个密码尝试回合：读入密码本到内存。
     * 重复调用时若已有活跃回合，则直接复用现有快照（避免重复读盘）。
     */
    @Synchronized
    fun begin(context: Context) {
        if (snapshot != null) return
        val loaded = LinkedHashSet<String>()
        try {
            val file = AppDataPaths.archivePasswordBook(context)
            if (file.exists()) {
                file.readLines(Charsets.UTF_8).forEach { line ->
                    val pwd = line.trim()
                    if (pwd.isNotEmpty()) loaded.add(pwd)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取密码本失败", e)
        }
        snapshot = loaded
        dirty = false
        Log.d(TAG, "密码本载入内存: ${loaded.size} 条")
    }

    /** 当前回合的密码候选快照（按写入顺序）；无活跃回合时返回空列表。 */
    @Synchronized
    fun candidates(): List<String> = snapshot?.toList() ?: emptyList()

    /**
     * 追加一个密码到内存快照（已存在则跳过），并标记待写回。
     * 不立即写盘，由 [commit] 统一写回。
     */
    @Synchronized
    fun add(password: String) {
        val pwd = password.trim()
        if (pwd.isEmpty()) return
        val set = snapshot ?: return
        if (set.add(pwd)) {
            dirty = true
            Log.d(TAG, "密码本内存新增一条，当前 ${set.size} 条")
        }
    }

    /** 把内存快照一次性写回磁盘（仅在有待写回内容时执行）。 */
    @Synchronized
    fun commit(context: Context) {
        val set = snapshot ?: return
        if (!dirty) return
        try {
            val file = AppDataPaths.archivePasswordBook(context)
            file.parentFile?.mkdirs()
            file.writeText(set.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            dirty = false
            Log.d(TAG, "密码本写回磁盘: ${set.size} 条")
        } catch (e: Exception) {
            Log.e(TAG, "写回密码本失败", e)
        }
    }

    /** 结束回合并释放内存快照（用户取消或放弃时调用，不写盘）。 */
    @Synchronized
    fun release() {
        if (snapshot != null) Log.d(TAG, "释放密码本内存快照")
        snapshot = null
        dirty = false
    }
}
