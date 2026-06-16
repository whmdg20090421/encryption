package com.whmdg.mczj.tools.fileop.webdav

import com.whmdg.mczj.tools.fileop.webdav.client.Authority
import com.whmdg.mczj.tools.fileop.webdav.client.WebDavClientPath
import okhttp3.HttpUrl

/**
 * Lightweight WebDAV path implementation.
 * Replaces MaterialFiles' NIO-based WebDavPath (which depends on ByteStringListPath).
 */
class WebDavPath(
    override val authority: Authority,
    private val pathString: String
) : WebDavClientPath {

    override val url: HttpUrl
        get() = HttpUrl.Builder()
            .scheme(authority.protocol.httpScheme)
            .host(authority.host)
            .apply {
                val port = authority.port
                if (port != authority.protocol.defaultPort) {
                    port(port)
                }
            }
            .addPathSegments(pathString.removePrefix("/"))
            .build()

    override fun resolve(other: String): WebDavPath {
        val base = if (pathString.endsWith("/")) pathString else "$pathString/"
        return WebDavPath(authority, "$base$other")
    }

    override fun toString(): String = pathString

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebDavPath) return false
        return authority == other.authority && pathString == other.pathString
    }

    override fun hashCode(): Int = 31 * authority.hashCode() + pathString.hashCode()
}
