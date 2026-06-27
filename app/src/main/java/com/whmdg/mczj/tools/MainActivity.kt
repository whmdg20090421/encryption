package com.whmdg.mczj.tools

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import com.whmdg.mczj.tools.ui.theme.LocalIsDarkMode
import com.whmdg.mczj.tools.ui.theme.LocalOnToggleTheme
import com.whmdg.mczj.tools.ui.theme.LocalIsGlowEnabled
import com.whmdg.mczj.tools.ui.theme.LocalOnToggleGlow
import com.whmdg.mczj.tools.ui.theme.工具箱Theme
import com.whmdg.mczj.tools.ui.MainAppContainer
import com.whmdg.mczj.tools.util.DiagnosticLog
import com.whmdg.mczj.tools.auth.PermissionManager
import com.whmdg.mczj.tools.encryption.services.EncryptionTaskManager
import com.whmdg.mczj.tools.security.CrashMonitor

class MainActivity : ComponentActivity() {

    companion object {
        /** 4号元素（分割线）顶部在窗口中的 Y 坐标，由 composable 通过 onGloballyPositioned 更新 */
        var element4TopPx: Float = 0f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Native 层崩溃监控（通过 pipe 传递崩溃信息到 Java 层）
        CrashMonitor.init(this)
        DiagnosticLog.log("MainActivity", "NativeCrashMonitor 已安装")

        PermissionManager.init(applicationContext)
        EncryptionTaskManager.init(applicationContext)

        enableEdgeToEdge()

        // ── 键盘动画回调：adjustNothing 模式下手动平移布局 ──
        val contentView = findViewById<View>(android.R.id.content)
        contentView.doOnLayout {
            val initialHeight = contentView.height
            ViewCompat.setWindowInsetsAnimationCallback(contentView,
                object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onProgress(
                        insets: WindowInsetsCompat,
                        runningAnimations: List<WindowInsetsAnimationCompat>
                    ): WindowInsetsCompat {
                        val imeInset = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                        val imeTop = initialHeight - imeInset
                        val offset = if (element4TopPx > 0 && element4TopPx > imeTop)
                            -(element4TopPx - imeTop) else 0f
                        contentView.translationY = offset
                        return insets
                    }
                    override fun onEnd(animation: WindowInsetsAnimationCompat) {
                        if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                            val imeInset = ViewCompat.getRootWindowInsets(contentView)
                                ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                            if (imeInset == 0) contentView.translationY = 0f
                        }
                    }
                }
            )
        }

        setContent {
            val themePrefs = remember { getSharedPreferences(AppDataPaths.PREFS_THEME, MODE_PRIVATE) }

            // ── 首次启动申请通知权限 ──
            val notifRequested = remember { themePrefs.getBoolean("notif_permission_requested", false) }
            val notifLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { _ ->
                themePrefs.edit().putBoolean("notif_permission_requested", true).apply()
            }
            LaunchedEffect(Unit) {
                if (!notifRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            // ── 白天/黑夜 ──
            var isDarkMode by remember { mutableStateOf(themePrefs.getBoolean("is_dark_mode", true)) }
            val onToggleTheme: (Boolean) -> Unit = remember {
                { value ->
                    themePrefs.edit().putBoolean("is_dark_mode", value).apply()
                    isDarkMode = value
                }
            }

            // ── 光晕效果 ──
            var isGlowEnabled by remember { mutableStateOf(themePrefs.getBoolean("enable_glow_effect", true)) }
            val onToggleGlow: (Boolean) -> Unit = remember {
                { value ->
                    themePrefs.edit().putBoolean("enable_glow_effect", value).apply()
                    isGlowEnabled = value
                }
            }

            CompositionLocalProvider(
                LocalIsDarkMode provides isDarkMode,
                LocalOnToggleTheme provides onToggleTheme,
                LocalIsGlowEnabled provides isGlowEnabled,
                LocalOnToggleGlow provides onToggleGlow
            ) {
                工具箱Theme(darkTheme = isDarkMode) {
                    MainAppContainer()
                }
            }
        }
    }
}