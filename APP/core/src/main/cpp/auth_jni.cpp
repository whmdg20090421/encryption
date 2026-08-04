#include <jni.h>
#include <string.h>
#include <stdint.h>
#include <android/log.h>

#include "obf.h"
#include "obf_key.h"
#include "deadline_hmac_key.h"
#include "hashes.inc"
#include "argon2.h"
#include "blake2.h"

#include <stdlib.h>
#include <stdio.h>
#include <time.h>

#define LOG_TAG "authcore"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int g_last_key_id = -1;

static void hkdf_sha256_expand(const uint8_t *prk, size_t prk_len,
                                const uint8_t *info, size_t info_len,
                                uint8_t *okm, size_t okm_len) {
    uint8_t prev[32];
    size_t done = 0;
    uint8_t counter = 1;

    while (done < okm_len) {
        if (counter > 1) {
            memcpy(prev, okm + done - 32, 32);
        }
        // info_len max = 8(prefix) + 8(keyId) = 16
        uint8_t block[32 + 16 + 1];
        size_t idx = 0;
        if (counter > 1) {
            memcpy(block + idx, prev, 32);
            idx += 32;
        }
        memcpy(block + idx, info, info_len);
        idx += info_len;
        block[idx++] = counter;

        size_t chunk = (okm_len - done > 32) ? 32 : okm_len - done;
        blake2b(okm + done, chunk, block, idx, prk, prk_len);
        done += chunk;
        counter++;
        if (counter > 1) secure_zero(prev, 32);
    }
    secure_zero(prev, 32);
}

static int verify_and_derive(const char *pw, size_t pw_len,
                              uint8_t *derived_key, size_t dk_len) {
    uint8_t raw_hash[32];
    uint8_t exp_hash[32];
    uint8_t salt[16];

    for (int i = 0; i < NUM_KEYS; i++) {
        deobf(ALL_SALTS[i], salt, 16);

        int rc = argon2id_hash_raw(2, 65536, 2,
                                   pw, pw_len,
                                   salt, 16,
                                   raw_hash, 32);
        if (rc != ARGON2_OK) {
            secure_zero(salt, 16);
            continue;
        }

        deobf(ALL_OBF_HASHES[i], exp_hash, 32);

        if (ct_memcmp(raw_hash, exp_hash, 32) == 0) {
            g_last_key_id = i;

            uint8_t kid[8];
            size_t kid_len = ALL_KEYID_LENS[i];
            deobf(ALL_OBF_KEYIDS[i], kid, kid_len);

            uint8_t info[32];
            memcpy(info, "auth-v1|", 8);
            memcpy(info + 8, kid, kid_len);
            size_t info_len = 8 + kid_len;

            hkdf_sha256_expand(raw_hash, 32, info, info_len, derived_key, dk_len);

            secure_zero(raw_hash, 32);
            secure_zero(exp_hash, 32);
            secure_zero(salt, 16);
            secure_zero(kid, kid_len);
            secure_zero(info, info_len);
            return 1;
        }
        secure_zero(raw_hash, 32);
        secure_zero(exp_hash, 32);
        secure_zero(salt, 16);
    }
    g_last_key_id = -1;
    return 0;
}

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_com_whmdg_mczj_tools_auth_NativeAuth_verifyPassword(
        JNIEnv *env, jclass /* clazz */, jstring jpw) {

    if (!jpw) return nullptr;

    const char *pw = env->GetStringUTFChars(jpw, nullptr);
    if (!pw) return nullptr;

    size_t pw_len = strlen(pw);
    uint8_t derived[32];
    int ok = verify_and_derive(pw, pw_len, derived, 32);

    env->ReleaseStringUTFChars(jpw, pw);

    if (!ok) return nullptr;

    jbyteArray result = env->NewByteArray(32);
    if (result) {
        env->SetByteArrayRegion(result, 0, 32, (const jbyte *)derived);
    }
    secure_zero(derived, 32);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_whmdg_mczj_tools_auth_NativeAuth_keyIdOf(
        JNIEnv *env, jclass /* clazz */, jstring /* jpw */) {
    return (jint)g_last_key_id;
}

/* 计算 deadline HMAC（用于存储，不做验证） */
JNIEXPORT jstring JNICALL
Java_com_whmdg_mczj_tools_auth_NativeAuth_computeDeadlineHmac(
        JNIEnv *env, jclass /* clazz */,
        jstring jDeadline, jstring jVaultId) {

    if (!jDeadline || !jVaultId) return env->NewStringUTF("");

    const char *deadline = env->GetStringUTFChars(jDeadline, nullptr);
    const char *vaultId = env->GetStringUTFChars(jVaultId, nullptr);
    if (!deadline || !vaultId) {
        if (deadline) env->ReleaseStringUTFChars(jDeadline, deadline);
        if (vaultId) env->ReleaseStringUTFChars(jVaultId, vaultId);
        return env->NewStringUTF("");
    }

    uint8_t hmac_key[32];
    deobf(DEADLINE_HMAC_KEY_OBF, hmac_key, 32);

    size_t dl_len = strlen(deadline);
    size_t vi_len = strlen(vaultId);
    size_t msg_len = dl_len + vi_len;
    uint8_t *msg = (uint8_t *)malloc(msg_len);
    if (!msg) {
        secure_zero(hmac_key, 32);
        env->ReleaseStringUTFChars(jDeadline, deadline);
        env->ReleaseStringUTFChars(jVaultId, vaultId);
        return env->NewStringUTF("");
    }
    memcpy(msg, deadline, dl_len);
    memcpy(msg + dl_len, vaultId, vi_len);

    uint8_t mac[32];
    blake2b(mac, 32, msg, msg_len, hmac_key, 32);
    secure_zero(hmac_key, 32);
    secure_zero(msg, msg_len);
    free(msg);

    char mac_hex[65];
    for (int i =  0; i < 32; i++) {
        snprintf(mac_hex + i * 2, 3, "%02x", mac[i]);
    }
    mac_hex[64] = '\0';
    secure_zero(mac, 32);

    env->ReleaseStringUTFChars(jDeadline, deadline);
    env->ReleaseStringUTFChars(jVaultId, vaultId);

    return env->NewStringUTF(mac_hex);
}

/* 验证 deadline：HMAC 匹配 + 未过期 → 返回 proof；否则空字符串 */
JNIEXPORT jstring JNICALL
Java_com_whmdg_mczj_tools_auth_NativeAuth_verifyDeadline(
        JNIEnv *env, jclass /* clazz */,
        jstring jDeadline, jstring jVaultId, jstring jStoredProof) {

    if (!jDeadline || !jVaultId || !jStoredProof) {
        return env->NewStringUTF("");
    }

    const char *deadline = env->GetStringUTFChars(jDeadline, nullptr);
    const char *vaultId = env->GetStringUTFChars(jVaultId, nullptr);
    const char *storedProof = env->GetStringUTFChars(jStoredProof, nullptr);
    if (!deadline || !vaultId || !storedProof) {
        if (deadline) env->ReleaseStringUTFChars(jDeadline, deadline);
        if (vaultId) env->ReleaseStringUTFChars(jVaultId, vaultId);
        if (storedProof) env->ReleaseStringUTFChars(jStoredProof, storedProof);
        return env->NewStringUTF("");
    }

    /* 还原 HMAC 密钥 */
    uint8_t hmac_key[32];
    deobf(DEADLINE_HMAC_KEY_OBF, hmac_key, 32);

    /* 拼接 deadline + vaultId */
    size_t dl_len = strlen(deadline);
    size_t vi_len = strlen(vaultId);
    size_t msg_len = dl_len + vi_len;
    uint8_t *msg = (uint8_t *)malloc(msg_len);
    if (!msg) {
        secure_zero(hmac_key, 32);
        env->ReleaseStringUTFChars(jDeadline, deadline);
        env->ReleaseStringUTFChars(jVaultId, vaultId);
        env->ReleaseStringUTFChars(jStoredProof, storedProof);
        return env->NewStringUTF("");
    }
    memcpy(msg, deadline, dl_len);
    memcpy(msg + dl_len, vaultId, vi_len);

    /* 计算 Blake2b-MAC */
    uint8_t mac[32];
    blake2b(mac, 32, msg, msg_len, hmac_key, 32);
    secure_zero(hmac_key, 32);
    secure_zero(msg, msg_len);
    free(msg);

    /* 转 hex */
    char mac_hex[65];
    for (int i = 0; i < 32; i++) {
        snprintf(mac_hex + i * 2, 3, "%02x", mac[i]);
    }
    mac_hex[64] = '\0';
    secure_zero(mac, 32);

    /* 比较 storedProof 与计算出的 MAC（恒定时间） */
    size_t sp_len = strlen(storedProof);
    if (sp_len != 64) {
        env->ReleaseStringUTFChars(jDeadline, deadline);
        env->ReleaseStringUTFChars(jVaultId, vaultId);
        env->ReleaseStringUTFChars(jStoredProof, storedProof);
        return env->NewStringUTF("");
    }

    volatile uint8_t diff = 0;
    for (size_t i = 0; i < 64; i++) {
        diff |= (uint8_t)mac_hex[i] ^ (uint8_t)storedProof[i];
    }

    env->ReleaseStringUTFChars(jVaultId, vaultId);
    env->ReleaseStringUTFChars(jStoredProof, storedProof);

    if (diff != 0) {
        /* HMAC 不匹配 → 数据被篡改 */
        env->ReleaseStringUTFChars(jDeadline, deadline);
        return env->NewStringUTF("");
    }

    /* HMAC 匹配 → 检查时间 */
    long long dl_val = strtoll(deadline, nullptr, 10);
    env->ReleaseStringUTFChars(jDeadline, deadline);

    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    long long now_ms = (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;

    if (now_ms < dl_val) {
        /* 未过期 → 返回 MAC 作为 proof */
        return env->NewStringUTF(mac_hex);
    }

    /* 已过期 */
    return env->NewStringUTF("");
}

JNIEXPORT jboolean JNICALL
Java_com_whmdg_mczj_tools_auth_NativeAuth_bypassHiddenApi(
        JNIEnv *env, jclass /* clazz */) {
    jclass vmRuntimeClass = env->FindClass("dalvik/system/VMRuntime");
    if (!vmRuntimeClass) return JNI_FALSE;

    jmethodID getRuntime = env->GetStaticMethodID(vmRuntimeClass, "getRuntime",
        "()Ldalvik/system/VMRuntime;");
    if (!getRuntime) return JNI_FALSE;

    jobject vmRuntime = env->CallStaticObjectMethod(vmRuntimeClass, getRuntime);
    if (!vmRuntime) return JNI_FALSE;

    jmethodID setExemptions = env->GetMethodID(vmRuntimeClass, "setHiddenApiExemptions",
        "([Ljava/lang/String;)V");
    if (!setExemptions) {
        env->DeleteLocalRef(vmRuntime);
        env->DeleteLocalRef(vmRuntimeClass);
        return JNI_FALSE;
    }

    jclass stringClass = env->FindClass("java/lang/String");
    jstring exemption = env->NewStringUTF("L");
    jobjectArray exemptions = env->NewObjectArray(1, stringClass, exemption);

    env->CallVoidMethod(vmRuntime, setExemptions, exemptions);

    env->DeleteLocalRef(exemption);
    env->DeleteLocalRef(exemptions);
    env->DeleteLocalRef(vmRuntime);
    env->DeleteLocalRef(vmRuntimeClass);

    LOGE("bypassHiddenApi: setHiddenApiExemptions called successfully");
    return JNI_TRUE;
}

}
