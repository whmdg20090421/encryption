package com.whmdg.mczj.tools.ui.filemanager

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import com.whmdg.mczj.tools.encryption.services.VaultService
import com.whmdg.mczj.tools.encryption.services.VaultSession

@Composable
fun FileManagerModuleScreen(
    onBack: () -> Unit,
    vaultSession: VaultSession? = null,
    vaultService: VaultService? = null
) {
    BackHandler(enabled = true) {
        onBack()
    }

    // ImageViewer 和 TextEditor 已迁移到独立的 ViewerActivity，
    // 返回手势由 Activity 自行处理，不再经过 Compose 导航。
    FileManagerScreen(
        onBack = { onBack() },
        vaultSession = vaultSession,
        vaultService = vaultService
    )
}
