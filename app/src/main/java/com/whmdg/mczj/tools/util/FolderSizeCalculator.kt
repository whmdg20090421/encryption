package com.whmdg.mczj.tools.util

import com.whmdg.mczj.tools.encryption.data.FolderSizeDb
import com.whmdg.mczj.tools.encryption.data.FolderSizeInfo
import kotlinx.coroutines.delay

/** 大小统计树形节点（文件或目录）。 */
data class SizeTreeNode(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val children: List<SizeTreeNode> = emptyList()
)

/**
 * 文件夹大小统计核心算法（双内存 A/B 架构 + BFS 剪枝）。
 *
 * 入口：[calculateFolderSize]
 *
 * 算法流程：
 *   1. 根目录快速路径：若根目录缓存 mtime 未变，直接返回缓存大小
 *   2. BFS 阶段：从根向叶子逐层展开目录树
 *      - 每层检查当前目录 mtime 是否与缓存一致
 *      - 一致 → 跳过 ls，作为叶子节点，直接使用缓存大小
 *      - 不一致 → ls 展开子目录，子目录入队继续 BFS
 *      信任链：父目录 mtime 变化才会 ls → 子目录 mtime 从 ls 获取 → 与缓存对比
 *   3. 累加阶段：从叶子向根逐层处理（仅处理被展开的目录）
 *      - 叶子目录（缓存命中）：BFS 阶段已设置 memA，跳过
 *      - 展开目录：累加直接子文件大小 + 子文件夹 size（从 memA 读取）
 *   4. 写缓存：先 removeDescendants 清除旧子树，再 bulkPut 写入新数据
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
    onBinderCooldown: (suspend (secondsLeft: Int) -> Unit)? = null
): SizeCalcResult {
    // ── 0. 统计总目录数（用于进度条） ──
    val escaped = rootPath.replace("'", "'\\''")
    val (countOut, _, countExit) = accessor.exec("find '$escaped' -type d | wc -l")
    val totalDirs = countOut.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
    onTotal(totalDirs)

    // ── 0.5 快速路径：根目录缓存 mtime 未变 → 直接返回 ──
    val rootCurrentMtime = accessor.statMtime(rootPath) ?: 0L
    val rootCached = db.get(rootPath)
    if (rootCached != null && rootCached.lastModified == rootCurrentMtime) {
        onScanned(1, rootPath)
        onProgress(1, 1, rootPath)
        val tree = SizeTreeNode(
            name = rootPath.substringAfterLast('/').ifEmpty { "/" },
            path = rootPath,
            isDir = true,
            size = rootCached.size,
            children = emptyList()
        )
        return SizeCalcResult.Success(rootCached.size, tree)
    }

    // ── 1. BFS 阶段：从根向叶子逐层展开，mtime 未变的子树跳过 ──
    val children = LinkedHashMap<String, List<DirEntry>>()
    val depth = HashMap<String, Int>()
    // 存储每个目录的当前 mtime（从父目录 listChildren 或 statMtime 获取）
    val currentMtimes = HashMap<String, Long>()
    var result: SizeCalcResult? = null

    currentMtimes[rootPath] = rootCurrentMtime
    val queue = ArrayDeque<Pair<String, Int>>()
    queue.add(rootPath to 0)
    depth[rootPath] = 0

    var scanned = 0
    while (queue.isNotEmpty()) {
        if (isCancelled()) { result = SizeCalcResult.Cancelled; break }
        val (dir, d) = queue.removeFirst()

        // 检查缓存：mtime 未变 → 跳过 ls，作为叶子节点
        val dirMtime = currentMtimes[dir] ?: 0L
        val cached = db.get(dir)
        if (cached != null && cached.lastModified == dirMtime) {
            children[dir] = emptyList()  // 叶子，不展开
            scanned++
            onScanned(scanned, dir)
            continue
        }

        // mtime 变化或无缓存 → ls 展开子目录
        val listChildrenStart = System.currentTimeMillis()
        val list = accessor.listChildren(dir)
        val listChildrenElapsed = System.currentTimeMillis() - listChildrenStart
        if (listChildrenElapsed > 300 && onBinderCooldown != null) {
            for (sec in 5 downTo 1) {
                onBinderCooldown(sec)
                delay(1000)
            }
            onBinderCooldown(0)
        }
        if (list == null) { result = SizeCalcResult.PermissionDenied(dir); break }
        children[dir] = list
        scanned++
        onScanned(scanned, dir)
        for (e in list) {
            if (e.isDir) {
                currentMtimes[e.path] = e.mtime
                depth[e.path] = d + 1
                queue.add(e.path to d + 1)
            }
        }
    }

    // ── 2. 构建内存 B：从 DB 查缓存（仅对被展开的目录） ──
    // memA: 目录路径 → 最新计算的 size
    // 需要累加的目录集合（被展开的、非叶子的目录）
    val memA = HashMap<String, Long>(children.size)
    val needAccumulation = mutableListOf<String>()

    for ((path, list) in children) {
        if (list.isEmpty()) {
            // 叶子目录（缓存命中）：BFS 阶段已决定跳过，从缓存取 size
            val c = db.get(path)
            memA[path] = c?.size ?: 0L
        } else {
            // 被展开的目录：需要累加计算
            memA[path] = 0L
            needAccumulation.add(path)
        }
    }

    // ── 3. 累加阶段：仅处理被展开的目录，从叶子向根 ──
    needAccumulation.sortByDescending { depth[it] ?: 0 }
    val total = needAccumulation.size
    val updates = HashMap<String, FolderSizeInfo>(total)

    try {
        var processed = 0
        for (dir in needAccumulation) {
            if (isCancelled()) { result = SizeCalcResult.Cancelled; break }
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
            val mtime = currentMtimes[dir] ?: 0L
            memA[dir] = sum
            updates[dir] = FolderSizeInfo(sum, mtime)
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
