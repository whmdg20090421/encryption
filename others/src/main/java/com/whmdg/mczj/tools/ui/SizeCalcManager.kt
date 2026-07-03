package com.whmdg.mczj.tools.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.whmdg.mczj.tools.encryption.data.FolderSizeDb
import com.whmdg.mczj.tools.util.FormatUtils.formatBytes
import com.whmdg.mczj.tools.util.SizeTreeNode
import kotlinx.coroutines.delay
import java.io.File

/**
 * 文件夹大小统计进度管理（全局单例）。
 * FolderSizeCalculator 写入进度，MainAppContainer 读取并显示进度条。
 *
 * 状态流转：begin → onTotal → onScanned×N → finish
 *   - begin: "正在统计文件夹数量..."
 *   - onTotal: 切换到进度条显示
 *   - onScanned: 进度条实时更新
 *   - finish: "已统计完成，大小: XXX"（持久显示，直到下次 begin 或用户关闭）
 */
object SizeCalcManager {
    /** 0f ~ 1f，进度 = scannedCount / totalCount */
    var progress by mutableFloatStateOf(0f)
        set
    /** 当前正在处理的目录名 */
    var currentFolder by mutableStateOf("")
        set
    /** BFS 已扫描的目录数 */
    var scannedCount by mutableIntStateOf(0)
        set
    /** 总目录数（find 统计） */
    var totalCount by mutableIntStateOf(0)
        set
    /** 是否正在计算（从 begin 到 finish） */
    var isCalculating by mutableStateOf(false)
        private set

    /** 状态提示（持久显示：正在统计/进度/完成/报错） */
    var statusMessage by mutableStateOf<String?>(null)
        set
    /** 统计完成后显示的大小 */
    var completedSize by mutableLongStateOf(-1L)
        set
    /** 统计完成后的树形数据 */
    var completedTree by mutableStateOf<SizeTreeNode?>(null)
        set

    /** 报错弹窗 */
    var loadError by mutableStateOf<Throwable?>(null)
        set

    /** Binder 冷却倒计时（秒），0 = 未在冷却 */
    var binderCooldownSeconds by mutableIntStateOf(0)
        set

    /** 是否弹出"保存进度？"对话框 */
    var pendingSaveDialog by mutableStateOf(false)
        set

    /** 用户请求取消 */
    @Volatile
    var cancelRequested = false
        private set

    /** 当前正在计算的 db 引用，用于手动保存 */
    private var currentDb: FolderSizeDb? = null
    private var saveDir: File? = null
    /** 丢弃回调（由 FileManagerViewModel 注册，用于重载 DB） */
    private var onDiscard: (() -> Unit)? = null

    fun requestCancel() { cancelRequested = true }

    /** 用户点击"保存"：将当前已计算的结果持久化 */
    fun save() { currentDb?.save(saveDir ?: return) }

    /** 错误弹窗：用户选择保存已统计的部分结果 */
    fun confirmSavePartial() {
        currentDb?.save(saveDir ?: return)
        pendingSaveDialog = false
    }

    /** 错误弹窗：用户选择丢弃本次数据 */
    fun discardPartial() {
        pendingSaveDialog = false
        onDiscard?.invoke()
    }

    /** 关闭状态提示 */
    fun dismissStatus() { statusMessage = null; completedSize = -1L; completedTree = null }

    /** Binder 冷却倒计时（由 FolderSizeCalculator 回调） */
    internal suspend fun onBinderCooldown(secondsLeft: Int) {
        binderCooldownSeconds = secondsLeft
    }

    internal fun begin(db: FolderSizeDb, saveDir: File, onDiscard: (() -> Unit)? = null) {
        currentDb = db; this.saveDir = saveDir
        this.onDiscard = onDiscard
        progress = 0f; currentFolder = ""
        scannedCount = 0; totalCount = 0
        cancelRequested = false; loadError = null
        completedSize = -1L
        completedTree = null
        binderCooldownSeconds = 0
        pendingSaveDialog = false
        isCalculating = true
        statusMessage = "正在统计文件夹数量..."
    }

    /** BFS 前：统计总目录数，切换到进度条模式 */
    internal fun onTotal(total: Int) {
        if (total > 0) {
            totalCount = total
            statusMessage = null  // 切换到进度条显示
        }
    }

    /** BFS 阶段：每扫描一个目录调用 */
    internal fun onScanned(count: Int, folder: String) {
        scannedCount = count
        currentFolder = folder
        progress = if (totalCount > 0) count.toFloat() / totalCount else 0f
    }

    /** 累加阶段（微秒级，可忽略） */
    internal fun onProgress(processed: Int, total: Int, folder: String) {
        currentFolder = folder
    }

    internal fun finish(size: Long = -1L, tree: SizeTreeNode? = null) {
        isCalculating = false
        progress = 0f; currentFolder = ""
        scannedCount = 0; totalCount = 0
        cancelRequested = false; currentDb = null; saveDir = null; onDiscard = null
        binderCooldownSeconds = 0
        completedSize = size
        completedTree = tree
        statusMessage = if (size >= 0) {
            "已统计完成，大小: ${formatBytes(size)}"
        } else {
            "已统计完成"
        }
    }
}
