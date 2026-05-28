package com.whmdg.mczj.tools.auth

import com.whmdg.mczj.tools.encryption.data.CanonicalJson
import kotlinx.serialization.json.*
import java.security.MessageDigest

object TokenCodec {

    data class Token(
        val keyId: String,
        val features: Set<Feature>,
        val issuedAt: Long
    )

    fun encode(token: Token, key: ByteArray): ByteArray {
        val json = buildJsonObject {
            put("keyId", token.keyId)
            putJsonArray("features") {
                token.features.forEach { add(it.name) }
            }
            put("issuedAt", token.issuedAt)
        }
        val canonical = CanonicalJson.encode(json)
        val payload = canonical.toByteArray(Charsets.UTF_8)
        val hmac = hmacSha256(key, payload)
        return payload + hmac
    }

    fun decode(data: ByteArray, key: ByteArray): Token? {
        if (data.size <= 32) return null
        val payloadLen = data.size - 32
        val payload = data.copyOfRange(0, payloadLen)
        val expectedHmac = data.copyOfRange(payloadLen, data.size)
        val actualHmac = hmacSha256(key, payload)
        // 使用恒定时间比较防止时序攻击
        if (!MessageDigest.isEqual(expectedHmac, actualHmac)) return null
        return try {
            val obj = Json.parseToJsonElement(String(payload, Charsets.UTF_8)).jsonObject
            val keyId = obj["keyId"]?.jsonPrimitive?.content ?: return null
            val features = obj["features"]?.jsonArray?.map {
                Feature.valueOf(it.jsonPrimitive.content)
            }?.toSet() ?: return null
            val issuedAt = obj["issuedAt"]?.jsonPrimitive?.long ?: return null
            Token(keyId, features, issuedAt)
        } catch (_: Exception) {
            null
        }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val blockSize = 64
        val k = if (key.size > blockSize) md.digest(key) else key.copyOf(blockSize)
        val ipad = ByteArray(blockSize) { k[it].toInt().xor(0x36).toByte() }
        val opad = ByteArray(blockSize) { k[it].toInt().xor(0x5C).toByte() }
        md.update(ipad)
        md.update(data)
        val inner = md.digest()
        md.update(opad)
        val result = md.digest(inner)
        k.fill(0)
        return result
    }
}
