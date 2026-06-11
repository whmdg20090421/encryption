package com.whmdg.mczj.tools.util

import com.whmdg.mczj.tools.encryption.data.FolderSizeDb
import com.whmdg.mczj.tools.encryption.data.FolderSizeInfo

/**
 * 文件夹大小统计核心算法。
 *
 * 入口：[calculateFolderSize]
 *   - 若 DB 中无该子树缓存 → 全量统计 [fullScan]
 *   - 若有 → 差异统计 [diffScan]
 *
 * 优化：使用 [FileAccessor.listChildrenRecursive] 单次 shell 调用获取整个子树，
 * 替代逐目录 BFS + listChildren 的 N 次调用，消除反复 fork 开销。
 *
 * 算法关键不变量：
 *   - 同一权限通道下 mtime 单位一致（NORMAL 毫秒、SHELL 秒→毫秒），跨通道切换会触发全量重扫
 *   - POSIX 保证：目录直接 children 增删改时其 mtime 变化（但子目录内部变化不会传播）
 *     → "mtime 未变"意味着自身直接文件层未变，但子树内部可能变了，仍要应用子目录 delta
 *
 * 进度回调：
 *   - onTotal：递归扫描前统计总目录数
 *   - onScanned：递归扫描完成后一次性回调
 *   - onProgress：自底向上聚合时逐目录回调
 */
fun calculateFolderSize(
    rootPath: String,
    accessor: FileAccessor,
    db: FolderSizeDb,
    onTotal: (total: Int) -> Unit,
    onScanned: (count: Int, currentFolder: String) -> Unit,
    onProgress: (processed: Int, total: Int, currentFolder: String) -> Unit,
    isCancelled: () -> Boolean
): SizeCalcResult {
    // 尝试用 shell 统计总目录数（ShellAccessor 有 exec 权限）
    val escaped = rootPath.replace("'", "'\\''")
    val (countOut, _, countExit) = accessor.exec("find '$escaped' -type d | wc -l")
    val shellTotal = countOut.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
    if (shellTotal > 0) onTotal(shellTotal)
    // NormalAccessor 的 exec 不可用时，onTotal 延迟到递归扫描后由 buildTree 统计

    val snapshot = db.getDescendants(rootPath)
    return if (snapshot.isEmpty()) {
        fullScan(rootPath, accessor, db, onTotal, shellTotal, onScanned, onProgress, isCancelled)
    } else {
        diffScan(rootPath, snapshot, accessor, db, onTotal, shellTotal, onScanned, onProgress, isCancelled)
    }
}

/**
 * 构建目录树结构。
 *
 * @return Triple(childrenMap, depthMap, mtimeMap)，key 均为目录绝对路径
 *   - childrenMap[dir] = 该目录下的 DirEntry 列表（文件 + 子目录）
 *   - depthMap[dir] = 相对 rootPath 的深度
 *   - mtimeMap[dir] = 目录自身的 mtime
 */
private fun buildTree(
    rootPath: String,
    entries: List<DirEntry>,
    accessor: FileAccessor
): Triple<Map<String, List<DirEntry>>, Map<String, Int>, Map<String, Long>> {
    val rootNorm = rootPath.trimEnd('/').ifEmpty { "/" }
    val childrenMap = LinkedHashMap<String, MutableList<DirEntry>>()
    val mtimeMap = HashMap<String, Long>()
    val depthMap = HashMap<String, Int>()

    // rootPath 自身
    mtimeMap[rootNorm] = accessor.statMtime(rootNorm) ?: 0L
    depthMap[rootNorm] = 0

    // 将所有条目按父目录分组
    for (e in entries) {
        val parent = e.path.substringBeforeLast('/').ifEmpty { "/" }
        childrenMap.getOrPut(parent) { mutableListOf() }.add(e)
    }

    // 计算每个目录的深度（从 rootPath 开始 BFS）
    val queue = ArrayDeque<String>()
    queue.add(rootNorm)
    while (queue.isNotEmpty()) {
        val dir = queue.removeFirst()
        val d = depthMap[dir] ?: 0
        for (e in childrenMap[dir] ?: emptyList()) {
            if (e.isDir) {
                mtimeMap[e.path] = e.mtime
                depthMap[e.path] = d + 1
                queue.add(e.path)
            }
        }
    }

    return Triple(childrenMap, depthMap, mtimeMap)
}

/**
 * 全量统计：单次递归扫描 → 自底向上累加。
 *
 * 实现细节：
 *   1. listChildrenRecursive 一次获取所有文件/子目录条目
 *   2. 在 Kotlin 中构建目录树（parent → children 映射）
 *   3. 按深度降序自底向上累加：每个目录 size = 直接子文件 size + 直接子目录 size
 *   4. 全部计算完成后通过 db.bulkPut 一次性写入
 */
private fun fullScan(
    rootPath: String,
    accessor: FileAccessor,
    db: FolderSizeDb,
    onTotal: (Int) -> Unit,
    shellTotal: Int,
    onScanned: (Int, String) -> Unit,
    onProgress: (Int, Int, String) -> Unit,
    isCancelled: () -> Boolean
): SizeCalcResult {
    // 单次递归扫描
    val rootNorm = rootPath.trimEnd('/').ifEmpty { "/" }
    val entries = accessor.listChildrenRecursive(rootNorm)
        ?: return SizeCalcResult.PermissionDenied(rootNorm)

    // shell 统计失败时，从递归结果中统计目录数
    if (shellTotal <= 0) {
        val dirCount = entries.count { it.isDir } + 1 // +1 for rootPath itself
        onTotal(dirCount)
    }
    onScanned(1, rootNorm) // 递归扫描完成，一次性回调

    if (isCancelled()) return SizeCalcResult.Cancelled

    // 构建目录树
    val (childrenMap, depthMap, mtimeMap) = buildTree(rootNorm, entries, accessor)

    // 按深度降序排列，自底向上计算大小
    val ordered = childrenMap.keys.sortedByDescending { depthMap[it] ?: 0 }
    val sizes = HashMap<String, Long>(ordered.size)
    val updates = HashMap<String, FolderSizeInfo>(ordered.size)

    try {
        var processed = 0
        for (dir in ordered) {
            if (isCancelled()) {
                db.bulkPut(updates)
                return SizeCalcResult.Cancelled
            }
            var s = 0L
            for (e in childrenMap[dir] ?: emptyList()) {
                s += if (e.isDir) (sizes[e.path] ?: 0L) else e.size
            }
            sizes[dir] = s
            updates[dir] = FolderSizeInfo(s, mtimeMap[dir] ?: 0L)
            processed++
            onProgress(processed, ordered.size, dir)
        }
    } finally {
        // 无论正常完成、Cancelled、PermissionDenied 还是异常，都保存已计算的部分结果
        db.bulkPut(updates)
    }

    return SizeCalcResult.Success(sizes[rootNorm] ?: 0L)
}

/**
 * 差异统计：单次递归扫描 + mtime 对比 + delta 冒泡。
 *
 * 处理三类节点（按深度降序处理，确保子结果先就绪）：
 *   - 旧节点存在且 mtime 不变：newSize = oldSize + childDelta（仅当 childDelta != 0 才更新 DB）
 *   - 旧节点存在但 mtime 变化：重扫直接子文件 + 直接子目录的最新 size → 重算 newSize
 *   - 新节点（旧快照里没有）：等价局部 fullScan
 *
 * 删除节点（旧快照里有、当前没有）只需要从 DB 移除即可：
 *   POSIX 保证其 parent 的 mtime 已变，parent 走"重扫"分支会自然反映新状态。
 */
private fun diffScan(
    rootPath: String,
    snapshot: Map<String, FolderSizeInfo>,
    accessor: FileAccessor,
    db: FolderSizeDb,
    onTotal: (Int) -> Unit,
    shellTotal: Int,
    onScanned: (Int, String) -> Unit,
    onProgress: (Int, Int, String) -> Unit,
    isCancelled: () -> Boolean
): SizeCalcResult {
    // 单次递归扫描
    val rootNorm = rootPath.trimEnd('/').ifEmpty { "/" }
    val entries = accessor.listChildrenRecursive(rootNorm)
        ?: return SizeCalcResult.PermissionDenied(rootNorm)

    // shell 统计失败时，从递归结果中统计目录数
    if (shellTotal <= 0) {
        val dirCount = entries.count { it.isDir } + 1
        onTotal(dirCount)
    }
    onScanned(1, rootNorm) // 递归扫描完成，一次性回调

    if (isCancelled()) return SizeCalcResult.Cancelled

    // 构建目录树
    val (childrenMap, depthMap, currentMtimes) = buildTree(rootNorm, entries, accessor)

    // 清理快照里存在但当前已消失的节点（其 parent mtime 必变，重扫时会自然剔除）
    for (p in snapshot.keys - childrenMap.keys) {
        db.remove(p)
    }

    // 按深度降序排列，自底向上计算大小
    val ordered = childrenMap.keys.sortedByDescending { depthMap[it] ?: 0 }
    val newSizes = HashMap<String, Long>(ordered.size)
    val deltas = HashMap<String, Long>(ordered.size)
    val updates = HashMap<String, FolderSizeInfo>(ordered.size)

    try {
        var processed = 0
        for (dir in ordered) {
            if (isCancelled()) {
                db.bulkPut(updates)
                return SizeCalcResult.Cancelled
            }
            val old = snapshot[dir]
            val list = childrenMap[dir]!!
            val currentMtime = currentMtimes[dir] ?: 0L
            val childDelta = deltas[dir] ?: 0L

            val newSize: Long
            val mtimeForDb: Long
            val shouldWrite: Boolean

            when {
                old == null -> {
                    // 新增：等价于局部 fullScan 在此节点的一步
                    var s = 0L
                    for (e in list) {
                        s += if (e.isDir) (newSizes[e.path] ?: 0L) else e.size
                    }
                    newSize = s
                    mtimeForDb = currentMtime
                    shouldWrite = true
                }
                currentMtime == old.lastModified -> {
                    // mtime 未变：自身直接文件层未变，size 仅受子目录 delta 影响
                    newSize = old.size + childDelta
                    mtimeForDb = old.lastModified
                    shouldWrite = childDelta != 0L
                }
                else -> {
                    // mtime 变化：重扫直接子项
                    var s = 0L
                    for (e in list) {
                        s += if (e.isDir) {
                            newSizes[e.path] ?: snapshot[e.path]?.size ?: 0L
                        } else e.size
                    }
                    newSize = s
                    mtimeForDb = currentMtime
                    shouldWrite = true
                }
            }

            newSizes[dir] = newSize
            if (shouldWrite) updates[dir] = FolderSizeInfo(newSize, mtimeForDb)

            val delta = newSize - (old?.size ?: 0L)
            if (dir != rootNorm && delta != 0L) {
                val parent = dir.substringBeforeLast('/').ifEmpty { "/" }
                deltas.merge(parent, delta) { a, b -> a + b }
            }
            processed++
            onProgress(processed, ordered.size, dir)
        }
    } finally {
        // 无论正常完成、Cancelled、PermissionDenied 还是异常，都保存已计算的部分结果
        db.bulkPut(updates)
    }

    return SizeCalcResult.Success(newSizes[rootNorm] ?: 0L)
}
