#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>
#include <openssl/evp.h>
#include <openssl/rand.h>

#define LOG_TAG "NativeAesGcm"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define IV_LEN 12
#define TAG_LEN 16

/*
 * AES-256-GCM 加密（硬件加速）
 *
 * 返回 byte[]: [IV 12B][ciphertext][tag 16B]
 * 若 plaintext 为空，返回 [IV 12B][tag 16B]（仅28字节）
 */
JNIEXPORT jbyteArray JNICALL
Java_com_whmdg_mczj_tools_encryption_core_NativeAesGcm_encrypt(
        JNIEnv *env, jobject thiz,
        jbyteArray jkey, jbyteArray jplaintext, jbyteArray jaad) {

    int key_len = (*env)->GetArrayLength(env, jkey);
    if (key_len != 32) {
        LOGE("key must be 32 bytes, got %d", key_len);
        return NULL;
    }
    int in_len = (*env)->GetArrayLength(env, jplaintext);

    jbyte *key = (*env)->GetByteArrayElements(env, jkey, NULL);
    jbyte *plain = (in_len > 0) ? (*env)->GetByteArrayElements(env, jplaintext, NULL) : NULL;
    jbyte *aad = NULL;
    int aad_len = 0;
    if (jaad != NULL) {
        aad_len = (*env)->GetArrayLength(env, jaad);
        if (aad_len > 0) aad = (*env)->GetByteArrayElements(env, jaad, NULL);
    }

    // 生成随机 IV
    unsigned char iv[IV_LEN];
    if (RAND_bytes(iv, IV_LEN) != 1) {
        LOGE("RAND_bytes failed");
        goto cleanup_key;
    }

    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx) { LOGE("EVP_CIPHER_CTX_new failed"); goto cleanup_key; }

    // 初始化 GCM，设 IV 长度
    if (EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), NULL, NULL, NULL) != 1) {
        LOGE("EncryptInit setup failed"); goto cleanup_ctx;
    }
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, IV_LEN, NULL) != 1) {
        LOGE("SET_IVLEN failed"); goto cleanup_ctx;
    }

    // 设置密钥 + IV
    if (EVP_EncryptInit_ex(ctx, NULL, NULL, (unsigned char *)key, iv) != 1) {
        LOGE("EncryptInit key/iv failed"); goto cleanup_ctx;
    }

    // AAD（附加认证数据）
    if (aad != NULL && aad_len > 0) {
        int tmp;
        if (EVP_EncryptUpdate(ctx, NULL, &tmp, (unsigned char *)aad, aad_len) != 1) {
            LOGE("AAD update failed"); goto cleanup_ctx;
        }
    }

    // 加密
    unsigned char *out = NULL;
    int out_len = 0;
    if (in_len > 0) {
        out = (unsigned char *)malloc(in_len + EVP_MAX_BLOCK_LENGTH);
        if (!out) { LOGE("malloc failed"); goto cleanup_ctx; }
        if (EVP_EncryptUpdate(ctx, out, &out_len, (unsigned char *)plain, in_len) != 1) {
            LOGE("EncryptUpdate failed"); free(out); goto cleanup_ctx;
        }
    }

    // 完成加密（GCM 模式下无额外 padding）
    int final_len = 0;
    if (EVP_EncryptFinal_ex(ctx, out ? out + out_len : NULL, &final_len) != 1) {
        LOGE("EncryptFinal failed"); if (out) free(out); goto cleanup_ctx;
    }
    out_len += final_len;

    // 取 GCM tag
    unsigned char tag[TAG_LEN];
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, TAG_LEN, tag) != 1) {
        LOGE("GET_TAG failed"); if (out) free(out); goto cleanup_ctx;
    }

    // 组装结果: [IV 12B][ciphertext][tag 16B]
    int result_len = IV_LEN + out_len + TAG_LEN;
    jbyteArray result = (*env)->NewByteArray(env, result_len);
    if (!result) { if (out) free(out); goto cleanup_ctx; }

    (*env)->SetByteArrayRegion(env, result, 0, IV_LEN, (jbyte *)iv);
    if (out_len > 0) {
        (*env)->SetByteArrayRegion(env, result, IV_LEN, out_len, (jbyte *)out);
    }
    (*env)->SetByteArrayRegion(env, result, IV_LEN + out_len, TAG_LEN, (jbyte *)tag);

    if (out) free(out);
    EVP_CIPHER_CTX_free(ctx);
    if (aad) (*env)->ReleaseByteArrayElements(env, jaad, aad, JNI_ABORT);
    if (plain) (*env)->ReleaseByteArrayElements(env, jplaintext, plain, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jkey, key, JNI_ABORT);
    return result;

cleanup_ctx:
    EVP_CIPHER_CTX_free(ctx);
cleanup_key:
    if (aad) (*env)->ReleaseByteArrayElements(env, jaad, aad, JNI_ABORT);
    if (plain) (*env)->ReleaseByteArrayElements(env, jplaintext, plain, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jkey, key, JNI_ABORT);
    return NULL;
}

/*
 * AES-256-GCM 解密
 *
 * 参数 ciphertextAndTag: [ciphertext][tag 16B]（不含 IV）
 * 返回 byte[]: plaintext
 */
JNIEXPORT jbyteArray JNICALL
Java_com_whmdg_mczj_tools_encryption_core_NativeAesGcm_decrypt(
        JNIEnv *env, jobject thiz,
        jbyteArray jkey, jbyteArray jiv, jbyteArray jciphertextAndTag, jbyteArray jaad) {

    int key_len = (*env)->GetArrayLength(env, jkey);
    int iv_len = (*env)->GetArrayLength(env, jiv);
    int cat_len = (*env)->GetArrayLength(env, jciphertextAndTag);

    if (key_len != 32 || iv_len != IV_LEN || cat_len < TAG_LEN) {
        LOGE("invalid params: key=%d iv=%d cat=%d", key_len, iv_len, cat_len);
        return NULL;
    }

    int cipher_len = cat_len - TAG_LEN;

    jbyte *key = (*env)->GetByteArrayElements(env, jkey, NULL);
    jbyte *iv = (*env)->GetByteArrayElements(env, jiv, NULL);
    jbyte *cat = (*env)->GetByteArrayElements(env, jciphertextAndTag, NULL);
    jbyte *aad = NULL;
    int aad_len = 0;
    if (jaad != NULL) {
        aad_len = (*env)->GetArrayLength(env, jaad);
        if (aad_len > 0) aad = (*env)->GetByteArrayElements(env, jaad, NULL);
    }

    // tag 在 ciphertextAndTag 的最后16字节
    unsigned char *tag = (unsigned char *)cat + cipher_len;

    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx) goto cleanup_all;

    if (EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), NULL, NULL, NULL) != 1) goto cleanup_ctx;
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, IV_LEN, NULL) != 1) goto cleanup_ctx;
    if (EVP_DecryptInit_ex(ctx, NULL, NULL, (unsigned char *)key, (unsigned char *)iv) != 1)
        goto cleanup_ctx;

    // AAD
    if (aad != NULL && aad_len > 0) {
        int tmp;
        if (EVP_DecryptUpdate(ctx, NULL, &tmp, (unsigned char *)aad, aad_len) != 1) goto cleanup_ctx;
    }

    // 解密
    unsigned char *out = NULL;
    int out_len = 0;
    if (cipher_len > 0) {
        out = (unsigned char *)malloc(cipher_len);
        if (!out) goto cleanup_ctx;
        if (EVP_DecryptUpdate(ctx, out, &out_len, (unsigned char *)cat, cipher_len) != 1) {
            free(out); goto cleanup_ctx;
        }
    }

    // 设置 tag 并验证
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, TAG_LEN, tag) != 1) {
        if (out) free(out); goto cleanup_ctx;
    }

    int final_len = 0;
    if (EVP_DecryptFinal_ex(ctx, out ? out + out_len : NULL, &final_len) != 1) {
        LOGE("GCM tag verification failed"); if (out) free(out); goto cleanup_ctx;
    }
    out_len += final_len;

    // 返回明文
    jbyteArray result = (*env)->NewByteArray(env, out_len);
    if (result && out_len > 0) {
        (*env)->SetByteArrayRegion(env, result, 0, out_len, (jbyte *)out);
    }
    if (out) free(out);
    EVP_CIPHER_CTX_free(ctx);
    if (aad) (*env)->ReleaseByteArrayElements(env, jaad, aad, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jciphertextAndTag, cat, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jiv, iv, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jkey, key, JNI_ABORT);
    return result;

cleanup_ctx:
    EVP_CIPHER_CTX_free(ctx);
cleanup_all:
    if (aad) (*env)->ReleaseByteArrayElements(env, jaad, aad, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jciphertextAndTag, cat, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jiv, iv, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jkey, key, JNI_ABORT);
    return NULL;
}
