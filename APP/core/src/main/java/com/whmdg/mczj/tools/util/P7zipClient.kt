package com.whmdg.mczj.tools.util

import android.content.Context
import android.util.Log
import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.security.ShizukuAuthorizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * P7zip 客户端 API（一次性执行版）。
 * 每次命令直接 su -c "7zzs ..." 执行，无需 daemon 进程。
 * 自动选择最高可用权限。
 */
object P7zipClient {

    private const val TAG = "P7zipClient"
    private const val COMMAND_TIMEOUT_MS = 60_000L

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 兼容旧接口，一次性执行模式无需预热 */
    suspend fun ensureDaemonOrThrow() {}

    // ── 对外 API ──

    suspend fun listArchive(archivePath: String, password: String = ""): Result<String> = withContext(Dispatchers.IO) {
        val cmd = SevenZipCommand.buildListCommand(getBinaryPath(), archivePath, password)
        execCommand(cmd)
    }

    suspend fun listArchiveDetail(archivePath: String, password: String = ""): Result<String> = withContext(Dispatchers.IO) {
        val cmd = SevenZipCommand.buildListDetailCommand(getBinaryPath(), archivePath, password)
        execCommand(cmd)
    }

    suspend fun extractSingleFile(archivePath: String, fileName: String, outputDir: String, password: String = ""): Result<String> = withContext(Dispatchers.IO) {
        val cmd = SevenZipCommand.buildExtractSingleCommand(getBinaryPath(), archivePath, fileName, outputDir, password)
        execCommand(cmd)
    }

    suspend fun extractAll(archivePath: String, outputDir: String, password: String = ""): Result<String> = withContext(Dispatchers.IO) {
        val cmd = SevenZipCommand.buildExtractCommand(getBinaryPath(), archivePath, outputDir, password)
        execCommand(cmd)
    }

    suspend fun compress(
        sourcePaths: List<String>,
        outputPath: String,
        format: String,
        level: Int,
        password: String = "",
        useAes: Boolean = false,
        encryptNames: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        val options = CompressService.CompressOptions(
            sourcePaths = sourcePaths, outputPath = outputPath, format = format,
            compressionLevel = level, password = password, useAes = useAes, encryptNames = encryptNames
        )
        val cmd = SevenZipCommand.build(getBinaryPath(), options)
        execCommand(cmd)
    }

    suspend fun detectPassword(archivePath: String): Result<String> = withContext(Dispatchers.IO) {
        val cmd = SevenZipCommand.buildListDetailCommand(getBinaryPath(), archivePath, password = "dummy")
        val result = execCommand(cmd)
        result.map { output ->
            when {
                output.contains("7zAES", ignoreCase = true) || output.contains("Encrypted = +") -> "true"
                output.contains("Cannot open encrypted archive", ignoreCase = true) -> "true"
                else -> "false"
            }
        }
    }

    suspend fun compressStream(
        sourcePaths: List<String>,
        outputPath: String,
        format: String,
        level: Int,
        password: String = "",
        useAes: Boolean = false,
        encryptNames: Boolean = false,
        onLine: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val options = CompressService.CompressOptions(
            sourcePaths = sourcePaths, outputPath = outputPath, format = format,
            compressionLevel = level, password = password, useAes = useAes, encryptNames = encryptNames
        )
        val cmd = SevenZipCommand.build(getBinaryPath(), options)
        execStreamingCommand(cmd, onLine)
    }

    suspend fun extractStream(
        archivePath: String,
        outputDir: String,
        password: String = "",
        onLine: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val cmd = SevenZipCommand.buildExtractCommand(getBinaryPath(), archivePath, outputDir, password)
        execStreamingCommand(cmd, onLine)
    }

    // ── 内部执行 ──

    private fun getBinaryPath(): String {
        val context = appContext ?: throw RuntimeException("P7zipClient 未初始化，请先调用 init(context)")
        return BinaryExtractor.ensureExtracted(context).absolutePath
    }

    private fun execCommand(cmd: String): Result<String> {
        return try {
            Log.d(TAG, "执行: $cmd")
            val permission = resolvePermission()
            val process = when (permission) {
                Permission.ROOT -> Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                else -> Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            }
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
            process.waitFor()
            val exitCode = process.exitValue()
            Log.d(TAG, "退出码=$exitCode, 输出=${output.length}字符")
            if (exitCode == 0) Result.success(output)
            else Result.failure(RuntimeException(output.ifEmpty { "7zzs 退出码: $exitCode" }))
        } catch (e: Exception) {
            Log.e(TAG, "命令执行失败", e)
            Result.failure(RuntimeException("命令执行失败: ${e.message}", e))
        }
    }

    private fun execStreamingCommand(cmd: String, onLine: (String) -> Unit): Result<String> {
        return try {
            Log.d(TAG, "流式执行: $cmd")
            val permission = resolvePermission()
            val process = when (permission) {
                Permission.ROOT -> Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                else -> Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            }
            val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                onLine(line!!)
            }
            process.waitFor()
            val exitCode = process.exitValue()
            Log.d(TAG, "流式执行完成, 退出码=$exitCode")
            if (exitCode == 0) Result.success("")
            else {
                val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText().trim()
                Result.failure(RuntimeException(stderr.ifEmpty { "7zzs 退出码: $exitCode" }))
            }
        } catch (e: Exception) {
            Log.e(TAG, "流式命令执行失败", e)
            Result.failure(RuntimeException("流式命令执行失败: ${e.message}", e))
        }
    }

    // ── 权限 ──

    private fun resolvePermission(): Permission {
        if (SpecialPermissionVerifier.isRootAvailable()) return Permission.ROOT
        if (ShizukuAuthorizer.getShellService() != null) return Permission.ADB
        return Permission.APPLICANT
    }
}
