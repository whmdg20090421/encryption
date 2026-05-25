# CLAUDE.md — 艨艟战舰工具箱 Android 项目

## 项目概览

**应用名称**：艨艟战舰 / 工具箱  
**包名**：`com.whmdg.mczj.tools`  
**语言**：Kotlin  
**UI 框架**：Jetpack Compose + Material 3  
**Min SDK**：24 | **Target SDK**：36  
**AGP**：9.2.1 | **Kotlin**：2.2.10 | **Compose BOM**：2026.02.01

---

## 构建与运行

```bash
# 调试包
./gradlew assembleDebug

# 发布包
./gradlew assembleRelease

# 单元测试
./gradlew test

# 仪器测试
./gradlew connectedAndroidTest
```

---

## 项目结构

```
app/src/main/java/com/whmdg/mczj/tools/
├── MainActivity.kt                    # 入口，启用 Edge-to-Edge
├── encryption/
│   ├── core/                          # 密码学原语层
│   │   ├── AesGcm256.kt               # AES-256-GCM 加密/解密
│   │   ├── Argon2idKdf.kt             # Argon2id KDF（BouncyCastle）
│   │   ├── Pbkdf2Kdf.kt               # PBKDF2-SHA256 KDF
│   │   ├── KeyDerivation.kt           # KDF 统一调度（Argon2id / PBKDF2）
│   │   ├── FileCodec.kt               # 文件分块加密/解密（与 Python 工具二进制兼容）
│   │   ├── FilenameCodec.kt           # 文件名 AES-GCM 加密（含 zlib 压缩回退 + SHA-256 映射）
│   │   ├── NailObfuscation.kt         # 钉子混淆（自定义模式的抗篡改插入/校验）
│   │   ├── HexCodec.kt                # Hex 编解码
│   │   ├── SecureRandom.kt            # 安全随机字节
│   │   └── FileConstants.kt           # 文件格式常量（魔数头、法律尾、分块大小）
│   ├── data/                          # 数据模型与持久化
│   │   ├── VaultConfig.kt             # 保险箱配置（KDF 参数、加密 DEK、HMAC 完整性、3 副本备份）
│   │   ├── VaultRecord.kt             # 保险箱元信息记录
│   │   ├── VaultDb.kt                 # 全局 vault_db.json（内部 + 外部双备份）
│   │   ├── VaultPaths.kt              # 路径解析（内部/外部存储）
│   │   ├── NameMapping.kt             # 文件名哈希→Hex 映射持久化
│   │   ├── StorageLocation.kt         # INTERNAL / EXTERNAL 枚举
│   │   └── CanonicalJson.kt           # Python json.dumps(sort_keys=True) 的 Kotlin 等价
│   └── services/                      # 业务逻辑层
│       ├── VaultService.kt            # 保险箱 CRUD（创建/打开/删除/导入/改密）
│       ├── VaultSession.kt            # 解锁后的在线会话（持有 DEK）
│       └── CryptoService.kt           # 高级加密/解密文件入/出保险箱
├── security/
│   ├── TeeManager.kt                  # Android Keystore RSA TEE + 生物识别快速解锁
│   ├── SpecialPermissionVerifier.kt   # 检测 & 提权运行（无障碍/ADB/管理员/Root）
│   ├── MyAccessibilityService.kt      # 无障碍服务声明
│   └── MyDeviceAdminReceiver.kt       # 设备管理器接收器
└── ui/
    ├── HomeScreen.kt                  # 导航容器 + 主页/加密/云盘/设置所有屏幕
    ├── VaultCreateScreen.kt           # 新建保险箱向导（含 Argon2id 基准测试）
    ├── VaultOpenScreen.kt             # 保险箱文件浏览器（加密导入/解密导出/重命名/移动/复制/删除）
    ├── VaultChangePasswordScreen.kt   # 修改保险箱密码
    ├── EncryptionSettings.kt          # 加密模块偏好（SharedPreferences 响应式）
    ├── SecurityScreen.kt              # 安全设置入口菜单
    ├── SpecialPermissionsScreen.kt    # 特殊权限管理 + 能力矩阵对比
    ├── PermissionSettingsScreen.kt    # Android 运行时权限管理
    └── theme/                         # Color / Theme / Type
```

---

## 核心架构设计

### 导航（Screen sealed class）

```
主页 (Dashboard)
  └── 加密 (EncryptionHome)
        ├── 保险箱列表 (VaultsListTab)
        │     ├── 新建保险箱 (VaultCreate)
        │     ├── 打开保险箱 (VaultOpen)  ← 含 TEE 生物识别快速解锁
        │     └── 修改密码 (VaultChangePassword)
        ├── 云盘 (CloudTab)             ← 占位，待实现
        └── 设置 (EncryptionSettingsTab)

设置 (Settings)
  └── 安全 (Security)
        ├── 权限设置 (PermissionSettings)
        └── 特殊权限 (SpecialPermissions)
```

### 加密模型（KEK-DEK 两层结构）

```
用户密码
    │ Argon2id / PBKDF2-SHA256
    ▼
   KEK (密钥加密密钥, 32 字节)
    │ AES-256-GCM 加密
    ▼
   DEK (数据加密密钥, 32 字节, 存于 vault_config.json)
    │ AES-256-GCM 分块加密
    ▼
   加密文件（.aes）
```

### 文件格式（与 Python 工具二进制兼容）

```
[魔数头 "艨艟战舰" 12字节] [footer标志 1字节]  ← 仅 custom_encryption=true 时
[4字节 meta块长度] [12字节 IV] [加密metadata]
[4字节 chunk长度] [12字节 IV] [加密数据块] × N  ← 每块 4MiB
[法律尾声字节串]                                ← 仅文件 > 4MiB 且 custom_encryption=true
```

### 保险箱配置完整性（3 副本多数投票）

| 副本位置 | 路径 |
|---------|------|
| 主配置 | `<vaultDir>/vault_config.json` |
| 箱内备份 | `<vaultDir>/vault_config.backup.json` |
| 私有备份 | `filesDir/.vault_private_backup/vault_config_<pathHash16>.json` |

打开时：HMAC-SHA256 校验 → 多数投票选最可信副本 → 不一致时弹出篡改警告。

### TEE 快速解锁

1. 生成 RSA 密钥对存入 Android Keystore（私钥需生物认证，绑定指纹列表变更）
2. 首次解锁成功后，用**公钥**静默加密密码，Hex 存入 `SharedPreferences("tee_passwords")`
3. 再次打开时用**私钥**（需指纹验证）解密 → 自动开箱

---

## 关键依赖

| 库 | 版本 | 用途 |
|----|------|------|
| BouncyCastle `bcprov-jdk18on` | 1.80 | Argon2id KDF |
| `kotlinx-serialization-json` | 1.8.0 | JSON 序列化 |
| Compose BOM | 2026.02.01 | UI 全套 |
| `activity-compose` | 1.13.0 | ComponentActivity 集成 |
| `lifecycle-runtime-ktx` | 2.10.0 | 生命周期 |

---

## 重要约定

### 密钥安全
- KEK 和 DEK 在使用后必须 `fill(0)` 清零，参见 `VaultService.open()` / `changePassword()`
- `VaultSession.dispose()` 退出保险箱时清零内存中的 DEK
- TeeManager 中 DEK **不存原文**，只存 RSA 公钥加密后的密文

### 文件命名
- 普通模式：`原文件名.aes`
- 加密文件名模式：`<iv+ciphertext hex>.aes`（长则 zlib 压缩，超长则 SHA-256 哈希 + 映射表）

### Argon2id 默认参数
| 档位 | timeCost | memoryCostKb | parallelism |
|------|----------|--------------|-------------|
| LOW | 1 | 32768 (32MB) | 1 |
| MEDIUM（推荐） | 2 | 65536 (64MB) | 2 |
| HIGH | 3 | 131072 (128MB) | 4 |

### CanonicalJson
所有 HMAC 计算使用 `CanonicalJson.encode()`（键排序、无空格、UTF-8），与 Python `json.dumps(sort_keys=True)` 兼容。

### 特殊权限提权
`SpecialPermissionVerifier.runWithPrivilegeElevation()` 支持"非必要时不使用权限"模式：
- `use_only_when_necessary=true`：先以普通权限执行，仅在 SecurityException 时自动提权重试
- `use_only_when_necessary=false`：直接以选定特权级别执行

---

## 待实现功能

- **云盘 (CloudTab)**：加密文件云端存储 / 同步（占位 UI 已存在）
- `ChaCha20-Poly1305` 算法：UI 已支持选择，但 `FileCodec` 目前仅实现 AES-GCM
- `AES-128-GCM`：同上，UI 可选但底层固定 32 字节 DEK

---

## 已知注意点

- `VaultOpenScreen` 的文件过滤目前排除了 `name_mapping.json.bak`，但实际写入的是 `name_mappings.json`（复数），无实际影响但需注意命名一致性
- `importVaultWithPassword()` 硬编码 `StorageLocation.EXTERNAL`，从 SAF URI 导入时路径解析依赖 `content://` → 绝对路径的转换，部分机型可能不准确
- `LaunchedEffect(Unit)` 中的 `Looper.loop()` 全局异常捕获仅在加密流程中生效，设计较激进，需注意主线程异常逃逸风险
