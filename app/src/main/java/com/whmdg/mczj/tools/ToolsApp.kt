package com.whmdg.mczj.tools

import android.app.Application
import android.util.Log
import android.webkit.WebView
import java.io.File

class ToolsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        migrateWebViewData()
        WebView.setDataDirectorySuffix("app")
    }

    /**
     * 首次升级时将旧 WebView 数据目录 (app_webview/) 迁移到新目录 (app_webview-app/)。
     * setDataDirectorySuffix() 必须在任何 WebView 创建前调用，
     * 且新目录必须不存在，所以先重命名旧目录。
     */
    private fun migrateWebViewData() {
        val dataDir = File(filesDir.parentFile, "app_webview")
        val newDir = File(filesDir.parentFile, "app_webview-app")
        if (dataDir.exists() && !newDir.exists()) {
            if (dataDir.renameTo(newDir)) {
                Log.i("ToolsApp", "WebView 数据已迁移: app_webview/ → app_webview-app/")
            } else {
                Log.w("ToolsApp", "WebView 数据迁移失败（renameTo 返回 false）")
            }
        }
    }
}
