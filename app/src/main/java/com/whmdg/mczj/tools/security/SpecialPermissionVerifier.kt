package com.whmdg.mczj.tools.security

import android.content.ComponentName
import android.content.Context
import com.topjohnwu.superuser.Shell
import com.whmdg.mczj.tools.AppDataPaths
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import android.app.admin.DevicePolicyManager
import java.io.File

object SpecialPermissionVerifier {

    /**
     * 检测无障碍服务是否已启用
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expectedComponentName = ComponentName(context, MyAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    /**
     * 检测 ADB 权限（WRITE_SECURE_SETTINGS）是否授予
     */
    fun isAdbEnabled(context: Context): Boolean {
        // 通过 PackageManager 检查 WRITE_SECURE_SETTINGS
        val hasSecureSettings = try {
            val pkgInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS
            )
            val idx = pkgInfo.requestedPermissions?.indexOf(
                android.Manifest.permission.WRITE_SECURE_SETTINGS
            ) ?: -1
            idx >= 0 && (pkgInfo.requestedPermissionsFlags!![idx] and
                    android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
        } catch (_: Exception) {
            false
        }
        return hasSecureSettings
    }

    /**
     * 检测 Shizuku 是否已启动且已授权本应用（使用 Shizuku SDK）
     */
    fun isShizukuAuthorized(context: Context): Boolean {
        ShizukuAuthorizer.initialize()
        return ShizukuAuthorizer.isShizukuServiceRunning() && ShizukuAuthorizer.hasShizukuPermission()
    }

    /**
     * 检测 Shizuku 服务是否在运行
     */
    fun isShizukuRunning(context: Context): Boolean {
        ShizukuAuthorizer.initialize()
        return ShizukuAuthorizer.isShizukuServiceRunning()
    }


    /**
     * 检测设备管理器权限是否激活
     */
    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }

    /**
     * 检测 Root 权限是否可用（通过 libsu）
     */
    fun isRootAvailable(): Boolean {
        return try {
            Shell.isAppGrantedRoot() == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 自动提权运行机制（即时提权与最小特权化运行）
     * 当“非必要时不使用权限”开启时，优先以普通APP沙盒运行，出现权限不足报错时自动套用特权级别重试。
     */
    fun <T> runWithPrivilegeElevation(context: Context, action: () -> T): T {
        val sp = context.getSharedPreferences(AppDataPaths.PREFS_LEGACY_SPECIAL_PERMISSIONS, Context.MODE_PRIVATE)
        val target = sp.getString("target_permission_level", "NORMAL") ?: "NORMAL"
        val useOnlyWhenNecessary = sp.getBoolean("use_only_when_necessary", false)

        if (useOnlyWhenNecessary) {
            try {
                // 1. 优先使用普通应用自身权限尝试运行
                return action()
            } catch (e: Exception) {
                val isPermissionError = e is SecurityException || 
                                        e is java.io.FileNotFoundException ||
                                        e.message?.contains("permission", ignoreCase = true) == true || 
                                        e.message?.contains("denied", ignoreCase = true) == true

                if (isPermissionError && target != "NORMAL") {
                    // 2. 自动提升权限到选定级别并重新执行
                    return runWithSpecialPrivilege(context, target, action)
                } else {
                    throw e
                }
            }
        } else {
            // 开关关闭时，直接使用选定的特殊权限运行
            if (target != "NORMAL") {
                return runWithSpecialPrivilege(context, target, action)
            } else {
                return action()
            }
        }
    }

    private fun <T> runWithSpecialPrivilege(context: Context, privilege: String, action: () -> T): T {
        return when (privilege) {
            "ROOT" -> {
                // Root 模式下，确认 su 可用后执行。若 action 内部涉及文件 I/O 以 Root 身份访问，
                // 此层仅作守卫确认；若需要在 su shell 中运行命令，请使用 executeRootCommand()。
                if (!isRootAvailable()) {
                    throw SecurityException("Root 权限不可用，请确保已授予 su 授权")
                }
                action()
            }
            "ADB" -> {
                if (!isAdbEnabled(context)) {
                    throw SecurityException("ADB 权限不可用，请通过 Shizuku 或 USB 调试授权")
                }
                action()
            }
            "ADMIN" -> {
                if (!isDeviceAdminActive(context)) {
                    throw SecurityException("设备管理器未激活，请先在特殊权限中激活管理员")
                }
                action()
            }
            "ACCESSIBILITY" -> {
                if (!isAccessibilityEnabled(context)) {
                    throw SecurityException("无障碍服务未启用，请先在系统设置中启用")
                }
                action()
            }
            else -> action()
        }
    }

    /**
     * 以 Root 权限执行 shell 命令并返回输出（通过 libsu）
     */
    fun executeRootCommand(command: String): String {
        val result = Shell.cmd(command).exec()
        if (!result.isSuccess) {
            val err = result.getErr().joinToString("\n").trimEnd()
            throw SecurityException("Root 命令执行失败 (exit ${result.getCode()}): $err")
        }
        return result.getOut().joinToString("\n").trimEnd()
    }

    /**
     * 以普通 app 权限执行 shell 命令，并行读取 stdout 和 stderr，不抛错。
     * 返回 (stdout, stderr, exitCode)。app 进程 SELinux 域允许 exec /system/bin/sh。
     */
    fun executeShellCommandFull(command: String): Triple<String, String, Int> {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        val tOut = Thread {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { stdoutBuf.appendLine(it) }
                }
            } catch (_: Exception) {}
        }.apply { start() }
        val tErr = Thread {
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { stderrBuf.appendLine(it) }
                }
            } catch (_: Exception) {}
        }.apply { start() }

        process.waitFor()
        tOut.join(2000)
        tErr.join(2000)

        return Triple(
            stdoutBuf.toString().trimEnd(),
            stderrBuf.toString().trimEnd(),
            process.exitValue()
        )
    }

    /**
     * 通过 Shizuku 执行 shell 命令（以 shell UID 运行，拥有 MANAGE_APP_OPS_MODES 等权限）
     */
    fun executeShizukuCommand(command: String): Triple<String, String, Int> {
        return ShizukuAuthorizer.executeCommand(command)
    }

    /**
     * 以 Root 权限执行 shell 命令，不抛错（通过 libsu）。
     * 返回 (stdout, stderr, exitCode)
     */
    fun executeRootCommandFull(command: String): Triple<String, String, Int> {
        return try {
            val result = Shell.cmd(command).exec()
            Triple(
                result.getOut().joinToString("\n").trimEnd(),
                result.getErr().joinToString("\n").trimEnd(),
                result.getCode()
            )
        } catch (e: Exception) {
            Triple("", e.message ?: "Shell 执行异常", -1)
        }
    }

    /**
     * 以 Root 权限执行 shell 命令，不关心输出，仅判断成功/失败（通过 libsu）
     */
    fun executeRootCommandSilent(command: String): Boolean {
        return try {
            Shell.cmd(command).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 安全删除文件或目录。
     * 策略：先用 Java File API 删除；若失败且 root 可用，则 chmod -R 777 后重试 Java 删除。
     * 绝不使用 rm 命令。
     */
    fun safeDelete(file: File): Boolean {
        // 1. 先尝试普通删除
        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (deleted) return true

        // 2. root 可用时，chmod 777 后重试
        if (!isRootAvailable()) return false
        val path = file.absolutePath
        if (path.isEmpty() || path == "/" || path.isBlank()) return false
        val escaped = path.replace("'", "'\\''")
        // 目录需要递归 chmod，文件只需单层
        val chmodCmd = if (file.isDirectory) "chmod -R 777 '$escaped'" else "chmod 777 '$escaped'"
        executeRootCommandSilent(chmodCmd)

        // 3. chmod 后重试 Java 删除
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }
}
