package com.whmdg.mczj.tools.util

import com.whmdg.mczj.tools.encryption.data.FolderSizeCache
import com.whmdg.mczj.tools.encryption.data.FolderSizeInfo
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
 * 文件夹大小统计核心算法（单命令递归扫描 + 自底向上求和）。
 *
 * ## 流程
 *   1. **一次递归扫描**：[FileAccessor.scanTree] 用单条 `find` 命令流式列出
 *      rootPath 及其整棵子树里的所有文件与目录（路径 / 类型 / 大小 / mtime），
 *      取代旧的「逐目录 listChildren」上千次 shell 往返。
 *   2. **分拣**：文件记录自身 size；目录记录自身 mtime，并按父子关系归集
 *      直接子文件字节和与直接子目录。
 *   3. **自底向上求和**：按路径长度从大到小（越深越长）遍历目录，目录累计大小 =
 *      直接文件字节和 + 直接子目录累计大小。
 *   4. **产出完整快照**：把文件与目录的最终 `(size, mtime)` 全部交给 [cache] 暂存。
 *      是否写库、删除消失项由提交层（[com.whmdg.mczj.tools.encryption.data.FolderSizeStore.commitStaging]）
 *      与持久层做差集决定——本算法只负责给出「当前真实状态」。
 *
 * ## 与旧实现的区别
 *   - 不再用「目录 mtime 是否变化」判断是否重算（改文件内容不会改目录 mtime，
 *     旧的目录级判断会漏检）。所有文件大小每轮都由扫描直接得到，天然准确。
 *   - 目录 mtime 仅作为属性随扫描结果存储，不参与复用判断。
 *
 * @param onScanned 已扫描条目数回调（用于进度显示），第二个参数为最近一条路径
 */
suspend fun calculateFolderSize(
    rootPath: String,
    accessor: FileAccessor,
    cache: FolderSizeCache,
    onScanned: (count: Int, currentPath: String) -> Unit = { _, _ -> },
    isCancelled: () -> Boolean = { false },
    cancelFlag: AtomicBoolean? = null
): SizeCalcResult {
    val normalizedRoot = if (rootPath == "/") "/" else rootPath.trimEnd('/').ifEmpty { "/" }

    // 扫描期间的分拣容器
    val fileSizes = HashMap<String, Long>()
    val fileMtimes = HashMap<String, Long>()
    val dirMtimes = HashMap<String, Long>()
    val allDirs = HashSet<String>()
    /** 目录 → 其直接子文件字节和 */
    val dirFileSum = HashMap<String, Long>()
    /** 目录 → 其直接子目录列表 */
    val dirChildren = HashMap<String, MutableList<String>>()
    /** 目录 → 其直接子文件 (路径, 大小) 列表 */
    val dirFiles = HashMap<String, MutableList<Pair<String, Long>>>()
    var scanned = 0

    val outcome = accessor.scanTree(normalizedRoot, cancelFlag) { batch ->
        for (e in batch) {
            // 绝对路径的父目录；根为 "/" 时子项（如 "/data"）的父应为 "/"
            val parentRaw = e.path.substringBeforeLast('/', "")
            val parent = if (parentRaw.isEmpty()) "/" else parentRaw
            if (e.isDir) {
                allDirs.add(e.path)
                dirMtimes[e.path] = e.mtime
                if (parent != e.path) {
                    dirChildren.getOrPut(parent) { ArrayList() }.add(e.path)
                }
            } else {
                fileSizes[e.path] = e.size
                fileMtimes[e.path] = e.mtime
                dirFileSum[parent] = (dirFileSum[parent] ?: 0L) + e.size
                dirFiles.getOrPut(parent) { ArrayList() }.add(e.path to e.size)
            }
            scanned++
        }
        if (batch.isNotEmpty()) onScanned(scanned, batch.last().path)
    }

    if (isCancelled()) return SizeCalcResult.Cancelled
    when (outcome) {
        ScanOutcome.Cancelled -> return SizeCalcResult.Cancelled
        is ScanOutcome.Failed -> return SizeCalcResult.Failed(outcome.message)
        else -> Unit
    }

    // 自底向上求和：路径越长越深，先算子目录再算父目录
    val dirSize = HashMap<String, Long>(allDirs.size)
    for (dir in allDirs.sortedByDescending { it.length }) {
        var sum = dirFileSum[dir] ?: 0L
        dirChildren[dir]?.let { for (child in it) sum += dirSize[child] ?: 0L }
        dirSize[dir] = sum
    }

    // 完整快照：文件 + 目录的最终 (size, mtime)
    val updates = HashMap<String, FolderSizeInfo>(fileSizes.size + dirSize.size)
    for ((path, size) in fileSizes) {
        updates[path] = FolderSizeInfo(size, fileMtimes[path] ?: 0L)
    }
    for ((path, size) in dirSize) {
        updates[path] = FolderSizeInfo(size, dirMtimes[path] ?: 0L)
    }
    cache.bulkPut(updates)

    val fullSnapshot = outcome is ScanOutcome.Success
    val tree = buildSizeTree(normalizedRoot, allDirs, dirChildren, dirFiles, dirSize)
    // 根若是普通文件（无子目录），直接返回其文件大小
    val rootSize = dirSize[normalizedRoot] ?: fileSizes[normalizedRoot] ?: 0L
    return SizeCalcResult.Success(
        rootSize = rootSize,
        tree = tree,
        fullSnapshot = fullSnapshot
    )
}

/** 基于扫描结果构建树形结构，子项按大小降序。 */
private fun buildSizeTree(
    rootPath: String,
    dirs: Set<String>,
    dirChildren: Map<String, List<String>>,
    dirFiles: Map<String, List<Pair<String, Long>>>,
    dirSize: Map<String, Long>
): SizeTreeNode? {
    if (rootPath !in dirs) return null
    fun build(path: String): SizeTreeNode {
        val name = path.substringAfterLast('/').ifEmpty { "/" }
        val childNodes = ArrayList<SizeTreeNode>()
        for (child in dirChildren[path] ?: emptyList()) {
            childNodes.add(build(child))
        }
        for ((filePath, size) in dirFiles[path] ?: emptyList()) {
            childNodes.add(SizeTreeNode(filePath.substringAfterLast('/'), filePath, false, size))
        }
        return SizeTreeNode(name, path, true, dirSize[path] ?: 0L, childNodes.sortedByDescending { it.size })
    }
    return build(rootPath)
}
