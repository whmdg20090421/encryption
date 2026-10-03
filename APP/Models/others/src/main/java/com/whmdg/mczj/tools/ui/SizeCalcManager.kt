package com.whmdg.mczj.tools.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.whmdg.mczj.tools.encryption.data.FolderSizeStaging
import com.whmdg.mczj.tools.encryption.data.FolderSizeStore
import com.whmdg.mczj.tools.util.FormatUtils.formatBytes
import com.whmdg.mczj.tools.util.SizeTreeNode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 文件夹大小统计进度管理（全局单例）。
 * FolderSizeCalculator 写入进度，MainAppContainer 读取并显示进度条。
 *
 * 状态流转：begin → onScanned×N → finish
 *   - begin: "正在扫描目录..."
 *   - onScanned: 实时更新已扫描条目数
 *   - finish: "已统计完成，大小: XXX"（持久显示，直到下次 begin 或用户关闭）
 *
 * 单条 find 递归扫描无法预先得知总条目数，故不显示百分比进度，只显示已扫描数量。
 */
object SizeCalcManager {
    /** 当前正在处理的路径 */
    var currentFolder by mutableStateOf("")
        set
    /** 已扫描的条目数（文件+目录） */
    var scannedCount by mutableIntStateOf(0)
        set
    /** 是否正在计算（从 begin 到 finish） */
    var isCalculating by mutableStateOf(false)
        private set

    /** 状态提示（持久显示：正在统计/完成/报错） */
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

    /** 是否弹出"保存进度？"对话框 */
    var pendingSaveDialog by mutableStateOf(false)
        set

    /** 用户请求取消 */
    @Volatile
    var cancelRequested = false
        private set

    /** 供 ShellExecutor 使用的取消标志（与 cancelRequested 同步） */
    val cancelFlag = AtomicBoolean(false)

    /** 当前统计的内存暂存区，用户确认后由 [save] 提交 */
    private var currentStaging: FolderSizeStaging? = null
    /** 本次统计是否为完整快照（决定提交时是否删除消失项） */
    private var currentFullSnapshot = false
    /** 丢弃回调（由 FileManagerViewModel 注册，用于刷新面板） */
    private var onDiscard: (() -> Unit)? = null

    fun requestCancel() { cancelRequested = true; cancelFlag.set(true) }

    /**
     * 用户点击"保存"：将当前已统计的结果提交落库。
     * 暂存区不清空——统计若随后中断，错误弹窗的「保存」仍能提交同一份数据。
     */
    fun save() {
        currentStaging?.let { FolderSizeStore.commitStaging(it, deleteMissing = currentFullSnapshot) }
    }

    /** 错误弹窗：用户选择保存已统计的部分结果 */
    fun confirmSavePartial() {
        currentStaging?.let { FolderSizeStore.commitStaging(it, deleteMissing = currentFullSnapshot) }
        clearStaging()
        pendingSaveDialog = false
    }

    /** 错误弹窗：用户选择丢弃本次数据 */
    fun discardPartial() {
        val discard = onDiscard
        clearStaging()
        pendingSaveDialog = false
        discard?.invoke()
    }

    private fun clearStaging() {
        currentStaging = null
        currentFullSnapshot = false
        onDiscard = null
    }

    /** 统计正常完成或取消后调用，释放暂存区引用（不再参与后续保存/丢弃）。 */
    internal fun releaseStaging() = clearStaging()

    /** 标记本次统计是否为完整快照（提交时决定是否删除消失项）。 */
    internal fun setFullSnapshot(full: Boolean) { currentFullSnapshot = full }

    /** 关闭状态提示 */
    fun dismissStatus() { statusMessage = null; completedSize = -1L; completedTree = null }

    internal fun begin(staging: FolderSizeStaging, onDiscard: (() -> Unit)? = null) {
        currentStaging = staging
        currentFullSnapshot = false
        this.onDiscard = onDiscard
        currentFolder = ""
        scannedCount = 0
        cancelRequested = false; cancelFlag.set(false); loadError = null
        completedSize = -1L
        completedTree = null
        pendingSaveDialog = false
        isCalculating = true
        statusMessage = "正在扫描目录..."
    }

    /** 扫描阶段：每积累一批条目调用，展示已扫描数量 */
    internal fun onScanned(count: Int, path: String) {
        scannedCount = count
        currentFolder = path
        // 已有扫描数据后切换到「已扫描 N 个条目」显示
        statusMessage = null
    }

    /**
     * 结束本次统计。**不清空暂存区**——统计中断时需保留暂存数据，
     * 供随后的「保存/丢弃」弹窗使用；暂存区由 [begin] 或 [clearStaging] 释放。
     */
    internal fun finish(size: Long = -1L, tree: SizeTreeNode? = null) {
        isCalculating = false
        currentFolder = ""
        scannedCount = 0
        cancelRequested = false; cancelFlag.set(false)
        completedSize = size
        completedTree = tree
        statusMessage = if (size >= 0) {
            "已统计完成，大小: ${formatBytes(size)}"
        } else {
            "已统计完成"
        }
    }
}
