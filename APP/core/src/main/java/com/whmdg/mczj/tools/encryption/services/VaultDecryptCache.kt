package com.whmdg.mczj.tools.encryption.services

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.whmdg.mczj.tools.encryption.core.FileCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 保险箱解密缓存的统一类型。
 *
 * 每类对应 `cacheDir/vault_cache/` 下的一个中文子目录，
 * 便于用户与调试时直接辨认缓存内容。
 */
enum class VaultCacheType(val dirName: String) {
    IMAGE("图片"),
    VIDEO("视频"),
    AUDIO("音频"),
    TEXT("文本"),
    OTHER("其他")
}

/**
 * 保险箱解密缓存索引条目。
 *
 * 键为加密源文件（`.whm`）的绝对路径，值为缓存文件位置与解密时源文件的
 * 大小 / 最后修改时间，用于判断缓存是否失效。
 */
data class VaultCacheEntry(
    val encryptedPath: String,
    val cachePath: String,
    val srcSize: Long,
    val srcMtime: Long
)

/**
 * `vault_cache/index.db` 的 SQLite 封装。
 *
 * 选用 SQLite 而非 JSON：缓存条目可达上千（尤其图片），需要按单条查询 / 更新，
 * 且需并发安全与抗损坏能力。
 */
private class VaultCacheIndexDb(context: Context) : SQLiteOpenHelper(
    context,
    File(context.cacheDir, "vault_cache/index.db").absolutePath,
    null,
    1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE vault_cache (
                encrypted_path TEXT PRIMARY KEY,
                cache_path     TEXT NOT NULL,
                src_size       INTEGER NOT NULL,
                src_mtime      INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS vault_cache")
        onCreate(db)
    }

    fun query(encryptedPath: String): VaultCacheEntry? {
        readableDatabase.query(
            "vault_cache",
            arrayOf("encrypted_path", "cache_path", "src_size", "src_mtime"),
            "encrypted_path = ?",
            arrayOf(encryptedPath),
            null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return VaultCacheEntry(
                encryptedPath = c.getString(0),
                cachePath = c.getString(1),
                srcSize = c.getLong(2),
                srcMtime = c.getLong(3)
            )
        }
    }

    fun upsert(entry: VaultCacheEntry) {
        val values = ContentValues().apply {
            put("encrypted_path", entry.encryptedPath)
            put("cache_path", entry.cachePath)
            put("src_size", entry.srcSize)
            put("src_mtime", entry.srcMtime)
        }
        writableDatabase.insertWithOnConflict(
            "vault_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun delete(encryptedPath: String) {
        writableDatabase.delete("vault_cache", "encrypted_path = ?", arrayOf(encryptedPath))
    }

    /** 清理缓存文件已不存在的脏记录（系统单独清缓存文件时可能留下）。 */
    fun pruneMissing(cacheRoot: File) {
        val stale = mutableListOf<String>()
        readableDatabase.query(
            "vault_cache", arrayOf("encrypted_path", "cache_path"),
            null, null, null, null, null
        ).use { c ->
            while (c.moveToNext()) {
                val cachePath = c.getString(1)
                if (!File(cacheRoot, cachePath).exists()) {
                    stale.add(c.getString(0))
                }
            }
        }
        if (stale.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            stale.forEach { path ->
                writableDatabase.delete("vault_cache", "encrypted_path = ?", arrayOf(path))
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }
}

/**
 * 保险箱文件解密的统一入口。
 *
 * 所有「保险箱加密文件 → 外部缓存目录明文」的需求都必须经过 [decryptToCache]，
 * 由本对象统一负责路径规则、缓存命中判断与失效重建。
 *
 * 缓存路径规则：
 * ```
 * cacheDir/vault_cache/{类型中文名}/{保险箱名}/{保险箱内相对路径}
 * ```
 * 例如 `cacheDir/vault_cache/音频/我的保险箱/music/song.mp3`。
 *
 * 缓存有效性由 `index.db` 记录的源文件大小 / 最后修改时间判定，源文件一旦变化
 * 即失效并重新解密，不使用时间过期策略。
 */
object VaultDecryptCache {

    private const val CACHE_ROOT_NAME = "vault_cache"

    /** 进程内只做一次脏记录清理，避免每次命中都全表扫描。 */
    private val pruned = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 按加密源路径加锁，避免同一文件并发解密互相覆盖。 */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** 进程级单例 DB 连接，避免每次解密都开关数据库。 */
    @Volatile
    private var indexDb: VaultCacheIndexDb? = null

    private fun db(context: Context): VaultCacheIndexDb =
        indexDb ?: synchronized(this) {
            indexDb ?: VaultCacheIndexDb(context.applicationContext).also { indexDb = it }
        }

    private fun cacheRoot(context: Context): File = File(context.cacheDir, CACHE_ROOT_NAME)

    /**
     * 将保险箱加密文件解密到缓存目录。
     *
     * @param vaultDir 保险箱根目录绝对路径（用于推导保险箱名与相对路径）
     * @param encryptedPath 加密源文件（`.whm`）绝对路径
     * @param dek 保险箱数据密钥
     * @param customEncryption 是否启用自定义加密（魔数头 + Nail 混淆）
     * @param type 缓存类型，决定缓存子目录；未传入（null）时兜底为 [VaultCacheType.OTHER]
     * @return 成功时返回解密后文件的绝对路径；失败时返回带原因的 [Result.failure]
     */
    suspend fun decryptToCache(
        context: Context,
        vaultDir: String,
        encryptedPath: String,
        dek: ByteArray,
        customEncryption: Boolean,
        type: VaultCacheType? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val cacheType = type ?: VaultCacheType.OTHER
        val lock = locks.getOrPut(encryptedPath) { Any() }
        synchronized(lock) {
            try {
                val src = File(encryptedPath)
                if (!src.exists()) {
                    return@synchronized Result.failure(IllegalArgumentException("加密文件不存在: $encryptedPath"))
                }

                val vaultName = File(vaultDir).name
                val relativePath = encryptedPath
                    .removePrefix(vaultDir)
                    .removePrefix("/")
                    .removeSuffix(".whm")
                val relativeCachePath = "${cacheType.dirName}/$vaultName/$relativePath"

                val root = cacheRoot(context)
                val destFile = File(root, relativeCachePath)
                val db = db(context)
                if (pruned.compareAndSet(false, true)) {
                    db.pruneMissing(root)
                }

                val cached = db.query(encryptedPath)
                if (cached != null &&
                    cached.cachePath == relativeCachePath &&
                    cached.srcSize == src.length() &&
                    cached.srcMtime == src.lastModified() &&
                    destFile.exists()
                ) {
                    return@synchronized Result.success(destFile.absolutePath)
                }

                // 缓存不存在或已失效：清理旧缓存后重新解密
                if (destFile.exists()) destFile.delete()
                destFile.parentFile?.mkdirs()

                FileCodec.decrypt(
                    src = src,
                    dst = destFile,
                    dek = dek,
                    customEncryption = customEncryption
                )

                db.upsert(
                    VaultCacheEntry(
                        encryptedPath = encryptedPath,
                        cachePath = relativeCachePath,
                        srcSize = src.length(),
                        srcMtime = src.lastModified()
                    )
                )
                Result.success(destFile.absolutePath)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 由加密源路径计算缓存文件路径（不执行解密）。
     *
     * 供需要预知缓存位置（如构建播放列表、缩略图映射）的场景使用。
     *
     * @param type 缓存类型；未传入（null）时兜底为 [VaultCacheType.OTHER]
     */
    fun cachePathFor(
        context: Context,
        vaultDir: String,
        encryptedPath: String,
        type: VaultCacheType? = null
    ): String {
        val cacheType = type ?: VaultCacheType.OTHER
        val vaultName = File(vaultDir).name
        val relativePath = encryptedPath
            .removePrefix(vaultDir)
            .removePrefix("/")
            .removeSuffix(".whm")
        return File(cacheRoot(context), "${cacheType.dirName}/$vaultName/$relativePath").absolutePath
    }
}
