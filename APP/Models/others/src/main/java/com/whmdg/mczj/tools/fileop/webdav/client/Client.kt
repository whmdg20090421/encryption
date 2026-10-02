/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.whmdg.mczj.tools.fileop.webdav.client

import okhttp3.Response as OkHttpResponse
import at.bitfire.dav4jvm.DavCollection
import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.HttpUtils
import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.exception.ConflictException
import at.bitfire.dav4jvm.exception.DavException
import at.bitfire.dav4jvm.exception.ForbiddenException
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.exception.NotFoundException
import at.bitfire.dav4jvm.exception.PreconditionFailedException
import at.bitfire.dav4jvm.exception.ServiceUnavailableException
import at.bitfire.dav4jvm.exception.UnauthorizedException
import at.bitfire.dav4jvm.property.webdav.CreationDate
import at.bitfire.dav4jvm.property.webdav.GetContentLength
import at.bitfire.dav4jvm.property.webdav.GetLastModified
import at.bitfire.dav4jvm.property.webdav.ResourceType
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.time.Instant
import java.util.Collections
import java.util.WeakHashMap
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Route
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.TimeUnit

/**
 * WebDAV client path interface.
 * Replaces MaterialFiles' java.nio.file.Path dependency.
 */
interface WebDavClientPath {
    val authority: Authority
    val url: HttpUrl
    fun resolve(other: String): WebDavClientPath
}

/**
 * PROPFIND 返回的单条资源。encodedPath 为服务端 href 归一化后的绝对路径（已解码）。
 */
data class DavPropfindEntry(
    val encodedPath: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

// See also https://github.com/miquels/webdavfs/blob/master/fuse.go
object Client {
    private val FILE_PROPERTIES = arrayOf(
        ResourceType.NAME,
        CreationDate.NAME,
        GetContentLength.NAME,
        GetLastModified.NAME
    )

    @Volatile
    lateinit var authenticator: Authenticator

    private val clients = mutableMapOf<Authority, OkHttpClient>()

    private val collectionMemberCache =
        Collections.synchronizedMap(WeakHashMap<WebDavClientPath, Response>())

    /**
     * 并发上传支持的最大并行连接数，与 UI 的 `max_concurrency` 上限保持一致。
     *
     * HTTP/2 会在同一 host 上复用单条 TCP 连接、多路复用所有请求流；当多个大文件传输占满
     * 连接级/流级窗口时，同连接上的小文件可能长时间等不到响应头而触发 read timeout。
     * 改用 HTTP/1.1 后，OkHttp 会为每个并行请求各建一条独立连接，
     * 使每个上传任务拥有自己的传输通道，互不抢占。
     */
    private const val MAX_PARALLEL_REQUESTS = 10

    private val PROPFIND_XML = "application/xml; charset=utf-8".toMediaType()

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            // 服务端在并发下响应可能较慢（列目录、写元数据等），默认 10s 读超时过短，
            // 放宽到 20s 以减少 readResponseHeaders 超时。
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            // 关闭 HTTP/2 多路复用：HTTP/1.1 下每个并行请求独占一条连接，
            // 避免大文件与小文件共享同一连接导致小文件超时。
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(MAX_PARALLEL_REQUESTS, 1, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply {
                maxRequests = MAX_PARALLEL_REQUESTS
                maxRequestsPerHost = MAX_PARALLEL_REQUESTS
            })
            .build()
    }

    @Throws(IOException::class)
    private fun getClient(authority: Authority): OkHttpClient {
        synchronized(clients) {
            var client = clients[authority]
            if (client == null) {
                val authenticatorInterceptor =
                    OkHttpAuthenticatorInterceptor(authenticator, authority)
                client = okHttpClient.newBuilder()
                    .followRedirects(false)
                    .cookieJar(MemoryCookieJar())
                    .addNetworkInterceptor(authenticatorInterceptor)
                    .authenticator(authenticatorInterceptor)
                    .build()
                clients[authority] = client
            }
            return client
        }
    }

    @Throws(DavException::class)
    fun makeCollection(path: WebDavClientPath) {
        try {
            DavResource(getClient(path.authority), path.url).mkCol(null) {}
        } catch (e: IOException) {
            throw e.toDavException()
        }
    }

    @Throws(DavException::class)
    fun makeFile(path: WebDavClientPath) {
        try {
            put(path, 0L, ByteArray(0).inputStream(), {}).use {}
        } catch (e: IOException) {
            throw e.toDavException()
        }
    }

    @Throws(DavException::class)
    fun delete(path: WebDavClientPath) {
        try {
            DavResource(getClient(path.authority), path.url).delete {}
        } catch (e: IOException) {
            throw e.toDavException()
        }
        collectionMemberCache -= path
    }

    @Throws(DavException::class)
    fun move(source: WebDavClientPath, target: WebDavClientPath) {
        if (source.authority != target.authority) {
            throw IOException("Paths aren't on the same authority")
        }
        try {
            DavResource(getClient(source.authority), source.url).move(target.url, false) {}
        } catch (e: IOException) {
            throw e.toDavException()
        }
        collectionMemberCache -= source
        collectionMemberCache -= target
    }

    @Throws(DavException::class)
    fun get(path: WebDavClientPath): InputStream =
        try {
            DavResource(getClient(path.authority), path.url).getCompat("*/*", null)
        } catch (e: IOException) {
            throw e.toDavException()
        }

    @Throws(DavException::class)
    fun findCollectionMembers(path: WebDavClientPath): List<WebDavClientPath> =
        buildList {
            try {
                DavCollection(getClient(path.authority), path.url)
                    .propfind(1, *FILE_PROPERTIES) { response, relation ->
                        if (relation != Response.HrefRelation.MEMBER) {
                            return@propfind
                        }
                        this += path.resolve(response.hrefName())
                            .also {
                                if (response.isSuccess()) {
                                    collectionMemberCache[it] = response
                                }
                            }
                    }
            } catch (e: IOException) {
                throw e.toDavException()
            }
        }

    /**
     * 以指定 Depth 发起一次 PROPFIND 并解析全部条目。
     *
     * dav4jvm 的 propfind(depth: Int) 只能表达整数深度，无法发送 `Depth: infinity`，
     * 因此这里直接发原始 OkHttp 请求，authentication interceptor 仍由 getClient 提供。
     * 解析用 Android 自带 XmlPullParser，流式读取，避免大目录响应整体入内存。
     *
     * @param depth "1" 或 "infinity"
     * @throws java.io.IOException 请求失败或服务端返回非 207
     */
    @Throws(java.io.IOException::class)
    fun propfindRaw(path: WebDavClientPath, depth: String): List<DavPropfindEntry> {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:">
              <D:prop>
                <D:resourcetype/>
                <D:getcontentlength/>
                <D:getlastmodified/>
              </D:prop>
            </D:propfind>
        """.trimIndent().toRequestBody(PROPFIND_XML)
        val request = Request.Builder()
            .url(path.url)
            .method("PROPFIND", body)
            .header("Depth", depth)
            .build()
        val response = getClient(path.authority).newCall(request).execute()
        response.use { resp ->
            if (resp.code != HttpURLConnection.HTTP_MULTI_STATUS) {
                throw IOException("PROPFIND(depth=$depth) HTTP ${resp.code} ${resp.message}")
            }
            val input = resp.body?.byteStream() ?: throw IOException("PROPFIND 响应无 body")
            return parseMultiStatus(input)
        }
    }

    private fun parseMultiStatus(input: InputStream): List<DavPropfindEntry> {
        val parser = android.util.Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val result = mutableListOf<DavPropfindEntry>()
        var href: String? = null
        var isDir = false
        var size = 0L
        var lastModified = 0L
        var inResponse = false
        var inResourceType = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (localName(parser.name)) {
                    "response" -> {
                        inResponse = true; href = null; isDir = false; size = 0L; lastModified = 0L
                    }
                    "resourcetype" -> inResourceType = true
                    // resourcetype 内的 collection 子元素即表示该资源是目录
                    "collection" -> if (inResponse && inResourceType) isDir = true
                    "href" -> if (inResponse && href == null) href = parser.nextText()
                    "getcontentlength" -> if (inResponse) size = parser.nextText().trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> if (inResponse) lastModified = parseHttpDate(parser.nextText().trim())
                }
                XmlPullParser.END_TAG -> when (localName(parser.name)) {
                    "resourcetype" -> inResourceType = false
                    "response" -> {
                        if (inResponse && href != null) {
                            result.add(DavPropfindEntry(decodeHref(href!!), isDir, size, lastModified))
                        }
                        inResponse = false
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun localName(raw: String): String = raw.substringAfterLast(':')

    /** href 形如 `/webdav/sandbox/sub1/`，去掉尾斜杠并按 URL 解码得到稳定路径。 */
    private fun decodeHref(href: String): String {
        val decoded = percentDecode(href)
        val noTrailing = if (decoded.length > 1) decoded.trimEnd('/') else decoded
        return noTrailing.ifEmpty { "/" }
    }

    /**
     * 仅解码 %XX 的百分号转义，不把 '+' 当作空格（URL 路径中 '+' 是字面量）。
     * URLDecoder 的语义针对 application/x-www-form-urlencoded，用在这里会误伤含 '+' 的文件名。
     */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            val bytes = c.toString().toByteArray(Charsets.UTF_8)
            out.write(bytes, 0, bytes.size)
            i++
        }
        return out.toString("UTF-8")
    }

    private fun parseHttpDate(s: String): Long = try {
        java.time.ZonedDateTime.parse(s, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli()
    } catch (_: Exception) { 0L }

    @Throws(DavException::class)
    fun findPropertiesOrNull(path: WebDavClientPath, noFollowLinks: Boolean): Response? =
        try {
            findProperties(path, noFollowLinks)
        } catch (e: NotFoundException) {
            null
        } catch (e: IOException) {
            throw e.toDavException()
        }

    /**
     * 严格探测远端资源是否存在：绕过 collectionMemberCache，直连服务器做 PROPFIND。
     *
     * - 存在 → 返回元数据
     * - 确认不存在（服务端返回 404）→ 返回 null
     * - 网络错误 / 认证失败 / 服务端异常（结果未知）→ 抛出异常
     *
     * 用于"删除前查元数据""云端数据库存在性对账"等必须区分
     * "确实不存在"与"暂时查不到"的场景，不可用吞异常的 exists() 替代。
     */
    @Throws(DavException::class, IOException::class)
    fun probePropertiesOrNull(path: WebDavClientPath): Response? {
        try {
            return findProperties(
                DavResource(getClient(path.authority), path.url), *FILE_PROPERTIES
            )
        } catch (e: NotFoundException) {
            return null
        }
    }

    @Throws(DavException::class)
    fun findProperties(path: WebDavClientPath, noFollowLinks: Boolean): Response {
        synchronized(collectionMemberCache) {
            collectionMemberCache.remove(path)?.let { return it }
        }
        try {
            return findProperties(
                DavResource(getClient(path.authority), path.url), *FILE_PROPERTIES
            )
        } catch (e: IOException) {
            throw e.toDavException()
        }
    }

    @Throws(DavException::class, IOException::class)
    internal fun findProperties(
        resource: DavResource,
        vararg properties: Property.Name
    ): Response {
        var responseRef: Response? = null
        resource.propfind(0, *properties) { response, relation ->
            if (relation != Response.HrefRelation.SELF) {
                return@propfind
            }
            if (responseRef != null) {
                throw DavException("Duplicate response for self")
            }
            responseRef = response
        }
        val response = responseRef ?: throw DavException("Couldn't find a response for self")
        response.checkSuccess()
        return response
    }

    @Throws(DavException::class)
    fun setLastModifiedTime(path: WebDavClientPath, lastModifiedTime: Instant) {
        if (true) {
            return
        }
        // The following doesn't work on most servers. See also
        // https://github.com/sabre-io/dav/issues/1277
        try {
            DavResource(getClient(path.authority), path.url).proppatch(
                mapOf(GetLastModified.NAME to HttpUtils.formatDate(lastModifiedTime)), emptyList()
            ) { response, _ -> response.checkSuccess() }
        } catch (e: IOException) {
            throw e.toDavException()
        }
    }

    @Throws(DavException::class)
    fun put(
        path: WebDavClientPath,
        contentLength: Long,
        inputStream: InputStream,
        onProgress: (Long) -> Unit
    ): OkHttpResponse =
        try {
            DavResource(getClient(path.authority), path.url)
                .putCompat(contentLength, inputStream, onProgress)
        } catch (e: IOException) {
            throw e.toDavException()
        }

    // @see DavResource.checkStatus
    private fun Response.checkSuccess() {
        if (isSuccess()) {
            return
        }
        val status = status!!
        throw when (status.code) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> UnauthorizedException(status.message)
            HttpURLConnection.HTTP_FORBIDDEN -> ForbiddenException(status.message)
            HttpURLConnection.HTTP_NOT_FOUND -> NotFoundException(status.message)
            HttpURLConnection.HTTP_CONFLICT -> ConflictException(status.message)
            HttpURLConnection.HTTP_PRECON_FAILED -> PreconditionFailedException(status.message)
            HttpURLConnection.HTTP_UNAVAILABLE -> ServiceUnavailableException(status.message)
            else -> HttpException(status.code, status.message)
        }
    }

    private class OkHttpAuthenticatorInterceptor(
        private val authenticator: Authenticator,
        private val authority: Authority
    ) : AuthenticatorInterceptor {
        private var authenticatorInterceptorCache:
            Pair<Authentication, AuthenticatorInterceptor>? = null

        private fun getAuthenticatorInterceptor(): AuthenticatorInterceptor {
            val authentication = authenticator.getAuthentication(authority)
                ?: throw IOException("No authentication found for $authority")
            authenticatorInterceptorCache?.let {
                (cachedAuthentication, cachedAuthenticatorInterceptor) ->
                if (cachedAuthentication == authentication) {
                    return cachedAuthenticatorInterceptor
                }
            }
            return authentication.createAuthenticatorInterceptor(authority).also {
                authenticatorInterceptorCache = authentication to it
            }
        }

        override fun authenticate(route: Route?, response: OkHttpResponse): Request? =
            getAuthenticatorInterceptor().authenticate(route, response)

        override fun intercept(chain: Interceptor.Chain): OkHttpResponse =
            getAuthenticatorInterceptor().intercept(chain)
    }
}
