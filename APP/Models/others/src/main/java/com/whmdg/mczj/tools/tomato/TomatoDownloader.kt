package com.whmdg.mczj.tools.tomato

import android.content.Context
import android.util.Log
import com.whmdg.mczj.tools.AppDataPaths
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.ServerSocket
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

    /** 端口随机选取范围（2 万余的偏僻端口段） */
    private const val PORT_RANGE_START = 20000
    private const val PORT_RANGE_END = 29999

    private const val STARTUP_TIMEOUT_MS = 30_000L
    private const val POLL_INTERVAL_MS = 300L

    /** 当前运行使用的端口（首次启动时解析并持久化） */
    @Volatile
    private var serverPort: Int = -1

    /** 当前运行的服务器进程 */
    private var serverProcess: Process? = null

    /** 服务器是否已启动 */
    val isRunning: Boolean
        get() = serverProcess?.isAlive == true

    /** 获取服务器 URL */
    fun getServerUrl(): String = "http://$SERVER_HOST:$serverPort"

    /**
     * 解析并持久化本地监听端口。
     *
     * - 首次运行（本地无记录）：随机取一个 2 万余段的端口，若被占用则端口 +1
     *   （范围回绕）直到可用，然后写入本地文件。
     * - 后续运行：直接读取本地记录；若该端口已被占用，则默认占用者就是本应用的
     *   TND 服务，直接复用，不再报错。
     */
    private fun resolvePort(context: Context): Int {
        val portFile = AppDataPaths.tomatoNovelTndPortFile(context)

        if (portFile.exists()) {
            val saved = portFile.readText().trim().toIntOrNull()
            if (saved != null && saved in PORT_RANGE_START..PORT_RANGE_END) {
                // 端口被占用时判定为本应用的服务在跑，直接复用
                return saved
            }
        }

        // 首次运行（或记录非法）：随机起点，向后探测可用端口
        var port = (PORT_RANGE_START..PORT_RANGE_END).random()
        val start = port
        while (!isPortAvailable(port)) {
            port++
            if (port > PORT_RANGE_END) port = PORT_RANGE_START
            if (port == start) {
                // 整个端口段都不可用（极不可能）
                throw IllegalStateException(
                    "端口段 $PORT_RANGE_START-$PORT_RANGE_END 全部被占用"
                )
            }
        }
        portFile.writeText(port.toString())
        Log.i(TAG, "已选定并保存本地端口: $port")
        return port
    }

    /**
     * 检查端口是否被占用。
     * @return true 如果端口可用，false 如果被占用
     */
    fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (_: Exception) {
            false
        }
    }

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
     * @param onError 启动失败的回调（在后台线程调用），errorCode: "STARTUP_FAILED"
     */
    fun startServer(
        context: Context,
        onReady: () -> Unit,
        onError: (errorCode: String, message: String) -> Unit
    ) {
        if (serverProcess?.isAlive == true) {
            Log.w(TAG, "服务器已在运行中")
            onReady()
            return
        }
        // 进程已退出但引用残留，清理后重新启动
        serverProcess = null

        // 解析本地端口（端口被占用时默认为本应用的服务，直接复用）
        val port = try {
            resolvePort(context)
        } catch (e: Exception) {
            Log.e(TAG, "解析端口失败", e)
            onError("STARTUP_FAILED", e.message ?: "无法解析本地端口")
            return
        }
        serverPort = port

        Thread({
            try {
                val binary = getBinaryFile(context)
                binary.setExecutable(true, false)

                val dataDir = AppDataPaths.tomatoNovelTndData(context)

                // 首次启动时创建数据目录
                initDataDir(dataDir)

                val bindAddr = "$SERVER_HOST:$port"
                Log.i(TAG, "启动 TND 服务器: ${binary.absolutePath}")
                Log.i(TAG, "监听地址: $bindAddr")
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
                    // 通过环境变量指定监听地址（默认 127.0.0.1:18423）
                    environment()["TOMATO_WEB_ADDR"] = bindAddr
                }

                serverProcess = pb.start()

                // 记录服务器输出（忽略 InterruptedIOException）
                Thread({
                    try {
                        serverProcess?.inputStream?.bufferedReader()?.useLines { lines ->
                            lines.forEach { Log.d(TAG, "[TND] $it") }
                        }
                    } catch (_: InterruptedIOException) {
                        // 退出时正常行为，忽略
                    } catch (e: Exception) {
                        Log.w(TAG, "读取 TND 输出时出错", e)
                    }
                }, "tnd-stdout").apply { isDaemon = true }.start()

                // 等待服务器就绪
                val url = "http://$SERVER_HOST:$port"
                if (waitForServer(url, STARTUP_TIMEOUT_MS)) {
                    Log.i(TAG, "TND 服务器已就绪")
                    onReady()
                } else {
                    throw RuntimeException("服务器未在 ${STARTUP_TIMEOUT_MS / 1000} 秒内启动")
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动 TND 服务器失败", e)
                serverProcess?.destroy()
                serverProcess = null
                onError("STARTUP_FAILED", e.message ?: "未知错误")
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
