package com.whmdg.mczj.tools.auth

import android.content.Context
import android.util.Base64
import com.whmdg.mczj.tools.AppDataPaths

object TokenStorage {
    private const val KEY_WK = "wk"
    private const val KEY_IV_W = "iv_w"
    private const val KEY_CT = "ct"
    private const val KEY_IV_T = "iv_t"
    private const val KEY_ID = "key_id"
    private const val KEY_CREATED = "created_at"

    data class Blob(
        val wrappedKey: ByteArray,
        val ivWrap: ByteArray,
        val cipherToken: ByteArray,
        val ivToken: ByteArray,
        val keyId: String,
        val createdAt: Long
    )

    fun load(ctx: Context): Blob? {
        val sp = ctx.getSharedPreferences(AppDataPaths.PREFS_AUTH_TOKEN, Context.MODE_PRIVATE)
        val wk = sp.getString(KEY_WK, null) ?: return null
        val ivW = sp.getString(KEY_IV_W, null) ?: return null
        val ct = sp.getString(KEY_CT, null) ?: return null
        val ivT = sp.getString(KEY_IV_T, null) ?: return null
        val kid = sp.getString(KEY_ID, null) ?: return null
        val ts = sp.getLong(KEY_CREATED, 0L)
        return Blob(
            wrappedKey = Base64.decode(wk, Base64.NO_WRAP),
            ivWrap = Base64.decode(ivW, Base64.NO_WRAP),
            cipherToken = Base64.decode(ct, Base64.NO_WRAP),
            ivToken = Base64.decode(ivT, Base64.NO_WRAP),
            keyId = kid,
            createdAt = ts
        )
    }

    fun save(ctx: Context, blob: Blob) {
        ctx.getSharedPreferences(AppDataPaths.PREFS_AUTH_TOKEN, Context.MODE_PRIVATE).edit()
            .putString(KEY_WK, Base64.encodeToString(blob.wrappedKey, Base64.NO_WRAP))
            .putString(KEY_IV_W, Base64.encodeToString(blob.ivWrap, Base64.NO_WRAP))
            .putString(KEY_CT, Base64.encodeToString(blob.cipherToken, Base64.NO_WRAP))
            .putString(KEY_IV_T, Base64.encodeToString(blob.ivToken, Base64.NO_WRAP))
            .putString(KEY_ID, blob.keyId)
            .putLong(KEY_CREATED, blob.createdAt)
            .apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(AppDataPaths.PREFS_AUTH_TOKEN, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
