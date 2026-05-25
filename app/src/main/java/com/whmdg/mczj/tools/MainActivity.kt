package com.whmdg.mczj.tools

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.whmdg.mczj.tools.ui.theme.工具箱Theme
import com.whmdg.mczj.tools.ui.MainAppContainer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 捕获后台线程的系统级崩溃（如 ApkAssets 加载失败），防止应用整体崩溃
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val name = throwable.javaClass.name
            val msg = throwable.message
            // 只吞噬已知无害的系统框架层崩溃
            if (name.contains("ApkAssets") || name.contains("nativeLoad")) {
                Log.e("GlobalCrashHandler", "系统框架层异常: $name: $msg", throwable)
            } else {
                defaultHandler?.uncaughtException(thread, throwable)
                    ?: throw throwable
            }
        }

        enableEdgeToEdge()
        setContent {
            工具箱Theme {
                MainAppContainer()
            }
        }
    }
}