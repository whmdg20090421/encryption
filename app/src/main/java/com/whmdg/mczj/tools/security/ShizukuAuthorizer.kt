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
import com.whmdg.mczj.tools.util.ShellDebugLog
import rikka.shizuku.Shizuku

/**
 * Shizuku 授权工具类
 * 使用 UserService 模式执行特权 shell 命令（替代已废弃的 newProcess）。
 * 异步绑定不阻塞主线程，首次调用自动回退到 newProcess。
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
     * 通过 Shizuku 执行 shell 命令。
     * 优先使用 UserService（Binder IPC ~1ms），回退到 newProcess（fork+exec ~50ms）。
     * @return Triple(stdout, stderr, exitCode)
     */
    fun executeCommand(command: String): Triple<String, String, Int> {
        val ctx = appContext
        // 快速路径：UserService 已就绪
        val service = shellService
        if (service != null) {
            return try {
                val rawResult = service.execute(command)
                if (ctx != null) ShellDebugLog.log(ctx, "UserService", "cmd=$command")
                if (ctx != null) ShellDebugLog.log(ctx, "UserService", "rawBase64=${rawResult.take(200)}")
                val parsed = parseResult(rawResult)
                if (ctx != null) {
                    ShellDebugLog.log(ctx, "UserService", "exit=${parsed.third} stdout=${parsed.first.length}字符 stderr=${parsed.second.length}字符")
                    if (parsed.first.isNotBlank()) ShellDebugLog.log(ctx, "UserService", "stdout前500: ${parsed.first.take(500)}")
                    if (parsed.second.isNotBlank()) ShellDebugLog.log(ctx, "UserService", "stderr: ${parsed.second.take(300)}")
                }
                parsed
            } catch (e: Exception) {
                // binder 死亡等，清除缓存并回退
                synchronized(bindLock) {
                    shellService = null
                }
                Log.w(TAG, "UserService 调用失败，回退到 newProcess: ${e.message}")
                if (ctx != null) ShellDebugLog.log(ctx, "UserService", "异常回退: ${e.message}")
                executeCommandViaNewProcess(command)
            }
        }

        // 慢路径：触发异步绑定，当前调用先用 newProcess 兜底
        ensureBound { /* 后续调用自动走快速路径 */ }
        if (ctx != null) ShellDebugLog.log(ctx, "newProcess", "cmd=$command (UserService 未就绪)")
        return executeCommandViaNewProcess(command)
    }

    /**
     * 通过已废弃的 newProcess 执行命令（回退方案）。
     * 反射调用 IShizukuService.newProcess。
     */
    private fun executeCommandViaNewProcess(command: String): Triple<String, String, Int> {
        try {
            if (!hasShizukuPermission()) {
                return Triple("", "Shizuku 未授权", -1)
            }

            val binder = Shizuku.getBinder()
            val iShizukuService = Class.forName("moe.shizuku.server.IShizukuService\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder)

            val process = iShizukuService.javaClass
                .getMethod("newProcess", Array<String>::class.java, String::class.java, Array<String>::class.java)
                .invoke(iShizukuService, arrayOf("sh", "-c", command), null, null)

            val processClass = process.javaClass
            val inputStream = processClass.getMethod("getInputStream").invoke(process) as? android.os.ParcelFileDescriptor
            val errorStream = processClass.getMethod("getErrorStream").invoke(process) as? android.os.ParcelFileDescriptor

            val stdout = inputStream?.let {
                java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(it.fileDescriptor))).use { r -> r.readText() }
            } ?: ""

            val stderr = errorStream?.let {
                java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(it.fileDescriptor))).use { r -> r.readText() }
            } ?: ""

            val exitCode = processClass.getMethod("waitFor").invoke(process) as Int

            inputStream?.close()
            errorStream?.close()

            return Triple(stdout.trimEnd(), stderr.trimEnd(), exitCode)
        } catch (e: Exception) {
            return Triple("", "Shizuku 执行异常: ${e.message}", -1)
        }
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
