package com.whmdg.mczj.tools.encryption.core

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * AES-256-GCM 批量文件加密实现。
 *
 * 统一走 Android 平台 JCA（`Cipher("AES/GCM/NoPadding")`）。在 ARMv8 设备上，
 * Android 默认 provider（Conscrypt/BoringSSL）会自动使用 CPU 的 AES 硬件指令
 * （ARMv8 Crypto Extensions / AES-NI），无需自建 JNI/OpenSSL 层。
 */
object AesGcm256 {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12

    // ThreadLocal 缓存：避免每块重复 Cipher.getInstance() + SecretKeySpec 创建
    private val cachedCipher = ThreadLocal<Cipher>()
    private val cachedKey = ThreadLocal<Pair<ByteArray, SecretKeySpec>>()

    /** 当前实际生效的加密实现名称（供 UI 显示，直接来自平台 provider）。 */
    val backendName: String by lazy {
        Cipher.getInstance(ALGORITHM).provider.name
    }

    /** 判定阈值：单块实测吞吐高于此值（MB/s）视为 AES 硬件指令已生效，否则为软件降级。 */
    private const val HARDWARE_THRESHOLD_MBPS = 200.0

    /** 测量样本下限：小于此值（字节）的块太小、噪声过大，不参与判定。 */
    private const val MIN_MEASURE_BYTES = 512 * 1024

    /** 已跳过的预热样本数；首个够大的块含 JIT/Provider 冷启动开销，不作判定。 */
    private val warmupSeen = AtomicInteger(0)

    /** null = 尚未判定 */
    private val hardware = AtomicReference<Boolean?>(null)

    /**
     * AES 硬件加速是否真正走通（供 UI 显示）。
     *
     * 平台 JCA 在支持 AES 指令的 CPU 上会自动启用硬件实现，不支持时静默降级为软件表。
     * 二者均无公开 API 可查，故取**真实加密块**的实测吞吐来判定：达阈值视为硬件，否则
     * 软件。首个足够大的块仅作预热（含 JIT/Provider 冷启动开销，不代表稳定性能），从
     * 第二个够大的块开始计时判定。该数据本来就要加密，判定不产生额外开销。
     * 在得到判定前返回 null。
     */
    val isHardwareAccelerated: Boolean? get() = hardware.get()

    /** 用真实加密块的实测耗时更新硬件判定（CAS + 预热跳过，保证并发安全）。 */
    private fun measure(nanos: Long, bytes: Int) {
        if (hardware.get() != null) return
        if (nanos <= 0L || bytes < MIN_MEASURE_BYTES) return
        val mbps = (bytes.toDouble() / (1024.0 * 1024.0)) / (nanos / 1_000_000_000.0)
        if (warmupSeen.incrementAndGet() == 1) return // 首个够大的块跳过
        hardware.compareAndSet(null, mbps >= HARDWARE_THRESHOLD_MBPS)
    }

    data class EncryptResult(val iv: ByteArray, val ciphertext: ByteArray)

    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): EncryptResult {
        require(key.size == 32) { "AES-256 密钥必须 32 字节，当前 ${key.size}" }

        val iv = SecureRandom.bytes(IV_LENGTH_BYTES)
        val cipher = cachedCipher.get()
            ?: Cipher.getInstance(ALGORITHM).also { cachedCipher.set(it) }
        val keySpec = cachedKey.get()?.takeIf { it.first === key }?.second
            ?: SecretKeySpec(key, "AES").also { cachedKey.set(key to it) }
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        aad?.let { cipher.updateAAD(it) }
        val t0 = System.nanoTime()
        val ciphertext = cipher.doFinal(plaintext)
        measure(System.nanoTime() - t0, plaintext.size)
        return EncryptResult(iv, ciphertext)
    }

    fun decrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray, aad: ByteArray? = null): ByteArray {
        require(key.size == 32) { "AES-256 密钥必须 32 字节" }
        require(iv.size == IV_LENGTH_BYTES) { "GCM IV 必须 12 字节" }
        require(ciphertext.size >= 16) { "密文太短：少于 16 字节 tag" }

        val cipher = cachedCipher.get()
            ?: Cipher.getInstance(ALGORITHM).also { cachedCipher.set(it) }
        val keySpec = cachedKey.get()?.takeIf { it.first === key }?.second
            ?: SecretKeySpec(key, "AES").also { cachedKey.set(key to it) }
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        aad?.let { cipher.updateAAD(it) }
        return cipher.doFinal(ciphertext)
    }
}
