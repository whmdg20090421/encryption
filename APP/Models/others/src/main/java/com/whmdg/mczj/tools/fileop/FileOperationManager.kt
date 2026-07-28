package com.whmdg.mczj.tools.fileop

import android.content.Context
import com.whmdg.mczj.tools.util.FileAccessLevel
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 文件操作全局管理器，UI 层与 Job 线程的桥梁。
 *
 * - [progress]: 进度状态，Compose 弹窗观察此 StateFlow
 * - [conflictRequest] / [errorRequest]: 冲突/错误请求，触发 Compose 弹窗
 * - [resolveConflict] / [resolveError]: Job 线程调用，suspend 等待用户选择
 */
object FileOperationManager {

    // ── 进度 ──
    private val _progress = MutableStateFlow<FileOpProgress?>(null)
    val progress: StateFlow<FileOpProgress?> = _progress

    // ── 冲突请求 ──
    private val _conflictRequest = MutableStateFlow<ConflictRequest?>(null)
    val conflictRequest: StateFlow<ConflictRequest?> = _conflictRequest

    private var conflictContinuation: CancellableContinuation<ConflictResult>? = null

    // ── 错误请求 ──
    private val _errorRequest = MutableStateFlow<ErrorRequest?>(null)
    val errorRequest: StateFlow<ErrorRequest?> = _errorRequest

    private var errorContinuation: CancellableContinuation<ErrorResult>? = null

    // ── 刷新回调 ──
    private var _onRefreshNeeded: (() -> Unit)? = null

    /** 设置操作完成后的刷新回调（由 FileManagerScreen 注册） */
    fun setRefreshCallback(callback: (() -> Unit)?) {
        _onRefreshNeeded = callback
    }

    fun notifyRefreshNeeded() {
        _onRefreshNeeded?.invoke()
    }

    // ── 进度更新（由 Job 调用） ──
    fun updateProgress(progress: FileOpProgress?) {
        _progress.value = progress
    }

    // ── 冲突解决（Job 线程 suspend 等待用户选择） ──
    suspend fun resolveConflict(request: ConflictRequest): ConflictResult {
        return suspendCancellableCoroutine { cont ->
            conflictContinuation = cont
            _conflictRequest.value = request
            cont.invokeOnCancellation {
                conflictContinuation = null
                _conflictRequest.value = null
            }
        }
    }

    /** UI 层调用：用户做出冲突选择后 */
    fun onConflictResolved(result: ConflictResult) {
        conflictContinuation?.resumeWith(Result.success(result))
        conflictContinuation = null
        _conflictRequest.value = null
    }

    // ── 错误解决（Job 线程 suspend 等待用户选择） ──
    suspend fun resolveError(request: ErrorRequest): ErrorResult {
        return suspendCancellableCoroutine { cont ->
            errorContinuation = cont
            _errorRequest.value = request
            cont.invokeOnCancellation {
                errorContinuation = null
                _errorRequest.value = null
            }
        }
    }

    /** UI 层调用：用户做出错误选择后 */
    fun onErrorResolved(result: ErrorResult) {
        errorContinuation?.resumeWith(Result.success(result))
        errorContinuation = null
        _errorRequest.value = null
    }

    // ── 提交任务 ──
    fun copy(
        sources: List<String>,
        targetDir: String,
        accessLevel: FileAccessLevel,
        context: Context,
        debugMode: Boolean = false
    ) {
        FileOpDiagnostics.clear()
        FileOpDiagnostics.setEnabled(debugMode)
        _progress.value = FileOpProgress(phase = "正在复制", currentBytes = 0, totalBytes = 0, isScanning = true)
        val operator = FileOperator.create(accessLevel)
        val job = CopyJob(CopyPurpose.COPY, sources, targetDir, this).apply {
            this.operator = operator
        }
        FileOperationService.submit(job, context)
    }

    fun move(
        sources: List<String>,
        targetDir: String,
        accessLevel: FileAccessLevel,
        context: Context,
        debugMode: Boolean = false
    ) {
        FileOpDiagnostics.clear()
        FileOpDiagnostics.setEnabled(debugMode)
        _progress.value = FileOpProgress(phase = "正在移动", currentBytes = 0, totalBytes = 0, isScanning = true)
        val operator = FileOperator.create(accessLevel)
        val job = CopyJob(CopyPurpose.MOVE, sources, targetDir, this).apply {
            this.operator = operator
        }
        FileOperationService.submit(job, context)
    }

    fun delete(
        entries: List<DeleteEntry>,
        toRecycleBin: Boolean,
        accessLevel: FileAccessLevel,
        context: Context
    ) {
        _progress.value = FileOpProgress(phase = "正在删除", currentBytes = 0, totalBytes = 0, isScanning = true)
        val operator = FileOperator.create(accessLevel)
        val job = DeleteJob(entries, toRecycleBin, this, context).apply {
            this.operator = operator
        }
        FileOperationService.submit(job, context)
    }

    fun cancelJob(id: Int) {
        FileOperationService.cancelJob(id)
        _progress.value = null
        _conflictRequest.value = null
        _errorRequest.value = null
    }

    /** 强制取消：设 cancelFlag + 关闭当前 PFD 中断 I/O。任务 finally 自行清理。 */
    fun cancelHard() {
        FileOperationService.cancelHardAll()
    }

    /** 取消所有正在运行的任务 */
    fun cancelAll() {
        FileOperationService.cancelAll()
        _progress.value = null
        _conflictRequest.value = null
        _errorRequest.value = null
    }
}

// ── 进度数据类（与 ViewModel.FileOpProgress 结构一致） ──

data class FileOpProgress(
    val phase: String,
    val currentBytes: Long,
    val totalBytes: Long,
    val currentFileName: String = "",
    val isRunning: Boolean = true,
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val isScanning: Boolean = false
) {
    val fraction: Float get() =
        if (totalBytes > 0) currentBytes.toFloat() / totalBytes else 0f
}

// ── 冲突/错误模型 ──

enum class ConflictAction { REPLACE, RENAME, SKIP, CANCEL }

data class ConflictRequest(
    val sourceName: String,
    val targetName: String,
    val isDirectory: Boolean,
    val sourceSize: Long,
    val targetSize: Long,
    val sourceModifiedTime: Long,
    val targetModifiedTime: Long
)

data class ConflictResult(val action: ConflictAction, val newName: String? = null)

enum class ErrorAction { RETRY, SKIP, SKIP_ALL, CANCEL }

data class ErrorRequest(val fileName: String, val errorMessage: String, val detailMessage: String = "")

data class ErrorResult(val action: ErrorAction)

data class DeleteEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L
)
