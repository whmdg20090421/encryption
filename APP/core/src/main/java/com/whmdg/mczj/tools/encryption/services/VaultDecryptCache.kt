package com.whmdg.mczj.tools.encryption.services

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.encryption.core.FileCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 统一缓存的类型（二级子目录名）。
 *
 * 视频缓存路径由源文件绝对路径唯一决定（见 [VaultDecryptCache.videoPathFor]），
 * 图片 / 音频 / 文本 / 其他按 `{保险箱名}/{相对路径}` 存放。
 */
enum class VaultCacheType(val dirName: String) {
    VIDEO(AppDataPaths.CACHE_DIR_VIDEO),
    IMAGE(AppDataPaths.CACHE_DIR_IMAGE),
    AUDIO(AppDataPaths.CACHE_DIR_AUDIO),
    TEXT(AppDataPaths.CACHE_DIR_TEXT),
    OTHER(AppDataPaths.CACHE_DIR_OTHER)
}

/**
 * 统一缓存索引条目。
 *
 * [cachePath] 为缓存文件绝对路径（唯一，可能带 `.thumb` 后缀）；[encryptedPath]
 * 为源文件绝对路径（可重复，同一源可对应完整视频缓存与缩略图缓存等多条记录），
 * 用于在源文件变化时批量失效该源的全部缓存。
 */
data class VaultCacheEntry(
    val cachePath: String,
    val encryptedPath: String,
    val srcSize: Long,
    val srcMtime: Long
)

/**
 * `{统一缓存}/index.db` 的 SQLite 封装。
 *
 * 选用 SQLite 而非 JSON：缓存条目可达上千，需要按单条查询 / 更新，
 * 且需并发安全与抗损坏能力。
 */
private class VaultCacheIndexDb(context: Context) : SQLiteOpenHelper(
    context,
    AppDataPaths.cacheIndexDb(context).absolutePath,
    null,
    2
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE vault_cache (
                cache_path     TEXT PRIMARY KEY,
                encrypted_path TEXT NOT NULL,
                src_size       INTEGER NOT NULL,
                src_mtime      INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_vault_cache_encrypted ON vault_cache(encrypted_path)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS vault_cache")
        onCreate(db)
    }

    fun query(cachePath: String): VaultCacheEntry? {
        readableDatabase.query(
            "vault_cache",
            arrayOf("cache_path", "encrypted_path", "src_size", "src_mtime"),
            "cache_path = ?",
            arrayOf(cachePath),
            null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return VaultCacheEntry(
                cachePath = c.getString(0),
                encryptedPath = c.getString(1),
                srcSize = c.getLong(2),
                srcMtime = c.getLong(3)
            )
        }
    }

    fun entriesOf(encryptedPath: String): List<VaultCacheEntry> {
        val list = mutableListOf<VaultCacheEntry>()
        readableDatabase.query(
            "vault_cache",
            arrayOf("cache_path", "encrypted_path", "src_size", "src_mtime"),
            "encrypted_path = ?",
            arrayOf(encryptedPath),
            null, null, null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    VaultCacheEntry(
                        cachePath = c.getString(0),
                        encryptedPath = c.getString(1),
                        srcSize = c.getLong(2),
                        srcMtime = c.getLong(3)
                    )
                )
            }
        }
        return list
    }

    fun upsert(entry: VaultCacheEntry) {
        val values = ContentValues().apply {
            put("cache_path", entry.cachePath)
            put("encrypted_path", entry.encryptedPath)
            put("src_size", entry.srcSize)
            put("src_mtime", entry.srcMtime)
        }
        writableDatabase.insertWithOnConflict(
            "vault_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun delete(cachePath: String) {
        writableDatabase.delete("vault_cache", "cache_path = ?", arrayOf(cachePath))
    }

    fun deleteByEncryptedPath(encryptedPath: String) {
        writableDatabase.delete("vault_cache", "encrypted_path = ?", arrayOf(encryptedPath))
    }

    /** 清理缓存文件已不存在的脏记录。 */
    fun pruneMissing() {
        val stale = mutableListOf<String>()
        readableDatabase.query(
            "vault_cache", arrayOf("cache_path"),
            null, null, null, null, null
        ).use { c ->
            while (c.moveToNext()) {
                val cachePath = c.getString(0)
                if (!File(cachePath).exists()) {
                    stale.add(cachePath)
                }
            }
        }
        if (stale.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            stale.forEach { path ->
                writableDatabase.delete("vault_cache", "cache_path = ?", arrayOf(path))
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }
}

/**
 * 文件解密的统一入口。
 *
 * 所有「加密文件 → 明文缓存」的需求都必须经过本对象，由本对象统一负责
 * 路径规则、缓存命中判断与失效重建。普通文件与保险箱文件共用同一套规则：
 *
 * 缓存路径 = `{外部数据目录}/cache/{类型}/{源文件绝对路径去首斜杠}{后缀}`
 *
 * 其中保险箱源文件为磁盘上的 `.whm`，普通源文件为原始文件自身；源文件绝对路径
 * 全局唯一，故缓存路径天然不冲突。完整视频缓存无额外后缀，缩略图缓存追加 `.thumb`。
 *
 * 缓存有效性由 `index.db` 记录的源文件大小 / 最后修改时间判定，源文件一旦变化
 * 即失效并重新生成，不使用时间过期策略。
 */
object VaultDecryptCache {

    /** 进程内只做一次脏记录清理，避免每次命中都全表扫描。 */
    private val pruned = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 按源文件路径加锁，避免同一文件并发解密互相覆盖。 */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** 进程级单例 DB 连接，避免每次解密都开关数据库。 */
    @Volatile
    private var indexDb: VaultCacheIndexDb? = null

    private fun db(context: Context): VaultCacheIndexDb =
        indexDb ?: synchronized(this) {
            indexDb ?: VaultCacheIndexDb(context.applicationContext).also { indexDb = it }
        }

    // ── 路径计算 ──

    /** 视频完整缓存的绝对路径：`{统一缓存}/视频/{源绝对路径去首斜杠}`。 */
    fun videoPathFor(context: Context, encryptedPath: String): String =
        AppDataPaths.videoCacheFile(context, encryptedPath, thumbnail = false).absolutePath

    /** 视频缩略图缓存的绝对路径：完整缓存路径 + `.thumb`。 */
    fun thumbPathFor(context: Context, encryptedPath: String): String =
        AppDataPaths.videoCacheFile(context, encryptedPath, thumbnail = true).absolutePath

    /**
     * 按类型计算非视频明文的缓存路径（图片 / 音频 / 文本等）。
     *
     * 规则：`{统一缓存}/{类型}/{保险箱名}/{保险箱内相对路径}`
     */
    fun typedPathFor(
        context: Context,
        vaultDir: String,
        encryptedPath: String,
        type: VaultCacheType
    ): String {
        val vaultName = File(vaultDir).name
        val relativePath = encryptedPath
            .removePrefix(vaultDir)
            .removePrefix("/")
            .removeSuffix(".whm")
        return File(AppDataPaths.cacheDir(context, type.dirName), "$vaultName/$relativePath").absolutePath
    }

    /**
     * 将加密文件解密到缓存目录。
     *
     * @param vaultDir 保险箱根目录绝对路径（用于推导保险箱名与相对路径）
     * @param encryptedPath 加密源文件（`.whm`）绝对路径
     * @param dek 保险箱数据密钥
     * @param customEncryption 是否启用自定义加密（魔数头 + Nail 混淆）
     * @param type 缓存类型，决定缓存子目录
     * @return 成功时返回解密后文件的绝对路径；失败时返回带原因的 [Result.failure]
     */
    suspend fun decryptToCache(
        context: Context,
        vaultDir: String,
        encryptedPath: String,
        dek: ByteArray,
        customEncryption: Boolean,
        type: VaultCacheType
    ): Result<String> = withContext(Dispatchers.IO) {
        // 视频按源绝对路径组织；其余类型按 {类型}/{保险箱名}/{相对路径}
        val destPath = if (type == VaultCacheType.VIDEO) {
            videoPathFor(context, encryptedPath)
        } else {
            typedPathFor(context, vaultDir, encryptedPath, type)
        }
        synchronized(lockOf(encryptedPath)) {
            try {
                val src = File(encryptedPath)
                if (!src.exists()) {
                    return@synchronized Result.failure(IllegalArgumentException("加密文件不存在: $encryptedPath"))
                }

                val destFile = File(destPath)
                if (isHit(context, destPath, src)) {
                    return@synchronized Result.success(destPath)
                }

                // 缓存失效：清理该源的全部缓存（完整视频 / 缩略图等）后重新解密
                invalidate(context, src)

                destFile.parentFile?.mkdirs()
                FileCodec.decrypt(
                    src = src,
                    dst = destFile,
                    dek = dek,
                    customEncryption = customEncryption
                )
                db(context).upsert(
                    VaultCacheEntry(
                        cachePath = destPath,
                        encryptedPath = src.absolutePath,
                        srcSize = src.length(),
                        srcMtime = src.lastModified()
                    )
                )
                Result.success(destPath)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // ── 命中判断 / 失效 ──

    /** 判断缓存文件是否命中：记录存在、源未变化、文件存在。 */
    fun isHit(context: Context, cachePath: String, src: File): Boolean {
        val db = db(context)
        if (pruned.compareAndSet(false, true)) {
            db.pruneMissing()
        }
        val cached = db.query(cachePath) ?: return false
        return cached.encryptedPath == src.absolutePath &&
            cached.srcSize == src.length() &&
            cached.srcMtime == src.lastModified() &&
            File(cachePath).exists()
    }

    /** 登记一条缓存记录。 */
    fun register(context: Context, cachePath: String, src: File) {
        db(context).upsert(
            VaultCacheEntry(
                cachePath = cachePath,
                encryptedPath = src.absolutePath,
                srcSize = src.length(),
                srcMtime = src.lastModified()
            )
        )
    }

    /** 清除某源文件的全部缓存记录与磁盘文件（源变化或重建前调用）。 */
    fun invalidate(context: Context, src: File) {
        val sourcePath = src.absolutePath
        val db = db(context)
        db.entriesOf(sourcePath).forEach { entry ->
            File(entry.cachePath).delete()
        }
        db.deleteByEncryptedPath(sourcePath)
    }

    private fun lockOf(encryptedPath: String): Any =
        locks.getOrPut(encryptedPath) { Any() }
}
