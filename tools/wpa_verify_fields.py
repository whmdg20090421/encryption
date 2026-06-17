#!/usr/bin/env python3
"""
脚本 2: 从已解析的握手字段验证 WPA2 MIC
用法: python3 wpa_verify_fields.py --bssid AA:BB:CC:DD:EE:FF --client 11:22:33:44:55:66 \
         --anonce <hex> --snonce <hex> --mic <hex> [--eapol <hex>]

参数来源: HandshakeCapture.kt 解析输出的明文数据
"""
import sys
import argparse
import hashlib
import hmac

# ─── 硬编码目标 WiFi 信息 ───
TARGET_SSID = "wang1203_5G"
TARGET_PASSWORD = "19850324"


def mac_to_bytes(mac_str):
    return bytes.fromhex(mac_str.replace(':', '').replace('-', ''))


def hex_to_bytes(hex_str):
    return bytes.fromhex(hex_str.strip())


def prf_384(pmk, mac1, mac2, nonce1, nonce2):
    """IEEE 802.11i PRF-384"""
    label = b"Pairwise key expansion"
    data = label + b'\x00' + mac1 + mac2 + nonce1 + nonce2
    result = b""
    for i in range(3):
        result += hmac.new(pmk, data + bytes([i]), hashlib.sha1).digest()
    return result[:48]


def verify_fields(bssid, client_mac, anonce_hex, snonce_hex, mic_hex, eapol_hex=None):
    ap_mac = mac_to_bytes(bssid)
    sta_mac = mac_to_bytes(client_mac)
    anonce = hex_to_bytes(anonce_hex)
    snonce = hex_to_bytes(snonce_hex)
    expected_mic = hex_to_bytes(mic_hex)

    # PMK
    pmk = hashlib.pbkdf2_hmac('sha1', TARGET_PASSWORD.encode(), TARGET_SSID.encode(), 4096, dklen=32)

    # PTK
    mac1, mac2 = sorted([ap_mac, sta_mac])
    nonce1, nonce2 = sorted([anonce, snonce])
    ptk = prf_384(pmk, mac1, mac2, nonce1, nonce2)
    kck = ptk[:16]

    print("=== 握手数据 ===")
    print(f"  SSID:      {TARGET_SSID}")
    print(f"  BSSID:     {bssid}")
    print(f"  Client:    {client_mac}")
    print(f"  ANonce:    {anonce_hex}")
    print(f"  SNonce:    {snonce_hex}")
    print(f"  期望 MIC:  {mic_hex}")
    print()

    print("=== 密钥派生 ===")
    print(f"  Password:  {TARGET_PASSWORD}")
    print(f"  PMK:       {pmk.hex()}")
    print(f"  KCK:       {kck.hex()}")
    print()

    if eapol_hex:
        # 有 EAPOL 帧数据，直接计算 MIC
        eapol_bytes = hex_to_bytes(eapol_hex)
        mic_pos = eapol_bytes.find(expected_mic)

        if mic_pos >= 0:
            zeroed = bytearray(eapol_bytes)
            zeroed[mic_pos:mic_pos+16] = b'\x00' * 16
            computed_mic = hmac.new(kck, bytes(zeroed), hashlib.sha1).digest()[:16]
        else:
            # MIC 不在帧中，尝试常见偏移
            print("  注意: 在 EAPOL 帧中未找到 MIC 字段，尝试常见偏移...")
            computed_mic = None
            for offset in [77, 81, 79, 83]:
                if offset + 16 <= len(eapol_bytes):
                    zeroed = bytearray(eapol_bytes)
                    zeroed[offset:offset+16] = b'\x00' * 16
                    mic = hmac.new(kck, bytes(zeroed), hashlib.sha1).digest()[:16]
                    if mic == expected_mic:
                        computed_mic = mic
                        print(f"  在 offset {offset} 找到匹配!")
                        break
            if computed_mic is None:
                # 用 offset 81 作为默认
                zeroed = bytearray(eapol_bytes)
                if 81 + 16 <= len(zeroed):
                    zeroed[81:97] = b'\x00' * 16
                    computed_mic = hmac.new(kck, bytes(zeroed), hashlib.sha1).digest()[:16]
                else:
                    computed_mic = b'\x00' * 16
    else:
        print("  注意: 未提供 EAPOL 帧数据，无法计算 MIC")
        print("  提示: 使用 --eapol 参数传入 Msg2 的完整 EAPOL 帧 hex")
        computed_mic = None

    print("=== MIC 验证 ===")
    if computed_mic:
        print(f"  期望 MIC:  {expected_mic.hex()}")
        print(f"  计算 MIC:  {computed_mic.hex()}")
        if computed_mic == expected_mic:
            print("  结果: ✓ 匹配!")
        else:
            print("  结果: ✗ 不匹配")
            # 尝试变体
            if eapol_hex:
                print("\n  尝试其他变体...")
                _try_variants(pmk, ap_mac, sta_mac, anonce, snonce, eapol_bytes, expected_mic)
    else:
        print(f"  期望 MIC:  {expected_mic.hex()}")


def _try_variants(pmk, ap_mac, sta_mac, anonce, snonce, eapol_bytes, expected_mic):
    label = b"Pairwise key expansion"
    mac_perms = [("AP<STA", *sorted([ap_mac, sta_mac])), ("STA<AP", *sorted([sta_mac, ap_mac]))]
    nonce_perms = [("AN<SN", *sorted([anonce, snonce])), ("SN<AN", *sorted([snonce, anonce]))]

    for m_label, m1, m2 in mac_perms:
        for n_label, n1, n2 in nonce_perms:
            for sep_label, sep in [("sep", b'\x00'), ("nosep", b"")]:
                data = label + sep + m1 + m2 + n1 + n2
                ptk = b""
                for i in range(3):
                    ptk += hmac.new(pmk, data + bytes([i]), hashlib.sha1).digest()
                ptk = ptk[:48]
                kck = ptk[:16]

                for offset in [77, 81]:
                    if offset + 16 <= len(eapol_bytes):
                        zeroed = bytearray(eapol_bytes)
                        zeroed[offset:offset+16] = b'\x00' * 16
                        computed = hmac.new(kck, bytes(zeroed), hashlib.sha1).digest()[:16]
                        if computed == expected_mic:
                            print(f"  *** MATCH! MAC={m_label}, Nonce={n_label}, PRF={sep_label}, offset={offset} ***")
                            return

    print("  所有变体均未匹配")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="WPA2 MIC 验证 (从已解析字段)")
    parser.add_argument("--bssid", required=True, help="AP MAC (AA:BB:CC:DD:EE:FF)")
    parser.add_argument("--client", required=True, help="客户端 MAC")
    parser.add_argument("--anonce", required=True, help="ANonce hex (64 字符)")
    parser.add_argument("--snonce", required=True, help="SNonce hex (64 字符)")
    parser.add_argument("--mic", required=True, help="MIC hex (32 字符)")
    parser.add_argument("--eapol", default=None, help="Msg2 完整 EAPOL 帧 hex (可选)")
    args = parser.parse_args()

    verify_fields(args.bssid, args.client, args.anonce, args.snonce, args.mic, args.eapol)
