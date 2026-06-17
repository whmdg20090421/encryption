package com.whmdg.mczj.tools.capture

import android.content.Context
import com.whmdg.mczj.tools.security.SpecialPermissionVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.DataInputStream

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
        val eapolFrames: List<String>   // 各帧完整 EAPOL 数据 hex
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
        onProgress: (String) -> Unit
    ): HandshakeData? = withContext(Dispatchers.IO) {
        onProgress("启动 tcpdump（普通模式）...")

        // 1. 启动 tcpdump，管道输出 base64 到 stdout，内存处理不写物理存储
        //    -c 10: 最多抓 10 个 EAPOL 帧后自动停止
        val tcpdumpCmd = "tcpdump -i $iface -c 10 -w - ether proto 0x888e | base64"
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

            val frames = parsePcapBytes(rawData)
            if (frames.isEmpty()) {
                onProgress("未捕获到 EAPOL 帧")
                return@withContext null
            }

            // 5. 提取握手数据
            onProgress("提取握手数据...")
            val bssid = targetBssid ?: frames.firstOrNull()?.srcMac ?: ""
            extractHandshake(frames, targetSsid, bssid)
        } catch (e: Exception) {
            onProgress("抓包异常: ${e.message}")
            try {
                val pid = getPid(tcpdumpProcess)
                if (pid > 0) SpecialPermissionVerifier.executeRootCommandFull("kill -2 $pid")
            } catch (_: Exception) {}
            null
        }
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
     * 解析内存中的 pcap 字节数据，提取 EAPOL 帧
     */
    private fun parsePcapBytes(rawData: ByteArray): List<EapolFrame> {
        val frames = mutableListOf<EapolFrame>()
        try {
            DataInputStream(rawData.inputStream()).use { dis ->
                // 读取全局头 (24 bytes)
                val magic = dis.readInt()
                if (magic != 0xa1b2c3d4.toInt() && magic != 0xd4c3b2a1.toInt()) {
                    return frames // 不是有效 pcap
                }
                val swapped = (magic == 0xd4c3b2a1.toInt())
                dis.skipBytes(20) // 跳过剩余全局头

                // 逐个读取包
                while (true) {
                    val pktHeader = try { readBytes(dis, 16) } catch (_: Exception) { break }
                    if (pktHeader.size < 16) break

                    val inclLen = readUint32(pktHeader, 8, swapped)
                    if (inclLen > 65535 || inclLen < 0) break

                    val pktData = try { readBytes(dis, inclLen.toInt()) } catch (_: Exception) { break }
                    if (pktData.size < inclLen.toInt()) break

                    // 解析 EAPOL 帧
                    val frame = parseEapolFrame(pktData)
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
     * 链路层结构（假设 Ethernet II）：
     * [dst_mac 6B] [src_mac 6B] [ethertype 2B] [802.1X header] [EAPOL-Key]
     *
     * 802.1X header: [version 1B] [type 1B] [length 2B]
     * EAPOL-Key: [descriptor 1B] [key_info 2B] [key_len 2B] [replay_counter 8B]
     *            [nonce 32B] [key_iv 16B] [key_rsc 8B] [key_id 8B] [mic 16B]
     *            [key_data_len 2B] [key_data ...]
     */
    private fun parseEapolFrame(data: ByteArray): EapolFrame? {
        // 最小长度: 14(eth) + 4(802.1x) + 95(eapol-key头) = 113
        if (data.size < 113) return null

        // 以太网头
        val dstMac = formatMac(data, 0)
        val srcMac = formatMac(data, 6)
        val ethertype = ((data[12].toInt() and 0xFF) shl 8) or (data[13].toInt() and 0xFF)
        if (ethertype != 0x888E) return null // 不是 EAPOL

        // 802.1X header
        val eapolVersion = data[14].toInt() and 0xFF
        val eapolType = data[15].toInt() and 0xFF
        if (eapolType != 3) return null // 不是 EAPOL-Key (type=3)
        val eapolLen = ((data[16].toInt() and 0xFF) shl 8) or (data[17].toInt() and 0xFF)

        // EAPOL-Key 头部
        val keyOffset = 18
        val descriptor = data[keyOffset].toInt() and 0xFF           // Key Descriptor Type
        val keyInfo = ((data[keyOffset + 1].toInt() and 0xFF) shl 8) or
                (data[keyOffset + 2].toInt() and 0xFF)
        val keyLen = ((data[keyOffset + 3].toInt() and 0xFF) shl 8) or
                (data[keyOffset + 4].toInt() and 0xFF)

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

        // 完整 EAPOL 帧原始数据
        val rawEapol = data.copyOfRange(14, data.size) // 去掉以太网头

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
            nonce = nonce,
            mic = mic,
            keyData = keyData,
            rawEapol = rawEapol,
            msgType = msgType
        )
    }

    /**
     * 从 EAPOL 帧列表中提取握手数据
     */
    private fun extractHandshake(
        frames: List<EapolFrame>,
        ssid: String,
        bssid: String
    ): HandshakeData? {
        val msg1 = frames.find { it.msgType == 1 }
        val msg2 = frames.find { it.msgType == 2 }
        val msg3 = frames.find { it.msgType == 3 }

        // 至少需要 Msg1 + Msg2 才能构成有效握手
        if (msg1 == null || msg2 == null) return null

        // ANonce: 来自 Msg1 或 Msg3
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

        // 所有 EAPOL 帧的 hex dump
        val eapolHexList = frames.map { it.rawEapol.toHex() }

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
            eapolFrames = eapolHexList
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
