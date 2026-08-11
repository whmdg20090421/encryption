package com.whmdg.mczj.tools.fileop.webdav

/** WebDAV 连接状态 */
enum class WebDavConnectionStatus {
    NOT_LOGGED_IN,   // 未登录（未配置或清空配置）
    LOGGED_IN,       // 已登录（验证连接成功）
    EXPIRED          // 已失效（配置存在但连接失败）
}

/** WebDAV 账户状态 */
data class WebDavAccountState(
    val status: WebDavConnectionStatus = WebDavConnectionStatus.NOT_LOGGED_IN,
    val config: WebDavServerConfig? = null,
    val displayName: String = ""
)
