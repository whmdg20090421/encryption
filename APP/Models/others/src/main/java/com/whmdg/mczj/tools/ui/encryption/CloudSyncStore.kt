package com.whmdg.mczj.tools.ui.encryption

import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import org.json.JSONArray
import org.json.JSONObject

/**
 * 云盘同步项持久化存储。
 *
 * 使用 SharedPreferences + JSON 存储同步项列表，
 * 数据保存在 AppDataPaths.cloudSync() 目录对应的 SharedPreferences 中。
 */
object CloudSyncStore {

    private const val KEY_SYNC_ITEMS = "sync_items"

    private fun prefs(context: Context) =
        context.getSharedPreferences(AppDataPaths.PREFS_CLOUD_SYNC, Context.MODE_PRIVATE)

    /** 加载已保存的同步项列表 */
    fun load(context: Context): List<CloudSyncItem> {
        val json = prefs(context).getString(KEY_SYNC_ITEMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                CloudSyncItem(
                    id = obj.getString("id"),
                    vaultId = obj.optInt("vaultId", 0),
                    vaultName = obj.getString("vaultName"),
                    type = obj.getString("type"),
                    vaultSize = obj.optLong("vaultSize", 0),
                    lastSyncTime = obj.optString("lastSyncTime", "未同步"),
                    cloudSize = obj.optLong("cloudSize", 0),
                    diffFileCount = obj.optInt("diffFileCount", 0),
                    webdavPath = obj.optString("webdavPath", ""),
                    localFileCount = if (obj.has("localFileCount")) obj.optInt("localFileCount") else null,
                    cloudFileCount = if (obj.has("cloudFileCount")) obj.optInt("cloudFileCount") else null
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 保存同步项列表 */
    fun save(context: Context, items: List<CloudSyncItem>) {
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("vaultId", item.vaultId)
            obj.put("vaultName", item.vaultName)
            obj.put("type", item.type)
            obj.put("vaultSize", item.vaultSize)
            obj.put("lastSyncTime", item.lastSyncTime)
            obj.put("cloudSize", item.cloudSize)
            obj.put("diffFileCount", item.diffFileCount)
            obj.put("webdavPath", item.webdavPath)
            if (item.localFileCount != null) obj.put("localFileCount", item.localFileCount)
            if (item.cloudFileCount != null) obj.put("cloudFileCount", item.cloudFileCount)
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY_SYNC_ITEMS, arr.toString()).apply()
    }

    /** 添加一个同步项（自动去重） */
    fun add(context: Context, item: CloudSyncItem) {
        val items = load(context).toMutableList()
        if (items.none { it.id == item.id }) {
            items.add(item)
            save(context, items)
        }
    }

    /** 删除一个同步项 */
    fun remove(context: Context, id: String) {
        val items = load(context).filter { it.id != id }
        save(context, items)
    }

    /** 更新指定同步项（按 id 匹配） */
    fun update(context: Context, id: String, transform: (CloudSyncItem) -> CloudSyncItem) {
        val items = load(context).toMutableList()
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx] = transform(items[idx])
            save(context, items)
        }
    }
}
