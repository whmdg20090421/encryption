package com.whmdg.mczj.tools.ui.encryption

sealed class EncryptionRoute {
    object Home : EncryptionRoute()
    object VaultCreate : EncryptionRoute()
    data class VaultOpen(val vaultPath: String) : EncryptionRoute()
    data class VaultChangePassword(val vaultPath: String) : EncryptionRoute()
    object Settings : EncryptionRoute()
}