package com.whmdg.mczj.tools

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.whmdg.mczj.tools.ui.theme.LocalBgImageAlpha
import com.whmdg.mczj.tools.ui.theme.LocalBgImagePath
import com.whmdg.mczj.tools.ui.theme.LocalBgUiAlpha
import com.whmdg.mczj.tools.ui.theme.LocalCustomBgEnabled
import com.whmdg.mczj.tools.ui.theme.LocalIsDarkMode
import com.whmdg.mczj.tools.ui.theme.LocalOnSetBgImage
import com.whmdg.mczj.tools.ui.theme.LocalOnSetBgImageAlpha
import com.whmdg.mczj.tools.ui.theme.LocalOnSetBgUiAlpha
import com.whmdg.mczj.tools.ui.theme.LocalOnSetCustomBg
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

            // ── 自定义背景 ──
            var customBgEnabled by remember {
                mutableStateOf(themePrefs.getBoolean("custom_bg_enabled", false))
            }
            var bgImagePath by remember {
                mutableStateOf(themePrefs.getString("bg_image_path", null))
            }
            var bgImageAlpha by remember {
                mutableFloatStateOf(themePrefs.getFloat("bg_image_alpha", 0.3f))
            }
            var bgUiAlpha by remember {
                mutableFloatStateOf(themePrefs.getFloat("bg_ui_alpha", 0.85f))
            }
            val savePref: (String, Any) -> Unit = remember { { key, value ->
                themePrefs.edit().apply {
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                        is Float -> putFloat(key, value)
                    }.apply()
                }
            } }

            CompositionLocalProvider(
                LocalIsDarkMode provides isDarkMode,
                LocalOnToggleTheme provides onToggleTheme,
                LocalCustomBgEnabled provides customBgEnabled,
                LocalBgImagePath provides bgImagePath,
                LocalBgImageAlpha provides bgImageAlpha,
                LocalBgUiAlpha provides bgUiAlpha,
                LocalOnSetCustomBg provides { value ->
                    customBgEnabled = value
                    savePref("custom_bg_enabled", value)
                },
                LocalOnSetBgImage provides { value ->
                    bgImagePath = value
                    if (value != null) {
                        savePref("bg_image_path", value)
                    } else {
                        themePrefs.edit().remove("bg_image_path").apply()
                    }
                },
                LocalOnSetBgImageAlpha provides { value ->
                    bgImageAlpha = value
                    savePref("bg_image_alpha", value)
                },
                LocalOnSetBgUiAlpha provides { value ->
                    bgUiAlpha = value
                    savePref("bg_ui_alpha", value)
                }
            ) {
                val hasBgImage = !bgImagePath.isNullOrEmpty()
                val bgBitmap = remember(bgImagePath) {
                    if (hasBgImage) BitmapFactory.decodeFile(bgImagePath) else null
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // 背景图片层
                    if (customBgEnabled && hasBgImage && bgBitmap != null) {
                        Image(
                            bitmap = bgBitmap.asImageBitmap(),
                            contentDescription = "背景",
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(bgImageAlpha),
                            contentScale = ContentScale.Crop
                        )
                    }

                    // UI 内容层
                    Box(modifier = Modifier.fillMaxSize().alpha(bgUiAlpha)) {
                        工具箱Theme(darkTheme = isDarkMode) {
                            MainAppContainer()
                        }
                    }
                }
            }
        }
    }
}