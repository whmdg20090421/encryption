package com.whmdg.mczj.tools.util

import com.whmdg.mczj.tools.encryption.data.FolderSizeDb
import com.whmdg.mczj.tools.encryption.data.FolderSizeInfo
import kotlinx.coroutines.delay

/**
 * 文件夹大小统计核心算法。
 *
 * 入口：[calculateFolderSize]
 *   - 若 DB 中无该子树缓存 → 全量统计 [fullScan]（自底向上）
 *   - 若有 → 差异统计 [diffScan]（mtime 对比 + delta 冒泡）
 *
 * 算法关键不变量：
 *   - 同一权限通道下 mtime 单位一致（NORMAL 毫秒、SHELL 分钟级），跨通道切换会触发全量重扫
 *   - POSIX 保证：目录直接 children 增删改时其 mtime 变化（但子目录内部变化不会传播）
 *     → "mtime 未变"意味着自身直接文件层未变，但子树内部可能变了，仍要应用子目录 delta
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
    val escaped = rootPath.replace("'", "'\\''")
    val (countOut, _, countExit) = accessor.exec("find '$escaped' -type d | wc -l")
    val totalDirs = countOut.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
    onTotal(totalDirs)

    val snapshot = db.getDescendants(rootPath)
    return if (snapshot.isEmpty()) {
        fullScan(rootPath, accessor, db, onScanned, onProgress, isCancelled, onBinderCooldown)
    } else {
        diffScan(rootPath, snapshot, accessor, db, onScanned, onProgress, isCancelled, onBinderCooldown)
    }
}

/**
 * 全量统计：BFS 构建目录树 → 按深度降序自底向上累加。
 *
 * 实现细节：
 *   1. 子目录的 mtime 从 parent 的 listChildren 结果复用（DirEntry.mtime），
 *      仅 rootPath 单独 statMtime 一次。
 *   2. 每个目录的 size = 直接子文件 size 之和 + 直接子目录 size 之和（来自 sizes Map）。
 *   3. 全部计算完成后通过 db.bulkPut 一次性写入。
 */
private suspend fun fullScan(
    rootPath: String,
    accessor: FileAccessor,
    db: FolderSizeDb,
    onScanned: (Int, String) -> Unit,
    onProgress: (Int, Int, String) -> Unit,
    isCancelled: () -> Boolean,
    onBinderCooldown: (suspend (secondsLeft: Int) -> Unit)? = null
): SizeCalcResult {
    val children = LinkedHashMap<String, List<DirEntry>>()
    val depth = HashMap<String, Int>()
    val mtimes = HashMap<String, Long>()
    var result: SizeCalcResult? = null

    mtimes[rootPath] = accessor.statMtime(rootPath) ?: 0L
    val queue = ArrayDeque<Pair<String, Int>>()
    queue.add(rootPath to 0)
    depth[rootPath] = 0

    // BFS 阶段：收集目录树
    var scanned = 0
    while (queue.isNotEmpty()) {
        if (isCancelled()) { result = SizeCalcResult.Cancelled; break }
        val (dir, d) = queue.removeFirst()
        val listChildrenStart = System.currentTimeMillis()
        val list = accessor.listChildren(dir)
        val listChildrenElapsed = System.currentTimeMillis() - listChildrenStart
        if (listChildrenElapsed > 100 && onBinderCooldown != null) {
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
                mtimes[e.path] = e.mtime
                depth[e.path] = d + 1
                queue.add(e.path to d + 1)
            }
        }
    }

    // 累加阶段：对已收集的目录自底向上计算大小
    val total = children.size
    val sizes = HashMap<String, Long>(total)
    val updates = HashMap<String, FolderSizeInfo>(total)
    val ordered = children.keys.sortedByDescending { depth[it] ?: 0 }

    try {
        var processed = 0
        for (dir in ordered) {
            if (isCancelled()) { result = SizeCalcResult.Cancelled; break }
            val list = children[dir]!!
            var s = 0L
            for (e in list) {
                s += if (e.isDir) (sizes[e.path] ?: 0L) else e.size
            }
            sizes[dir] = s
            updates[dir] = FolderSizeInfo(s, mtimes[dir] ?: 0L)
            processed++
            onProgress(processed, total, dir)
        }
    } finally {
        // 无论正常完成、Cancelled、PermissionDenied 还是异常，都保存已计算的部分结果
        db.bulkPut(updates)
    }

    return result ?: SizeCalcResult.Success(sizes[rootPath] ?: 0L)
}

/**
 * 差异统计：基于 mtime 对比的 delta 冒泡。
 *
 * 处理三类节点（按深度降序处理，确保子结果先就绪）：
 *   - 旧节点存在且 mtime 不变：newSize = oldSize + childDelta（仅当 childDelta != 0 才更新 DB）
 *   - 旧节点存在但 mtime 变化：重扫直接子文件 + 直接子目录的最新 size → 重算 newSize
 *   - 新节点（旧快照里没有）：等价局部 fullScan
 *
 * 删除节点（旧快照里有、当前没有）只需要从 DB 移除即可：
 *   POSIX 保证其 parent 的 mtime 已变，parent 走"重扫"分支会自然反映新状态。
 */
private suspend fun diffScan(
    rootPath: String,
    snapshot: Map<String, FolderSizeInfo>,
    accessor: FileAccessor,
    db: FolderSizeDb,
    onScanned: (Int, String) -> Unit,
    onProgress: (Int, Int, String) -> Unit,
    isCancelled: () -> Boolean,
    onBinderCooldown: (suspend (secondsLeft: Int) -> Unit)? = null
): SizeCalcResult {
    val currentChildren = LinkedHashMap<String, List<DirEntry>>()
    val currentMtimes = HashMap<String, Long>()
    val depth = HashMap<String, Int>()
    var result: SizeCalcResult? = null

    currentMtimes[rootPath] = accessor.statMtime(rootPath) ?: 0L
    val queue = ArrayDeque<Pair<String, Int>>()
    queue.add(rootPath to 0)
    depth[rootPath] = 0

    // BFS 阶段：收集目录树（mtime 剪枝）
    var scanned = 0
    while (queue.isNotEmpty()) {
        if (isCancelled()) { result = SizeCalcResult.Cancelled; break }
        val (dir, d) = queue.removeFirst()
        val listChildrenStart = System.currentTimeMillis()
        val list = accessor.listChildren(dir)
        val listChildrenElapsed = System.currentTimeMillis() - listChildrenStart
        if (listChildrenElapsed > 100 && onBinderCooldown != null) {
            for (sec in 5 downTo 1) {
                onBinderCooldown(sec)
                delay(1000)
            }
            onBinderCooldown(0)
        }
        if (list == null) { result = SizeCalcResult.PermissionDenied(dir); break }
        currentChildren[dir] = list
        scanned++
        onScanned(scanned, dir)
        val old = snapshot[dir]
        val curMtime = currentMtimes[dir] ?: 0L
        if (old != null && curMtime == old.lastModified) continue
        for (e in list) {
            if (e.isDir) {
                currentMtimes[e.path] = e.mtime
                depth[e.path] = d + 1
                queue.add(e.path to d + 1)
            }
        }
    }

    // 清理快照里存在但当前已消失的节点（其 parent mtime 必变，重扫时会自然剔除）
    for (p in snapshot.keys - currentChildren.keys) {
        db.remove(p)
    }

    // 累加阶段：对已收集的目录自底向上计算大小
    val newSizes = HashMap<String, Long>()
    val deltas = HashMap<String, Long>()
    val updates = HashMap<String, FolderSizeInfo>()
    val ordered = currentChildren.keys.sortedByDescending { depth[it] ?: 0 }
    val total = ordered.size

    try {
        var processed = 0
        for (dir in ordered) {
            if (isCancelled()) { result = SizeCalcResult.Cancelled; break }
            val old = snapshot[dir]
            val list = currentChildren[dir]!!
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
            if (dir != rootPath && delta != 0L) {
                val parent = dir.substringBeforeLast('/').ifEmpty { "/" }
                deltas.merge(parent, delta) { a, b -> a + b }
            }
            processed++
            onProgress(processed, total, dir)
        }
    } finally {
        // 无论正常完成、Cancelled、PermissionDenied 还是异常，都保存已计算的部分结果
        db.bulkPut(updates)
    }

    return result ?: SizeCalcResult.Success(newSizes[rootPath] ?: 0L)
}
