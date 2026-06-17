#!/usr/bin/env python3
"""
使用 pcap 中实际的 rawEapol 字节数据验证 WPA2 MIC
重点: 用正确的 Msg2 rawEapol (121 字节) + 尝试所有 PRF/HMAC 变体
"""
import base64
import struct
import hashlib
import hmac

B64 = "1MOyoQIABAAAAAAAAAAAAAAABAABAAAA8xkzauKKAwBxAAAAcQAAAG5e+/lucZQ394ITpoiOAgMAXwIAigAQAAAAAAAAAAEoqJ8cJuyOoTxpfdeZmsdzxfXrkJ51xfgHiWAPqcUywAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA8xkzal+RAwCHAAAAhwAAAJQ394ITpm5e+/lucYiOAQMAdQIBCgAAAAAAAAAAAAE7kHmwEU/+jNOH+lvhswSe+j1217KTTCd9nz8Wel6AcwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQJTiuJVT8WzvmEGdYgFMZQAWMBQBAAAPrAIBAAAPrAQBAAAPrAIMAPUZM2q5gQMAcQAAAHEAAABuXvv5bnGUN/eCE6aIjgIDAF8CAIoAEAAAAAAAAAACKKifHCbsjqE8aX3XmZrHc8X165CedcX4B4lgD6nFMsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPUZM2qohAMAhwAAAIcAAACUN/eCE6ZuXvv5bnGIjgEDAHUCAQoAAAAAAAAAAAACO5B5sBFP/ozTh/pb4bMEnvo9dteyk0wnfZ8/FnpegHMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAECP5mWwUYv+cnypLrsrBFgAFjAUAQAAD6wCAQAAD6wEAQAAD6wCDAA="

PASSWORD = "19850324"
SSID = "wang1203_5G"

raw = base64.b64decode(B64)
endian = '<'  # confirmed little-endian from pcap magic d4c3b2a1

# 提取所有 EAPOL 帧
packets = []
offset = 24
while offset + 16 <= len(raw):
    incl_len = struct.unpack(endian + 'I', raw[offset+8:offset+12])[0]
    offset += 16
    if incl_len > 65535 or offset + incl_len > len(raw):
        break
    pkt_data = raw[offset:offset+incl_len]
    offset += incl_len

    if len(pkt_data) >= 14:
        ethertype = (pkt_data[12] << 8) | pkt_data[13]
        if ethertype == 0x888e:
            eapol_raw = pkt_data[14:]
            packets.append({
                'pkt': pkt_data,
                'eapol': eapol_raw,
                'src_mac': pkt_data[6:12],
                'dst_mac': pkt_data[0:6],
            })

print(f"捕获 {len(packets)} 个 EAPOL 帧\n")

# 解析每帧
for i, p in enumerate(packets):
    e = p['eapol']
    key_info = (e[5] << 8) | e[6]
    rc = int.from_bytes(e[9:17], 'big')
    is_ack = bool(key_info & 0x0080)
    is_mic = bool(key_info & 0x0100)
    msg_type = 1 if (is_ack and not is_mic) else (2 if (not is_ack and is_mic) else (3 if (is_ack and is_mic) else 4))
    mic = e[81:97]
    key_data_len = (e[93] << 8) | e[94]
    print(f"Frame {i+1}: Msg{msg_type} rc={rc} key_info=0x{key_info:04x} ver={e[0]} mic={mic.hex()} key_data_len={key_data_len}")

# 取 rc=1 的 Msg1 和 Msg2
msg1_eapol = next(p['eapol'] for p in packets if (packets.index(p) % 2 == 0))  # Msg1 is first
msg2_eapol = next(p['eapol'] for p in packets if (packets.index(p) % 2 == 1))  # Msg2 is second

# 更准确地通过 key_info 判断
for p in packets:
    e = p['eapol']
    key_info = (e[5] << 8) | e[6]
    rc = int.from_bytes(e[9:17], 'big')
    is_ack = bool(key_info & 0x0080)
    is_mic = bool(key_info & 0x0100)
    if rc == 1:
        if is_ack and not is_mic:
            msg1_eapol = e
            msg1_pkt = p['pkt']
        elif not is_ack and is_mic:
            msg2_eapol = e
            msg2_pkt = p['pkt']

ap_mac = msg1_pkt[6:12]
sta_mac = msg2_pkt[6:12]
anonce = msg1_eapol[17:49]
snonce = msg2_eapol[17:49]
expected_mic = msg2_eapol[81:97]

print(f"\nAP MAC:  {ap_mac.hex()}")
print(f"STA MAC: {sta_mac.hex()}")
print(f"ANonce:  {anonce.hex()}")
print(f"SNonce:  {snonce.hex()}")
print(f"期望 MIC: {expected_mic.hex()}")
print(f"Msg2 rawEapol 长度: {len(msg2_eapol)}")
print(f"Msg2 rawEapol hex: {msg2_eapol.hex()}")
print()

# ─── 构造 MIC 置零的 EAPOL 帧 ───
# 标准做法: MIC 在 offset 81 (从 802.1X header 开始)
msg2_zeroed = bytearray(msg2_eapol)
msg2_zeroed[81:97] = b'\x00' * 16
print(f"Msg2 (MIC 置零) hex: {bytes(msg2_zeroed).hex()}")
print(f"  长度: {len(msg2_zeroed)}")
print()

# ─── 测试各种密钥派生 ───
ssid_bytes = SSID.encode()
pass_bytes = PASSWORD.encode()

pmk = hashlib.pbkdf2_hmac('sha1', pass_bytes, ssid_bytes, 4096, dklen=32)
print(f"PMK: {pmk.hex()}")


def test_variant(name, ptk, mic_frame, mic_offset, mic_algo):
    kck = ptk[:16]
    computed = hmac.new(kck, bytes(mic_frame), mic_algo).digest()[:16]
    match = computed == expected_mic
    if match:
        print(f"  *** MATCH *** {name}: {computed.hex()}")
    return match


found = False

# ─── 变体 1-4: 不同 MAC/Nonce 顺序 + 标准 PRF ───
mac_nonce_combos = [
    ("AP<STA, AN<SN", *sorted([ap_mac, sta_mac]), *sorted([anonce, snonce])),
    ("AP<STA, SN<AN", *sorted([ap_mac, sta_mac]), *sorted([snonce, anonce])),
    ("STA<AP, AN<SN", *sorted([sta_mac, ap_mac]), *sorted([anonce, snonce])),
    ("STA<AP, SN<AN", *sorted([sta_mac, ap_mac]), *sorted([snonce, anonce])),
    ("AP,STA(no sort), AN,SN(no sort)", ap_mac, sta_mac, anonce, snonce),
    ("STA,AP(no sort), SN,AN(no sort)", sta_mac, ap_mac, snonce, anonce),
    ("AP,STA, SN,AN", ap_mac, sta_mac, snonce, anonce),
    ("STA,AP, AN,SN", sta_mac, ap_mac, anonce, snonce),
]

print("=== 测试标准 PRF-384 (SHA1, sep_byte=True) ===")
for label, mac1, mac2, nonce1, nonce2 in mac_nonce_combos:
    # 标准 PRF: label + 0x00 + data + counter
    data = b"Pairwise key expansion" + b'\x00' + mac1 + mac2 + nonce1 + nonce2
    ptk = b""
    for i in range(3):
        ptk += hmac.new(pmk, data + bytes([i]), hashlib.sha1).digest()
    ptk = ptk[:48]

    # 尝试不同 MIC 偏移和算法
    for offset in [77, 81]:
        for algo_name, algo in [("SHA1", hashlib.sha1), ("SHA256", hashlib.sha256), ("MD5", hashlib.md5)]:
            kck = ptk[:16]
            computed = hmac.new(kck, bytes(msg2_zeroed), algo).digest()[:16]
            if computed == expected_mic:
                print(f"  *** MATCH *** {label}, PRF-SHA1-std, @{offset}, {algo_name}")
                found = True

    # 也尝试直接用 msg2_eapol（不置零 MIC）
    for algo_name, algo in [("SHA1", hashlib.sha1), ("SHA256", hashlib.sha256), ("MD5", hashlib.md5)]:
        kck = ptk[:16]
        computed = hmac.new(kck, msg2_eapol, algo).digest()[:16]
        if computed == expected_mic:
            print(f"  *** MATCH *** {label}, PRF-SHA1-std (no zero), {algo_name}")
            found = True

print("\n=== 测试 PRF-384 (无 sep_byte) ===")
for label, mac1, mac2, nonce1, nonce2 in mac_nonce_combos:
    data = b"Pairwise key expansion" + mac1 + mac2 + nonce1 + nonce2
    ptk = b""
    for i in range(3):
        ptk += hmac.new(pmk, data + bytes([i]), hashlib.sha1).digest()
    ptk = ptk[:48]
    kck = ptk[:16]

    for offset in [77, 81]:
        computed = hmac.new(kck, bytes(msg2_zeroed), hashlib.sha1).digest()[:16]
        if computed == expected_mic:
            print(f"  *** MATCH *** {label}, PRF-SHA1-nosep, @{offset}")
            found = True

print("\n=== 测试 PRF-384 (MD5) ===")
for label, mac1, mac2, nonce1, nonce2 in mac_nonce_combos:
    data = b"Pairwise key expansion" + b'\x00' + mac1 + mac2 + nonce1 + nonce2
    ptk = b""
    for i in range(3):
        ptk += hmac.new(pmk, data + bytes([i]), hashlib.md5).digest()
    ptk = ptk[:48]
    kck = ptk[:16]

    for algo_name, algo in [("SHA1", hashlib.sha1), ("MD5", hashlib.md5)]:
        computed = hmac.new(kck, bytes(msg2_zeroed), algo).digest()[:16]
        if computed == expected_mic:
            print(f"  *** MATCH *** {label}, PRF-MD5, {algo_name}")
            found = True

print("\n=== 测试 KT PRF (counter 替换 template 中的 0x00) ===")
for label, mac1, mac2, nonce1, nonce2 in mac_nonce_combos:
    template = b"Pairwise key expansion" + b'\x00' + mac1 + mac2 + nonce1 + nonce2 + b'\x00'
    ptk = b""
    for i in range(3):
        inp = bytearray(template)
        inp[22] = i
        inp[-1] = i
        ptk += hmac.new(pmk, bytes(inp), hashlib.sha1).digest()
    ptk = ptk[:48]
    kck = ptk[:16]

    for algo_name, algo in [("SHA1", hashlib.sha1), ("SHA256", hashlib.sha256)]:
        computed = hmac.new(kck, bytes(msg2_zeroed), algo).digest()[:16]
        if computed == expected_mic:
            print(f"  *** MATCH *** {label}, PRF-KT, {algo_name}")
            found = True

# ─── 测试: 不同 PMK 来源 ───
print("\n=== 测试不同 PMK ===")
pmk_variants = [
    ("SHA1-4096", hashlib.pbkdf2_hmac('sha1', pass_bytes, ssid_bytes, 4096, dklen=32)),
    ("SHA1-2048", hashlib.pbkdf2_hmac('sha1', pass_bytes, ssid_bytes, 2048, dklen=32)),
    ("MD5-4096", hashlib.pbkdf2_hmac('md5', pass_bytes, ssid_bytes, 4096, dklen=32)),
    ("SHA256-4096", hashlib.pbkdf2_hmac('sha256', pass_bytes, ssid_bytes, 4096, dklen=32)),
]

for pmk_name, pmk_val in pmk_variants:
    mac1, mac2 = sorted([ap_mac, sta_mac])
    nonce1, nonce2 = sorted([anonce, snonce])
    data = b"Pairwise key expansion" + b'\x00' + mac1 + mac2 + nonce1 + nonce2
    ptk = b""
    for i in range(3):
        ptk += hmac.new(pmk_val, data + bytes([i]), hashlib.sha1).digest()
    ptk = ptk[:48]
    kck = ptk[:16]

    computed = hmac.new(kck, bytes(msg2_zeroed), hashlib.sha1).digest()[:16]
    print(f"  {pmk_name}: KCK={kck.hex()} MIC={computed.hex()} {'<-- MATCH!' if computed == expected_mic else ''}")

# ─── 测试: WPA1 (descriptor=254) ───
print("\n=== 测试 WPA1 PRF (HMAC-MD5) ===")
for pmk_name, pmk_val in pmk_variants:
    mac1, mac2 = sorted([ap_mac, sta_mac])
    nonce1, nonce2 = sorted([anonce, snonce])
    data = b"Pairwise key expansion" + b'\x00' + mac1 + mac2 + nonce1 + nonce2
    ptk = b""
    for i in range(3):
        ptk += hmac.new(pmk_val, data + bytes([i]), hashlib.md5).digest()
    ptk = ptk[:48]
    kck = ptk[:16]

    for algo_name, algo in [("SHA1", hashlib.sha1), ("MD5", hashlib.md5)]:
        computed = hmac.new(kck, bytes(msg2_zeroed), algo).digest()[:16]
        if computed == expected_mic:
            print(f"  *** MATCH *** {pmk_name}, PRF-MD5, MIC-{algo_name}")
            found = True

# ─── 测试: 不同 MIC 偏移 ───
print("\n=== 搜索正确 MIC 偏移 ===")
mac1, mac2 = sorted([ap_mac, sta_mac])
nonce1, nonce2 = sorted([anonce, snonce])
data = b"Pairwise key expansion" + b'\x00' + mac1 + mac2 + nonce1 + nonce2
ptk = b""
for i in range(3):
    ptk += hmac.new(pmk, data + bytes([i]), hashlib.sha1).digest()
ptk = ptk[:48]
kck = ptk[:16]

for offset in range(0, len(msg2_eapol) - 16):
    zeroed = bytearray(msg2_eapol)
    zeroed[offset:offset+16] = b'\x00' * 16
    computed = hmac.new(kck, bytes(zeroed), hashlib.sha1).digest()[:16]
    if computed == expected_mic:
        print(f"  *** MATCH *** MIC offset = {offset}")
        found = True

# ─── 测试: 用 PTK 后 16 字节做 KCK ───
print("\n=== 测试不同 KCK 位置 ===")
kck_alt1 = ptk[16:32]  # 第二个 16 字节
kck_alt2 = ptk[32:48]  # 第三个 16 字节
for name, kck_test in [("KCK[0:16]", kck), ("KCK[16:32]", kck_alt1), ("KCK[32:48]", kck_alt2)]:
    computed = hmac.new(kck_test, bytes(msg2_zeroed), hashlib.sha1).digest()[:16]
    print(f"  {name}: {computed.hex()} {'<-- MATCH!' if computed == expected_mic else ''}")

# ─── 测试: 不同 EAPOL 帧源 ───
# 用完整的以太网帧（含 Ethernet header）
print("\n=== 测试用完整以太网帧 ===")
full_pkt = msg2_pkt  # 完整的以太网帧
full_zeroed = bytearray(full_pkt)
# MIC 在以太网帧中的偏移: 14(eth) + 81 = 95
full_zeroed[95:111] = b'\x00' * 16
computed = hmac.new(kck, bytes(full_zeroed), hashlib.sha1).digest()[:16]
print(f"  完整帧 @{95}: {computed.hex()} {'<-- MATCH!' if computed == expected_mic else ''}")

# ─── 测试: 仅 EAPOL-Key body (去掉 802.1X header) ───
print("\n=== 测试仅 EAPOL-Key body ===")
key_body = msg2_eapol[4:]  # 去掉 4 字节 802.1X header
key_body_zeroed = bytearray(key_body)
key_body_zeroed[77:93] = b'\x00' * 16  # MIC 在 Key body 中的偏移是 77
computed = hmac.new(kck, bytes(key_body_zeroed), hashlib.sha1).digest()[:16]
print(f"  Key body @{77}: {computed.hex()} {'<-- MATCH!' if computed == expected_mic else ''}")

# ─── 测试: 不用置零 MIC，直接用原始帧 ───
print("\n=== 测试不置零 MIC ===")
computed = hmac.new(kck, msg2_eapol, hashlib.sha1).digest()[:16]
print(f"  原始帧: {computed.hex()} {'<-- MATCH!' if computed == expected_mic else ''}")

if not found:
    print("\n" + "=" * 60)
    print("所有变体均未匹配!")
    print("=" * 60)

    # 额外调试: 逐字节检查 msg2_eapol
    print(f"\n--- Msg2 EAPOL 逐字段分析 ---")
    print(f"802.1X ver: {msg2_eapol[0]} (1=WPA, 2=WPA2)")
    print(f"802.1X type: {msg2_eapol[1]} (3=Key)")
    print(f"802.1X len: {(msg2_eapol[2] << 8) | msg2_eapol[3]}")
    print(f"Key Descriptor: {msg2_eapol[4]} (254=WPA, 2=RSN)")
    print(f"Key Info: 0x{(msg2_eapol[5] << 8) | msg2_eapol[6]:04x}")
    print(f"Key Length: {(msg2_eapol[7] << 8) | msg2_eapol[8]}")
    print(f"Replay Counter: {int.from_bytes(msg2_eapol[9:17], 'big')}")
    print(f"Nonce: {msg2_eapol[17:49].hex()}")
    print(f"Key IV: {msg2_eapol[49:65].hex()}")
    print(f"Key RSC: {msg2_eapol[65:73].hex()}")
    print(f"Key ID: {msg2_eapol[73:81].hex()}")
    print(f"MIC: {msg2_eapol[81:97].hex()}")
    print(f"Key Data Len: {(msg2_eapol[93] << 8) | msg2_eapol[94]}")
    print(f"Key Data: {msg2_eapol[95:].hex()}")

    # 计算 EAPOL length 验证
    eapol_len = (msg2_eapol[2] << 8) | msg2_eapol[3]
    actual_body_len = len(msg2_eapol) - 4  # 去掉 802.1X header
    print(f"\n802.1X length field: {eapol_len}")
    print(f"实际 body 长度: {actual_body_len}")
    print(f"匹配: {eapol_len == actual_body_len}")
