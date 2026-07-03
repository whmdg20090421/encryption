package com.whmdg.mczj.tools.ui.security

sealed class SecurityRoute {
    object Security : SecurityRoute()
    object PermissionSettings : SecurityRoute()
    object SpecialPermissions : SecurityRoute()
    object AppPermissions : SecurityRoute()
    object PermissionManagementConfig : SecurityRoute()
}
