package com.whmdg.mczj.tools.security

import android.app.Activity
import android.content.Context
import com.whmdg.mczj.tools.AppDataPaths
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.widget.Toast
import com.whmdg.mczj.tools.encryption.core.HexCodec
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.util.concurrent.Executor
import javax.crypto.Cipher

object TeeManager {
    private const val KEY_ALIAS = "TeeQuickUnlockRsaKeyPair"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

    /**
     * 检查设备是否支持并录入了指纹/人脸
     */
    fun hasEnrolledBiometrics(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bm = context.getSystemService(Context.BIOMETRIC_SERVICE) as BiometricManager
                bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val bm = context.getSystemService(Context.BIOMETRIC_SERVICE) as BiometricManager
                bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
            } else {
                val km = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                km.isKeyguardSecure
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 生成 Android Keystore RSA 密钥对，私钥受指纹/人脸保护并绑定指纹列表变更（增加新指纹将失效）
     */
    fun generateRsaKeyPair() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                ANDROID_KEYSTORE
            )
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).apply {
                setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                setUserAuthenticationRequired(true) // 私钥解密需要指纹认证
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setInvalidatedByBiometricEnrollment(true) // 录入新指纹时原密钥自动失效，只允许激活该特权时的已有指纹解锁
                }
            }
            kpg.initialize(builder.build())
            kpg.generateKeyPair()
        }
    }

    /**
     * 擦除所有 TEE 信息
     */
    fun wipeAllTeeData(context: Context) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val sp = context.getSharedPreferences(AppDataPaths.PREFS_TEE, Context.MODE_PRIVATE)
        sp.edit().clear().apply()
    }

    /**
     * 使用 TEE 公钥安全加密保险箱密码，无需任何指纹授权，直接在后台静默写入
     */
    fun encryptPassword(context: Context, vaultId: Int, password: CharSequence) {
        try {
            generateRsaKeyPair()
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encryptedBytes = cipher.doFinal(password.toString().toByteArray(Charsets.UTF_8))

            val encryptedHex = HexCodec.encode(encryptedBytes)
            val sp = context.getSharedPreferences(AppDataPaths.PREFS_TEE, Context.MODE_PRIVATE)
            sp.edit().putString("vault_$vaultId", encryptedHex).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 判断某个保险箱是否已录入快速解锁密码
     */
    fun isVaultPasswordSaved(context: Context, vaultId: Int): Boolean {
        val sp = context.getSharedPreferences(AppDataPaths.PREFS_TEE, Context.MODE_PRIVATE)
        return !sp.getString("vault_$vaultId", null).isNullOrEmpty()
    }

    /**
     * 获取用于指纹认证解密的 Cipher
     */
    fun getDecryptCipher(context: Context, vaultId: Int): Cipher? {
        return try {
            val sp = context.getSharedPreferences(AppDataPaths.PREFS_TEE, Context.MODE_PRIVATE)
            val encryptedHex = sp.getString("vault_$vaultId", null) ?: return null
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, privateKey)
            cipher
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 使用已指纹授权的 Cipher 进行密码解密
     */
    fun decryptPassword(context: Context, vaultId: Int, authenticatedCipher: Cipher): String? {
        return try {
            val sp = context.getSharedPreferences(AppDataPaths.PREFS_TEE, Context.MODE_PRIVATE)
            val encryptedHex = sp.getString("vault_$vaultId", null) ?: return null
            val encryptedBytes = HexCodec.decode(encryptedHex)
            val decryptedBytes = authenticatedCipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 唤起系统指纹/人脸识别弹窗
     */
    fun showBiometricPrompt(
        activity: Activity,
        cryptoObject: BiometricPrompt.CryptoObject?,
        title: String,
        description: String,
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val executor = Executor { command -> activity.runOnUiThread(command) }
            val builder = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setDescription(description)
                .setNegativeButton("取消", executor) { _, _ -> onFailure("用户取消") }

            val biometricPrompt = builder.build()
            val cancellationSignal = CancellationSignal()

            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess(result)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onFailure(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // 保持识别开启，用户可再次验证
                }
            }

            if (cryptoObject != null) {
                biometricPrompt.authenticate(cryptoObject, cancellationSignal, executor, callback)
            } else {
                biometricPrompt.authenticate(cancellationSignal, executor, callback)
            }
        } else {
            onFailure("系统版本过低，不支持 TEE 生物识别")
        }
    }
}
