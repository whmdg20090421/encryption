package com.whmdg.mczj.tools

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.whmdg.mczj.tools.ui.theme.LocalIsDarkMode
import com.whmdg.mczj.tools.ui.theme.LocalOnToggleTheme
import com.whmdg.mczj.tools.ui.theme.工具箱Theme
import com.whmdg.mczj.tools.ui.MainAppContainer
import com.whmdg.mczj.tools.util.DiagnosticLog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全局未捕获异常 → 写诊断报告到 filesDir/debug_logs/，再决定是否吞噬
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val msg = throwable.message ?: ""
            val isApkAssetsCrash =
                throwable.stackTrace.any {
                    it.className == "android.content.res.ApkAssets" ||
                            it.methodName == "nativeLoadFd" ||
                            it.methodName == "nativeLoad"
                } || msg.contains("Failed to load asset path") || msg.contains(".apk from fd")

            val ctx = """
                |线程名:       ${thread.name}
                |线程 ID:      ${thread.id}
                |线程组:       ${thread.threadGroup?.name}
                |线程优先级:   ${thread.priority}
                |线程类:       ${thread.javaClass.name}
                |是否守护线程: ${thread.isDaemon}
                |是否吞噬:     $isApkAssetsCrash
            """.trimMargin()

            // 后台线程崩溃可能没有任何 session（用户没主动点击）。这里独立给一段标识。
            DiagnosticLog.log("GlobalCrash", "未捕获异常 thread='${thread.name}' type=${throwable.javaClass.simpleName} msg=$msg")
            val file = DiagnosticLog.exportCrashReport(applicationContext, throwable, ctx)
            Log.e("GlobalCrashHandler", "崩溃报告: ${file?.absolutePath ?: "(写入失败)"}", throwable)

            if (isApkAssetsCrash) {
                Log.w("GlobalCrashHandler", "已吞噬 ApkAssets 系统层崩溃")
            } else {
                defaultHandler?.uncaughtException(thread, throwable)
                    ?: throw throwable
            }
        }
        DiagnosticLog.log("MainActivity", "GlobalCrashHandler 已安装")

        enableEdgeToEdge()
        setContent {
            val themePrefs = remember { getSharedPreferences("theme_prefs", MODE_PRIVATE) }

            // ── 白天/黑夜 ──
            var isDarkMode by remember { mutableStateOf(themePrefs.getBoolean("is_dark_mode", true)) }
            val onToggleTheme: (Boolean) -> Unit = remember {
                { value ->
                    themePrefs.edit().putBoolean("is_dark_mode", value).apply()
                    isDarkMode = value
                }
            }

            CompositionLocalProvider(
                LocalIsDarkMode provides isDarkMode,
                LocalOnToggleTheme provides onToggleTheme
            ) {
                工具箱Theme(darkTheme = isDarkMode) {
                    MainAppContainer()
                }
            }
        }
    }
}