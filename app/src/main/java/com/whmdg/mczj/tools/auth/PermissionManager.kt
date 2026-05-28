package com.whmdg.mczj.tools.auth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import android.util.Log

object PermissionManager {

    private const val TAG = "PermMgr"

    private val _state = MutableStateFlow<AuthState>(AuthState.Locked)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    sealed class AuthState {
        object Locked : AuthState()
        data class Authed(val keyId: String, val features: Set<Feature>) : AuthState()
    }

    fun init(ctx: Context) {
        KeystoreMaster.ensureKey()
        val blob = TokenStorage.load(ctx) ?: run {
            Log.d(TAG, "init: no stored token found")
            return
        }
        val derivedKey = KeystoreMaster.unwrap(blob.wrappedKey, blob.ivWrap) ?: run {
            Log.w(TAG, "init: failed to unwrap key from Keystore")
            return
        }
        val decrypted = AESGCM.decrypt(blob.cipherToken, blob.ivToken, derivedKey) ?: run {
            Log.w(TAG, "init: failed to decrypt token")
            return
        }
        val token = TokenCodec.decode(decrypted, derivedKey) ?: run {
            Log.w(TAG, "init: failed to decode/verify token HMAC")
            return
        }
        _state.value = AuthState.Authed(token.keyId, token.features)
        Log.d(TAG, "init: restored auth state for keyId=${token.keyId}")
    }

    fun has(f: Feature): Boolean =
        (_state.value as? AuthState.Authed)?.features?.contains(f) == true

    suspend fun tryAuthenticate(ctx: Context, pw: String): Result<Set<Feature>> =
        withContext(Dispatchers.Default) {
            val derivedKey = NativeAuth.verifyPassword(pw)
            if (derivedKey == null) {
                return@withContext Result.failure(Exception("pw"))
            }
            val keyIdIdx = NativeAuth.keyIdOf(pw)
            val keyId = KeyProfile.allKeyIds().elementAtOrNull(keyIdIdx)
            if (keyId == null) {
                AESGCM.zero(derivedKey)
                return@withContext Result.failure(Exception("kid"))
            }
            val features = KeyProfile.featuresFor(keyId)
            val token = TokenCodec.Token(keyId, features, System.currentTimeMillis())
            val tokenBytes = TokenCodec.encode(token, derivedKey)
            val (cipherToken, ivToken) = AESGCM.encrypt(tokenBytes, derivedKey)
            val (wrappedKey, ivWrap) = KeystoreMaster.wrap(derivedKey)
            AESGCM.zero(derivedKey)
            TokenStorage.save(
                ctx,
                TokenStorage.Blob(wrappedKey, ivWrap, cipherToken, ivToken, keyId, System.currentTimeMillis())
            )
            _state.value = AuthState.Authed(keyId, features)
            Result.success(features)
        }

    suspend fun switchKey(ctx: Context, currentPw: String, newPw: String): Boolean {
        val currentDerived = NativeAuth.verifyPassword(currentPw) ?: return false
        AESGCM.zero(currentDerived)
        clearStorage(ctx)
        val result = tryAuthenticate(ctx, newPw)
        return result.isSuccess
    }

    suspend fun clearAuth(ctx: Context, currentPw: String): Boolean {
        val derived = NativeAuth.verifyPassword(currentPw) ?: return false
        AESGCM.zero(derived)
        clearStorage(ctx)
        _state.value = AuthState.Locked
        return true
    }

    private fun clearStorage(ctx: Context) {
        TokenStorage.clear(ctx)
        KeystoreMaster.deleteKey()
    }
}

private object AESGCM {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LEN = 128
    private const val IV_LEN = 12

    fun encrypt(plain: ByteArray, key: ByteArray): Pair<ByteArray, ByteArray> {
        val keySpec = javax.crypto.spec.SecretKeySpec(key, "AES")
        val iv = ByteArray(IV_LEN).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, javax.crypto.spec.GCMParameterSpec(GCM_TAG_LEN, iv))
        return cipher.doFinal(plain) to iv
    }

    fun decrypt(cipherText: ByteArray, iv: ByteArray, key: ByteArray): ByteArray? {
        return try {
            val keySpec = javax.crypto.spec.SecretKeySpec(key, "AES")
            val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, javax.crypto.spec.GCMParameterSpec(GCM_TAG_LEN, iv))
            cipher.doFinal(cipherText)
        } catch (_: Exception) {
            null
        }
    }

    fun zero(b: ByteArray) {
        b.fill(0)
    }
}
