#!/usr/bin/env python3
"""
从原始 pcap base64 数据解析 + WPA2 MIC 全面验证
"""
import base64
import struct
import hashlib
import hmac

B64 = "1MOyoQIABAAAAAAAAAAAAAAABAABAAAA8xkzauKKAwBxAAAAcQAAAG5e+/lucZQ394ITpoiOAgMAXwIAigAQAAAAAAAAAAEoqJ8cJuyOoTxpfdeZmsdzxfXrkJ51xfgHiWAPqcUywAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA8xkzal+RAwCHAAAAhwAAAJQ394ITpm5e+/lucYiOAQMAdQIBCgAAAAAAAAAAAAE7kHmwEU/+jNOH+lvhswSe+j1217KTTCd9nz8Wel6AcwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQJTiuJVT8WzvmEGdYgFMZQAWMBQBAAAPrAIBAAAPrAQBAAAPrAIMAPUZM2q5gQMAcQAAAHEAAABuXvv5bnGUN/eCE6aIjgIDAF8CAIoAEAAAAAAAAAACKKifHCbsjqE8aX3XmZrHc8X165CedcX4B4lgD6nFMsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPUZM2qohAMAhwAAAIcAAACUN/eCE6ZuXvv5bnGIjgEDAHUCAQoAAAAAAAAAAAACO5B5sBFP/ozTh/pb4bMEnvo9dteyk0wnfZ8/FnpegHMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAECP5mWwUYv+cnypLrsrBFgAFjAUAQAAD6wCAQAAD6wEAQAAD6wCDAA="

PASSWORD = "19850324"
SSID = "wang1203_5G"

raw = base64.b64decode(B64)
print(f"pcap 大小: {len(raw)} 字节")

# ─── 1. 解析 pcap 全局头 ───
magic_bytes = raw[0:4]
print(f"Magic bytes: {magic_bytes.hex()}")

# 判断字节序
if magic_bytes == b'\xd4\xc3\xb2\xa1':
    endian = '<'  # little-endian
    print("字节序: little-endian (标准)")
elif magic_bytes == b'\xa1\xb2\xc3\xd4':
    endian = '>'  # big-endian
    print("字节序: big-endian")
else:
    print(f"无效 pcap magic!")
    exit(1)

ver_major, ver_minor = struct.unpack(endian + 'HH', raw[4:8])
link_type = struct.unpack(endian + 'I', raw[20:24])[0]
print(f"pcap 版本: {ver_major}.{ver_minor}")
print(f"Link type: {link_type} ({'Ethernet' if link_type == 1 else '802.11' if link_type == 105 else 'SLL' if link_type == 113 else 'RadioTap' if link_type == 127 else 'Unknown'})")
print()

# ─── 2. 解析所有包 ───
packets_raw = []  # 原始包数据（含链路层头）
offset = 24
pkt_idx = 0
while offset + 16 <= len(raw):
    incl_len = struct.unpack(endian + 'I', raw[offset+8:offset+12])[0]
    offset += 16
    if incl_len > 65535 or offset + incl_len > len(raw):
        break
    pkt_data = raw[offset:offset+incl_len]
    offset += incl_len
    pkt_idx += 1
    packets_raw.append(pkt_data)

print(f"共 {len(packets_raw)} 个包\n")

# ─── 3. 解析每个包的 EAPOL 帧 ───
eapol_frames = []  # (eapol_bytes, src_mac, dst_mac, pkt_raw)

for idx, pkt_data in enumerate(packets_raw):
    print(f"=== 包 #{idx+1} ({len(pkt_data)} 字节) ===")
    print(f"  hex: {pkt_data.hex()}")

    if link_type == 1:  # Ethernet
        if len(pkt_data) < 14:
            print("  太短"); continue
        dst_mac = pkt_data[0:6]
        src_mac = pkt_data[6:12]
        ethertype = (pkt_data[12] << 8) | pkt_data[13]
        print(f"  Ethernet: {':'.join(f'{b:02x}' for b in src_mac)} -> {':'.join(f'{b:02x}' for b in dst_mac)}, ethertype=0x{ethertype:04x}")
        if ethertype != 0x888e:
            print("  非 EAPOL"); continue
        eapol_start = 14
    else:
        print(f"  非 Ethernet link type, 跳过"); continue

    # EAPOL header
    eapol_ver = pkt_data[eapol_start]
    eapol_type = pkt_data[eapol_start + 1]
    eapol_len = (pkt_data[eapol_start + 2] << 8) | pkt_data[eapol_start + 3]
    print(f"  EAPOL: ver={eapol_ver} type={eapol_type} len={eapol_len}")

    if eapol_type != 3:
        print("  非 EAPOL-Key"); continue

    # Key Descriptor
    ko = eapol_start + 4
    descriptor = pkt_data[ko]
    key_info = (pkt_data[ko+1] << 8) | pkt_data[ko+2]
    key_len_val = (pkt_data[ko+3] << 8) | pkt_data[ko+4]
    replay_counter = int.from_bytes(pkt_data[ko+5:ko+13], 'big')
    nonce = pkt_data[ko+13:ko+45]
    key_iv = pkt_data[ko+45:ko+61]
    key_rsc = pkt_data[ko+61:ko+69]
    key_id = pkt_data[ko+69:ko+77]
    mic_bytes = pkt_data[ko+77:ko+93]
    key_data_len = (pkt_data[ko+93] << 8) | pkt_data[ko+94]
    key_data = pkt_data[ko+95:ko+95+key_data_len]

    is_pairwise = bool(key_info & 0x0008)
    is_ack = bool(key_info & 0x0080)
    is_mic = bool(key_info & 0x0100)
    is_secure = bool(key_info & 0x0200)
    key_ver = key_info & 0x0007

    msg_type = 1 if (is_ack and not is_mic) else (2 if (not is_ack and is_mic) else (3 if (is_ack and is_mic) else 4))

    print(f"  Key: descriptor={descriptor} info=0x{key_info:04x} len={key_len_val} rc={replay_counter}")
    print(f"    pair={is_pairwise} ack={is_ack} mic={is_mic} secure={is_secure} ver={key_ver}")
    print(f"  Nonce: {nonce.hex()}")
    print(f"  MIC:   {mic_bytes.hex()}")
    print(f"  Msg type: Msg{msg_type}")
    print(f"  Key Data ({key_data_len}B): {key_data.hex() if key_data else '(empty)'}")

    # rawEapol (从 802.1X header 开始)
    raw_eapol = pkt_data[eapol_start:]
    print(f"  rawEapol 长度: {len(raw_eapol)}")
    print()

    eapol_frames.append({
        'raw': pkt_data,
        'eapol': raw_eapol,
        'src_mac': src_mac,
        'dst_mac': dst_mac,
        'ver': eapol_ver,
        'descriptor': descriptor,
        'key_info': key_info,
        'key_len': key_len_val,
        'rc': replay_counter,
        'nonce': nonce,
        'mic': mic_bytes,
        'key_ver': key_ver,
        'msg_type': msg_type,
    })

# ─── 4. 配对 Msg1 + Msg2 (rc=1) ───
print("=" * 60)
msg1 = next((f for f in eapol_frames if f['msg_type'] == 1 and f['rc'] == 1), None)
msg2 = next((f for f in eapol_frames if f['msg_type'] == 2 and f['rc'] == 1), None)

if not msg1 or not msg2:
    print("未找到 rc=1 的 Msg1+Msg2 配对!")
    exit(1)

ap_mac = msg1['src_mac']
sta_mac = msg2['src_mac']
anonce = msg1['nonce']
snonce = msg2['nonce']
expected_mic = msg2['mic']

print(f"AP MAC:  {':'.join(f'{b:02x}' for b in ap_mac)}")
print(f"STA MAC: {':'.join(f'{b:02x}' for b in sta_mac)}")
print(f"ANonce:  {anonce.hex()}")
print(f"SNonce:  {snonce.hex()}")
print(f"期望 MIC: {expected_mic.hex()}")
print(f"EAPOL ver: {msg2['ver']} (1=WPA, 2=WPA2)")
print(f"Key Info: 0x{msg2['key_info']:04x}, key_ver={msg2['key_ver']}")
print(f"Descriptor: {msg2['descriptor']} (254=WPA, 2=RSN/WPA2)")
print()

# ─── 5. 测试所有密钥派生变体 ───
ssid_bytes = SSID.encode('utf-8')
pass_bytes = PASSWORD.encode('utf-8')

# PMK 变体
pmk_sha1_4096 = hashlib.pbkdf2_hmac('sha1', pass_bytes, ssid_bytes, 4096, dklen=32)
pmk_sha1_2048 = hashlib.pbkdf2_hmac('sha1', pass_bytes, ssid_bytes, 2048, dklen=32)
pmk_md5_4096 = hashlib.pbkdf2_hmac('md5', pass_bytes, ssid_bytes, 4096, dklen=32)

print(f"PMK (SHA1-4096): {pmk_sha1_4096.hex()}")
print(f"PMK (SHA1-2048): {pmk_sha1_2048.hex()}")
print(f"PMK (MD5-4096):  {pmk_md5_4096.hex()}")
print()


def prf_384(pmk, mac1, mac2, nonce1, nonce2, sep_byte=True, hash_algo='sha1'):
    """标准 PRF-384"""
    label = b"Pairwise key expansion"
    if sep_byte:
        data = label + b'\x00' + mac1 + mac2 + nonce1 + nonce2
    else:
        data = label + mac1 + mac2 + nonce1 + nonce2
    result = b""
    for i in range(3):
        inp = data + bytes([i])
        result += hmac.new(pmk, inp, hash_algo).digest()
    return result[:48]


def prf_384_kt(pmk, mac1, mac2, nonce1, nonce2):
    """KT 实现: counter 替换 position 22 和末尾"""
    label = b"Pairwise key expansion"
    template = label + b'\x00' + mac1 + mac2 + nonce1 + nonce2 + b'\x00'
    result = b""
    for i in range(3):
        inp = bytearray(template)
        inp[22] = i
        inp[-1] = i
        result += hmac.new(pmk, bytes(inp), 'sha1').digest()
    return result[:48]


def compute_mic(kck, eapol_bytes, mic_offset, algo='sha1'):
    """计算 MIC"""
    zeroed = bytearray(eapol_bytes)
    zeroed[mic_offset:mic_offset+16] = b'\x00' * 16
    if algo == 'sha1':
        return hmac.new(kck, bytes(zeroed), hashlib.sha1).digest()[:16]
    elif algo == 'sha256':
        return hmac.new(kck, bytes(zeroed), hashlib.sha256).digest()[:16]
    elif algo == 'md5':
        return hmac.new(kck, bytes(zeroed), hashlib.md5).digest()[:16]
    return None


# MAC 和 Nonce 的所有排列
mac_perms = [
    ("AP,STA", ap_mac, sta_mac),
    ("STA,AP", sta_mac, ap_mac),
]
nonce_perms = [
    ("AN,SN", anonce, snonce),
    ("SN,AN", snonce, anonce),
]

# rawEapol 变体（Msg2 的完整 EAPOL 帧）
msg2_eapol = msg2['eapol']
print(f"Msg2 rawEapol 长度: {len(msg2_eapol)} 字节")
print(f"Msg2 rawEapol hex: {msg2_eapol.hex()}")
print()

# MIC 在 rawEapol 中的偏移: 802.1X header (4) + MIC 在 Key Descriptor 中的偏移 (77) = 81
# 但也测试 77（如果 rawEapol 不含 802.1X header 的情况）
mic_offsets_to_test = [77, 81]

test_count = 0
matches = []

for pmk_name, pmk in [("SHA1-4096", pmk_sha1_4096), ("SHA1-2048", pmk_sha1_2048), ("MD5-4096", pmk_md5_4096)]:
    for mac_label, mac1, mac2 in mac_perms:
        for nonce_label, nonce1, nonce2 in nonce_perms:
            # PRF 变体
            ptk_std = prf_384(pmk, mac1, mac2, nonce1, nonce2, sep_byte=True)
            ptk_nosep = prf_384(pmk, mac1, mac2, nonce1, nonce2, sep_byte=False)
            ptk_kt = prf_384_kt(pmk, mac1, mac2, nonce1, nonce2)
            ptk_md5 = prf_384(pmk, mac1, mac2, nonce1, nonce2, sep_byte=True, hash_algo='md5')

            prf_variants = [
                ("PRF-SHA1-std", ptk_std),
                ("PRF-SHA1-nosep", ptk_nosep),
                ("PRF-SHA1-kt", ptk_kt),
                ("PRF-MD5", ptk_md5),
            ]

            for prf_name, ptk in prf_variants:
                kck = ptk[:16]

                for mic_offset in mic_offsets_to_test:
                    for mic_algo in ['sha1', 'sha256', 'md5']:
                        computed = compute_mic(kck, msg2_eapol, mic_offset, mic_algo)
                        test_count += 1
                        if computed == expected_mic:
                            matches.append({
                                'pmk': pmk_name,
                                'mac': mac_label,
                                'nonce': nonce_label,
                                'prf': prf_name,
                                'mic_offset': mic_offset,
                                'mic_algo': mic_algo,
                                'ptk': ptk.hex(),
                                'kck': kck.hex(),
                                'mic': computed.hex(),
                            })

print(f"共测试 {test_count} 种变体")

if matches:
    print(f"\n{'='*60}")
    print(f"找到 {len(matches)} 种匹配!")
    print(f"{'='*60}")
    for i, m in enumerate(matches):
        print(f"\n--- 匹配 #{i+1} ---")
        for k, v in m.items():
            print(f"  {k}: {v}")
else:
    print("\n所有变体均未匹配!")

    # 详细调试: 检查 rawEapol 中的 MIC 字段位置
    print("\n--- Msg2 rawEapol 详细字节分析 ---")
    for offset in [77, 79, 81, 83, 85]:
        if offset + 16 <= len(msg2_eapol):
            mic_at = msg2_eapol[offset:offset+16]
            print(f"  rawEapol[{offset}:{offset+16}] = {mic_at.hex()}")
            if mic_at == expected_mic:
                print(f"    ^^^ 这就是期望的 MIC!")

    # 检查 msg2_eapol 中 MIC 的实际位置
    print(f"\n--- 在 rawEapol 中搜索期望 MIC ---")
    mic_hex = expected_mic.hex()
    eapol_hex = msg2_eapol.hex()
    pos = eapol_hex.find(mic_hex)
    if pos >= 0:
        byte_pos = pos // 2
        print(f"  找到! 在 byte offset {byte_pos} (hex offset {pos})")
    else:
        print(f"  未找到!")

    # 标准 WPA2 全流程 (使用正确的 MAC/Nonce 顺序)
    print(f"\n--- 标准 WPA2 (SHA1-4096, AP<STA, AN<SN, PRF-std, offset=81) ---")
    pmk = pmk_sha1_4096
    mac1, mac2 = sorted([ap_mac, sta_mac])
    nonce1, nonce2 = sorted([anonce, snonce])
    ptk = prf_384(pmk, mac1, mac2, nonce1, nonce2)
    kck = ptk[:16]
    print(f"  mac1={mac1.hex()} mac2={mac2.hex()}")
    print(f"  nonce1={nonce1.hex()}")
    print(f"  nonce2={nonce2.hex()}")
    print(f"  PMK: {pmk.hex()}")
    print(f"  PTK: {ptk.hex()}")
    print(f"  KCK: {kck.hex()}")
    for offset in mic_offsets_to_test:
        mic = compute_mic(kck, msg2_eapol, offset, 'sha1')
        print(f"  MIC @{offset}: {mic.hex()} {'<-- 期望!' if mic == expected_mic else ''}")

    # 使用不排序的 MAC/Nonce
    print(f"\n--- WPA2 (不排序, AP,STA, AN,SN) ---")
    ptk2 = prf_384(pmk, ap_mac, sta_mac, anonce, snonce)
    kck2 = ptk2[:16]
    print(f"  PTK: {ptk2.hex()}")
    print(f"  KCK: {kck2.hex()}")
    for offset in mic_offsets_to_test:
        mic = compute_mic(kck2, msg2_eapol, offset, 'sha1')
        print(f"  MIC @{offset}: {mic.hex()} {'<-- 期望!' if mic == expected_mic else ''}")

    # 使用 AP 作为 min MAC（不排序）
    print(f"\n--- WPA2 (AP<STA 不排序) ---")
    ptk3 = prf_384(pmk, ap_mac, sta_mac, anonce, snonce)
    kck3 = ptk3[:16]
    for offset in mic_offsets_to_test:
        mic = compute_mic(kck3, msg2_eapol, offset, 'sha1')
        print(f"  MIC @{offset}: {mic.hex()} {'<-- 期望!' if mic == expected_mic else ''}")

    # 使用 kt PRF
    print(f"\n--- WPA2 (KT PRF) ---")
    ptk_kt = prf_384_kt(pmk, mac1, mac2, nonce1, nonce2)
    kck_kt = ptk_kt[:16]
    print(f"  PTK: {ptk_kt.hex()}")
    print(f"  KCK: {kck_kt.hex()}")
    for offset in mic_offsets_to_test:
        mic = compute_mic(kck_kt, msg2_eapol, offset, 'sha1')
        print(f"  MIC @{offset}: {mic.hex()} {'<-- 期望!' if mic == expected_mic else ''}")

    # WPA1 (MD5)
    print(f"\n--- WPA1 (MD5-4096, AP<STA, AN<SN) ---")
    pmk_wpa1 = pmk_md5_4096
    ptk_wpa1 = prf_384(pmk_wpa1, mac1, mac2, nonce1, nonce2, hash_algo='md5')
    kck_wpa1 = ptk_wpa1[:16]
    print(f"  PMK: {pmk_wpa1.hex()}")
    print(f"  PTK: {ptk_wpa1.hex()}")
    print(f"  KCK: {kck_wpa1.hex()}")
    for offset in mic_offsets_to_test:
        mic_sha = compute_mic(kck_wpa1, msg2_eapol, offset, 'sha1')
        mic_md5 = compute_mic(kck_wpa1, msg2_eapol, offset, 'md5')
        print(f"  MIC @{offset} SHA1: {mic_sha.hex()} {'<-- 期望!' if mic_sha == expected_mic else ''}")
        print(f"  MIC @{offset} MD5:  {mic_md5.hex()} {'<-- 期望!' if mic_md5 == expected_mic else ''}")

    # 尝试: Msg2 EAPOL 不含 802.1X header (直接从 Key Descriptor 开始)
    print(f"\n--- 尝试 rawEapol 不含 802.1X header ---")
    msg2_no_header = msg2_eapol[4:]  # 去掉 4 字节 802.1X header
    for pmk_name, pmk_val in [("SHA1-4096", pmk_sha1_4096), ("SHA1-2048", pmk_sha1_2048)]:
        mac1b, mac2b = sorted([ap_mac, sta_mac])
        nonce1b, nonce2b = sorted([anonce, snonce])
        ptk_b = prf_384(pmk_val, mac1b, mac2b, nonce1b, nonce2b)
        kck_b = ptk_b[:16]
        for offset in [77]:  # MIC 在 Key Descriptor 中的偏移
            mic = compute_mic(kck_b, msg2_no_header, offset, 'sha1')
            print(f"  {pmk_name} @{offset} (no hdr): {mic.hex()} {'<-- 期望!' if mic == expected_mic else ''}")
