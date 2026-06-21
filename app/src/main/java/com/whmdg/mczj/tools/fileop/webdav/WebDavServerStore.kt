package com.whmdg.mczj.tools.fileop.webdav

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import kotlinx.serialization.json.Json

/**
 * WebDAV server configuration persistence using SharedPreferences + JSON.
 */
object WebDavServerStore {
    private const val KEY_SERVERS = "servers_json"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    fun getAll(context: Context): List<WebDavServerConfig> {
        val prefs = context.getSharedPreferences(AppDataPaths.PREFS_WEBDAV_SERVERS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<WebDavServerConfig>>(saved)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, config: WebDavServerConfig) {
        val all = getAll(context).toMutableList()
        val index = all.indexOfFirst { it.id == config.id }
        if (index != -1) {
            all[index] = config
        } else {
            all.add(config)
        }
        writeAll(context, all)
    }

    fun remove(context: Context, id: Long) {
        val all = getAll(context).filter { it.id != id }
        writeAll(context, all)
    }

    private fun writeAll(context: Context, list: List<WebDavServerConfig>) {
        val prefs = context.getSharedPreferences(AppDataPaths.PREFS_WEBDAV_SERVERS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVERS, json.encodeToString(list)).apply()
    }
}
