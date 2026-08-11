package com.whmdg.mczj.tools.ui.filemanager

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import com.whmdg.mczj.tools.encryption.services.VaultService
import com.whmdg.mczj.tools.encryption.services.VaultSession
import com.whmdg.mczj.tools.fileop.webdav.WebDavServerConfig

@Composable
fun FileManagerModuleScreen(
    onBack: () -> Unit,
    vaultSession: VaultSession? = null,
    vaultService: VaultService? = null,
    cloudMode: Boolean = false,
    webdavUrl: String = "",
    webdavUser: String = "",
    webdavPassword: String = "",
    webdavPath: String = "",
    cloudVaultDir: String? = null,
    cloudVaultId: Int = 0,
    cloudVaultName: String = ""
) {
    BackHandler(enabled = true) {
        onBack()
    }

    // 从 URL 解析协议/主机/端口，构建 WebDavServerConfig
    val webdavConfig = remember(webdavUrl, webdavUser, webdavPassword, webdavPath) {
        if (webdavUrl.isBlank()) null
        else {
            val parsed = com.whmdg.mczj.tools.ui.encryption.parseWebDavUrlPublic(webdavUrl)
            if (parsed != null) WebDavServerConfig(
                protocol = parsed.first,
                host = parsed.second,
                port = parsed.third,
                username = webdavUser,
                password = webdavPassword,
                relativePath = webdavPath
            ) else null
        }
    }

    // ImageViewer 和 TextEditor 已迁移到独立的 ViewerActivity，
    // 返回手势由 Activity 自行处理，不再经过 Compose 导航。
    FileManagerScreen(
        onBack = { onBack() },
        vaultSession = vaultSession,
        vaultService = vaultService,
        cloudMode = cloudMode,
        webdavConfig = webdavConfig,
        cloudVaultDir = cloudVaultDir,
        cloudVaultId = cloudVaultId,
        cloudVaultName = cloudVaultName
    )
}
