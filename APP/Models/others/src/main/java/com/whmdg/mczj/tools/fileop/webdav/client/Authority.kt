/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.whmdg.mczj.tools.fileop.webdav.client

data class Authority(
    val protocol: Protocol,
    val host: String,
    val port: Int,
    val username: String
) {
    fun toUriAuthorityString(): String {
        val userInfo = username.ifEmpty { null }
        val portStr = if (port != protocol.defaultPort) ":$port" else ""
        return if (userInfo != null) "$userInfo@$host$portStr" else "$host$portStr"
    }

    override fun toString(): String = toUriAuthorityString()
}
