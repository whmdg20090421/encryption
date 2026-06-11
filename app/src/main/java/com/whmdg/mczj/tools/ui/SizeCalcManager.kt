package com.whmdg.mczj.tools.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.whmdg.mczj.tools.encryption.data.FolderSizeDb
import java.io.File

/**
 * 文件夹大小统计进度管理（全局单例）。
 * FolderSizeCalculator 写入进度，MainAppContainer 读取并显示进度条。
 */
object SizeCalcManager {
    /** 0f ~ 1f，进度 = processedCount / totalCount */
    var progress by mutableFloatStateOf(0f)
        internal set
    /** 当前正在处理的目录名 */
    var currentFolder by mutableStateOf("")
        internal set
    /** BFS 已扫描的目录数 */
    var scannedCount by mutableIntStateOf(0)
        internal set
    /** 已完成大小计算的目录数 */
    var processedCount by mutableIntStateOf(0)
        internal set
    /** 总目录数（BFS 完成后确定） */
    var totalCount by mutableIntStateOf(0)
        internal set
    /** 是否正在计算 */
    val isCalculating: Boolean get() = scannedCount > 0 || (progress > 0f && progress < 1f)

    /** 报错弹窗 */
    var loadError by mutableStateOf<Throwable?>(null)
        internal set

    /** 用户请求取消 */
    @Volatile
    var cancelRequested = false
        private set

    /** 当前正在计算的 db 引用，用于手动保存 */
    private var currentDb: FolderSizeDb? = null
    private var saveDir: File? = null

    fun requestCancel() { cancelRequested = true }

    /** 用户点击"保存"：将当前已计算的结果持久化 */
    fun save() { currentDb?.save(saveDir ?: return) }

    internal fun begin(db: FolderSizeDb, saveDir: File) {
        currentDb = db; this.saveDir = saveDir
        progress = 0f; currentFolder = ""
        scannedCount = 0; processedCount = 0; totalCount = 0
        cancelRequested = false; loadError = null
    }

    /** BFS 前：统计总目录数 */
    internal fun onTotal(total: Int) {
        if (total > 0) totalCount = total
    }

    /** BFS 阶段：每扫描一个目录调用 */
    internal fun onScanned(count: Int, folder: String) {
        scannedCount = count
        processedCount = count
        currentFolder = folder
        progress = if (totalCount > 0) count.toFloat() / totalCount else 0f
    }

    /** 累加阶段：设置总数并更新进度 */
    internal fun onProgress(processed: Int, total: Int, folder: String) {
        processedCount = processed
        currentFolder = folder
        progress = if (total > 0) processed.toFloat() / total else 0f
    }

    internal fun finish() {
        progress = 1f; currentFolder = ""
        scannedCount = 0; processedCount = 0; totalCount = 0
        cancelRequested = false; currentDb = null; saveDir = null
    }
}
