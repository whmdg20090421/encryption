package com.whmdg.mczj.tools.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log

import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * Shizuku 授权工具类。
 *
 * 采用直连 Shizuku binder + `IShizukuService.newProcess` 的方式执行特权 shell 命令
 * （与 Operit 一致），不使用 UserService。进程以 Shizuku 身份（ADB 后端 uid 2000，
 * Root 后端 uid 0）运行，等价 ADB 能力。
 *
 * 无阻塞式绑定：`Shizuku.getBinder()` 只读取 ShizukuProvider 注入的缓存字段（0 IPC）。
 * 每次命令仅一次 `newProcess` IPC，全部在调用方线程（IO）执行，不阻塞主线程。
 */
object ShizukuAuthorizer {
    private const val TAG = "ShizukuAuthorizer"
    private const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"

    private var binderReceivedListenerRegistered = false
    private var lastError = ""

    /** 缓存的 IShizukuService。 */
    @Volatile
    private var serviceCache: IShizukuService? = null

    /**
     * 初始化 Shizuku binder 监听。应在 Application.onCreate 或首次使用前调用。
     * @param context Application 或 Activity context
     */
    fun initialize(context: Context? = null) {
        if (binderReceivedListenerRegistered) return

        try {
            Shizuku.addBinderReceivedListener {
                lastError = ""
            }
            Shizuku.addBinderDeadListener {
                lastError = "Shizuku binder 已断开"
                serviceCache = null
            }
            binderReceivedListenerRegistered = true
        } catch (e: Exception) {
            lastError = "初始化失败: ${e.message}"
        }
    }

    /**
     * 检查 Shizuku 是否已安装。
     */
    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            isShizukuServiceRunning()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检查 Shizuku 服务是否正在运行（binder 存活）。
     */
    fun isShizukuServiceRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检查应用是否已被 Shizuku 授权。
     */
    fun hasShizukuPermission(): Boolean {
        return try {
            if (!isShizukuServiceRunning()) {
                lastError = "Shizuku 服务未运行"
                return false
            }
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            lastError = "权限检查失败: ${e.message}"
            false
        }
    }

    /**
     * Shizuku 服务是否已启动且本应用已获授权。
     */
    fun isAvailable(): Boolean {
        return isShizukuServiceRunning() && hasShizukuPermission()
    }

    /**
     * 请求 Shizuku 权限。
     */
    fun requestShizukuPermission(onResult: (Boolean) -> Unit) {
        if (!isShizukuServiceRunning()) {
            lastError = "Shizuku 服务未运行"
            onResult(false)
            return
        }

        if (hasShizukuPermission()) {
            onResult(true)
            return
        }

        try {
            val requestCode = 100
            Shizuku.addRequestPermissionResultListener { code, grantResult ->
                if (code == requestCode) {
                    val granted = grantResult == PackageManager.PERMISSION_GRANTED
                    onResult(granted)
                    Shizuku.removeRequestPermissionResultListener { _, _ -> }
                }
            }
            Shizuku.requestPermission(requestCode)
        } catch (e: Exception) {
            lastError = "请求权限失败: ${e.message}"
            onResult(false)
        }
    }

    /**
     * 获取（或缓存）IShizukuService 接口。
     * 仅当 binder 存活且权限已授予时返回非空。
     */
    private fun getService(): IShizukuService? {
        val cached = serviceCache
        if (cached != null) {
            val alive = try {
                cached.asBinder().pingBinder()
            } catch (_: Exception) {
                false
            }
            if (alive) return cached
            serviceCache = null
        }

        if (!isAvailable()) return null

        return try {
            val binder: IBinder = Shizuku.getBinder() ?: return null
            if (!binder.isBinderAlive) return null
            val service = IShizukuService.Stub.asInterface(binder) ?: return null
            serviceCache = service
            service
        } catch (e: Exception) {
            Log.e(TAG, "获取 IShizukuService 失败: ${e.message}", e)
            lastError = "获取 Shizuku 服务失败: ${e.message}"
            null
        }
    }

    /**
     * 通过 Shizuku `newProcess` 同步执行命令，返回 (stdout, stderr, exitCode)。
     * 需在 IO 线程调用。
     * @throws ShizukuUnavailableException 服务不可用时抛出，由调用方决定回退
     */
    fun executeCommand(command: String): Triple<String, String, Int> {
        val service = getService() ?: throw ShizukuUnavailableException(
            lastError.ifBlank { "Shizuku 服务不可用" }
        )

        val process = try {
            service.newProcess(arrayOf("sh", "-c", command), null, null)
        } catch (e: Exception) {
            throw ShizukuUnavailableException("newProcess 失败: ${e.message}")
        } ?: throw ShizukuUnavailableException("Shizuku newProcess 返回空")

        // 并发读取 stdout / stderr，避免单流填满管道导致进程阻塞
        try {
            val outReader = readAsync(process.inputStream)
            val errReader = readAsync(process.errorStream)
            val exitCode = process.waitFor()
            return Triple(outReader.get(), errReader.get(), exitCode)
        } catch (e: ShizukuUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw ShizukuUnavailableException("执行失败: ${e.message}")
        } finally {
            try { process.destroy() } catch (_: Exception) {}
        }
    }

    /**
     * 通过 Shizuku `newProcess` 流式执行命令，逐行回调 stdout（或 stderr）。
     * 需在 IO 线程调用。
     * @param useStderr true 时回调 stderr，false 时回调 stdout
     * @param cancelFlag 为 true 时中断读取
     * @param onOutputLine 每行输出回调
     * @return exitCode
     */
    fun executeStreaming(
        command: String,
        useStderr: Boolean,
        cancelFlag: java.util.concurrent.atomic.AtomicBoolean?,
        onOutputLine: (String) -> Unit
    ): Int {
        val service = getService() ?: throw ShizukuUnavailableException(
            lastError.ifBlank { "Shizuku 服务不可用" }
        )

        val process = try {
            service.newProcess(arrayOf("sh", "-c", command), null, null)
        } catch (e: Exception) {
            throw ShizukuUnavailableException("newProcess 失败: ${e.message}")
        } ?: throw ShizukuUnavailableException("Shizuku newProcess 返回空")

        return try {
            val pfd = if (useStderr) process.errorStream else process.inputStream
            pfdAutoCloseInputStream(pfd).bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (cancelFlag?.get() == true) break
                    onOutputLine(line!!)
                }
            }
            process.waitFor()
        } catch (e: Exception) {
            throw ShizukuUnavailableException("流式执行失败: ${e.message}")
        } finally {
            try { process.destroy() } catch (_: Exception) {}
        }
    }

    /**
     * 以提升权限打开文件用于读取，返回 PFD 给调用方。
     *
     * 通过 `newProcess(["cat", path])` 读取，并由本地中继线程把 cat 的 stdout
     * 泵入一个 pipe 的写端；调用方拿 pipe 的读端。
     * 中继线程持有 IRemoteProcess 引用，保证复制期间进程不被回收；
     * 调用方关闭读端 → 中继写端 EPIPE → 线程退出并 destroy 进程。
     */
    fun openForRead(path: String): ParcelFileDescriptor? {
        val service = getService() ?: return null
        val process = try {
            service.newProcess(arrayOf("cat", path), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "openForRead 失败: ${e.message}")
            null
        } ?: return null

        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        Thread {
            try {
                pfdAutoCloseInputStream(process.inputStream).use { src ->
                    ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { dst ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            dst.write(buf, 0, n)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { writeEnd.close() } catch (_: Exception) {}
                try { process.destroy() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; name = "shizuku-relay-read" }.start()
        return readEnd
    }

    /**
     * 以提升权限打开/创建文件用于写入，返回 PFD 给调用方。
     *
     * 调用方拿 pipe 的写端；本地中继线程读 pipe 读端并泵入
     * `newProcess(["sh","-c","cat > path"])` 的 stdin。
     * 调用方关闭写端 → 中继读到 EOF → 关闭 cat stdin → 线程退出并 destroy 进程。
     */
    fun openForWrite(path: String): ParcelFileDescriptor? {
        val service = getService() ?: return null
        val escaped = com.whmdg.mczj.tools.util.ShellEscape.escape(path)
        val process = try {
            service.newProcess(arrayOf("sh", "-c", "cat > $escaped"), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "openForWrite 失败: ${e.message}")
            null
        } ?: return null

        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { src ->
                    val dst = android.os.ParcelFileDescriptor.AutoCloseOutputStream(process.outputStream)
                    try {
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            dst.write(buf, 0, n)
                        }
                        dst.flush()
                    } finally {
                        try { dst.close() } catch (_: Exception) {}
                    }
                    try { process.waitFor() } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            } finally {
                try { readEnd.close() } catch (_: Exception) {}
                try { process.destroy() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; name = "shizuku-relay-write" }.start()
        return writeEnd
    }

    fun getLastError(): String = lastError

    /**
     * 采集 Shizuku 侧诊断信息（多行文本），供权限回退弹窗展示。
     * 包括：服务是否运行、是否已授权、binder 是否存活、Shizuku 后端 UID、
     * 以及用 Shizuku 通道执行 `id` 的原始结果。
     */
    fun diagnose(): String = buildString {
        appendLine("Shizuku 状态：")
        appendLine("  - 服务运行（pingBinder）: ${isShizukuServiceRunning()}")
        appendLine("  - 已授权（checkSelfPermission）: ${hasShizukuPermission()}")
        val binder = try { Shizuku.getBinder() } catch (_: Exception) { null }
        appendLine("  - binder 存活: ${binder?.isBinderAlive == true}")
        appendLine("  - Shizuku 后端 UID: ${try { Shizuku.getUid() } catch (e: Exception) { "读取失败: ${e.message}" }}")
        appendLine("  - 最近错误: ${lastError.ifBlank { "无" }}")
        appendLine()
        appendLine("Shizuku 通道执行 id 探针：")
        append(
            try {
                val (out, err, code) = executeCommand("id")
                "  - 退出码: $code\n  - stdout: ${out.trim().ifBlank { "（空）" }}\n  - stderr: ${err.trim().ifBlank { "（空）" }}"
            } catch (e: Exception) {
                "  - 探针失败: ${e.message}"
            }
        )
    }

    /** 在独立线程读取 PFD 全文，返回携带结果的 Future；pfd 为空时返回空串。 */
    private fun readAsync(pfd: ParcelFileDescriptor?): java.util.concurrent.Future<String> {
        val future = java.util.concurrent.FutureTask {
            if (pfd == null) "" else try {
                pfdAutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                ""
            }
        }
        Thread(future, "shizuku-read").apply { isDaemon = true }.start()
        return future
    }

    private fun pfdAutoCloseInputStream(pfd: ParcelFileDescriptor) =
        ParcelFileDescriptor.AutoCloseInputStream(pfd)
}

/**
 * Shizuku 服务不可用（binder 缺失 / 无权限 / newProcess 失败）。
 * 由 ShellDaemon 捕获后回退到应用自身权限执行。
 */
class ShizukuUnavailableException(message: String) : Exception(message)
