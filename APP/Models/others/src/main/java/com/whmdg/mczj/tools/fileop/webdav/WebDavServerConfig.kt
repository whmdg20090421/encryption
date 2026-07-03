package com.whmdg.mczj.tools.fileop.webdav

import com.whmdg.mczj.tools.fileop.webdav.client.AccessTokenAuthentication
import com.whmdg.mczj.tools.fileop.webdav.client.Authentication
import com.whmdg.mczj.tools.fileop.webdav.client.Authority
import com.whmdg.mczj.tools.fileop.webdav.client.NoneAuthentication
import com.whmdg.mczj.tools.fileop.webdav.client.PasswordAuthentication
import com.whmdg.mczj.tools.fileop.webdav.client.Protocol
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
data class WebDavServerConfig(
    val id: Long = Random.nextLong(),
    val name: String = "",
    val protocol: String = "davs",    // "dav" | "davs"
    val host: String = "",
    val port: Int = 443,
    val username: String = "",
    val password: String = "",
    val authType: String = "password", // "password" | "token" | "none"
    val relativePath: String = ""
) {
    fun toAuthority(): Authority {
        val proto = if (protocol == "dav") Protocol.DAV else Protocol.DAVS
        return Authority(proto, host, port, username)
    }

    fun toAuthentication(): Authentication = when (authType) {
        "password" -> PasswordAuthentication(password)
        "token" -> AccessTokenAuthentication(password) // token stored in password field
        else -> NoneAuthentication
    }

    fun getDefaultName(): String {
        val proto = if (protocol == "dav") Protocol.DAV else Protocol.DAVS
        val portStr = if (port != proto.defaultPort) ":$port" else ""
        val userStr = if (username.isNotEmpty()) "$username@" else ""
        val base = "$userStr$host$portStr"
        return if (relativePath.isNotEmpty()) "$base/$relativePath" else base
    }

    fun getDisplayPath(): WebDavPath {
        val authority = toAuthority()
        val path = if (relativePath.isNotEmpty()) "/$relativePath" else "/"
        return WebDavPath(authority, path)
    }
}
