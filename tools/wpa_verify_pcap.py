#!/usr/bin/env python3
"""
脚本 1: 从 pcap base64 数据验证 WPA2 MIC
用法: python3 wpa_verify_pcap.py <base64_string>
      或: echo "base64..." | python3 wpa_verify_pcap.py
"""
import sys
import base64
import hashlib
import hmac
from scapy.all import rdpcap, EAPOL_KEY, EAPOL

# ─── 硬编码目标 WiFi 信息 ───
TARGET_SSID = "wang1203_5G"
TARGET_PASSWORD = "19850324"


def prf_384(pmk, mac1, mac2, nonce1, nonce2):
    """IEEE 802.11i PRF-384"""
    label = b"Pairwise key expansion"
    data = label + b'\x00' + mac1 + mac2 + nonce1 + nonce2
    result = b""
    for i in range(3):
        result += hmac.new(pmk, data + bytes([i]), hashlib.sha1).digest()
    return result[:48]


def verify_from_pcap(b64_data):
    raw = base64.b64decode(b64_data)
    with open("/tmp/_wpa_verify.pcap", "wb") as f:
        f.write(raw)
    pkts = rdpcap("/tmp/_wpa_verify.pcap")

    # 找 rc=1 的 Msg1 + Msg2
    msg1 = msg2 = None
    for pkt in pkts:
        if not pkt.haslayer(EAPOL_KEY):
            continue
        ek = pkt[EAPOL_KEY]
        if ek.key_replay_counter != 1:
            continue
        if ek.key_ack == 1 and ek.has_key_mic == 0:
            msg1 = pkt
        elif ek.key_ack == 0 and ek.has_key_mic == 1:
            msg2 = pkt

    if not msg1 or not msg2:
        print("错误: 未找到 rc=1 的 Msg1+Msg2 配对")
        return

    # 提取字段
    ap_mac = bytes.fromhex(msg1.src.replace(':', ''))
    sta_mac = bytes.fromhex(msg2.src.replace(':', ''))
    anonce = msg1[EAPOL_KEY].key_nonce
    snonce = msg2[EAPOL_KEY].key_nonce
    expected_mic = msg2[EAPOL_KEY].key_mic
    key_ver = msg1[EAPOL_KEY].key_descriptor_type_version

    print("=== 握手数据 (scapy 解析) ===")
    print(f"  AP MAC:   {msg1.src}")
    print(f"  STA MAC:  {msg2.src}")
    print(f"  ANonce:   {anonce.hex()}")
    print(f"  SNonce:   {snonce.hex()}")
    print(f"  MIC:      {expected_mic.hex()}")
    print(f"  Key Ver:  {key_ver}")
    print(f"  EAPOL ver (Msg1): {msg1[EAPOL].version}")
    print(f"  EAPOL ver (Msg2): {msg2[EAPOL].version}")
    print()

    # PMK
    pmk = hashlib.pbkdf2_hmac('sha1', TARGET_PASSWORD.encode(), TARGET_SSID.encode(), 4096, dklen=32)

    # PTK
    mac1, mac2 = sorted([ap_mac, sta_mac])
    nonce1, nonce2 = sorted([anonce, snonce])
    ptk = prf_384(pmk, mac1, mac2, nonce1, nonce2)
    kck = ptk[:16]

    # 获取 EAPOL 帧字节 (含 802.1X header)
    eapol_bytes = bytes(msg2[EAPOL])

    # 置零 MIC
    mic_pos = eapol_bytes.find(expected_mic)
    if mic_pos < 0:
        print("错误: 在 EAPOL 帧中未找到 MIC 字段")
        return

    zeroed = bytearray(eapol_bytes)
    zeroed[mic_pos:mic_pos+16] = b'\x00' * 16

    # 计算 MIC
    computed_mic = hmac.new(kck, bytes(zeroed), hashlib.sha1).digest()[:16]

    print("=== 密钥派生 ===")
    print(f"  SSID:     {TARGET_SSID}")
    print(f"  Password: {TARGET_PASSWORD}")
    print(f"  PMK:      {pmk.hex()}")
    print(f"  KCK:      {kck.hex()}")
    print()

    print("=== MIC 验证 ===")
    print(f"  期望 MIC: {expected_mic.hex()}")
    print(f"  计算 MIC: {computed_mic.hex()}")
    if computed_mic == expected_mic:
        print("  结果: ✓ 匹配!")
    else:
        print("  结果: ✗ 不匹配")
        # 尝试更多变体
        print("\n  尝试其他变体...")
        _try_variants(pmk, ap_mac, sta_mac, anonce, snonce, eapol_bytes, expected_mic, mic_pos)


def _try_variants(pmk, ap_mac, sta_mac, anonce, snonce, eapol_bytes, expected_mic, mic_pos):
    label = b"Pairwise key expansion"
    mac_perms = [
        ("AP<STA", *sorted([ap_mac, sta_mac])),
        ("STA<AP", *sorted([sta_mac, ap_mac])),
        ("AP,STA", ap_mac, sta_mac),
        ("STA,AP", sta_mac, ap_mac),
    ]
    nonce_perms = [
        ("AN<SN", *sorted([anonce, snonce])),
        ("SN<AN", *sorted([snonce, anonce])),
        ("AN,SN", anonce, snonce),
        ("SN,AN", snonce, anonce),
    ]

    for m_label, m1, m2 in mac_perms:
        for n_label, n1, n2 in nonce_perms:
            # 标准 PRF
            data = label + b'\x00' + m1 + m2 + n1 + n2
            ptk = b""
            for i in range(3):
                ptk += hmac.new(pmk, data + bytes([i]), hashlib.sha1).digest()
            ptk = ptk[:48]
            kck = ptk[:16]

            zeroed = bytearray(eapol_bytes)
            zeroed[mic_pos:mic_pos+16] = b'\x00' * 16
            computed = hmac.new(kck, bytes(zeroed), hashlib.sha1).digest()[:16]
            if computed == expected_mic:
                print(f"  *** MATCH! MAC={m_label}, Nonce={n_label}, PRF=SHA1-std ***")
                return

            # PRF 无 sep
            data2 = label + m1 + m2 + n1 + n2
            ptk2 = b""
            for i in range(3):
                ptk2 += hmac.new(pmk, data2 + bytes([i]), hashlib.sha1).digest()
            ptk2 = ptk2[:48]
            kck2 = ptk2[:16]
            computed2 = hmac.new(kck2, bytes(zeroed), hashlib.sha1).digest()[:16]
            if computed2 == expected_mic:
                print(f"  *** MATCH! MAC={m_label}, Nonce={n_label}, PRF=SHA1-nosep ***")
                return

    # 尝试 MD5
    pmk_md5 = hashlib.pbkdf2_hmac('md5', TARGET_PASSWORD.encode(), TARGET_SSID.encode(), 4096, dklen=32)
    for m_label, m1, m2 in mac_perms[:2]:
        for n_label, n1, n2 in nonce_perms[:2]:
            data = label + b'\x00' + m1 + m2 + n1 + n2
            ptk = b""
            for i in range(3):
                ptk += hmac.new(pmk_md5, data + bytes([i]), hashlib.md5).digest()
            ptk = ptk[:48]
            kck = ptk[:16]
            zeroed = bytearray(eapol_bytes)
            zeroed[mic_pos:mic_pos+16] = b'\x00' * 16
            for algo_name, algo in [("SHA1", hashlib.sha1), ("MD5", hashlib.md5)]:
                computed = hmac.new(kck, bytes(zeroed), algo).digest()[:16]
                if computed == expected_mic:
                    print(f"  *** MATCH! PMK=MD5, MAC={m_label}, Nonce={n_label}, MIC={algo_name} ***")
                    return

    print("  所有变体均未匹配")


if __name__ == "__main__":
    if len(sys.argv) > 1:
        b64 = sys.argv[1].strip()
    else:
        b64 = sys.stdin.read().strip()

    if not b64:
        print("用法: python3 wpa_verify_pcap.py <base64_string>")
        sys.exit(1)

    verify_from_pcap(b64)
