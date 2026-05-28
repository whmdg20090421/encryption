#ifndef OBF_H
#define OBF_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void deobf(const uint8_t *obf, uint8_t *out, size_t n);
void secure_zero(void *ptr, size_t len);
int  ct_memcmp(const uint8_t *a, const uint8_t *b, size_t n);

#ifdef __cplusplus
}
#endif

#endif /* OBF_H */
