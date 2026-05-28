#include <jni.h>
#include <string.h>
#include <stdint.h>
#include <android/log.h>

#include "obf.h"
#include "hashes.inc"
#include "argon2.h"
#include "blake2.h"

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

}
