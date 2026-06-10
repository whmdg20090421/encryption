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
 * calcDirSizeWithShell 写入进度，MainAppContainer 读取并显示进度条。
 */
object SizeCalcManager {
    /** 0f ~ 1f，0 表示未在计算 */
    var progress by mutableFloatStateOf(0f)
        internal set
    /** 当前正在计算的目录名 */
    var currentFolder by mutableStateOf("")
        internal set
    /** 已处理目录数 */
    var processed by mutableIntStateOf(0)
        internal set
    /** 总目录数 */
    var total by mutableIntStateOf(0)
        internal set
    /** 是否正在计算 */
    val isCalculating: Boolean get() = progress > 0f && progress < 1f

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
        progress = 0f; currentFolder = ""; processed = 0; total = 0; cancelRequested = false
    }

    internal fun update(processed: Int, total: Int, folder: String) {
        this.processed = processed; this.total = total
        currentFolder = folder
        progress = if (total > 0) processed.toFloat() / total else 0f
    }

    internal fun finish() {
        progress = 1f; currentFolder = ""; processed = 0; total = 0
        cancelRequested = false; currentDb = null; saveDir = null
    }
}
