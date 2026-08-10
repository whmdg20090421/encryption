package com.whmdg.mczj.tools.encryption.services

import java.util.concurrent.ConcurrentHashMap

/**
 * 保险箱文件浏览时的上下文信息。
 * 由 FileManagerViewModel 在启动 ViewerActivity 前存入 [VaultKeyHolder]，
 * ViewerActivity 通过 sessionId 取出，用于解密/重新加密文件。
 */
data class VaultViewContext(
    val dek: ByteArray,                 // DEK 引用（不拷贝）
    val vaultDir: String,               // 保险箱根目录绝对路径
    val originalEncryptedPath: String,  // 加密文件的原始路径
    val customEncryption: Boolean,      // 是否自定义加密
    val encryptMetadata: Boolean,       // 是否加密元数据
    val vaultId: Int                    // 保险箱记录 ID
)

/**
 * Application 级内存单例，跨 Activity 传递保险箱 DEK。
 *
 * 生命周期：
 * - put() 在 FileManagerViewModel 启动 ViewerActivity 前调用
 * - get() 在 ViewerActivity 中读取
 * - clear() 在 ViewerActivity onDestroy 中调用
 */
object VaultKeyHolder {

    private val sessions = ConcurrentHashMap<String, VaultViewContext>()

    fun put(sessionId: String, ctx: VaultViewContext) {
        sessions[sessionId] = ctx
    }

    fun get(sessionId: String): VaultViewContext? = sessions[sessionId]

    fun clear(sessionId: String) {
        sessions.remove(sessionId)
    }
}
