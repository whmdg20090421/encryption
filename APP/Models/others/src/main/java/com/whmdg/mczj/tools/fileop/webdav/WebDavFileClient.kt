package com.whmdg.mczj.tools.fileop.webdav

import at.bitfire.dav4jvm.exception.DavException
import com.whmdg.mczj.tools.fileop.webdav.client.Client
import com.whmdg.mczj.tools.fileop.webdav.client.toDavException
import com.whmdg.mczj.tools.fileop.webdav.client.isDirectory
import com.whmdg.mczj.tools.fileop.webdav.client.lastModifiedTime
import com.whmdg.mczj.tools.fileop.webdav.client.size
import com.whmdg.mczj.tools.util.FileAccessLevel
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * WebDAV file operations wrapper.
 * Provides a clean interface for the file manager to interact with WebDAV servers.
 */
class WebDavFileClient(private val config: WebDavServerConfig) {

    init {
        WebDavAuthenticator.addTransientServer(config)
    }

    private val authority = config.toAuthority()

    private fun path(remotePath: String): WebDavPath {
        val cleanPath = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
        return WebDavPath(authority, cleanPath)
    }

    /**
     * List children of a remote directory.
     * Returns null on error (connection failure, auth error, etc.)
     */
    fun listChildren(remotePath: String): List<WebDavFileInfo>? {
        return try {
            val p = path(remotePath)
            val members = Client.findCollectionMembers(p)
            members.mapNotNull { memberPath ->
                try {
                    val response = Client.findProperties(memberPath, false)
                    val name = memberPath.url.pathSegments.lastOrNull { it.isNotEmpty() } ?: ""
                    if (name.isEmpty()) return@mapNotNull null
                    WebDavFileInfo(
                        name = name,
                        remotePath = remotePath.trimEnd('/') + "/" + name,
                        isDirectory = response.isDirectory,
                        size = response.size,
                        lastModified = response.lastModifiedTime?.toEpochMilli() ?: 0L
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (e: DavException) {
            throw IOException(e.message, e)
        } catch (e: IOException) {
            throw e
        }
    }

    /**
     * Download a remote file to a local file.
     */
    fun downloadFile(remotePath: String, localFile: File, onProgress: (Long) -> Unit) {
        val p = path(remotePath)
        val input = Client.get(p)
        input.use { src ->
            localFile.outputStream().use { dst ->
                val buffer = ByteArray(8192)
                var total = 0L
                var read: Int
                while (src.read(buffer).also { read = it } != -1) {
                    dst.write(buffer, 0, read)
                    total += read
                    onProgress(total)
                }
            }
        }
    }

    /**
     * Get an InputStream for a remote file (for preview, etc.)
     */
    fun getInputStream(remotePath: String): InputStream {
        return Client.get(path(remotePath))
    }

    /**
     * Upload a local file to a remote path.
     * onProgress 接收每次写入的增量字节数（由 RequestBody.writeTo 直接回调）。
     */
    fun uploadFile(localFile: File, remotePath: String, onProgress: (Long) -> Unit) {
        val p = path(remotePath)
        localFile.inputStream().use { inputStream ->
            val response = Client.put(p, localFile.length(), inputStream, onProgress)
            response.use {
                if (!it.isSuccessful) {
                    throw IOException("上传失败: ${it.code} ${it.message}")
                }
            }
        }
    }

    /**
     * Delete a remote file or directory.
     */
    fun delete(remotePath: String) {
        Client.delete(path(remotePath))
    }

    /**
     * Create a remote directory.
     */
    fun mkdir(remotePath: String) {
        Client.makeCollection(path(remotePath))
    }

    /**
     * Move/rename a remote file or directory.
     */
    fun move(fromPath: String, toPath: String) {
        Client.move(path(fromPath), path(toPath))
    }

    /**
     * Check if a remote path exists.
     */
    fun exists(remotePath: String): Boolean {
        return try {
            Client.findProperties(path(remotePath), false)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 获取单个文件的元数据（size + lastModified），不存在返回 null */
    fun getFileMetadata(remotePath: String): WebDavFileInfo? {
        return try {
            val p = path(remotePath)
            val response = Client.findProperties(p, false)
            val name = p.url.pathSegments.lastOrNull { it.isNotEmpty() } ?: return null
            WebDavFileInfo(
                name = name,
                remotePath = remotePath,
                isDirectory = response.isDirectory,
                size = response.size,
                lastModified = response.lastModifiedTime?.toEpochMilli() ?: 0L
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Test connection by listing the root directory.
     * Throws IOException on failure.
     */
    fun testConnection() {
        val rootPath = config.getDisplayPath()
        Client.findCollectionMembers(rootPath)
    }
}

/**
 * WebDAV file info for displaying in the file manager.
 */
data class WebDavFileInfo(
    val name: String,
    val remotePath: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)
