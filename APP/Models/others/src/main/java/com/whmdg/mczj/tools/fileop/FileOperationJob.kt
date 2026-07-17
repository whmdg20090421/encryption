package com.whmdg.mczj.tools.fileop

import android.os.ParcelFileDescriptor
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * 文件操作任务基类。
 *
 * 子类实现 [run] 完成具体操作（复制/移动/删除）。
 * 通过 [throwIfCancelled] 在关键节点检查取消标志。
 */
abstract class FileOperationJob(val id: Int = Random.nextInt()) {

    lateinit var operator: FileOperator
    val cancelFlag = AtomicBoolean(false)

    /** 执行此任务的线程，用于强制取消时 interrupt */
    @Volatile
    var workerThread: Thread? = null

    /** 当前正在复制的源/目标 PFD，用于强制取消时关闭 fd 中断 I/O */
    @Volatile
    private var currentSrcPfd: ParcelFileDescriptor? = null
    @Volatile
    private var currentDstPfd: ParcelFileDescriptor? = null

    /** 注册当前复制的 PFD（由 ShellFileOperator.copyBetweenPfds 调用） */
    fun setCurrentPfds(src: ParcelFileDescriptor?, dst: ParcelFileDescriptor?) {
        currentSrcPfd = src
        currentDstPfd = dst
    }

    /**
     * 强制取消：设标志 + interrupt 线程 + 关闭 PFD。
     * - interrupt 中断 Java Stream 阻塞读写 + 扫描阶段的 shell 调用
     * - close PFD 中断 Os.read/write 的 native 阻塞
     * 关闭已关闭的 PFD 是 no-op，不会崩。
     */
    fun cancelHard() {
        cancelFlag.set(true)
        workerThread?.interrupt()
        currentSrcPfd?.close()
        currentSrcPfd = null
        currentDstPfd?.close()
        currentDstPfd = null
    }

    /** 最后一次进度更新时间戳（epoch millis），用于超时检测 */
    private val lastActivityTime = AtomicLong(System.currentTimeMillis())

    /** 记录当前正在执行的步骤描述 */
    @Volatile
    var currentStep: String = ""

    /** 更新活动时间戳，在每次 I/O 操作后调用 */
    protected fun heartbeat() {
        lastActivityTime.set(System.currentTimeMillis())
    }

    /** 获取距离上次活动的毫秒数 */
    fun millisSinceLastActivity(): Long = System.currentTimeMillis() - lastActivityTime.get()

    /** 由子类实现的具体操作逻辑。在线程池中执行。 */
    @Throws(Exception::class)
    abstract fun run()

    /** 检查取消标志，已取消则抛出 [InterruptedIOException]。 */
    @Throws(InterruptedIOException::class)
    protected fun throwIfCancelled() {
        if (cancelFlag.get()) {
            throw InterruptedIOException("操作已取消")
        }
    }

    /** 优雅取消检查：不抛异常，仅返回是否已请求取消。 */
    fun isGracefulCancelled(): Boolean = cancelFlag.get()

    /**
     * 扫描源文件列表，统计总文件数和总大小。
     * 每累加一个文件后回调 [onFileScanned] 通知当前总字节数。
     * 全部通过 operator（ShellExecutor Permission.MAX）执行，不使用 Java File API。
     */
    protected fun scanWithProgress(
        sources: List<String>,
        onFileScanned: (totalBytesSoFar: Long) -> Unit = {}
    ): ScanInfo {
        var fileCount = 0
        var totalBytes = 0L

        for (source in sources) {
            throwIfCancelled()
            heartbeat()
            if (!operator.exists(source)) continue

            if (operator.isDirectory(source)) {
                val stack = ArrayDeque<String>()
                stack.add(source)
                while (stack.isNotEmpty()) {
                    throwIfCancelled()
                    heartbeat()
                    val dir = stack.removeLast()
                    val children = operator.listChildren(dir) ?: continue
                    for (child in children) {
                        if (child.isDir) {
                            stack.add(child.path)
                        } else {
                            fileCount++
                            totalBytes += operator.fileSize(child.path)
                            onFileScanned(totalBytes)
                        }
                    }
                }
            } else {
                fileCount++
                totalBytes += operator.fileSize(source)
                onFileScanned(totalBytes)
            }
        }

        return ScanInfo(fileCount, totalBytes)
    }
}
