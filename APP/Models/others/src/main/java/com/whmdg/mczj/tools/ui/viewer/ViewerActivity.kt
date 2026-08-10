package com.whmdg.mczj.tools.ui.viewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.whmdg.mczj.tools.encryption.services.VaultKeyHolder
import com.whmdg.mczj.tools.ui.theme.工具箱Theme
import java.io.File

/**
 * 独立的文件浏览 Activity，承载 ImageViewer 和 TextEditor。
 *
 * 与 FileManager 的 Compose 导航完全隔离：
 * - 独立 Window / 焦点 / 手势区
 * - 系统返回手势不会穿透到底层 FileManager
 * - 通过 VaultKeyHolder 获取保险箱 DEK
 */
class ViewerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VIEWER_TYPE = "viewer_type"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_IMAGE_PATHS = "image_paths"
        const val EXTRA_START_INDEX = "start_index"
        const val EXTRA_VAULT_SESSION_ID = "vault_session_id"

        private const val TYPE_IMAGE = "image"
        private const val TYPE_TEXT = "text"

        fun createImageIntent(
            context: Context,
            filePath: String,
            imagePaths: List<String> = emptyList(),
            startIndex: Int = 0,
            vaultSessionId: String? = null
        ): Intent {
            return Intent(context, ViewerActivity::class.java).apply {
                putExtra(EXTRA_VIEWER_TYPE, TYPE_IMAGE)
                putExtra(EXTRA_FILE_PATH, filePath)
                putStringArrayListExtra(EXTRA_IMAGE_PATHS, ArrayList(imagePaths))
                putExtra(EXTRA_START_INDEX, startIndex)
                if (vaultSessionId != null) putExtra(EXTRA_VAULT_SESSION_ID, vaultSessionId)
            }
        }

        fun createTextIntent(
            context: Context,
            filePath: String,
            vaultSessionId: String? = null
        ): Intent {
            return Intent(context, ViewerActivity::class.java).apply {
                putExtra(EXTRA_VIEWER_TYPE, TYPE_TEXT)
                putExtra(EXTRA_FILE_PATH, filePath)
                if (vaultSessionId != null) putExtra(EXTRA_VAULT_SESSION_ID, vaultSessionId)
            }
        }
    }

    private var vaultSessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val viewerType = intent.getStringExtra(EXTRA_VIEWER_TYPE) ?: run { finish(); return }
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: run { finish(); return }
        val imagePaths = intent.getStringArrayListExtra(EXTRA_IMAGE_PATHS) ?: emptyList()
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
        vaultSessionId = intent.getStringExtra(EXTRA_VAULT_SESSION_ID)

        // 验证 vault session 有效性
        if (vaultSessionId != null && VaultKeyHolder.get(vaultSessionId!!) == null) {
            vaultSessionId = null
        }

        val isDarkMode = getSharedPreferences("theme_prefs", MODE_PRIVATE)
            .getBoolean("is_dark_mode", true)

        setContent {
            工具箱Theme(darkTheme = isDarkMode) {
                when (viewerType) {
                    TYPE_IMAGE -> ImageViewerScreen(
                        filePath = filePath,
                        imagePaths = imagePaths,
                        startIndex = startIndex,
                        onBack = { finish() }
                    )
                    TYPE_TEXT -> TextEditorScreen(
                        filePath = filePath,
                        vaultSessionId = vaultSessionId,
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理 vault session 和临时文件
        vaultSessionId?.let { id ->
            VaultKeyHolder.get(id)?.let { ctx ->
                // 删除解密产生的临时文件
                try {
                    cacheDir.listFiles { _, name ->
                        name.startsWith("vault_open_") || name.startsWith("vault_text_") || name.startsWith("vault_img_")
                    }?.forEach { it.delete() }
                } catch (_: Exception) {}
            }
            VaultKeyHolder.clear(id)
        }
    }
}
