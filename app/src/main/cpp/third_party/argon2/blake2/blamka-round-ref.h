/*
 * Argon2 reference source code package - reference C implementations
 *
 * Copyright 2015
 * Daniel Dinu, Dmitry Khovratovich, Jean-Philippe Aumasson, and Samuel Neves
 *
 * You may use this work under the terms of a Creative Commons CC0 1.0
 * License/Waiver or the Apache Public License 2.0, at your option. The terms of
 * these licenses can be found at:
 *
 * - CC0 1.0 Universal : http://creativecommons.org/publicdomain/zero/1.0/
 * - Apache 2.0        : http://www.apache.org/licenses/LICENSE-2.0
 *
 * You should have received a copy of both of these licenses along with this
 * software. If not, they may be obtained at the above URLs.
 */

#ifndef ARGON2_BLAKE2_ROUND_REF_H
#define ARGON2_BLAKE2_ROUND_REF_H

#include "blake2-impl.h"

#define fBlaMka(a, b)                                                          \
    do {                                                                        \
        (a) += (b) + 2 * (uint64_t)(uint32_t)(a) * (uint32_t)(b);             \
    } while (0)

#define G(a, b, c, d)                                                          \
    do {                                                                        \
        fBlaMka(a, b);                                                         \
        fBlaMka(c, d);                                                         \
        (a) = rotr64((a), 32);                                                 \
        (d) = rotr64((d) ^ (a), 24);                                           \
        fBlaMka(b, c);                                                         \
        (a) = rotr64((a) ^ (b), 16);                                           \
        (b) = rotr64((b), 63);                                                 \
    } while (0)

#define BLAKE2_ROUND_NOMSG(v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11,  \
                           v12, v13, v14, v15)                                 \
    do {                                                                        \
        G(v0, v4, v8, v12);                                                    \
        G(v1, v5, v9, v13);                                                    \
        G(v2, v6, v10, v14);                                                   \
        G(v3, v7, v11, v15);                                                   \
        G(v0, v5, v10, v15);                                                   \
        G(v1, v6, v11, v12);                                                   \
        G(v2, v7, v8, v13);                                                    \
        G(v3, v4, v9, v14);                                                    \
    } while (0)

#endif /* ARGON2_BLAKE2_ROUND_REF_H */
