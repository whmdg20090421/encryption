package com.whmdg.mczj.tools.ui.hook.内存管理

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whmdg.mczj.tools.security.Permission
import com.whmdg.mczj.tools.security.ShellExecutor
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MemoryProcessInfo(
    val processName: String,
    val displayName: String,
    val pssKb: Long,
    val isSystem: Boolean
)

data class KernelMemoryBreakdown(
    val sUnreclaimKb: Long,
    val kernelStackKb: Long,
    val vmallocUsedKb: Long,
    val cmaUsedKb: Long,
    val pageTablesKb: Long,
    val dmaBufKb: Long,
    val gpuKb: Long,
    val totalKb: Long    // 来自 dumpsys "Used RAM" 行的 kernel 值
)

data class CacheInfo(
    val buffersKb: Long,
    val cachedKb: Long,
    val swapCachedKb: Long,
    val totalKb: Long
)

data class MemoryUsageUiState(
    val isLoading: Boolean = true,
    val rootAvailable: Boolean = true,
    val totalRamKb: Long = 0,
    val memAvailableKb: Long = 0,
    val realUsedKb: Long = 0,
    val kernelInfo: KernelMemoryBreakdown? = null,
    val cacheInfo: CacheInfo? = null,
    val processList: List<MemoryProcessInfo> = emptyList(),
    val error: String? = null
)

class MemoryUsageViewModel(application: Application) : AndroidViewModel(application) {

    private val packageManager: PackageManager = application.packageManager
    private val _uiState = MutableStateFlow(MemoryUsageUiState())
    val uiState: StateFlow<MemoryUsageUiState> = _uiState.asStateFlow()

    init {
        checkRootAndLoad()
    }

    fun checkRootAndLoad() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val hasRoot = withContext(Dispatchers.IO) {
                SpecialPermissionVerifier.isRootAvailable()
            }

            if (!hasRoot) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    rootAvailable = false
                )
                return@launch
            }

            loadMemoryInfo()
        }
    }

    fun loadMemoryInfo() {
        viewModelScope.launch {
            loadMemoryInfoSync()
        }
    }

    fun clearCache(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    ShellExecutor.execute(Permission.ROOT, "sync && echo 3 > /proc/sys/vm/drop_caches")
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (success) {
                loadMemoryInfoSync()
            }
            onComplete(success)
        }
    }

    private suspend fun loadMemoryInfoSync() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        try {
            val memInfoData = withContext(Dispatchers.IO) { parseProcMeminfo() }
            val dumpsysResult = withContext(Dispatchers.IO) { parseDumpsysMeminfo() }

            val kernel = KernelMemoryBreakdown(
                sUnreclaimKb = memInfoData.sUnreclaim,
                kernelStackKb = memInfoData.kernelStack,
                vmallocUsedKb = memInfoData.vmallocUsed,
                cmaUsedKb = memInfoData.cmaUsed,
                pageTablesKb = memInfoData.pageTables,
                dmaBufKb = dumpsysResult.dmaBufTotalKb,
                gpuKb = dumpsysResult.gpuKb,
                totalKb = dumpsysResult.kernelTotalKb
            )

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                totalRamKb = memInfoData.totalRam,
                memAvailableKb = memInfoData.memAvailable,
                realUsedKb = memInfoData.realUsed,
                kernelInfo = kernel,
                cacheInfo = memInfoData.cacheInfo,
                processList = dumpsysResult.processes,
                error = null
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = "加载失败: ${e.message}")
        }
    }

    private data class ProcMeminfoResult(
        val totalRam: Long,
        val memAvailable: Long,
        val realUsed: Long,
        val sUnreclaim: Long,
        val kernelStack: Long,
        val vmallocUsed: Long,
        val cmaUsed: Long,
        val pageTables: Long,
        val cacheInfo: CacheInfo
    )

    private fun parseProcMeminfo(): ProcMeminfoResult {
        val output = ShellExecutor.execute(Permission.ROOT, "cat /proc/meminfo")

        val fields = mutableMapOf<String, Long>()
        for (line in output.lines()) {
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2) {
                val key = parts[0].removeSuffix(":")
                val value = parts[1].toLongOrNull()
                if (value != null) {
                    fields[key] = value
                }
            }
        }

        val totalRam = fields["MemTotal"] ?: 0L
        val memAvailable = fields["MemAvailable"] ?: 0L
        val buffers = fields["Buffers"] ?: 0L
        val cached = fields["Cached"] ?: 0L
        val swapCached = fields["SwapCached"] ?: 0L
        val cmaTotal = fields["CmaTotal"] ?: 0L
        val cmaFree = fields["CmaFree"] ?: 0L

        return ProcMeminfoResult(
            totalRam = totalRam,
            memAvailable = memAvailable,
            realUsed = totalRam - memAvailable,
            sUnreclaim = fields["SUnreclaim"] ?: 0L,
            kernelStack = fields["KernelStack"] ?: 0L,
            vmallocUsed = fields["VmallocUsed"] ?: 0L,
            cmaUsed = cmaTotal - cmaFree,
            pageTables = fields["PageTables"] ?: 0L,
            cacheInfo = CacheInfo(
                buffersKb = buffers,
                cachedKb = cached,
                swapCachedKb = swapCached,
                totalKb = buffers + cached + swapCached
            )
        )
    }

    private data class DumpsysResult(
        val processes: List<MemoryProcessInfo>,
        val dmaBufTotalKb: Long,
        val gpuKb: Long,
        val kernelTotalKb: Long
    )

    private fun parseDumpsysMeminfo(): DumpsysResult {
        val output = ShellExecutor.execute(Permission.ROOT, "dumpsys meminfo")

        val processes = mutableListOf<MemoryProcessInfo>()
        var dmaBufTotalKb = 0L
        var gpuKb = 0L
        var kernelTotalKb = 0L
        var inProcessSection = false

        for (line in output.lines()) {
            val trimmed = line.trim()

            if (trimmed.startsWith("Total PSS by process:")) {
                inProcessSection = true
                continue
            }

            if (trimmed.startsWith("DMA-BUF:") && !trimmed.startsWith("DMA-BUF Heaps")) {
                val match = Regex("""DMA-BUF:\s*([\d,]+)K""").find(trimmed)
                if (match != null) {
                    dmaBufTotalKb = match.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
                }
                continue
            }

            if (trimmed.startsWith("GPU:")) {
                val match = Regex("""GPU:\s*([\d,]+)K""").find(trimmed)
                if (match != null) {
                    gpuKb = match.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
                }
                continue
            }

            if (trimmed.startsWith("Used RAM:")) {
                val match = Regex("""([\d,]+)K kernel""").find(trimmed)
                if (match != null) {
                    kernelTotalKb = match.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
                }
                continue
            }

            if (inProcessSection) {
                if (trimmed.isEmpty() || trimmed.startsWith("Total PSS by") || trimmed.startsWith("Total RSS by")) {
                    inProcessSection = false
                } else {
                    val match = Regex("""^\s*([\d,]+)K:\s+(.+?)\s+\(pid\s+(\d+)""").find(trimmed)
                    if (match != null) {
                        val pssStr = match.groupValues[1].replace(",", "")
                        val pssKb = pssStr.toLongOrNull() ?: 0L
                        val processName = match.groupValues[2]
                        val isSystem = !processName.contains(".") && !processName.contains(":")

                        val displayName = if (!isSystem) {
                            val pkgName = processName.substringBefore(":")
                            try {
                                val appInfo = packageManager.getApplicationInfo(pkgName, 0)
                                packageManager.getApplicationLabel(appInfo).toString()
                            } catch (_: PackageManager.NameNotFoundException) {
                                processName
                            }
                        } else {
                            processName
                        }

                        processes.add(
                            MemoryProcessInfo(
                                processName = processName,
                                displayName = displayName,
                                pssKb = pssKb,
                                isSystem = isSystem
                            )
                        )
                    }
                }
            }
        }

        return DumpsysResult(
            processes = processes.sortedByDescending { it.pssKb },
            dmaBufTotalKb = dmaBufTotalKb,
            gpuKb = gpuKb,
            kernelTotalKb = kernelTotalKb
        )
    }

    fun buildCopyText(): String {
        val state = _uiState.value
        val sb = StringBuilder()
        sb.appendLine("=== 内存占用查询 ===")
        sb.appendLine()
        sb.appendLine("--- 系统总览 ---")
        sb.appendLine("总内存: ${state.totalRamKb} KB")
        sb.appendLine("可用: ${state.memAvailableKb} KB")
        sb.appendLine("已用: ${state.realUsedKb} KB")
        sb.appendLine()

        sb.appendLine("--- 内核 (dumpsys kernel) ---")
        sb.appendLine("总计: ${state.kernelInfo?.totalKb ?: 0} KB")
        state.kernelInfo?.let {
            sb.appendLine("  SUnreclaim: ${it.sUnreclaimKb} KB")
            sb.appendLine("  KernelStack: ${it.kernelStackKb} KB")
            sb.appendLine("  VmallocUsed: ${it.vmallocUsedKb} KB")
            sb.appendLine("  CMA 已用: ${it.cmaUsedKb} KB")
            sb.appendLine("  PageTables: ${it.pageTablesKb} KB")
            sb.appendLine("  DMA-BUF: ${it.dmaBufKb} KB")
            sb.appendLine("  GPU: ${it.gpuKb} KB")
        }
        sb.appendLine()

        sb.appendLine("--- 可清理缓存 ---")
        state.cacheInfo?.let {
            sb.appendLine("总计: ${it.totalKb} KB")
            sb.appendLine("  Buffers: ${it.buffersKb} KB")
            sb.appendLine("  Cached: ${it.cachedKb} KB")
            sb.appendLine("  SwapCached: ${it.swapCachedKb} KB")
        }
        sb.appendLine()

        sb.appendLine("--- 进程 PSS (dumpsys meminfo) ---")
        sb.appendLine("进程数: ${state.processList.size}")
        state.processList.forEach {
            sb.appendLine("  ${it.pssKb} KB  ${it.processName}  (${it.displayName})")
        }

        return sb.toString()
    }
}
