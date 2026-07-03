package com.whmdg.mczj.tools.encryption.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class StorageLocation {
    @SerialName("internal") INTERNAL,
    @SerialName("external") EXTERNAL;

    val displayName: String
        get() = when (this) {
            INTERNAL -> "内部私有目录"
            EXTERNAL -> "外部私有目录"
        }

    val description: String
        get() = when (this) {
            INTERNAL -> "空间小但更隐蔽，仅 App 自己能访问"
            EXTERNAL -> "空间大，USB / 文件管理器可见，依然不需要权限"
        }
}
