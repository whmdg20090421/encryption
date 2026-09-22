package com.whmdg.mczj.tools.fileop

/**
 * 传输速率统计器：最近 1 秒滑动窗口，窗口按 [BUCKET_MS] 切成若干桶。
 *
 * 用法：每次进度刷新时调用 [sample] 传入"累计已处理字节"，它会：
 * 1. 把相对上次采样的增量记入当前 0.1 秒桶；
 * 2. 丢弃窗口外的过期桶；
 * 3. 返回窗口内所有桶之和，即最近约 1 秒的字节数。
 *
 * 约定返回值为"每 1 秒的字节数"（即使窗口尚未填满也按 1 秒口径折算，便于显示稳定），
 * 因此刚启动时数值会偏小，随着窗口填满趋于真实速率。
 *
 * 线程安全：内部对状态加锁，可被多通道并发调用。
 */
internal class TransferSpeedMeter(
    private val windowMs: Long = 1000L,
    private val bucketMs: Long = 100L
) {
    private val lock = Any()

    /** 每个桶的结束时间戳（毫秒）。 */
    private val bucketTimes = LongArray((windowMs / bucketMs).toInt().coerceAtLeast(1))
    /** 每个桶累计的字节数，与 [bucketTimes] 一一对应（环形缓冲）。 */
    private val bucketBytes = LongArray(bucketTimes.size)

    private var lastTotalBytes = 0L
    private var started = false

    /**
     * 用当前累计字节 [totalBytes] 刷新一次采样，返回最近窗口的速率（字节/秒）。
     * 若尚未产生新的桶（同一 [bucketMs] 内重复调用），返回上一次窗口和，避免抖动。
     */
    fun sample(totalBytes: Long): Long = synchronized(lock) {
        val now = System.currentTimeMillis()
        if (!started) {
            // 首次采样只建立基准，避免把"从 0 到当前"的历史量误计入速率
            lastTotalBytes = totalBytes
            started = true
            return@synchronized 0L
        }

        val delta = totalBytes - lastTotalBytes
        lastTotalBytes = totalBytes

        val bucketIndex = ((now / bucketMs) % bucketTimes.size).toInt()
        if (bucketTimes[bucketIndex] != now / bucketMs) {
            // 进入新桶：先清空该槽位（它是一圈之前的数据）
            bucketTimes[bucketIndex] = now / bucketMs
            bucketBytes[bucketIndex] = 0L
        }
        if (delta > 0) bucketBytes[bucketIndex] += delta

        // 丢弃窗口外（早于 now - windowMs）的桶后求和
        var sum = 0L
        val cutoff = now - windowMs
        for (i in bucketTimes.indices) {
            if (bucketTimes[i] * bucketMs >= cutoff) sum += bucketBytes[i]
        }
        sum
    }

    /** 复位，用于重新开始统计（如切换阶段）。 */
    fun reset() = synchronized(lock) {
        bucketTimes.fill(0L)
        bucketBytes.fill(0L)
        lastTotalBytes = 0L
        started = false
    }
}
