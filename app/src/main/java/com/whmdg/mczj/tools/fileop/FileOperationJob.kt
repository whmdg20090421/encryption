package com.whmdg.mczj.tools.fileop

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
}
