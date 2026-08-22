package com.whmdg.mczj.tools.tomato

import android.content.Context
import android.util.Log
import com.whmdg.mczj.tools.AppDataPaths
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 番茄小说下载器（TND）管理。
 *
 * 职责：
 * - 管理 Rust 二进制文件的存放与执行
 * - 启动/停止 Web 服务器进程
 * - 首次启动时初始化数据目录
 */
object TomatoDownloader {
    private const val TAG = "TomatoDownloader"
    private const val BINARY_NAME = "libtnd.so"
    private const val SERVER_HOST = "127.0.0.1"
    private const val SERVER_PORT = 18423
    private const val SERVER_URL = "http://$SERVER_HOST:$SERVER_PORT"
    private const val STARTUP_TIMEOUT_MS = 30_000L
    private const val POLL_INTERVAL_MS = 300L

    /** 当前运行的服务器进程 */
    private var serverProcess: Process? = null

    /** 服务器是否已启动 */
    val isRunning: Boolean
        get() = serverProcess != null

    /** 获取服务器 URL */
    fun getServerUrl(): String = SERVER_URL

    /**
     * 获取二进制文件路径。
     * 直接使用 nativeLibraryDir 中的文件（系统解压位置，有正确的 SELinux 上下文）。
     */
    fun getBinaryFile(context: Context): File {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
            ?: throw IllegalStateException("nativeLibraryDir 为 null，请重新安装应用")
        val binaryFile = File(nativeLibDir, BINARY_NAME)

        if (!binaryFile.exists()) {
            throw IllegalStateException(
                "TND 二进制缺失（路径=${binaryFile.absolutePath}），请重新安装应用"
            )
        }

        return binaryFile
    }

    /**
     * 启动 Web 服务器。
     *
     * @param context Android Context
     * @param onReady 服务器就绪后的回调（在后台线程调用）
     * @param onError 启动失败的回调（在后台线程调用）
     */
    fun startServer(
        context: Context,
        onReady: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (serverProcess != null) {
            Log.w(TAG, "服务器已在运行中")
            onReady()
            return
        }

        Thread({
            try {
                val binary = getBinaryFile(context)
                binary.setExecutable(true, false)

                val dataDir = AppDataPaths.tomatoNovelTndData(context)

                // 首次启动时创建数据目录
                initDataDir(dataDir)

                Log.i(TAG, "启动 TND 服务器: ${binary.absolutePath}")
                Log.i(TAG, "数据目录: ${dataDir.absolutePath}")

                val pb = ProcessBuilder(
                    binary.absolutePath,
                    "--server",
                    "--data-dir", dataDir.absolutePath
                ).apply {
                    redirectErrorStream(true)
                    directory(dataDir)
                    environment()["HOME"] = dataDir.absolutePath
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                }

                serverProcess = pb.start()

                // 记录服务器输出
                Thread({
                    serverProcess?.inputStream?.bufferedReader()?.useLines { lines ->
                        lines.forEach { Log.d(TAG, "[TND] $it") }
                    }
                }, "tnd-stdout").apply { isDaemon = true }.start()

                // 等待服务器就绪
                if (waitForServer(SERVER_URL, STARTUP_TIMEOUT_MS)) {
                    Log.i(TAG, "TND 服务器已就绪")
                    onReady()
                } else {
                    throw RuntimeException("服务器未在 ${STARTUP_TIMEOUT_MS / 1000} 秒内启动")
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动 TND 服务器失败", e)
                serverProcess?.destroy()
                serverProcess = null
                onError(e)
            }
        }, "tnd-launcher").apply { isDaemon = true }.start()
    }

    /**
     * 停止 Web 服务器。
     */
    fun stopServer() {
        val process = serverProcess ?: return
        Log.i(TAG, "停止 TND 服务器")

        try {
            process.destroy()
            // 等待进程退出
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        } catch (e: Exception) {
            Log.w(TAG, "停止服务器时出错", e)
        } finally {
            serverProcess = null
        }
    }

    /**
     * 初始化数据目录（首次启动时）。
     * 创建数据根目录，并预先配置 save_path 指向 downloads 子目录，
     * 避免 Rust 程序把整个数据目录当作下载目录。
     */
    private fun initDataDir(dataDir: File) {
        if (!dataDir.exists()) {
            dataDir.mkdirs()
            Log.i(TAG, "创建数据目录: ${dataDir.absolutePath}")
        }

        // 首次启动时预写 config.yml，设置 save_path
        val configFile = File(dataDir, "config.yml")
        if (!configFile.exists()) {
            val downloadsDir = File(dataDir, "downloads")
            downloadsDir.mkdirs()

            val configContent = """save_path: ${downloadsDir.absolutePath}
"""
            configFile.writeText(configContent)
            Log.i(TAG, "预写配置: save_path=${downloadsDir.absolutePath}")
        }
    }

    /**
     * 等待服务器就绪。
     */
    private fun waitForServer(url: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 500
                    readTimeout = 500
                    requestMethod = "GET"
                }
                val code = c.responseCode
                c.disconnect()
                if (code in 200..499) return true
            } catch (_: Exception) {
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }
}
