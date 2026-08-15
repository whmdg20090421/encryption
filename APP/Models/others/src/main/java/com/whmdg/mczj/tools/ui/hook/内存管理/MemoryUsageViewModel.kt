package com.whmdg.mczj.tools.ui.hook.内存管理

import android.app.Application
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
    val totalKb: Long
)

data class MemoryUsageUiState(
    val isLoading: Boolean = true,
    val rootAvailable: Boolean = true,
    val totalRamKb: Long = 0,
    val memAvailableKb: Long = 0,
    val realUsedKb: Long = 0,
    val kernelBreakdown: KernelMemoryBreakdown? = null,
    val fileCacheKb: Long = 0,
    val processList: List<MemoryProcessInfo> = emptyList(),
    val queriedTotalKb: Long = 0,
    val error: String? = null
)

class MemoryUsageViewModel(application: Application) : AndroidViewModel(application) {

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
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                val memInfoData = withContext(Dispatchers.IO) {
                    parseProcMeminfo()
                }

                val dumpsysResult = withContext(Dispatchers.IO) {
                    parseDumpsysMeminfo()
                }

                // 补充 DMA-BUF 到内核 breakdown
                val kernel = memInfoData.kernelBreakdown.copy(
                    dmaBufKb = dumpsysResult.dmaBufTotalKb,
                    totalKb = memInfoData.kernelBreakdown.totalKb + dumpsysResult.dmaBufTotalKb
                )

                val processPssTotal = dumpsysResult.processes.sumOf { it.pssKb }
                val fileCache = memInfoData.fileCacheKb
                val queriedTotal = processPssTotal + kernel.totalKb + fileCache

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    totalRamKb = memInfoData.totalRamKb,
                    memAvailableKb = memInfoData.memAvailableKb,
                    realUsedKb = memInfoData.realUsedKb,
                    kernelBreakdown = kernel,
                    fileCacheKb = fileCache,
                    processList = dumpsysResult.processes,
                    queriedTotalKb = queriedTotal,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "加载失败: ${e.message}"
                )
            }
        }
    }

    private data class MemInfoResult(
        val totalRamKb: Long,
        val memAvailableKb: Long,
        val realUsedKb: Long,
        val kernelBreakdown: KernelMemoryBreakdown,
        val fileCacheKb: Long
    )

    private fun parseProcMeminfo(): MemInfoResult {
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
        val realUsed = totalRam - memAvailable

        val sUnreclaim = fields["SUnreclaim"] ?: 0L
        val kernelStack = fields["KernelStack"] ?: 0L
        val vmallocUsed = fields["VmallocUsed"] ?: 0L
        val cmaTotal = fields["CmaTotal"] ?: 0L
        val cmaFree = fields["CmaFree"] ?: 0L
        val cmaUsed = cmaTotal - cmaFree
        val pageTables = fields["PageTables"] ?: 0L

        // DMA-BUF 在 parseDumpsysMeminfo 中获取，此处先设 0
        val kernelTotal = sUnreclaim + kernelStack + vmallocUsed + cmaUsed + pageTables

        val buffers = fields["Buffers"] ?: 0L
        val cached = fields["Cached"] ?: 0L
        val swapCached = fields["SwapCached"] ?: 0L
        val fileCache = buffers + cached + swapCached

        return MemInfoResult(
            totalRamKb = totalRam,
            memAvailableKb = memAvailable,
            realUsedKb = realUsed,
            kernelBreakdown = KernelMemoryBreakdown(
                sUnreclaimKb = sUnreclaim,
                kernelStackKb = kernelStack,
                vmallocUsedKb = vmallocUsed,
                cmaUsedKb = cmaUsed,
                pageTablesKb = pageTables,
                dmaBufKb = 0L, // 在 parseDumpsysMeminfo 中补充
                totalKb = kernelTotal
            ),
            fileCacheKb = fileCache
        )
    }

    private data class DumpsysResult(
        val processes: List<MemoryProcessInfo>,
        val dmaBufTotalKb: Long
    )

    private fun parseDumpsysMeminfo(): DumpsysResult {
        val output = ShellExecutor.execute(Permission.ROOT, "dumpsys meminfo")

        val processes = mutableListOf<MemoryProcessInfo>()
        var dmaBufTotalKb = 0L
        var inProcessSection = false
        var inDmaBufSection = false

        for (line in output.lines()) {
            val trimmed = line.trim()

            // 解析 Total PSS by process 段
            if (trimmed.startsWith("Total PSS by process:")) {
                inProcessSection = true
                inDmaBufSection = false
                continue
            }

            // 解析 DMA-BUF 段
            if (trimmed.startsWith("DMA-BUF:") || trimmed.startsWith("Total DMA-BUF")) {
                inDmaBufSection = true
                inProcessSection = false
                // 如果是 "DMA-BUF: TOTAL" 行，直接提取
                val totalMatch = Regex("""TOTAL:\s*([\d,]+)""").find(trimmed)
                if (totalMatch != null) {
                    dmaBufTotalKb = totalMatch.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
                    inDmaBufSection = false
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

                        processes.add(
                            MemoryProcessInfo(
                                processName = processName,
                                pssKb = pssKb,
                                isSystem = isSystem
                            )
                        )
                    }
                }
            }

            if (inDmaBufSection) {
                // 尝试匹配 "TOTAL: 数字" 行
                val totalMatch = Regex("""TOTAL:\s*([\d,]+)""").find(trimmed)
                if (totalMatch != null) {
                    dmaBufTotalKb = totalMatch.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
                    inDmaBufSection = false
                }
                // 也尝试匹配 "Total: 数字 KB" 格式
                val totalMatch2 = Regex("""Total:\s*([\d,]+)\s*KB""").find(trimmed)
                if (totalMatch2 != null && dmaBufTotalKb == 0L) {
                    dmaBufTotalKb = totalMatch2.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
                    inDmaBufSection = false
                }
            }
        }

        return DumpsysResult(
            processes = processes.sortedByDescending { it.pssKb },
            dmaBufTotalKb = dmaBufTotalKb
        )
    }

    fun onResume() {
        checkRootAndLoad()
    }
}
