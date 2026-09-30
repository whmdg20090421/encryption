package com.whmdg.mczj.tools.ui.packagemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.whmdg.mczj.tools.ui.theme.工具箱Theme
import com.whmdg.mczj.tools.AppDataPaths

/**
 * 独立的应用管理 Activity，用于查看已安装应用列表。
 *
 * 与 FileManager 的 Compose 导航完全隔离，返回手势不会穿透到底层。
 */
class PackageManagerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isDarkMode = AppDataPaths.prefs(this, AppDataPaths.PREFS_THEME)
            .getBoolean("is_dark_mode", true)

        setContent {
            工具箱Theme(darkTheme = isDarkMode) {
                PackageManagerScreen(onBack = { finish() })
            }
        }
    }
}
