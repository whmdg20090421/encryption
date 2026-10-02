package com.whmdg.mczj.tools.fileop.webdav

import at.bitfire.dav4jvm.exception.DavException
import com.whmdg.mczj.tools.fileop.webdav.client.Client
import com.whmdg.mczj.tools.fileop.webdav.client.DavPropfindEntry
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

    /**
     * 严格探测远端资源是否存在（不吞网络异常）。
     *
     * - 存在 → true
     * - 服务端明确返回 404 → false（可安全认定"已被删除"）
     * - 网络错误 / 认证失败等结果未知的情况 → 抛出异常，由调用方决定如何处理
     *
     * 与 [exists] 的区别：exists 把一切异常都当成"不存在"，
     * 不能用于"确认已删除后才允许后续操作"的判断；此类场景必须用本方法。
     */
    fun probeExists(remotePath: String): Boolean =
        Client.probePropertiesOrNull(path(remotePath)) != null

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

    /**
     * 一次性拉取某目录下的整棵子树。
     *
     * 优先用 `PROPFIND Depth: infinity` 一条请求拿回所有后代；服务端不支持
     * （返回非 207，如 403）时自动回退到逐级 `Depth: 1` 递归遍历，行为一致。
     *
     * 返回路径均相对于 [remotePath]（不含前导 '/'），根自身不出现在结果里。
     * 目录也包含在内，便于调用方预先标记 remote dir 已创建。
     */
    fun fetchRemoteTree(remotePath: String): List<RemoteTreeEntry> {
        val base = path(remotePath)
        val entries = try {
            Client.propfindRaw(base, "infinity")
        } catch (e: IOException) {
            val msg = e.message ?: ""
            when {
                // 服务端明确不支持 Depth: infinity（403/405 等）→ 递归 Depth:1 兜底
                msg.contains("HTTP 403") || msg.contains("HTTP 405") ||
                    msg.contains("HTTP 400") || msg.contains("HTTP 501") -> {
                    val acc = mutableListOf<DavPropfindEntry>()
                    walkDepth1(base, acc)
                    acc
                }
                // 其余（404 不存在 / 网络错误）向上抛出，交由调用方判定
                else -> throw e
            }
        }
        // 用 WebDavPath 归一化后的路径（含前导 '/'）做前缀匹配，避免调用方传入
        // 不带前导 '/' 的 remotePath 时 projectTree 匹配失败。
        return projectTree(base.toString(), entries)
    }

    /** Depth:1 递归兜底：逐目录列直接子项，聚合成整棵树。 */
    private fun walkDepth1(nodeBase: WebDavClientPath, acc: MutableList<DavPropfindEntry>) {
        val nodeNorm = normalizePath(nodeBase.toString())
        val entries = Client.propfindRaw(nodeBase, "1")
        for (e in entries) {
            if (e.encodedPath == nodeNorm) continue
            acc.add(e)
            if (e.isDirectory) {
                val name = e.encodedPath.substringAfterLast('/')
                walkDepth1(nodeBase.resolve(name), acc)
            }
        }
    }

    private fun projectTree(queryPath: String, entries: List<DavPropfindEntry>): List<RemoteTreeEntry> {
        val qNorm = normalizePath(queryPath)
        val result = ArrayList<RemoteTreeEntry>(entries.size)
        for (e in entries) {
            if (e.encodedPath == qNorm) continue
            val rel = if (e.encodedPath.startsWith(qNorm)) {
                e.encodedPath.removePrefix(qNorm).trimStart('/')
            } else {
                e.encodedPath.trimStart('/')
            }
            if (rel.isEmpty()) continue
            result.add(RemoteTreeEntry(rel, e.isDirectory, e.size, e.lastModified))
        }
        return result
    }

    private fun normalizePath(p: String): String {
        val decoded = try { java.net.URLDecoder.decode(p, "UTF-8") } catch (_: Exception) { p }
        val noTrailing = if (decoded.length > 1) decoded.trimEnd('/') else decoded
        val withSlash = if (noTrailing.startsWith("/")) noTrailing else "/$noTrailing"
        return withSlash.ifEmpty { "/" }
    }
}

/** 远端整树的一颗节点，路径相对于查询目录。 */
data class RemoteTreeEntry(
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

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
