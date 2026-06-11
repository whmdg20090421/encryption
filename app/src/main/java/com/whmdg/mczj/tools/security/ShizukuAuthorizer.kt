package com.whmdg.mczj.tools.security

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast

import rikka.shizuku.Shizuku

/**
 * Shizuku 授权工具类
 * 使用 UserService 模式执行特权 shell 命令（已移除已废弃的 newProcess）。
 * UserService 断开时自动同步重连，重连失败返回错误。
 */
object ShizukuAuthorizer {
    private const val TAG = "ShizukuAuthorizer"
    private const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var binderReceivedListenerRegistered = false
    private var isServiceAvailable = false
    private var lastError = ""

    // UserService 相关
    private var shellService: IShellService? = null
    private var isBinding = false
    private val bindLock = Object()
    private var appContext: Context? = null
    private var hasShownConnectToast = false
    private var bindStartTime = 0L
    private val pendingCallbacks = mutableListOf<(Boolean) -> Unit>()

    private fun getUserServiceArgs(): Shizuku.UserServiceArgs {
        val ctx = appContext ?: throw IllegalStateException("ShizukuAuthorizer 未初始化，appContext 为空")
        return Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, ShellService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shell_service")
            .version(1)
    }

    /**
     * 初始化 Shizuku 绑定监听。
     * 应在 Application.onCreate 或首次使用前调用。
     * @param context Application 或 Activity context，用于 Toast 显示
     */
    fun initialize(context: Context? = null) {
        if (context != null) appContext = context.applicationContext

        if (binderReceivedListenerRegistered) return

        try {
            Shizuku.addBinderReceivedListener {
                isServiceAvailable = true
                lastError = ""
            }
            Shizuku.addBinderDeadListener {
                isServiceAvailable = false
                lastError = "Shizuku binder 已断开"
                synchronized(bindLock) {
                    shellService = null
                    isBinding = false
                }
            }
            binderReceivedListenerRegistered = true

            // 检查是否已在运行
            if (isShizukuServiceRunning()) {
                isServiceAvailable = true
            }
        } catch (e: Exception) {
            lastError = "初始化失败: ${e.message}"
        }
    }

    /**
     * 异步绑定 UserService，不阻塞主线程。
     * 如果已绑定则立即回调 true。
     */
    fun ensureBound(callback: (Boolean) -> Unit) {
        synchronized(bindLock) {
            if (shellService != null) {
                callback(true)
                return
            }
            pendingCallbacks.add(callback)
            if (isBinding) return // 已在绑定中，等待结果
            isBinding = true
            bindStartTime = System.currentTimeMillis()
        }

        try {
            Shizuku.bindUserService(getUserServiceArgs(), object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val elapsed = System.currentTimeMillis() - bindStartTime
                    val service = IShellService.Stub.asInterface(binder)
                    synchronized(bindLock) {
                        shellService = service
                        isBinding = false
                        pendingCallbacks.forEach { it(true) }
                        pendingCallbacks.clear()
                    }

                    Log.i(TAG, "UserService 已连接，用时${elapsed}ms")

                    // 首次连接 Toast 提示
                    if (!hasShownConnectToast) {
                        hasShownConnectToast = true
                        val timeStr = if (elapsed >= 2000) {
                            "%.1f秒".format(elapsed / 1000.0)
                        } else {
                            "${elapsed}毫秒"
                        }
                        val ctx = appContext
                        if (ctx != null) {
                            mainHandler.post {
                                Toast.makeText(
                                    ctx,
                                    "Shizuku UserService 已连接，用时$timeStr",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }

                    // 监听 binder 死亡
                    try {
                        binder.linkToDeath({
                            synchronized(bindLock) {
                                shellService = null
                                Log.w(TAG, "UserService binder 已死亡")
                            }
                        }, 0)
                    } catch (e: Exception) {
                        Log.w(TAG, "linkToDeath 失败: ${e.message}")
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    synchronized(bindLock) {
                        shellService = null
                        isBinding = false
                    }
                    Log.w(TAG, "UserService 已断开")
                }
            })
        } catch (e: Exception) {
            synchronized(bindLock) {
                isBinding = false
                pendingCallbacks.forEach { it(false) }
                pendingCallbacks.clear()
            }
            lastError = "绑定 UserService 失败: ${e.message}"
            Log.e(TAG, lastError, e)
        }
    }

    /**
     * 检查 Shizuku 是否已安装
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
     * 检查 Shizuku 服务是否正在运行
     */
    fun isShizukuServiceRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检查应用是否已被 Shizuku 授权
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
     * 请求 Shizuku 权限
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
     * 通过 Shizuku 执行 shell 命令（仅 UserService 模式）。
     * 首次调用异步绑定 UserService；断连后自动短超时重连（200ms × 3 次）。
     * @return Triple(stdout, stderr, exitCode)
     */
    fun executeCommand(command: String): Triple<String, String, Int> {
        // 快速路径：UserService 已就绪
        val service = shellService
        if (service != null) {
            try {
                val rawResult = service.execute(command)
                return parseResult(rawResult)
            } catch (e: Exception) {
                synchronized(bindLock) { shellService = null }
                Log.w(TAG, "UserService 调用失败: ${e.message}，尝试重连")
                return reconnectAndRetry(command)
            }
        }

        // 首次调用：异步绑定 UserService
        ensureBound { /* 后续调用自动走快速路径 */ }
        return Triple("", "Shizuku UserService 正在连接中，请稍后重试", -1)
    }

    /** 断连后重连并重试（200ms 超时 × 3 次） */
    private fun reconnectAndRetry(command: String): Triple<String, String, Int> {
        for (attempt in 1..3) {
            // 用 latch 等待绑定完成，200ms 超时
            val latch = java.util.concurrent.CountDownLatch(1)
            ensureBound { latch.countDown() }
            latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)

            val retryService = shellService
            if (retryService != null) {
                try {
                    return parseResult(retryService.execute(command))
                } catch (_: Exception) {
                    synchronized(bindLock) { shellService = null }
                }
            }
        }
        return Triple("", "Shizuku UserService 不可用，请检查 Shizuku 是否正常运行", -1)
    }

    /**
     * 解析 UserService 返回的 Base64 编码结果。
     * 格式: "stdoutBase64\nstderrBase64\nexitCode"
     */
    private fun parseResult(result: String): Triple<String, String, Int> {
        val lines = result.split("\n", limit = 3)
        val stdout = try {
            Base64.decode(lines.getOrElse(0) { "" }, Base64.NO_WRAP).toString(Charsets.UTF_8)
        } catch (_: Exception) { "" }
        val stderr = try {
            Base64.decode(lines.getOrElse(1) { "" }, Base64.NO_WRAP).toString(Charsets.UTF_8)
        } catch (_: Exception) { "" }
        val exitCode = lines.getOrElse(2) { "-1" }.trim().toIntOrNull() ?: -1
        return Triple(stdout, stderr, exitCode)
    }

    fun getLastError(): String = lastError
}
