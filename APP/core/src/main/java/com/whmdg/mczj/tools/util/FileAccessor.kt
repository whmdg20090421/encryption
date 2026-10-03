package com.whmdg.mczj.tools.util

import android.content.Context
import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.security.ShellException
import com.whmdg.mczj.tools.security.ShellExecutor
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 递归扫描得到的一个条目（文件或目录）。
 * mtime 单位：毫秒。
 */
data class ScanEntry(
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long
)

/** 递归扫描结果。 */
sealed class ScanOutcome {
    /** 完整扫描成功。 */
    object Success : ScanOutcome()

    /** 部分成功：文件系统遍历返回了数据，但存在无法访问的子目录。 */
    object Partial : ScanOutcome()

    /** 失败：路径不存在或无权限。 */
    data class Failed(val message: String) : ScanOutcome()

    /** 已被取消。 */
    object Cancelled : ScanOutcome()
}

/**
 * 文件访问抽象层：屏蔽普通 / Shizuku / Root 三种通道差异。
 *
 * 三种通道统一通过 shell 执行递归扫描命令：
 *   - NORMAL   → [Permission.APPLICANT]（应用自身权限的 `sh` 进程）
 *   - SHIZUKU  → [Permission.ADB]（Shizuku）
 *   - ROOT     → [Permission.ROOT]
 *
 * 递归扫描用一条 `find -printf` 命令一次遍历整棵子树，避免逐目录多次
 * shell 往返；输出用 NUL 分帧、整段 base64 编码，使换行/制表等特殊字符
 * 都能无损通过按行读取的 shell 传输层。
 */
interface FileAccessor {

    /**
     * 递归扫描 [rootPath] 及其所有子项（含根自身），按批次流式回调。
     *
     * 命令：`find <root> -printf '%M\0%s\0%T@\0%P\0' | base64`
     *   - `%M`  ls 格式的类型+权限（首字符 `d`=目录、`l`=软链）
     *   - `%s`  字节大小
     *   - `%T@` 修改时间（Unix 秒.小数）
     *   - `%P`  相对 root 的路径（根自身为空串）
     *
     * @param cancelFlag 置 true 时尽快中断
     * @param onBatch 每积累一批条目回调一次
     */
    fun scanTree(
        rootPath: String,
        cancelFlag: AtomicBoolean? = null,
        onBatch: (List<ScanEntry>) -> Unit
    ): ScanOutcome

    companion object {
        fun create(level: FileAccessLevel, @Suppress("UNUSED_PARAMETER") context: Context): FileAccessor =
            ShellAccessor(level)
    }
}

private class ShellAccessor(
    level: FileAccessLevel
) : FileAccessor {

    private val permission: Permission = when (level) {
        FileAccessLevel.ROOT -> Permission.ROOT
        FileAccessLevel.SHIZUKU -> Permission.ADB
        FileAccessLevel.NORMAL -> Permission.APPLICANT
    }

    /** 每积累多少条扫描结果回调一次，平衡内存与回调频率。 */
    private val batchSize = 512

    override fun scanTree(
        rootPath: String,
        cancelFlag: AtomicBoolean?,
        onBatch: (List<ScanEntry>) -> Unit
    ): ScanOutcome {
        val normalizedRoot = if (rootPath == "/") "/" else rootPath.trimEnd('/').ifEmpty { "/" }
        val escaped = ShellEscape.escape(normalizedRoot)

        // find 的退出码经 RC 记录随数据带回；base64 保证输出全部为可安全按行读取的字符。
        // __RC__ 记录固定为 4 个 NUL 字段，与正常记录同构，解析时可识别。
        // find 的 stderr 重定向到 /dev/null：外层 shell 包装会把 stderr 并入 stdout，
        // 若不放行会污染 base64 流；退出码仍由 RC 记录体现。
        val command = "{ find $escaped -printf '%M\\0%s\\0%T@\\0%P\\0' 2>/dev/null; " +
            "printf '__RC__\\0%s\\0\\0\\0' \"\$?\"; } | /system/bin/base64"

        // 累积已解码字节，按 NUL 切分 token；每 4 个 token 组成一条记录。
        val accumulator = NulTokenAccumulator()
        val pending = ArrayList<ScanEntry>(batchSize)
        val fields = ArrayList<String>(4)
        var findExitCode = -1
        var totalEntries = 0

        fun flushBatch() {
            if (pending.isNotEmpty()) {
                onBatch(ArrayList(pending))
                pending.clear()
            }
        }

        // 处理一条 4 字段记录；返回 false 表示遇到 RC 记录。
        fun consumeRecord(type: String, sizeText: String, mtimeText: String, relPath: String): Boolean {
            if (type == "__RC__") {
                findExitCode = sizeText.trim().toIntOrNull() ?: -1
                return false
            }
            val isDir = type.startsWith("d")
            val size = sizeText.toLongOrNull() ?: 0L
            val mtime = (mtimeText.toDoubleOrNull()?.times(1000.0))?.toLong() ?: 0L
            val path = if (relPath.isEmpty()) normalizedRoot
            else if (normalizedRoot == "/") "/$relPath"
            else "$normalizedRoot/$relPath"
            pending.add(ScanEntry(path, isDir, if (isDir) 0L else size, mtime))
            totalEntries++
            if (pending.size >= batchSize) flushBatch()
            return true
        }

        val outcome: ScanOutcome = try {
            ShellExecutor.executeWithStdout(permission, command, { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@executeWithStdout
                val bytes = try {
                    Base64.getDecoder().decode(trimmed)
                } catch (_: IllegalArgumentException) {
                    return@executeWithStdout
                }
                accumulator.append(bytes)
                while (true) {
                    val token = accumulator.nextToken() ?: break
                    fields.add(token)
                    if (fields.size == 4) {
                        consumeRecord(fields[0], fields[1], fields[2], fields[3])
                        fields.clear()
                    }
                }
            }, cancelFlag)
            when {
                cancelFlag?.get() == true -> ScanOutcome.Cancelled
                findExitCode == 0 -> ScanOutcome.Success
                // 完全没有任何条目：路径不存在或无权限访问
                totalEntries == 0 -> ScanOutcome.Failed("无法访问路径或路径不存在")
                // 有数据但遍历中途遇到不可读子目录
                else -> ScanOutcome.Partial
            }
        } catch (e: ShellException) {
            if (cancelFlag?.get() == true) ScanOutcome.Cancelled
            else ScanOutcome.Failed(e.message ?: "扫描失败")
        } catch (e: Exception) {
            if (cancelFlag?.get() == true) ScanOutcome.Cancelled
            else ScanOutcome.Failed(e.message ?: "扫描异常")
        }

        flushBatch()
        return outcome
    }
}

/**
 * NUL 分隔 token 的增量累积器。
 * 追加解码后的字节块，按 0x00 切分并逐条取出 UTF-8 字符串。
 */
private class NulTokenAccumulator {
    private var data = ByteArray(8192)
    private var start = 0
    private var end = 0

    fun append(bytes: ByteArray) {
        ensureCapacity(end + bytes.size)
        System.arraycopy(bytes, 0, data, end, bytes.size)
        end += bytes.size
    }

    /** 取出下一个完整 token；无完整 token 时返回 null。 */
    fun nextToken(): String? {
        var i = start
        while (i < end) {
            if (data[i] == 0.toByte()) {
                val token = String(data, start, i - start, StandardCharsets.UTF_8)
                start = i + 1
                compactIfNeeded()
                return token
            }
            i++
        }
        return null
    }

    /** 已消费的前缀足够长时，把剩余字节搬回缓冲区头部，避免无限增长。 */
    private fun compactIfNeeded() {
        if (start > 8192 && start * 2 > end) {
            System.arraycopy(data, start, data, 0, end - start)
            end -= start
            start = 0
        }
    }

    private fun ensureCapacity(required: Int) {
        if (required <= data.size) return
        var newSize = data.size
        while (newSize < required) newSize *= 2
        val copy = ByteArray(newSize)
        System.arraycopy(data, 0, copy, 0, end)
        data = copy
    }
}
