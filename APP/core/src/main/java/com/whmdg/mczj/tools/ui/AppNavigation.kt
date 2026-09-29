package com.whmdg.mczj.tools.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 跨 Activity 的导航请求。
 *
 * 独立 Activity（如 PackageManagerActivity）无法直接操作主界面的 Compose 导航栈，
 * 通过本单例投递「跳转到文件管理器并定位到指定路径」的请求；
 * MainAppContainer 负责确保文件管理器处于栈顶，FileManagerScreen 消费请求并导航。
 */
object AppNavigation {

    /** 文件管理器定位请求：[path] 为目标目录绝对路径。 */
    data class FileManagerRequest(val path: String)

    var pendingFileManager by mutableStateOf<FileManagerRequest?>(null)

    /** 请求跳转到文件管理器并定位到 [path]。 */
    fun requestFileManager(path: String) {
        pendingFileManager = FileManagerRequest(path)
    }

    /** 取出并清空当前请求（由消费方调用，保证只处理一次）。 */
    fun consumeFileManager(): FileManagerRequest? {
        val req = pendingFileManager ?: return null
        pendingFileManager = null
        return req
    }
}
