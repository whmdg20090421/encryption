package com.whmdg.mczj.tools.util

import com.whmdg.mczj.tools.encryption.data.FolderSizeDb
import com.whmdg.mczj.tools.encryption.data.FolderSizeInfo
import kotlinx.coroutines.delay

/**
 * 文件夹大小统计核心算法。
 *
 * 入口：[calculateFolderSize]
 *   - 若 DB 中无该子树缓存 → 全量统计 [fullScan]（自底向上）
 *   - 若有 → 差异统计 [diffScan]（BFS 全量收集 + 从叶子向上 mtime 对比 + delta 冒泡）
 *
 * 算法关键不变量：
 *   - diffScan 的 BFS 遍历子目录时检查缓存 mtime，未变则跳过子树（POSIX 保证：
 *     目录内文件增删时其 mtime 变化，mtime 不变 = 子树结构未变，可安全复用缓存）
 *   - 跳过的目录不进入 currentChildren，累加阶段不会处理，缓存值自然被使用
 *   - 累加阶段从叶子向根逐级检查 mtime，mtime 未变则复用旧 size + 子目录 delta，
 *     mtime 变化则重扫该目录并将 delta 向上冒泡
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
 * 差异统计：BFS 收集目录（mtime 未变的子树跳过）→ 从叶子向上逐级 mtime 对比 + delta 冒泡。
 *
 * BFS 阶段优化：遍历子目录时检查 snapshot 中的缓存 mtime，
 * 若未变则跳过该子树入队（不进入 currentChildren），
 * 累加阶段不处理这些目录，缓存值自然被使用，delta 也不会误冒泡。
 *
 * 累加阶段（按深度降序，叶子先处理）：
 *   - 旧节点存在且 mtime 不变：newSize = oldSize + childDelta（仅当 childDelta != 0 才更新 DB）
 *   - 旧节点存在但 mtime 变化：重扫直接子文件 + 直接子目录的最新 size → 重算 newSize
 *   - 新节点（旧快照里没有）：等价局部 fullScan
 *
 * 删除节点：仅当父目录已确认当前子列表中不含该节点时才从 DB 移除。
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

    // BFS 阶段：收集目录树，mtime 未变的子树跳过（不入队，缓存值在累加阶段自然被使用）
    var scanned = 0
    while (queue.isNotEmpty()) {
        if (isCancelled()) { result = SizeCalcResult.Cancelled; break }
        val (dir, d) = queue.removeFirst()
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
        currentChildren[dir] = list
        scanned++
        onScanned(scanned, dir)
        for (e in list) {
            if (e.isDir) {
                currentMtimes[e.path] = e.mtime
                depth[e.path] = d + 1
                // 缓存命中且 mtime 未变 → 跳过子树，累加阶段直接用缓存冒泡
                val cached = snapshot[e.path]
                if (cached != null && e.mtime == cached.lastModified) continue
                queue.add(e.path to d + 1)
            }
        }
    }

    // 清理：仅当 BFS 正常完成（result == null）时才执行，
    // 因为 BFS 中断时 currentChildren 不完整，误删未访问的目录
    if (result == null) {
        for (p in snapshot.keys) {
            if (p == rootPath) continue
            val parent = p.substringBeforeLast('/').ifEmpty { "/" }
            if (parent in currentChildren) {
                val siblings = currentChildren[parent] ?: continue
                if (siblings.none { it.path == p }) {
                    db.remove(p)
                }
            }
        }
    }

    // 累加阶段：从叶子向根逐级 mtime 对比，delta 冒泡
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
