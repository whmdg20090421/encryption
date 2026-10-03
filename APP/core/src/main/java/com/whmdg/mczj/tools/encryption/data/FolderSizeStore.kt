package com.whmdg.mczj.tools.encryption.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.whmdg.mczj.tools.AppDataPaths
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import com.whmdg.mczj.tools.util.FileAccessLevel
import com.whmdg.mczj.tools.util.FileAccessor
import com.whmdg.mczj.tools.util.SizeCalcResult
import com.whmdg.mczj.tools.util.calculateFolderSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * 文件夹大小统一存取门面（进程单例）。
 *
 * ## 职责
 *
 * 收敛全项目「路径 → 大小」的读写：
 * - 写入：[saveSize] —— 传入路径、大小、修改时间；size 与 mtime 都未变则跳过，否则落库。
 * - 读取：[getSize] —— 传入路径与「是否实时」标志：
 *   - `realtime = false`：查记录库；命中返回，未命中则自动转实时计算、写回并返回；本地不存在返回 null。
 *   - `realtime = true`：通过 shell 实时计算当前大小，按一致性判断写回后返回；不存在返回 null。
 *
 * ## 存储与迁移
 *
 * 底层为 [FolderSizeDb]（SQLite，`folder_sizes.db`），以 `path` 为主键，
 * 单条写 O(1)、查询走索引 O(log N)，不再全量重写文本文件。
 * 首次使用通过 [AppDataPaths.PREF_KEY_FOLDER_SIZE_DB_MIGRATED] 标志判断是否
 * 已从旧版 `folder_sizes.json` / `folder_sizes.txt` 迁移；未迁移则一次性导入并删除旧文件。
 *
 * ## 减少 IO
 *
 * 高频单条写入先攒入内存 [pending]，达到阈值或定时（[FLUSH_INTERVAL_MS]）后合并成一笔
 * SQLite 事务提交，避免逐条 fsync。读取时先看 [pending] 再看库，保证写后即读一致。
 * [flush] 供生命周期回收点显式调用。
 */
object FolderSizeStore {

    /** 攒够多少条立即落库。 */
    private const val FLUSH_THRESHOLD = 64

    /** 定时落库间隔（毫秒）。 */
    private const val FLUSH_INTERVAL_MS = 1500L

    @Volatile
    private var appContext: Context? = null

    /** 待落库的写入缓冲（路径已归一化）。 */
    private val pending = LinkedHashMap<String, FolderSizeInfo>()

    /** 待落库的子树删除请求。 */
    private val pendingRemoveDesc = ArrayList<String>()

    private val lock = Any()

    /** Compose 可观察的版本号：任何写操作（含缓冲）递增，用于触发刷新。 */
    var version by mutableIntStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var flushLoopStarted = false

    /** 应用启动时调用一次：绑定 Context、执行一次性迁移、启动定时落库。 */
    fun init(context: Context) {
        val app = context.applicationContext
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            appContext = app
            migrateIfNeeded(app)
            if (!flushLoopStarted) {
                flushLoopStarted = true
                scope.launch {
                    while (true) {
                        delay(FLUSH_INTERVAL_MS)
                        flush()
                    }
                }
            }
        }
    }

    private fun requireContext(): Context =
        appContext ?: error("FolderSizeStore 未初始化，请先在 ToolsApp.onCreate 调用 FolderSizeStore.init(context)")

    private fun dbInternal(): FolderSizeDb = FolderSizeDb.getInstance(requireContext())

    private fun normalize(path: String): String = path.trimEnd('/')

    private fun bumpVersion() {
        Snapshot.withMutableSnapshot { version++ }
    }

    // ── 写入 ──

    /**
     * 写入单条路径大小。size 与 mtime 都未变时跳过，避免无谓 IO。
     */
    fun saveSize(path: String, size: Long, mtime: Long) {
        val key = normalize(path)
        synchronized(lock) {
            val pendingRemoval = pendingRemoveDesc.any { key == it || key.startsWith("$it/") }
            val current = pending[key] ?: dbInternal().get(key)
            if (!pendingRemoval && current != null && current.size == size && current.lastModified == mtime) return
            pending[key] = FolderSizeInfo(size, mtime)
            if (pending.size >= FLUSH_THRESHOLD) {
                flushLocked()
            }
        }
        bumpVersion()
    }

    fun remove(path: String) {
        val key = normalize(path)
        synchronized(lock) {
            pending.remove(key)
            pendingRemoveDesc.add(key)
        }
        bumpVersion()
    }

    /** 删除路径自身及其所有后代记录。 */
    fun removeDescendants(path: String) {
        val key = normalize(path)
        synchronized(lock) {
            val prefix = "$key/"
            pending.keys.removeAll { it == key || it.startsWith(prefix) }
            pendingRemoveDesc.add(key)
        }
        bumpVersion()
    }

    /**
     * 提交内存暂存区的统计结果。
     *
     * 读取库里该子树的旧记录，若 [deleteMissing] 为 true（完整快照）则删掉
     * 「本次已不存在」的条目，并只写入「新增 / size 或 mtime 变化」的条目，
     * 最后一笔事务落库。统计完整成功、或用户在弹窗选择「保存」时调用。
     *
     * @param deleteMissing 是否删除本次未扫描到的旧记录。完整扫描为 true；
     *   部分成功（存在不可读子目录）为 false，防止误删漏扫数据。
     */
    fun commitStaging(staging: FolderSizeStaging, deleteMissing: Boolean) {
        val writes = staging.stagedSnapshot()
        if (writes.isEmpty()) return
        // 先把缓冲中的增量写入落库，确保下方读取的「旧记录」是最新的，
        // 避免提交的扫描结果被尚未落库的增量写入覆盖（或反之）。
        synchronized(lock) { flushLocked() }
        val db = dbInternal()
        val existing = db.getDescendants(staging.rootPath)
        val scanned = if (deleteMissing) staging.scannedPaths() else emptySet()
        val removed = if (scanned.isNotEmpty()) existing.keys.filter { it !in scanned } else emptyList()

        val changed = LinkedHashMap<String, FolderSizeInfo>()
        for ((path, info) in writes) {
            val old = existing[path]
            if (old == null || old.size != info.size || old.lastModified != info.lastModified) {
                changed[path] = info
            }
        }
        if (changed.isEmpty() && removed.isEmpty()) return
        db.applyDiff(changed, removed)
        bumpVersion()
    }

    /** 将缓冲区合并为一笔事务落库。可由生命周期回收点显式调用。 */
    fun flush() {
        synchronized(lock) { flushLocked() }
    }

    private fun flushLocked() {
        if (pending.isEmpty() && pendingRemoveDesc.isEmpty()) return
        val db = dbInternal()
        val removes = ArrayList(pendingRemoveDesc)
        val writes = LinkedHashMap(pending)
        pending.clear()
        pendingRemoveDesc.clear()
        // pendingRemoveDesc 既有「精确单条删除」也有「子树删除」，
        // 统一按子树删除即可同时覆盖两种情况（子树删除含路径自身）。
        for (key in removes) db.removeDescendants(key)
        db.bulkPut(writes)
    }

    // ── 读取（记录模式，仅查库/缓冲，不触发实时计算）──

    /** 仅查记录，不触发实时计算。未命中返回 null。 */
    fun peek(path: String): FolderSizeInfo? {
        val key = normalize(path)
        synchronized(lock) {
            pending[key]?.let { return it }
        }
        return dbInternal().get(key)
    }

    fun peekSize(path: String): Long? = peek(path)?.size

    /**
     * 批量读取多个路径的记录（不触发实时计算）。
     * 先合并内存缓冲，再对缺失项按主键批量查库（分块规避 SQLite 参数上限）。
     * 返回 `路径 → 大小`（以调用方传入的原始路径为键），未命中的路径不出现在结果中。
     * 供 UI 一次渲染整个目录时使用，避免逐行查询（N+1）。
     */
    fun peekSizes(paths: Collection<String>): Map<String, Long> {
        if (paths.isEmpty()) return emptyMap()
        val keyToOriginal = HashMap<String, String>(paths.size)
        val result = HashMap<String, Long>(paths.size)
        val missing = ArrayList<String>(paths.size)
        synchronized(lock) {
            for (p in paths) {
                val key = normalize(p)
                keyToOriginal[key] = p
                val cached = pending[key]
                if (cached != null) result[p] = cached.size else missing.add(key)
            }
        }
        if (missing.isNotEmpty()) {
            for ((key, size) in dbInternal().getSizes(missing)) {
                result[keyToOriginal[key] ?: key] = size
            }
        }
        return result
    }

    // ── 读取（统一入口）──

    /**
     * 读取路径大小。
     *
     * @param path 文件或目录的绝对路径
     * @param realtime true = 通过 shell 实时计算；false = 查记录库，未命中自动转实时
     * @return 大小（字节）；路径不存在或无权限时返回 null
     *
     * 注意：realtime = true（或记录未命中而转实时）会阻塞执行整棵子树扫描，
     * 必须在后台线程调用，禁止在主线程直接调用。
     */
    fun getSize(path: String, realtime: Boolean): Long? {
        val key = normalize(path)
        if (!realtime) {
            synchronized(lock) {
                pending[key]?.let { return it.size }
            }
            dbInternal().get(key)?.let { return it.size }
            // 记录未命中：转实时计算、写回并返回
            return computeRealtimeAndStore(key)
        }
        return computeRealtimeAndStore(key)
    }

    /** 实时计算路径大小并写回记录。不存在/无权限返回 null。 */
    private fun computeRealtimeAndStore(path: String): Long? {
        val context = requireContext()
        val accessor = FileAccessor.create(detectAccessLevel(context), context)
        val staging = FolderSizeStaging.create(path)
        val result = runBlocking {
            calculateFolderSize(
                rootPath = path,
                accessor = accessor,
                cache = staging,
                isCancelled = { false },
                cancelFlag = null
            )
        }
        return when (result) {
            is SizeCalcResult.Success -> {
                commitStaging(staging, deleteMissing = result.fullSnapshot)
                result.rootSize
            }
            else -> null
        }
    }

    private fun detectAccessLevel(context: Context): FileAccessLevel = when {
        SpecialPermissionVerifier.isRootAvailable() -> FileAccessLevel.ROOT
        SpecialPermissionVerifier.isShizukuAuthorized(context) -> FileAccessLevel.SHIZUKU
        else -> FileAccessLevel.NORMAL
    }

    // ── 一次性迁移：旧 JSON / 文本 → SQLite ──

    private fun migrateIfNeeded(context: Context) {
        val prefs = AppDataPaths.prefs(context, AppDataPaths.PREFS_FILE_MANAGER)
        if (prefs.getBoolean(AppDataPaths.PREF_KEY_FOLDER_SIZE_DB_MIGRATED, false)) return

        val jsonFile = AppDataPaths.legacyFolderSizeJson(context)
        val txtFile = AppDataPaths.legacyFolderSizeTxt(context)
        val records = LinkedHashMap<String, FolderSizeInfo>()
        if (jsonFile.exists()) {
            runCatching { parseLegacyJson(jsonFile) }.getOrNull()?.let { records.putAll(it) }
        }
        if (txtFile.exists()) {
            runCatching { parseLegacyTxt(txtFile) }.getOrNull()?.let { records.putAll(it) }
        }
        if (records.isNotEmpty()) {
            runCatching { FolderSizeDb.getInstance(context).bulkPut(records) }
        }
        runCatching { jsonFile.delete() }
        runCatching { txtFile.delete() }
        prefs.edit().putBoolean(AppDataPaths.PREF_KEY_FOLDER_SIZE_DB_MIGRATED, true).apply()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseLegacyJson(file: File): Map<String, FolderSizeInfo> {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val folders = root["folders"] as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, FolderSizeInfo>()
        for ((path, el) in folders) {
            val obj = el as? JsonObject ?: continue
            val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
            val mtime = obj["lastModified"]?.jsonPrimitive?.longOrNull ?: 0L
            out[path] = FolderSizeInfo(size, mtime)
        }
        return out
    }

    private fun parseLegacyTxt(file: File): Map<String, FolderSizeInfo> {
        val out = LinkedHashMap<String, FolderSizeInfo>()
        val lines = file.readLines()
        // 首行为版本标识（v1.1 / v1.2），跳过
        for (i in lines.indices) {
            val line = lines[i]
            if (i == 0 && line.startsWith("v")) continue
            if (line.isBlank()) continue
            val parts = line.split("\t")
            if (parts.isEmpty()) continue
            val path = parts[0]
            val size = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val mtime = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            out[path] = FolderSizeInfo(size, mtime)
        }
        return out
    }
}
