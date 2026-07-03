package com.whmdg.mczj.tools.fileop.webdav

import com.whmdg.mczj.tools.fileop.webdav.client.Authentication
import com.whmdg.mczj.tools.fileop.webdav.client.Authenticator
import com.whmdg.mczj.tools.fileop.webdav.client.Authority

/**
 * WebDAV authenticator that manages server credentials.
 * Follows MaterialFiles' WebDavServerAuthenticator pattern.
 */
object WebDavAuthenticator : Authenticator {
    private val transientServers = mutableListOf<WebDavServerConfig>()
    private val persistentServers = mutableListOf<WebDavServerConfig>()

    fun addTransientServer(config: WebDavServerConfig) {
        synchronized(transientServers) { transientServers.add(config) }
    }

    fun removeTransientServer(config: WebDavServerConfig) {
        synchronized(transientServers) { transientServers.remove(config) }
    }

    fun setPersistentServers(servers: List<WebDavServerConfig>) {
        synchronized(persistentServers) {
            persistentServers.clear()
            persistentServers.addAll(servers)
        }
    }

    override fun getAuthentication(authority: Authority): Authentication? {
        val server = synchronized(transientServers) {
            transientServers.find { it.host == authority.host && it.port == authority.port }
        } ?: synchronized(persistentServers) {
            persistentServers.find { it.host == authority.host && it.port == authority.port }
        }
        return server?.toAuthentication()
    }
}
