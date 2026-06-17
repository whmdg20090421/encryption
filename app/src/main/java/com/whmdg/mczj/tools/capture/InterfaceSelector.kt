package com.whmdg.mczj.tools.capture

import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * WiFi 网卡自动选择器
 * 枚举 wlan 接口、查询状态、选出空闲网卡用于 tcpdump 抓包
 */
object InterfaceSelector {

    enum class IfaceState { CONNECTED, UP_NO_IP, DOWN }

    data class IfaceInfo(
        val name: String,
        val state: IfaceState,
        val ssid: String? = null
    )

    /**
     * 自动选择空闲网卡用于抓包。
     * 情况 C（双网卡均连接）时返回 null，通过 onConflict 回调弹出对话框让用户选择。
     */
    suspend fun selectCaptureInterface(
        onConflict: (IfaceInfo, IfaceInfo) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val ifaces = enumerateInterfaces()
        if (ifaces.isEmpty()) return@withContext null

        val infos = ifaces.map { queryInterfaceState(it) }
        decide(infos, onConflict)
    }

    /**
     * 枚举 /sys/class/net/ 下所有 wlan 开头的接口（需 root 权限读取）
     */
    private fun enumerateInterfaces(): List<String> {
        val (stdout, _, exitCode) = SpecialPermissionVerifier.executeRootCommandFull("ls /sys/class/net/")
        if (exitCode != 0) return emptyList()
        return stdout.split("\\s+".toRegex()).filter { it.startsWith("wlan") }.sorted()
    }

    /**
     * 查询单个接口的状态
     */
    private fun queryInterfaceState(iface: String): IfaceInfo {
        // 1. 检查 operstate (需 root)
        val (opState, _, opExit) = SpecialPermissionVerifier.executeRootCommandFull("cat /sys/class/net/$iface/operstate")
        val operstate = if (opExit == 0) opState.trim() else "down"

        // 2. 检查是否有 IP 地址
        val (stdout, _, _) = SpecialPermissionVerifier.executeRootCommandFull("ip addr show $iface")
        val hasIp = stdout.contains("inet ")

        // 3. 确定状态
        val state = when {
            operstate == "up" && hasIp -> IfaceState.CONNECTED
            operstate == "up" -> IfaceState.UP_NO_IP
            else -> IfaceState.DOWN
        }

        // 4. 已连接时获取 SSID
        val ssid = if (state == IfaceState.CONNECTED) {
            getSsidForInterface(iface)
        } else null

        return IfaceInfo(iface, state, ssid)
    }

    /**
     * 获取已连接接口的 SSID
     */
    private fun getSsidForInterface(iface: String): String? {
        val (stdout, _, exitCode) = SpecialPermissionVerifier.executeRootCommandFull("iw dev $iface link")
        if (exitCode != 0) return null
        val ssidLine = stdout.lines().find { it.trimStart().startsWith("SSID:") }
        return ssidLine?.substringAfter("SSID:")?.trim()
    }

    /**
     * 选卡决策
     */
    private suspend fun decide(
        infos: List<IfaceInfo>,
        onConflict: (IfaceInfo, IfaceInfo) -> Unit
    ): String? {
        if (infos.size == 1) {
            // 只有一个接口，直接用
            val iface = infos[0]
            if (iface.state == IfaceState.DOWN) {
                bringInterfaceUp(iface.name)
            }
            return iface.name
        }

        val connected = infos.filter { it.state == IfaceState.CONNECTED }
        val idle = infos.filter { it.state != IfaceState.CONNECTED }

        return when {
            // 情况 A：一个已连接 → 选另一个
            connected.size == 1 -> {
                val target = idle.firstOrNull() ?: infos.first { it.name != connected[0].name }
                if (target.state == IfaceState.DOWN) {
                    bringInterfaceUp(target.name)
                }
                target.name
            }
            // 情况 B：都未连接 → 默认 wlan0
            connected.isEmpty() -> {
                val target = infos.firstOrNull { it.name == "wlan0" } ?: infos[0]
                if (target.state == IfaceState.DOWN) {
                    bringInterfaceUp(target.name)
                }
                target.name
            }
            // 情况 C：都已连接 → 回调让用户选择
            else -> {
                onConflict(infos[0], infos[1])
                null
            }
        }
    }

    /**
     * 启用接口（ip link set up）
     */
    private suspend fun bringInterfaceUp(iface: String) {
        withContext(Dispatchers.IO) {
            SpecialPermissionVerifier.executeRootCommandFull("ip link set $iface up")
            delay(500)
        }
    }

    /**
     * 断开指定接口（ip link set down）
     */
    fun disconnectInterface(iface: String): Boolean {
        val (_, _, exitCode) = SpecialPermissionVerifier.executeRootCommandFull("ip link set $iface down")
        return exitCode == 0
    }

    /**
     * 切换到监听模式
     */
    suspend fun enableMonitorMode(iface: String): Boolean = withContext(Dispatchers.IO) {
        // 先断开连接
        SpecialPermissionVerifier.executeRootCommandFull("ip link set $iface down")
        delay(200)
        // 切监听模式
        val (_, _, exitCode) = SpecialPermissionVerifier.executeRootCommandFull("iw dev $iface set type monitor")
        if (exitCode != 0) return@withContext false
        delay(200)
        // 启用接口
        SpecialPermissionVerifier.executeRootCommandFull("ip link set $iface up")
        delay(300)
        true
    }

    /**
     * 恢复 managed 模式
     */
    suspend fun restoreManagedMode(iface: String): Boolean = withContext(Dispatchers.IO) {
        SpecialPermissionVerifier.executeRootCommandFull("ip link set $iface down")
        delay(200)
        val (_, _, exitCode) = SpecialPermissionVerifier.executeRootCommandFull("iw dev $iface set type managed")
        if (exitCode != 0) return@withContext false
        delay(200)
        SpecialPermissionVerifier.executeRootCommandFull("ip link set $iface up")
        delay(300)
        true
    }
}
