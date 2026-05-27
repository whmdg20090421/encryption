package com.whmdg.mczj.tools.security

enum class AndroidPermissionLevel {
    STANDARD,      // 普通应用权限
    ACCESSIBILITY, // 无障碍服务权限
    ADB,           // ADB 调试权限
    DEBUGGER,      // 调试权限
    ADMIN,         // 管理员权限
    ROOT;          // Root权限

    companion object {
        fun fromString(value: String?): AndroidPermissionLevel {
            return when (value?.uppercase()) {
                "STANDARD" -> STANDARD
                "ACCESSIBILITY" -> ACCESSIBILITY
                "ADB" -> ADB
                "DEBUGGER" -> DEBUGGER
                "ADMIN" -> ADMIN
                "ROOT" -> ROOT
                else -> STANDARD
            }
        }
    }
}
