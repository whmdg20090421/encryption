package com.whmdg.mczj.tools.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku

/**
 * Shizuku 授权工具类
 * 参考 Operit 的 ShizukuAuthorizer 实现
 */
object ShizukuAuthorizer {
    private const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var binderReceivedListenerRegistered = false
    private var isServiceAvailable = false
    private var lastError = ""

    /**
     * 初始化 Shizuku 绑定监听
     * 应在 Application.onCreate 或首次使用前调用
     */
    fun initialize() {
        if (binderReceivedListenerRegistered) return

        try {
            Shizuku.addBinderReceivedListener {
                isServiceAvailable = true
                lastError = ""
            }
            Shizuku.addBinderDeadListener {
                isServiceAvailable = false
                lastError = "Shizuku binder 已断开"
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
     * 检查 Shizuku 是否已安装
     */
    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            // 尝试 Sui 后端
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
     * 通过 Shizuku 执行 shell 命令
     * @return Triple(stdout, stderr, exitCode)
     */
    fun executeCommand(command: String): Triple<String, String, Int> {
        try {
            if (!hasShizukuPermission()) {
                return Triple("", "Shizuku 未授权", -1)
            }

            // 通过反射调用 newProcess（兼容 Shizuku AIDL 接口）
            val binder = Shizuku.getBinder()
            val iShizukuService = Class.forName("moe.shizuku.server.IShizukuService\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder)

            val process = iShizukuService.javaClass
                .getMethod("newProcess", Array<String>::class.java, String::class.java, Array<String>::class.java)
                .invoke(iShizukuService, arrayOf("sh", "-c", command), null, null)

            // 读取输出
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

    fun getLastError(): String = lastError
}
