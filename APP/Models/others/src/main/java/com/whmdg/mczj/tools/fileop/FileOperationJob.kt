package com.whmdg.mczj.tools.fileop

import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean
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
     */
    protected fun scanWithProgress(
        sources: List<String>,
        onFileScanned: (totalBytesSoFar: Long) -> Unit = {}
    ): ScanInfo {
        var fileCount = 0
        var totalBytes = 0L

        for (source in sources) {
            throwIfCancelled()
            val file = File(source)
            if (!file.exists()) continue

            if (file.isDirectory) {
                val stack = ArrayDeque<File>()
                stack.add(file)
                while (stack.isNotEmpty()) {
                    throwIfCancelled()
                    val f = stack.removeLast()
                    if (f.isDirectory) {
                        f.listFiles()?.forEach { stack.add(it) }
                    } else {
                        fileCount++
                        totalBytes += f.length()
                        onFileScanned(totalBytes)
                    }
                }
            } else {
                fileCount++
                totalBytes += file.length()
                onFileScanned(totalBytes)
            }
        }

        return ScanInfo(fileCount, totalBytes)
    }
}
