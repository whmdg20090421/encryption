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
import okhttp3.Request
import okhttp3.Route
import java.util.concurrent.TimeUnit

/**
 * WebDAV client path interface.
 * Replaces MaterialFiles' java8.nio.file.Path dependency.
 */
interface WebDavClientPath {
    val authority: Authority
    val url: HttpUrl
    fun resolve(other: String): WebDavClientPath
}

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

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
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

    @Throws(DavException::class)
    fun findPropertiesOrNull(path: WebDavClientPath, noFollowLinks: Boolean): Response? =
        try {
            findProperties(path, noFollowLinks)
        } catch (e: NotFoundException) {
            null
        } catch (e: IOException) {
            throw e.toDavException()
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
