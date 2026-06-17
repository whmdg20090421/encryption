#!/usr/bin/env python3
"""
WPA/WPA2 四次握手 MIC 验证全算法测试
测试所有可能的密钥派生和 MIC 计算变体，找出正确的组合。
"""
import hashlib
import hmac
import struct
import sys

# ─── 握手数据（从 pcap 解析）───
SSID = "wang1203_5G"
PASSWORD = "19850324"

# Msg1 (AP→STA)
MSG1_ANONCE = bytes.fromhex("28a89f1c26ec8ea13c697dd7999ac773c5f5eb909e75c5f80789600fa9c532c0")
MSG1_AP_MAC = bytes.fromhex("9437f78213a6")

# Msg2 (STA→AP)
MSG2_STA_MAC = bytes.fromhex("6e5efbf96e71")
MSG2_SNONCE = bytes.fromhex("3b9079b0114ffe8cd387fa5be1b3049efa3d76d7b2934c277d9f3f167a5e8073")
MSG2_MIC_EXPECTED = bytes.fromhex("4094e2b89553f16cef98419d62014c65")

# ─── EAPOL-Key 各字段（从 pcap 逐字节提取）───
# 802.1X header: version=1, type=3(Key), length=0x00fb
EAPOL_VERSION = 0x01
EAPOL_TYPE = 0x03
EAPOL_LEN = 0x00fb  # 251

# Key Descriptor
KEY_DESCRIPTOR = 0x02  # RSN (WPA2)
KEY_INFO = 0x010a      # Msg2: Pairwise=1, MIC=1, SNonce=1, ACK=0
KEY_LEN = 0x0000
REPLAY_COUNTER = 1

# Msg2 EAPOL 帧的完整字段（不含链路层，从 802.1X header 开始）
# 结构: [802.1X 4B] [descriptor 1B] [key_info 2B] [key_len 2B] [replay 8B]
#        [nonce 32B] [iv 16B] [rsc 8B] [id 8B] [mic 16B] [key_data_len 2B] [key_data]
MSG2_EAPOL_HEX = (
    "010300fb02010a00"  # 802.1X(4) + descriptor(1) + key_info(2) + key_len(1+1)
    "0000000000000001"  # replay counter (8)
    "3b9079b0114ffe8cd387fa5be1b3049efa3d76d7b2934c277d9f3f167a5e8073"  # SNonce (32)
    "00000000000000000000000000000000"  # Key IV (16)
    "0000000000000000"  # Key RSC (8)
    "0000000000000000"  # Key ID (8)
    "4094e2b89553f16cef98419d62014c65"  # MIC (16) - 实际值
    "0000"  # Key Data Length
)
MSG2_EAPOL_BYTES = bytes.fromhex(MSG2_EAPOL_HEX)

# ─── 工具函数 ───

def prf_x(pmk: bytes, label: bytes, data: bytes, x_bits: int) -> bytes:
    """PRF-X (IEEE 802.11i): 标准实现"""
    x_bytes = x_bits // 8
    result = b""
    counter = 0
    while len(result) < x_bytes:
        hmac_sha1 = hmac.new(pmk, label + b'\x00' + data + bytes([counter]), hashlib.sha1).digest()
        result += hmac_sha1
        counter += 1
    return result[:x_bytes]


def prf_x_variant_b(pmk: bytes, label: bytes, data: bytes, x_bits: int) -> bytes:
    """PRF-X 变体 B: label 后不加 0x00"""
    x_bytes = x_bits // 8
    result = b""
    counter = 0
    while len(result) < x_bytes:
        hmac_sha1 = hmac.new(pmk, label + data + bytes([counter]), hashlib.sha1).digest()
        result += hmac_sha1
        counter += 1
    return result[:x_bytes]


def prf_x_md5(pmk: bytes, label: bytes, data: bytes, x_bits: int) -> bytes:
    """PRF-X (WPA1): 使用 HMAC-MD5"""
    x_bytes = x_bits // 8
    result = b""
    counter = 0
    while len(result) < x_bytes:
        hmac_md5 = hmac.new(pmk, label + b'\x00' + data + bytes([counter]), hashlib.md5).digest()
        result += hmac_md5
        counter += 1
    return result[:x_bytes]


def prf384_kt(pmk: bytes, template: bytes) -> bytes:
    """复现 HandshakeCapture.kt 的 PRF-384: 计数器替换 position 22 和末尾"""
    result = b""
    mac = hmac.new(pmk, digestmod=hashlib.sha1)
    for i in range(3):
        inp = bytearray(template)
        inp[22] = i
        inp[-1] = i
        mac_copy = mac.copy()
        mac_copy.update(inp)
        result += mac_copy.digest()
    return result[:48]


def compute_mic_wpa2_sha1(kck: bytes, eapol: bytes, mic_offset: int) -> bytes:
    """WPA2 MIC: HMAC-SHA1(KCK, eapol_with_mic_zeroed)[0:16]"""
    zeroed = bytearray(eapol)
    zeroed[mic_offset:mic_offset+16] = b'\x00' * 16
    return hmac.new(kck, bytes(zeroed), hashlib.sha1).digest()[:16]


def compute_mic_wpa2_sha256(kck: bytes, eapol: bytes, mic_offset: int) -> bytes:
    """WPA2 MIC (SHA256): HMAC-SHA256(KCK, eapol_with_mic_zeroed)[0:16]"""
    zeroed = bytearray(eapol)
    zeroed[mic_offset:mic_offset+16] = b'\x00' * 16
    return hmac.new(kck, bytes(zeroed), hashlib.sha256).digest()[:16]


def compute_mic_wpa1_md5(kck: bytes, eapol: bytes, mic_offset: int) -> bytes:
    """WPA1 MIC: HMAC-MD5(KCK, eapol_with_mic_zeroed)[0:16]"""
    zeroed = bytearray(eapol)
    zeroed[mic_offset:mic_offset+16] = b'\x00' * 16
    return hmac.new(kck, bytes(zeroed), hashlib.md5).digest()[:16]


def format_hex(b: bytes) -> str:
    return b.hex()


# ─── 测试所有变体 ───

def run_tests():
    ssid_bytes = SSID.encode('utf-8')
    password_bytes = PASSWORD.encode('utf-8')

    # PMK 变体
    pmk_wpa2 = hashlib.pbkdf2_hmac('sha1', password_bytes, ssid_bytes, 4096, 32)
    pmk_wpa2_2048 = hashlib.pbkdf2_hmac('sha1', password_bytes, ssid_bytes, 2048, 32)
    pmk_wpa1 = hashlib.pbkdf2_hmac('md5', password_bytes, ssid_bytes, 4096, 32)

    print(f"SSID: {SSID}")
    print(f"Password: {PASSWORD}")
    print(f"PMK (PBKDF2-SHA1-4096): {format_hex(pmk_wpa2)}")
    print(f"PMK (PBKDF2-SHA1-2048): {format_hex(pmk_wpa2_2048)}")
    print(f"PMK (PBKDF2-MD5-4096):  {format_hex(pmk_wpa1)}")
    print(f"Expected MIC: {format_hex(MSG2_MIC_EXPECTED)}")
    print()

    # MAC/Nonce 排列
    mac_cases = [
        ("AP<STA", MSG1_AP_MAC, MSG2_STA_MAC),
        ("STA<AP", MSG2_STA_MAC, MSG1_AP_MAC),
        ("AP,STA (no sort)", MSG1_AP_MAC, MSG2_STA_MAC),
        ("STA,AP (no sort)", MSG2_STA_MAC, MSG1_AP_MAC),
    ]
    nonce_cases = [
        ("AN<SN", MSG1_ANONCE, MSG2_SNONCE),
        ("SN<AN", MSG2_SNONCE, MSG1_ANONCE),
        ("AN,SN (no sort)", MSG1_ANONCE, MSG2_SNONCE),
        ("SN,AN (no sort)", MSG2_SNONCE, MSG1_ANONCE),
    ]

    # MIC 偏移变体
    # 802.1X header = 4 bytes, MIC 在 Key Descriptor 中偏移 77
    # 所以 MIC 在 EAPOL 帧中的偏移 = 4 + 77 = 81
    mic_offsets = [77, 81, 79, 83]

    # EAPOL 帧变体
    eapol_variants = [
        ("full EAPOL", MSG2_EAPOL_BYTES),
        ("without 802.1X header", MSG2_EAPOL_BYTES[4:]),
    ]

    test_num = 0
    matches = []

    for pmk_name, pmk in [
        ("PBKDF2-SHA1-4096", pmk_wpa2),
        ("PBKDF2-SHA1-2048", pmk_wpa2_2048),
        ("PBKDF2-MD5-4096", pmk_wpa1),
    ]:
        for mac_label, mac1, mac2 in mac_cases:
            for nonce_label, nonce1, nonce2 in nonce_cases:
                # PRF 变体 1: 标准 IEEE 802.11i PRF-384
                label = b"Pairwise key expansion"
                data_std = mac1 + mac2 + nonce1 + nonce2
                ptk_std = prf_x(pmk, label, data_std, 384)

                # PRF 变体 2: kt 实现（counter 替换 template 中的 0x00）
                template = label + b'\x00' + mac1 + mac2 + nonce1 + nonce2 + b'\x00'
                ptk_kt = prf384_kt(pmk, template)

                # PRF 变体 3: label 后不加 0x00
                ptk_nosep = prf_x_variant_b(pmk, label, data_std, 384)

                # PRF 变体 4: HMAC-MD5 (WPA1)
                ptk_md5 = prf_x_md5(pmk, label, data_std, 384)

                prf_variants = [
                    ("PRF-SHA1-std", ptk_std),
                    ("PRF-SHA1-kt", ptk_kt),
                    ("PRF-SHA1-nosep", ptk_nosep),
                    ("PRF-MD5", ptk_md5),
                ]

                for prf_name, ptk in prf_variants:
                    kck = ptk[:16]

                    for eapol_label, eapol_data in eapol_variants:
                        for mic_offset in mic_offsets:
                            # MIC 计算变体
                            mic_variants = [
                                ("SHA1", compute_mic_wpa2_sha1(kck, eapol_data, mic_offset)),
                                ("SHA256", compute_mic_wpa2_sha256(kck, eapol_data, mic_offset)),
                                ("MD5", compute_mic_wpa1_md5(kck, eapol_data, mic_offset)),
                            ]

                            for mic_name, computed_mic in mic_variants:
                                test_num += 1
                                if computed_mic == MSG2_MIC_EXPECTED:
                                    matches.append({
                                        "pmk": pmk_name,
                                        "mac": mac_label,
                                        "nonce": nonce_label,
                                        "prf": prf_name,
                                        "eapol": eapol_label,
                                        "mic_offset": mic_offset,
                                        "mic_algo": mic_name,
                                        "ptk": format_hex(ptk),
                                        "kck": format_hex(kck),
                                        "mic": format_hex(computed_mic),
                                    })

    print(f"共测试 {test_num} 种变体")

    if matches:
        print(f"\n{'='*60}")
        print(f"找到 {len(matches)} 种匹配！")
        print(f"{'='*60}")
        for i, m in enumerate(matches):
            print(f"\n--- 匹配 #{i+1} ---")
            for k, v in m.items():
                print(f"  {k}: {v}")
    else:
        print("\n所有变体均未匹配！")
        print("\n可能原因:")
        print("1. pcap 中的 Msg2 EAPOL 帧数据不完整或有误")
        print("2. MIC 偏移计算有误")
        print("3. 密码或 SSID 不正确")
        print("4. AP 使用了非标准的密钥派生方式")

        # 打印一些调试信息
        print(f"\n--- 调试信息 ---")
        print(f"Msg2 EAPOL 长度: {len(MSG2_EAPOL_BYTES)} 字节")
        print(f"Msg2 EAPOL hex (前 100 字节): {format_hex(MSG2_EAPOL_BYTES[:100])}")

        # 检查 MIC 偏移处的值
        for offset in mic_offsets:
            if offset + 16 <= len(MSG2_EAPOL_BYTES):
                mic_at = MSG2_EAPOL_BYTES[offset:offset+16]
                print(f"MIC @{offset}: {format_hex(mic_at)}")

        # 标准 WPA2 全流程
        print(f"\n--- 标准 WPA2 全流程 (SHA1-4096, AP<STA, AN<SN) ---")
        pmk = pmk_wpa2
        mac1, mac2 = MSG1_AP_MAC, MSG2_STA_MAC
        nonce1, nonce2 = MSG1_ANONCE, MSG2_SNONCE
        label = b"Pairwise key expansion"
        data = mac1 + mac2 + nonce1 + nonce2

        ptk = prf_x(pmk, label, data, 384)
        print(f"PTK: {format_hex(ptk)}")
        kck = ptk[:16]
        print(f"KCK: {format_hex(kck)}")

        for eapol_label, eapol_data in eapol_variants:
            for mic_offset in mic_offsets:
                mic = compute_mic_wpa2_sha1(kck, eapol_data, mic_offset)
                print(f"  {eapol_label} @ offset {mic_offset}: {format_hex(mic)}")

        # 测试: kt 的 PRF 实现 vs 标准
        print(f"\n--- PRF 实现对比 ---")
        template = label + b'\x00' + mac1 + mac2 + nonce1 + nonce2 + b'\x00'
        ptk_kt = prf384_kt(pmk, template)
        ptk_std = prf_x(pmk, label, data, 384)
        print(f"PTK (kt):  {format_hex(ptk_kt)}")
        print(f"PTK (std): {format_hex(ptk_std)}")
        print(f"Match: {ptk_kt == ptk_std}")

        # 测试: 反转 MAC 顺序
        print(f"\n--- 反转 MAC 顺序 ---")
        data_rev = MSG2_STA_MAC + MSG1_AP_MAC + MSG1_ANONCE + MSG2_SNONCE
        ptk_rev = prf_x(pmk, label, data_rev, 384)
        print(f"PTK (STA,AP): {format_hex(ptk_rev)}")
        kck_rev = ptk_rev[:16]
        for eapol_label, eapol_data in eapol_variants:
            for mic_offset in mic_offsets:
                mic = compute_mic_wpa2_sha1(kck_rev, eapol_data, mic_offset)
                print(f"  {eapol_label} @ offset {mic_offset}: {format_hex(mic)}")

        # 测试: 反转 Nonce 顺序
        print(f"\n--- 反转 Nonce 顺序 ---")
        data_rev2 = MSG1_AP_MAC + MSG2_STA_MAC + MSG2_SNONCE + MSG1_ANONCE
        ptk_rev2 = prf_x(pmk, label, data_rev2, 384)
        print(f"PTK (SN,AN): {format_hex(ptk_rev2)}")
        kck_rev2 = ptk_rev2[:16]
        for eapol_label, eapol_data in eapol_variants:
            for mic_offset in mic_offsets:
                mic = compute_mic_wpa2_sha1(kck_rev2, eapol_data, mic_offset)
                print(f"  {eapol_label} @ offset {mic_offset}: {format_hex(mic)}")


if __name__ == "__main__":
    run_tests()
