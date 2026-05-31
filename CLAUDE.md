# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# 艨艟战舰工具箱 Android 项目

## 项目概览

**应用名称**：艨艟战舰 / 工具箱  
**包名**：`com.whmdg.mczj.tools`  
**语言**：Kotlin  
**UI 框架**：Jetpack Compose + Material 3  
**Min SDK**：24 | **Target SDK**：36 | **Compile SDK**：36 (minorApiLevel=1)
**AGP**：9.2.1 | **Kotlin**：2.2.10 | **Compose BOM**：2026.02.01 | **NDK**：27.0.12077973

---

## 构建与运行

> **注意**：本地无编译环境，不要运行 `./gradlew` 构建命令。修改代码后通过阅读源文件检查语法错误即可。

```bash
# 调试包
./gradlew assembleDebug

# 发布包（需设置签名环境变量，否则自动回退 debug 签名）
KEYSTORE_PASSWORD=xxx KEY_ALIAS=xxx KEY_PASSWORD=xxx ./gradlew assembleRelease

# 单元测试
./gradlew test

# 仪器测试
./gradlew connectedAndroidTest

# 仅构建 arm64-v8a（默认已配置 splits，无需额外参数）
./gradlew :app:assembleRelease
```

---

## 项目结构

```
app/src/main/java/com/whmdg/mczj/tools/
├── AppDataPaths.kt                    # 应用数据路径解析
├── MainActivity.kt                    # 入口，启用 Edge-to-Edge
├── CrashActivity.kt                   # Native 崩溃显示界面（从 pipe 接收崩溃信息）
├── util/
│   ├── DiagnosticLog.kt               # 诊断日志工具
│   └── FormatUtils.kt                 # 格式化工具（文件大小等）
├── auth/                              # 认证/授权模块（密钥→功能特性门控）
│   ├── Feature.kt                     # 功能枚举（ENCRYPTION_VAULT, FILE_MANAGER, BATCH_DOWNLOADER 等）
│   ├── NativeAuth.kt                  # JNI 接口，调用 authcore 验证密码（返回派生密钥）
│   ├── KeyProfile.kt                  # 密钥 ID → Feature 集合映射（3 组预置密钥）
│   ├── PermissionManager.kt           # 全局认证状态管理（StateFlow<AuthState>）
│   ├── TokenCodec.kt                  # Token 编解码（JSON + HMAC-SHA256 签名）
│   ├── TokenStorage.kt                # Token 持久化（SharedPreferences + AES-GCM 加密）
│   ├── KeystoreMaster.kt              # Android Keystore AES-256-GCM 密钥包装（StrongBox 优先）
│   ├── SecurityEnforcer.kt            # 业务层权限检查失败时的安全自杀机制
│   ├── LocalPermissionGate.kt         # CompositionLocal<Boolean> UI 权限门控
│   ├── ReadOnlyGate.kt                # 只读模式包装组件（透明遮罩拦截触摸）
│   ├── PasswordDialog.kt              # 密码输入对话框
│   └── NoPermissionScreen.kt          # 无权限提示页面
├── encryption/
│   ├── core/                          # 密码学原语层
│   │   ├── AesGcm.kt                  # AES-256-GCM 加密/解密
│   │   ├── Argon2id.kt                # Argon2id KDF（BouncyCastle）
│   │   ├── Pbkdf2.kt                  # PBKDF2-SHA256 KDF
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
│   ├── models/
│   │   └── EncryptionNode.kt          # 加密文件树节点模型
│   └── services/                      # 业务逻辑层
│       ├── VaultService.kt            # 保险箱 CRUD（创建/打开/删除/导入/改密）
│       ├── VaultSession.kt            # 解锁后的在线会话（持有 DEK）
│       ├── CryptoService.kt           # 高级加密/解密文件入/出保险箱
│       └── EncryptionTaskManager.kt   # 加密任务队列管理（并发控制/进度回调）
├── security/
│   ├── TeeManager.kt                  # Android Keystore RSA TEE + 生物识别快速解锁
│   ├── SpecialPermissionVerifier.kt   # 检测 & 提权运行（无障碍/ADB/管理员/Root）
│   ├── ShizukuAuthorizer.kt           # Shizuku 授权（privileged shell 命令执行）
│   ├── AndroidPermissionLevel.kt      # 权限级别枚举（STANDARD → ROOT 六级）
│   ├── MyAccessibilityService.kt      # 无障碍服务声明
│   └── MyDeviceAdminReceiver.kt       # 设备管理器接收器
└── ui/
    ├── HomeScreen.kt                  # 导航容器 + 主页/加密/云盘/设置所有屏幕
    ├── VaultCreateScreen.kt           # 新建保险箱向导（含 Argon2id 基准测试）
    ├── VaultOpenScreen.kt             # 保险箱文件浏览器（加密导入/解密导出/重命名/移动/复制/删除）
    ├── VaultChangePasswordScreen.kt   # 修改保险箱密码
    ├── EncryptionSettings.kt          # 加密模块偏好（SharedPreferences 响应式）
    ├── FileManagerScreen.kt           # 文件管理器
    ├── AuthManagementScreen.kt        # 认证管理（切换密钥、清除授权）
    ├── SecurityScreen.kt              # 安全设置入口菜单
    ├── SpecialPermissionsScreen.kt    # 特殊权限管理 + 能力矩阵对比
    ├── PermissionSettingsScreen.kt    # Android 运行时权限管理
    ├── AppPermissionsScreen.kt        # 应用权限详情
    ├── PermissionManagementConfigScreen.kt  # 权限管理配置
    ├── PermissionGuideViewModel.kt    # 权限引导 ViewModel
    ├── ErrorDialog.kt                 # 错误对话框组件
    ├── components/
    │   └── GlowCard.kt               # 青色光晕边框卡片组件
    ├── encryption/
    │   ├── EncryptionProgressIcon.kt  # 加密进度动画图标
    │   └── EncryptionProgressPanel.kt # 加密进度面板（任务列表/统计）
    ├── download/                      # 下载器模块
    │   ├── BatchDownloaderScreen.kt   # 批量下载器界面
    │   ├── FADownloaderScreen.kt      # FA 下载器界面
    │   ├── FADownloaderViewModel.kt   # FA 下载器 ViewModel
    │   ├── FALoginScreen.kt           # FA 登录界面
    │   └── Deviant/                   # DeviantArt 下载器
    │       ├── DeviantDownloaderScreen.kt
    │       ├── DeviantDownloaderViewModel.kt
    │       ├── DeviantLoginScreen.kt
    │       └── DeviantModels.kt
    └── theme/                         # Color / Theme / Type
```

**构建工具** (`tools/`)：
```
gen_password_hashes.py                 # 预生成 Argon2id 密码哈希 → hashes.inc + obf_key.h
wait_and_download.sh                   # CI 产物下载辅助脚本
```

**Native 代码** (`app/src/main/cpp/`)：
```
CMakeLists.txt                         # 构建 authcore 共享库
auth_jni.cpp                           # JNI 入口（verifyPassword / keyIdOf）
obf.c / obf.h / obf_key.h             # 密码混淆层
crash_handler.c / crash_handler.h      # Native 信号崩溃处理（pipe → CrashActivity）
crash_monitor_jni.c                    # 崩溃监控 JNI 接口
hashes.inc                             # 预计算哈希表
third_party/argon2/                    # 内嵌 Argon2 实现（供 JNI 直接调用）
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

### 认证/授权系统（auth 模块）

应用采用**密钥→功能特性**的门控模型，而非传统的用户角色：

```
用户输入密码
    │ NativeAuth.verifyPassword() [JNI → authcore.so → 内嵌 Argon2]
    ▼
派生密钥 (32 字节) + keyId 索引
    │ KeyProfile.featuresFor(keyId)
    ▼
Feature 集合（ENCRYPTION_VAULT / FILE_MANAGER / BATCH_DOWNLOADER 等）
    │ TokenCodec.encode() + KeystoreMaster.wrap()
    ▼
Token 持久化（SharedPreferences 中存储 AES-GCM 加密的 Token）
    │ PermissionManager.state: StateFlow<AuthState>
    ▼
UI 门控：LocalPermissionGate (CompositionLocal<Boolean>)
    ├── true  → 正常访问
    └── false → ReadOnlyWrapper（透明遮罩 + 只读提示）
```

**3 组预置密钥**（KeyProfile.kt）：
| 密钥 | 权限范围 |
|------|---------|
| k1 | 全部功能 |
| k2 | 保险箱 + 批量下载 + 安全设置 |
| k3 | 批量下载 + 安全设置 |

**安全分层**：
1. **UI 门控**：`LocalPermissionGate` + `ReadOnlyWrapper` 阻止未授权操作
2. **业务层检查**：`SecurityEnforcer.checkOrDie()` — 若 UI 门控被绕过，清除授权状态并自杀进程
3. **Native 验证**：密码验证在 JNI 层完成，派生密钥不暴露给 Java 层

### TEE 快速解锁

1. 生成 RSA 密钥对存入 Android Keystore（私钥需生物认证，绑定指纹列表变更）
2. 首次解锁成功后，用**公钥**静默加密密码，Hex 存入 `SharedPreferences("tee_passwords")`
3. 再次打开时用**私钥**（需指纹验证）解密 → 自动开箱

---

## CI/CD

GitHub Actions workflow `.github/workflows/build.yml`:
- **触发**：仅手动 `workflow_dispatch`
- **环境**：JDK 21，Gradle cache disabled（确保干净构建）
- **预构建**：`python3 tools/gen_password_hashes.py` 生成 Native 认证哈希（`hashes.inc` + `obf_key.h`，均 gitignored）
- **签名**：release 签名通过 secrets 注入（`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`），缺失时自动回退到 debug 签名
- **ABI**：仅构建 `arm64-v8a`
- **版本号**：`versionCode` 基于时间戳动态生成，`versionName` 格式 `2.1.<timestamp>`
- **产物**：重命名为 `工具箱-v<版本>-<日期时间>-arm64-v8a-release.apk`，保留 30 天

---

## 关键依赖

| 库 | 版本 | 用途 |
|----|------|------|
| `core-ktx` | 1.18.0 | Android KTX 扩展 |
| BouncyCastle `bcprov-jdk18on` | 1.80 | Argon2id KDF |
| `kotlinx-serialization-json` | 1.8.0 | JSON 序列化 |
| Compose BOM | 2026.02.01 | UI 全套（含 Material 3 + Icons Extended） |
| `activity-compose` | 1.13.0 | ComponentActivity 集成 |
| `lifecycle-runtime-ktx` | 2.10.0 | 生命周期 |
| Shizuku `api` + `provider` | — | 特权 shell 命令执行 |

---

## 重要约定

### 认证安全
- `NativeAuth` 的 JNI 层使用内嵌 Argon2 验证密码，派生密钥仅在内存中短暂存在
- `TokenCodec` 使用 HMAC-SHA256 签名 Token，解码时使用 `MessageDigest.isEqual()` 恒定时间比较防时序攻击
- `KeystoreMaster` 优先使用 StrongBox 硬件安全模块，不可用时回退到普通 Android Keystore
- `SecurityEnforcer.checkOrDie()` 是最后防线：业务层权限检查失败时自杀进程

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

### Native 崩溃处理

Native 层（`crash_handler.c`）注册信号处理器捕获 SIGSEGV/SIGABRT 等，通过 pipe 将崩溃信息传递给 `CrashActivity`，用户可复制崩溃详情或退出应用。退出时通过 pipe2 通知 Native 层执行 `_exit()`。

### Shizuku 集成

`ShizukuAuthorizer` 提供通过 Shizuku 服务执行特权 shell 命令的能力（反射调用 `IShizukuService.newProcess`）。支持 Sui 后端回退。

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
