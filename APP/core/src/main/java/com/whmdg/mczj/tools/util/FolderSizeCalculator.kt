package com.whmdg.mczj.tools.util

import com.whmdg.mczj.tools.encryption.data.FolderSizeDb
import com.whmdg.mczj.tools.encryption.data.FolderSizeInfo
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/** 大小统计树形节点（文件或目录）。 */
data class SizeTreeNode(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val children: List<SizeTreeNode> = emptyList()
)

/**
 * 文件夹大小统计核心算法（双内存 A/B 架构）。
 *
 * 入口：[calculateFolderSize]
 *
 * 算法流程：
 *   1. BFS 阶段：无条件扫描 rootPath 下所有子目录，构建目录树（内存 A）
 *   2. 构建内存 B：按 A 中每个路径从 DB 查缓存，有则填入 size+mtime，无则为空
 *   3. 从叶子向根逐层处理：
 *      - 查 B：有值且 mtime 未变 → 复用 B 的 size（缓存命中）
 *      - B 无值或 mtime 变化 → 列出直接子项，累加文件大小 + 子文件夹 size（从 A 读取）
 *   4. 写缓存：先 removeDescendants 清除旧子树（含已删除目录），再 bulkPut 写入 A 的数据
 *
 * 进度回调：
 *   - BFS 阶段：每扫描一个目录调用 onScanned
 *   - 累加阶段：每计算完一个目录调用 onProgress
 */
suspend fun calculateFolderSize(
    rootPath: String,
    accessor: FileAccessor,
    db: FolderSizeDb,
    onTotal: (total: Int) -> Unit,
    onScanned: (count: Int, currentFolder: String) -> Unit,
    onProgress: (processed: Int, total: Int, currentFolder: String) -> Unit,
    isCancelled: () -> Boolean,
    onBinderCooldown: (suspend (secondsLeft: Int) -> Unit)? = null,
    cancelFlag: AtomicBoolean? = null
): SizeCalcResult {
    // ── 0. 统计总目录数（用于进度条） ──
    val escaped = SevenZipCommand.escape(rootPath)
    val (countOut, countErr, countExit) = accessor.exec("find $escaped -type d | wc -l", cancelFlag)
    if (isCancelled()) return SizeCalcResult.Cancelled
    val totalDirs = countOut.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
    onTotal(totalDirs)

    // ── 1. BFS 阶段：扫描目录树，构建内存 A ──
    // children: 目录路径 → 直接子项列表（含文件和子目录）
    // dirMtimes: 目录路径 → 该目录本身的 mtime
    // depth: 目录路径 → 深度（rootPath=0）
    val children = LinkedHashMap<String, List<DirEntry>>()
    val dirMtimes = HashMap<String, Long>()
    val depth = HashMap<String, Int>()
    var result: SizeCalcResult? = null

    // rootPath 自身的 mtime 需要单独 stat（BFS 只能从父目录获取子目录 mtime）
    dirMtimes[rootPath] = accessor.statMtime(rootPath) ?: 0L
    val queue = ArrayDeque<Pair<String, Int>>()
    queue.add(rootPath to 0)
    depth[rootPath] = 0

    var scanned = 0
    while (queue.isNotEmpty()) {
        if (isCancelled()) { result = SizeCalcResult.Cancelled; break }
        val (dir, d) = queue.removeFirst()
        val listChildrenStart = System.currentTimeMillis()
        val list = accessor.listChildren(dir)
        val listChildrenElapsed = System.currentTimeMillis() - listChildrenStart
        if (listChildrenElapsed > 300 && onBinderCooldown != null) {
            for (sec in 5 downTo 1) {
                if (isCancelled()) { result = SizeCalcResult.Cancelled; break }
                onBinderCooldown(sec)
                delay(1000)
            }
            onBinderCooldown(0)
        }
        if (result != null) break
        if (list == null) { result = SizeCalcResult.PermissionDenied(dir); break }
        children[dir] = list
        scanned++
        onScanned(scanned, dir)
        for (e in list) {
            if (e.isDir) {
                dirMtimes[e.path] = e.mtime
                depth[e.path] = d + 1
                queue.add(e.path to d + 1)
            }
        }
    }

    // ── 2. 构建内存 B：从 DB 查缓存 ──
    // memA: 目录路径 → 最新计算的 size（初始为 0）
    // memB: 目录路径 → 缓存的 FolderSizeInfo（无缓存则为 null）
    val memA = HashMap<String, Long>(children.size)
    val memB = HashMap<String, FolderSizeInfo?>(children.size)
    for (path in children.keys) {
        memA[path] = 0L
        memB[path] = db.get(path)
    }

    // ── 3. 从叶子向根逐层处理 ──
    val ordered = children.keys.sortedByDescending { depth[it] ?: 0 }
    val total = ordered.size
    val updates = HashMap<String, FolderSizeInfo>(total)

    try {
        var processed = 0
        for (dir in ordered) {
            if (isCancelled()) { result = SizeCalcResult.Cancelled; break }
            val currentMtime = dirMtimes[dir] ?: 0L
            val cached = memB[dir]

            if (cached != null && cached.lastModified == currentMtime) {
                // 缓存命中：mtime 未变，复用旧 size
                memA[dir] = cached.size
                updates[dir] = cached
            } else {
                // 缓存未命中：列出直接子项，累加大小
                val list = children[dir] ?: emptyList()
                var sum = 0L
                for (e in list) {
                    sum += if (e.isDir) {
                        // 子文件夹：从 A 读取（叶子已先处理，值已就绪）
                        memA[e.path] ?: 0L
                    } else {
                        // 直接子文件：累加大小
                        e.size
                    }
                }
                memA[dir] = sum
                updates[dir] = FolderSizeInfo(sum, currentMtime)
            }
            processed++
            onProgress(processed, total, dir)
        }
    } catch (e: Throwable) {
        // 异常：保存已计算的部分结果（不清除旧缓存，保留未处理目录的数据）
        db.bulkPut(updates)
        throw e
    }

    if (result == null) {
        // 完整完成：先清除旧子树缓存（含已删除目录），再写入新数据
        db.removeDescendants(rootPath)
        db.bulkPut(updates)
    } else {
        // 部分完成（Cancelled / PermissionDenied）：只写新数据，不清除旧缓存
        db.bulkPut(updates)
    }

    val tree = if (result == null) buildSizeTree(rootPath, children, memA) else null
    return result ?: SizeCalcResult.Success(memA[rootPath] ?: 0L, tree)
}

/**
 * 基于 BFS 收集的 children 和 sizes 构建树形结构。
 * children 中的条目按大小降序排列。
 */
private fun buildSizeTree(
    rootPath: String,
    children: Map<String, List<DirEntry>>,
    sizes: Map<String, Long>
): SizeTreeNode {
    val rootName = rootPath.substringAfterLast('/').ifEmpty { "/" }
    val rootSize = sizes[rootPath] ?: 0L
    val list = children[rootPath] ?: emptyList()
    val childNodes = list.map { e ->
        if (e.isDir) buildSizeTree(e.path, children, sizes)
        else SizeTreeNode(e.name, e.path, false, e.size)
    }.sortedByDescending { it.size }
    return SizeTreeNode(rootName, rootPath, true, rootSize, childNodes)
}
