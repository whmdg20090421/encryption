package com.whmdg.mczj.tools.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import android.app.admin.DevicePolicyManager

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
     * 检测 ADB 权限是否授予
     * 1. 检查是否持有 WRITE_SECURE_SETTINGS 权限（传统方式）
     * 2. 或者检测 Shizuku 是否运行且已对本应用授权（无感方式）
     */
    fun isAdbEnabled(context: Context): Boolean {
        val hasSecureSettings = ContextCompat.checkSelfPermission(
            context,
            "android.permission.WRITE_SECURE_SETTINGS"
        ) == PackageManager.PERMISSION_GRANTED

        if (hasSecureSettings) return true

        return isShizukuAuthorized(context)
    }

    /**
     * 检测 Shizuku 是否已启动并授予本应用权限
     */
    fun isShizukuAuthorized(context: Context): Boolean {
        return try {
            val uri = android.net.Uri.parse("content://rikka.shizuku.provider")
            val bundle = context.contentResolver.call(uri, "checkPermission", null, null)
            val code = bundle?.getInt("result", -1) ?: -1
            code == 0 // 0 表示 PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检测 Shizuku 服务是否在运行
     */
    fun isShizukuRunning(context: Context): Boolean {
        return try {
            val uri = android.net.Uri.parse("content://rikka.shizuku.provider")
            val bundle = context.contentResolver.call(uri, "isRun", null, null)
            bundle?.getBoolean("isRun", false) == true
        } catch (e: Exception) {
            false
        }
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
     * 检测 Root 权限是否可用
     */
    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = java.io.DataOutputStream(process.outputStream)
            os.writeBytes("id\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 自动提权运行机制（即时提权与最小特权化运行）
     * 当“非必要时不使用权限”开启时，优先以普通APP沙盒运行，出现权限不足报错时自动套用特权级别重试。
     */
    fun <T> runWithPrivilegeElevation(context: Context, action: () -> T): T {
        val sp = context.getSharedPreferences("special_permissions", Context.MODE_PRIVATE)
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
     * 以 Root 权限执行 shell 命令并返回输出
     */
    fun executeRootCommand(command: String): String {
        val process = Runtime.getRuntime().exec("su")
        val os = java.io.DataOutputStream(process.outputStream)
        os.writeBytes("$command\n")
        os.writeBytes("exit\n")
        os.flush()

        val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
        val output = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            output.appendLine(line)
        }
        reader.close()
        os.close()
        process.waitFor()

        if (process.exitValue() != 0) {
            val errReader = java.io.BufferedReader(java.io.InputStreamReader(process.errorStream))
            val errorOutput = errReader.readText()
            errReader.close()
            throw SecurityException("Root 命令执行失败 (exit ${process.exitValue()}): $errorOutput")
        }
        return output.toString().trimEnd()
    }

    /**
     * 以 Root 权限执行 shell 命令，不关心输出，仅判断成功/失败
     */
    fun executeRootCommandSilent(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = java.io.DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }
}
