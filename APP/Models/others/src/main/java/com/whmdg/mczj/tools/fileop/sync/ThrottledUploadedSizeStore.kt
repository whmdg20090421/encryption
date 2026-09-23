package com.whmdg.mczj.tools.fileop.sync

/**
 * 上传已写字节的落库节流器。
 *
 * 上传循环每收到一个数据块都会即时回调进度，但若同步地把 `uploaded_size` 写进 SQLite，
 * 每次写入都会独立提交事务；并发上传时多个协程争抢同一写锁，会把上传循环拖慢到数秒一次，
 * 表现为进度条长时间不动后突然跳跃。
 *
 * 本类把落库按时间合并：距上次写入不足 [throttleMs] 的调用直接跳过，仅在超出间隔或显式
 * [flush] 时写入。进度回调本身不受影响——调用方仍应按块即时回调 UI。
 *
 * 线程安全：每个上传任务实例只由其所属的上传协程访问，无需额外加锁。
 */
class ThrottledUploadedSizeStore(
    private val throttleMs: Long = DEFAULT_THROTTLE_MS,
    private val write: (written: Long) -> Unit
) {
    private var lastWriteMs = 0L

    /**
     * 报告当前累计已写字节数。未达节流间隔时不落库，等待后续调用或 [flush]。
     */
    fun onBytesWritten(written: Long) {
        val now = System.currentTimeMillis()
        if (now - lastWriteMs >= throttleMs) {
            persist(written)
        }
    }

    /**
     * 强制落库。上传结束（成功或终止）时必须调用，确保 DB 中的 `uploaded_size` 为最终值。
     */
    fun flush(written: Long) {
        persist(written)
    }

    private fun persist(written: Long) {
        write(written)
        lastWriteMs = System.currentTimeMillis()
    }

    companion object {
        /** 落库节流间隔：与 UI 帧率无关，仅用于合并 DB 写入。 */
        const val DEFAULT_THROTTLE_MS = 250L
    }
}
