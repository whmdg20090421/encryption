#include "obf.h"
#include "obf_key.h"
#include <string.h>

void deobf(const uint8_t *obf, uint8_t *out, size_t n) {
    for (size_t i = 0; i < n; i++) {
        out[i] = obf[i] ^ OBF_KEY[i % 32];
    }
}

void secure_zero(void *ptr, size_t len) {
    volatile uint8_t *p = (volatile uint8_t *)ptr;
    while (len--) *p++ = 0;
    __asm__ __volatile__("" : : "r"(ptr) : "memory");
}

int ct_memcmp(const uint8_t *a, const uint8_t *b, size_t n) {
    volatile uint8_t diff = 0;
    for (size_t i = 0; i < n; i++) {
        diff |= a[i] ^ b[i];
    }
    return diff;
}
