#!/usr/bin/env python3
"""
预先生成 Argon2id 密码哈希并输出为 C 头文件 (hashes.inc)。

用法:
    pip install argon2-cffi
    python tools/gen_password_hashes.py

输出:
    app/src/main/cpp/hashes.inc   —— XOR 编码后的哈希常量（直接 #include）
    app/src/main/cpp/obf_key.h   —— OBF_KEY[32] 宏定义（编译期固定）

参数 (与 C 层保持一致):
    time_cost=2, memory_cost_kb=65536, parallelism=2, hash_len=32, salt_len=16
"""

import os
import sys
import secrets

try:
    from argon2.low_level import hash_secret_raw, Type
except ImportError:
    print("请先安装 argon2-cffi: pip install argon2-cffi", file=sys.stderr)
    sys.exit(1)

# ── 参数（与 C 层 / Kotlin 层保持一致）──
TIME_COST = 2
MEMORY_COST_KB = 65536
PARALLELISM = 2
HASH_LEN = 32
SALT_LEN = 16

# ── 三组预设密码 ──
KEYS = [
    ("k1", "Whm20090421"),
    ("k2", "Transformation"),
    ("k3", "DownloadTest"),
]

# ── 路径 ──
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
CPP_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "cpp")
HASHES_INC = os.path.join(CPP_DIR, "hashes.inc")
OBF_KEY_H = os.path.join(CPP_DIR, "obf_key.h")


def xor_bytes(data: bytes, key: bytes) -> bytes:
    return bytes(b ^ key[i % len(key)] for i, b in enumerate(data))


def fmt_c_array(data: bytes) -> str:
    return ", ".join(f"0x{b:02X}" for b in data)


def generate():
    os.makedirs(CPP_DIR, exist_ok=True)

    obf_key = secrets.token_bytes(32)

    salts = []
    obf_hashes = []
    obf_keyids = []

    print("=" * 60)
    print("Argon2id 哈希预生成")
    print(f"  time_cost={TIME_COST}, memory={MEMORY_COST_KB}KB, parallelism={PARALLELISM}")
    print(f"  hash_len={HASH_LEN}, salt_len={SALT_LEN}")
    print("=" * 60)

    for key_id, password in KEYS:
        salt = secrets.token_bytes(SALT_LEN)
        raw_hash = hash_secret_raw(
            secret=password.encode("utf-8"),
            salt=salt,
            time_cost=TIME_COST,
            memory_cost=MEMORY_COST_KB,
            parallelism=PARALLELISM,
            hash_len=HASH_LEN,
            type=Type.ID,
        )
        obf_hash = xor_bytes(raw_hash, obf_key)
        obf_keyid = xor_bytes(key_id.encode("utf-8"), obf_key)

        salts.append(salt)
        obf_hashes.append(obf_hash)
        obf_keyids.append(obf_keyid)

        print(f"\n[{key_id}] 密码长度={len(password)}")
        print(f"  salt(hex)    : {salt.hex()}")
        print(f"  raw_hash(hex): {raw_hash.hex()}")
        print(f"  obf_hash(hex): {obf_hash.hex()}")

        verify_hash = hash_secret_raw(
            secret=password.encode("utf-8"),
            salt=salt,
            time_cost=TIME_COST,
            memory_cost=MEMORY_COST_KB,
            parallelism=PARALLELISM,
            hash_len=HASH_LEN,
            type=Type.ID,
        )
        assert verify_hash == raw_hash, "Sanity check failed!"
        print("  [OK] 自验通过")

    # ── 输出 obf_key.h ──
    with open(OBF_KEY_H, "w") as f:
        f.write("/* 由 gen_password_hashes.py 自动生成，请勿手动编辑 */\n\n")
        f.write("#ifndef OBF_KEY_H\n#define OBF_KEY_H\n\n")
        f.write(f"static const unsigned char OBF_KEY[32] = {{\n    {fmt_c_array(obf_key)}\n}};\n\n")
        f.write("#endif /* OBF_KEY_H */\n")
    print(f"\n已生成: {OBF_KEY_H}")

    # ── 输出 hashes.inc ──
    with open(HASHES_INC, "w") as f:
        f.write("/* 由 gen_password_hashes.py 自动生成，请勿手动编辑 */\n\n")
        f.write("#ifndef HASHES_INC\n#define HASHES_INC\n\n")
        f.write('#include "obf_key.h"\n\n')

        for i, (key_id, _) in enumerate(KEYS):
            upper = key_id.upper()
            f.write(f"static const unsigned char SALT_{upper}[{SALT_LEN}] = {{\n    {fmt_c_array(salts[i])}\n}};\n\n")
            f.write(f"static const unsigned char OBF_HASH_{upper}[{HASH_LEN}] = {{\n    {fmt_c_array(obf_hashes[i])}\n}};\n\n")
            f.write(f"static const unsigned char OBF_KEYID_{upper}[{len(key_id)}] = {{\n    {fmt_c_array(obf_keyids[i])}\n}};\n\n")

        f.write(f"#define NUM_KEYS {len(KEYS)}\n\n")
        f.write("static const unsigned char *const ALL_SALTS[NUM_KEYS] = {\n")
        for key_id, _ in KEYS:
            f.write(f"    SALT_{key_id.upper()},\n")
        f.write("};\n\n")

        f.write("static const unsigned char *const ALL_OBF_HASHES[NUM_KEYS] = {\n")
        for key_id, _ in KEYS:
            f.write(f"    OBF_HASH_{key_id.upper()},\n")
        f.write("};\n\n")

        f.write("static const unsigned char *const ALL_OBF_KEYIDS[NUM_KEYS] = {\n")
        for key_id, _ in KEYS:
            f.write(f"    OBF_KEYID_{key_id.upper()},\n")
        f.write("};\n\n")

        f.write("static const unsigned char ALL_KEYID_LENS[NUM_KEYS] = {\n")
        for key_id, _ in KEYS:
            f.write(f"    {len(key_id)},\n")
        f.write("};\n\n")

        f.write("#endif /* HASHES_INC */\n")
    print(f"已生成: {HASHES_INC}")

    print("\n" + "=" * 60)
    print("所有哈希已生成并通过自验。")
    print("请将 obf_key.h 和 hashes.inc 加入 .gitignore 以防泄露。")
    print("=" * 60)


if __name__ == "__main__":
    generate()
