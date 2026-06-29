# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 工具使用规范

- **严禁使用 Explore 工具**，所有需要探索/查找代码的任务，必须直接使用 Agent(任务描述) 完成
- 不要将 Explore 作为 Agent 的前置步骤单独调用

## 代码查找规范

- 寻找某个模块或功能时，**优先查看本文件 CLAUDE.md 中记录的项目结构树状图**及其对文件的解释
- 如果树状图中没有找到，再进行广泛搜索（grep/find）

## 数据存储规范

- 所有本地持久化数据必须通过 `AppDataPaths.kt` 管理
- SharedPreferences 名称使用 `AppDataPaths.PREFS_*` 常量，禁止硬编码
- 文件存储使用 `AppDataPaths.*()` 方法获取目录路径
- 新增模块时先在 `AppDataPaths` 中定义常量/方法再使用

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

> **注意**：本地无编译环境，不要运行 `./gradlew` 构建命令。修改代码后通过阅读源文件检查语法错误即可。在没有用户确定的命令前，不要自行上传代码。

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
├── ToolsApp.kt                        # Application 类（初始化）
├── MainActivity.kt                    # 入口，启用 Edge-to-Edge
├── CrashActivity.kt                   # Native 崩溃显示界面（从 pipe 接收崩溃信息）
├── ErrorReportActivity.kt             # 错误报告界面
├── util/
│   ├── DiagnosticLog.kt               # 诊断日志工具
│   ├── FormatUtils.kt                 # 格式化工具（文件大小等）
│   ├── FileAccessLevel.kt             # 文件访问通道枚举（NORMAL / SHIZUKU / ROOT）
│   ├── FileAccessor.kt                # 文件访问抽象层（屏蔽普通/Shizuku/Root 差异）
│   ├── FolderSizeCalculator.kt        # 文件夹大小统计（全量+差异自底向上算法）
│   ├── AppIconHelper.kt               # 文件管理器中显示已安装应用图标
│   ├── JxlCoilDecoder.kt             # Coil 图片加载器 JPEG XL 解码器
│   ├── BinaryExtractor.kt             # 7zzs 二进制提取（nativeLibraryDir → AppDataPaths.binaries()）
│   ├── SevenZipCommand.kt             # 7zzs 命令行构建器（压缩/列表/解压/单文件提取）
│   ├── ArchiveBrowser.kt              # 压缩包浏览（7zzs l 解析目录树 + 密码检测 + 会话缓存）
│   ├── CompressService.kt             # 压缩/解压服务（三条权限路径：Normal/Shizuku/Root + 进度回调）
│   └── XposedDetector.kt              # Xposed 模块激活检测（反射 + SystemProperties）
├── xposed/
│   └── XposedInit.kt                  # Xposed 模块入口（libxposed API，设置 mcjz.xposed.active 属性）
├── auth/                              # 认证/授权模块（密钥→功能特性门控）
│   ├── Feature.kt                     # 功能枚举（ENCRYPTION_VAULT, FILE_MANAGER, BATCH_DOWNLOADER, WIFI, DIARY, RP_HUB, ACCOUNTING 等）
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
│   │   ├── CanonicalJson.kt           # Python json.dumps(sort_keys=True) 的 Kotlin 等价
│   │   └── FolderSizeDb.kt            # 文件夹大小缓存数据库（v1.2 文本格式）
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
│   ├── ShellService.kt                # Shizuku UserService（Binder IPC 执行 shell）
│   ├── AndroidPermissionLevel.kt      # 权限级别枚举（STANDARD → ROOT 六级）
│   ├── MyAccessibilityService.kt      # 无障碍服务声明
│   └── MyDeviceAdminReceiver.kt       # 设备管理器接收器
├── fileop/                            # 文件操作模块（参考 MaterialFiles 架构）
│   ├── FileOperator.kt                # 文件操作抽象接口（copy/move/delete/mkdir）
│   ├── JavaFileOperator.kt            # Java File API 实现
│   ├── ShellFileOperator.kt           # Root/Shizuku Shell 实现
│   ├── FileOperationJob.kt            # Job 基类
│   ├── CopyJob.kt / MoveJob.kt / DeleteJob.kt  # 具体操作 Job
│   ├── FileOperationManager.kt        # 全局单例，StateFlow 驱动进度/冲突/错误弹窗
│   ├── FileOperationService.kt        # 前台 Service 执行长时间操作
│   └── webdav/                        # WebDAV 客户端模块
│       ├── WebDavServerConfig.kt      # 服务器配置模型
│       ├── WebDavServerStore.kt       # 配置持久化存储
│       ├── WebDavFileClient.kt        # WebDAV 文件操作（实现 FileOperator 接口）
│       ├── WebDavPath.kt              # 路径解析
│       ├── WebDavAuthenticator.kt     # 认证
│       └── client/                    # 底层 HTTP 客户端（OkHttp + dav4jvm）
│           ├── Client.kt
│           ├── Authentication.kt
│           ├── Authority.kt
│           ├── Authenticator.kt
│           ├── DavIOException.kt
│           ├── DavResourceCompat.kt
│           ├── MemoryCookieJar.kt
│           ├── Protocol.kt
│           └── ResponseExtensions.kt
└── ui/
    ├── HomeScreen.kt                  # 导航容器 + 主页/加密/云盘/设置所有屏幕
    ├── VaultCreateScreen.kt           # 新建保险箱向导（含 Argon2id 基准测试）
    ├── VaultOpenScreen.kt             # 保险箱文件浏览器（加密导入/解密导出/重命名/移动/复制/删除）
    ├── VaultChangePasswordScreen.kt   # 修改保险箱密码
    ├── EncryptionSettings.kt          # 加密模块偏好（SharedPreferences 响应式）
    ├── FileManagerScreen.kt           # 文件管理器
    ├── FileManagerViewModel.kt        # 文件管理器 ViewModel（shell 路由 + 大小统计）
    ├── RpHubScreen.kt                 # RP Hub WebView 界面
    ├── RpHubServer.kt                 # RP Hub 本地 NanoHTTPD 服务器
    ├── RpHubTrafficPanel.kt           # RP Hub 流量统计面板
    ├── RpHubDownloadPanel.kt          # RP Hub 下载管理面板
    ├── RpHubDebugPanel.kt             # RP Hub 调试面板
    ├── WifiScreen.kt                  # WiFi 传输功能界面
    ├── SizeCalcManager.kt             # 大小统计进度管理（全局单例）
    ├── AuthManagementScreen.kt        # 认证管理（切换密钥、清除授权）
    ├── SecurityScreen.kt              # 安全设置入口菜单
    ├── SpecialPermissionsScreen.kt    # 特殊权限管理 + 能力矩阵对比
    ├── PermissionSettingsScreen.kt    # Android 运行时权限管理
    ├── AppPermissionsScreen.kt        # 应用权限详情
    ├── PermissionManagementConfigScreen.kt  # 权限管理配置
    ├── PermissionGuideViewModel.kt    # 权限引导 ViewModel
    ├── ErrorDialog.kt                 # 错误对话框组件
    ├── DiaryScreen.kt                 # 日记首页（笔记本列表 + DropdownMenu）
    ├── DiaryBookScreen.kt             # 笔记本详情（日期时间线 + Canvas 圆圈）
    ├── DiaryModels.kt                 # 日记数据模型（DiaryBook / DiaryDb，JSON 持久化）
    ├── ImageViewerScreen.kt           # 图片查看器（HorizontalPager + telephoto zoom）
    ├── TextEditorScreen.kt            # 代码/文本编辑器（Sora CodeEditor）
    ├── ChangelogScreen.kt             # 更新日志
    ├── AboutScreen.kt                 # 关于页面
    ├── WebDavEditDialog.kt            # WebDAV 服务器编辑对话框
    ├── FileOperationDialogs.kt        # 文件操作冲突/错误确认弹窗
    ├── accounting/                    # 记账本模块
    │   ├── AccountingScreen.kt        # 记账本首页（5 Tab：首页/资产/统计/日历/我的）
    │   ├── AccountingDetailScreen.kt  # 账单详情（报销操作 + 编辑入口）
    │   ├── AddAccountingScreen.kt     # 记一笔（收支类型 + 金额键盘 + 日期时间齿轮 + 分类选择 + 附件）
    │   ├── AccountingModels.kt        # 数据模型（Record/Category/Account/Attachment + 账户类型配置）
    │   ├── AccountingDatabase.kt      # SQLite 数据库（5 表：settings/categories/records/accounts/attachment_trash）
    │   ├── AccountingRepository.kt    # 数据访问层（Repository 模式，禁止外部直接使用 Database）
    │   ├── CsvImportScreen.kt         # CSV 导入流程（字段映射→账户映射→分类映射→确认）
    │   ├── NotePredictor.kt           # 备注预测器（MLP + Adam + 对比学习，持久化到 ai_model/）
    │   ├── ReimbursementAccountScreen.kt  # 报销账户列表（分组 + 汇总）
    │   └── AddReimbursementAccountScreen.kt # 添加报销账户
    ├── components/
    │   ├── GlowCard.kt               # 青色光晕边框卡片组件
    │   ├── ApkInfoDialog.kt          # APK 信息弹窗
    │   └── FileTypeIcon.kt           # 文件类型彩色图标组件
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
  ├── 加密 (EncryptionHome)
  │     ├── 保险箱列表 (VaultsListTab)
  │     │     ├── 新建保险箱 (VaultCreate)
  │     │     ├── 打开保险箱 (VaultOpen)  ← 含 TEE 生物识别快速解锁
  │     │     └── 修改密码 (VaultChangePassword)
  │     ├── 云盘 (CloudTab)             ← 占位，待实现
  │     └── 设置 (EncryptionSettingsTab)
  ├── 日记 (Diary)
  │     └── 笔记本详情 (DiaryBookDetail)  ← 日期时间线
  ├── 记账本 (Accounting)
  │     ├── 记一笔 (AddAccounting)         ← 金额键盘 + 日期时间齿轮选择器
  │     ├── 账单详情 (AccountingDetail)    ← 报销操作
  │     ├── 报销账户 (ReimbursementAccount)
  │     └── 添加报销账户 (AddReimbursementAccount)
  ├── WiFi (Wifi)
  ├── 批量下载 (BatchDownloader)
  ├── FA 下载 (FADownloader / FALogin)
  ├── DeviantArt 下载 (DeviantDownloader / DeviantLogin)
  └── 文件管理器 (FileManager)           ← 含 WebDAV 快捷访问

设置 (Settings)
  ├── 安全 (Security)
  │     ├── 权限设置 (PermissionSettings)
  │     └── 特殊权限 (SpecialPermissions)
  ├── 关于 (About)
  └── 更新日志 (Changelog)
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

### 文件管理器 Shell 路由

文件管理器采用**最高权限优先**策略：有 Shell 引擎（Root/Shizuku）时所有操作优先走 Shell，失败才回退 Java File API。

```
FileManagerViewModel
    ├── hasShellEngine = isRootEngine || isShizukuAuthorized
    ├── listChildrenOrNull() → 有 shell 时 listDirChildrenViaShell()，否则 Java File API
    ├── navigateToFolder() → shell 优先，失败回退 Java API
    ├── shellPathExists() → test -e（不用 cd，在 Shizuku shell 中 cd 对特殊字符路径失败）
    └── getPropertyData() → stat 失败时回退 shell

FileAccessor（FolderSizeCalculator 使用）
    ├── NormalAccessor → Java File API
    └── ShellAccessor → executeShizukuCommand / executeRootCommandFull
```

**关键实现细节**：
- Shell 命令使用 `ls -lap '$escaped'` 模式（不用 `cd`，Shizuku 的 `cd` 对含括号/特殊字符路径失败）
- `ls -lap` 输出解析使用逐字符定位（非 `split("\\s+")`），精确保留文件名中的连续空格
- 大小统计 BFS 前执行 `find -type d | wc -l` 获取总目录数，实现实时进度显示

### 文件操作模块（fileop）

参考 MaterialFiles 架构，采用 Job 模式：

```
FileOperationManager（全局单例）
    ├── StateFlow<FileOperationState> → 进度/冲突/错误 UI
    ├── enqueue(job) → 前台 Service 执行
    └── suspend 等待用户决策（冲突覆盖/跳过/重命名）

FileOperator（抽象接口）
    ├── JavaFileOperator → 普通路径
    ├── ShellFileOperator → Root/Shizuku 路径
    └── WebDavFileClient → WebDAV 远程路径

CopyJob / MoveJob / DeleteJob
    └── 递归遍历 → 冲突检测 → 执行 → 进度回调
```

**WebDAV 客户端**：基于 OkHttp + dav4jvm，配置持久化在 `AppDataPaths`，通过 `WebDavEditDialog` 编辑服务器信息。

### 日记模块

- `DiaryBook` 数据模型 + `DiaryDb` JSON 持久化（存储于 `AppDataPaths`）
- 导航：`Screen.Diary`（笔记本列表）→ `Screen.DiaryBookDetail`（笔记本详情）
- 笔记本详情页左侧日期时间线：Canvas 绘制竖线 + 空心圆圈，LazyColumn 前后各 10 年无限滚动
- 工具栏名称居中：`onSizeChanged` 动态测量按钮宽度，`widthIn(max)` 约束避免重叠

### 压缩包模块

基于 APK 内嵌的 `7zzs` 静态二进制（7-Zip 命令行），支持三条权限路径（Normal/Shizuku/Root）。

```
BinaryExtractor                         # 7zzs 提取：nativeLibraryDir/lib7zzs.so → AppDataPaths.binaries()/7zzs
    └── ensureExtracted() → chmod 755，返回目标路径

SevenZipCommand                         # 命令行构建器（纯字符串拼接）
    ├── escape() / escapePassword()     # 单引号包裹 + '\'' 转义（行业标准 shell 路径转义）
    ├── build()                         # 压缩命令：a -t<format> -mx=<level> [-p'pwd'] -bsp1
    ├── buildListCommand()              # 列表命令：l -ba [-p'pwd'] <archive>
    ├── buildListDetailCommand()        # 技术详情命令：l -slt（检测加密状态）
    ├── buildExtractCommand()           # 解压命令：x [-p'pwd'] -bsp1 <archive> -o<dir> -aoa
    └── buildExtractSingleCommand()     # 单文件提取：x -i!'file' <archive> -o<dir> -aoa

ArchiveBrowser                          # 压缩包浏览（只读）
    ├── isArchiveFile()                 # 识别 zip/7z/rar/tar.gz/tar.bz2/tar.xz/lz4/zst 等
    ├── checkPasswordRequired()         # 密码探测（7z 二进制头部 0x17 快速检测 + 7zzs l -slt 详细检测）
    ├── openArchive()                   # 7zzs l -ba → parseListOutput → buildTree → ArchiveSession
    ├── navigateTo() / navigateUp()     # 目录树导航
    ├── extractSingleFile()             # 单文件提取（-i! 参数）
    └── 会话缓存                         # JSON 序列化到 AppDataPaths.fileManager()/archive_session_cache.json

CompressService                         # 压缩/解压服务
    ├── compress()                      # 压缩入口（ProgressCallback + cancelFlag）
    ├── extract()                       # 解压入口（基于 fileSizes 的真实字节级进度）
    └── 进度解析                          # 正则匹配 7zzs -bsp1 输出 "75% 1" 格式
```

**加密检测策略**（`checkPasswordRequired()`）：
1. 7z 二进制头部快速检测：偏移 32 == `0x17` (kEncodedHeader) → 头部加密（无需启动进程）
2. `7zzs l -slt` 输出含 `7zAES` → 内容加密
3. 输出含 `Encrypted = +` → 加密
4. exitCode=2 且为 7z 文件 → 头部加密（兜底）

**压缩格式支持**：zip、7z、tar、tar.gz、tar.bz2、tar.xz，支持 AES-256 加密（zip/7z）

### 记账本模块

**存储架构**：SQLite（`AccountingDatabase`，`SQLiteOpenHelper`，当前 DB_VERSION=14），DB 文件存储于 `AppDataPaths.accounting()/accounting.db`。历史 JSON 文件和 SharedPreferences 数据在首次启动时自动迁移到 SQLite 后删除。

**数据访问层**：所有数据库操作必须通过 `AccountingRepository`（单例 Repository 模式），禁止外部代码直接使用 `AccountingDatabase`。

**五张表**：
| 表 | 用途 | 关键字段 |
|---|---|---|
| `settings` | 键值设置（分类图标色、账本列表、报销统计等） | key/value |
| `categories` | 分类（一级+二级，parent_id 树形） | id, name, icon, page, type, parent_id, sort_order |
| `records` | 记账记录 | id, book_name, type, amount, category_id, subcategory_id, note, happened_at, account_id, discount_before/off/after, reimbursement_account_id, attachments, exclude_from_stats/budget, reimburse_status/amount/after_amount, refund_amount, address, created_at, updated_at |
| `accounts` | 资金/估值账户 | id, name, type, category(tradable/valuation), initial_amount, note |
| `attachment_trash` | 附件回收站（软删除） | id, attachment_json, original_record_id, original_record_status, deleted_at |

**记录模型核心字段**（`AccountingRecord`）：
- `amount` 保留字符串精度，`happenedAt` 为毫秒时间戳
- `type`：支出/收入/转账/债务
- 报销：`reimbursementAccountId` 关联报销账户，`reimburseStatus` 标记是否已报销，`reimburseAmount` 报销金额，`reimburseAfterAmount` 报销后金额（用于余额计算）
- 优惠：`discountBefore`/`discountOff`/`discountAfter` 三值联动（BigDecimal 精确运算）
- 附件：`attachments` JSON 数组，文件本体存储于 `AppDataPaths.accountingAttachments()`
- 属性标志：`excludeFromStats`（不计入收支）、`excludeFromBudget`（不计入预算）

**导航结构**：
```
Screen.Accounting（首页，含5个底部Tab）
  ├── Tab 0: 首页 — 记录列表 + 月度统计 + 记账本切换
  ├── Tab 1: 资产 — 账户卡片 + 报销/债务/理财汇总
  ├── Tab 2: 统计（占位）
  ├── Tab 3: 日历（占位）
  └── Tab 4: 我的 — 头像/签名 + 个性化设置 + 数据管理（JSON/CSV 导入导出）
Screen.AccountingDetail(bookName, recordId) — 账单详情 + 报销操作
Screen.AddAccounting(bookName, recordId?) — 记一笔 / 编辑
Screen.ReimbursementAccount — 报销账户列表（分组+汇总）
Screen.AddReimbursementAccount — 添加报销账户
```

**分类系统**：
- 内置默认模板（`AccountingCategoryDb.defaultCategories()`）：22 个支出一级分类 + 14 个收入一级分类，每个含二级子分类
- 首次安装释放到 SQLite，版本号 `CURRENT_VERSION=2` 控制重新释放
- 自定义分类：`createParentCategory()` / `createChildCategory()` 动态创建
- CSV 导入时自动创建缺失分类

**记账本管理**：记账本列表存储在 `settings` 表（JSON 数组），默认"默认记账本"，支持多账本切换。上次使用的账本名称持久化。

**报销账户**：`ReimbursementAccountEntity` 序列化存储在 `settings` 表的 `reimbursement_accounts` 键中。支持分组（groupName）、记账本范围（allBooks/selectedBooks）。报销统计（可报销/已报销总额）在导入和报销操作后全量重算。

**CSV 导入流程**（`CsvImportScreen`）：
1. 解析 + 多行引号字段合并（`mergeCsvLines`）
2. 自动检测列映射（`HEADER_ALIASES` 支持中英文别名）
3. "收入"/"支出"分类名归一化（`normalizeCsvText`）
4. 账户映射（匹配已有账户 or 创建新账户）
5. 分类映射（精确匹配 + 子串模糊匹配，歧义时放弃）
6. 追加/替换模式 + 导入后重算余额和报销统计

**备注预测器**（`NotePredictor`）：基于小型 MLP（输入 119 维 → 隐藏层 32/16 → 16 维 embedding），使用 Adam 优化器对比学习，持久化权重和 embedding 到 `ai_model/` 目录。输入特征：一级分类 one-hot + 二级分类 one-hot + 金额 + sin/cos(hour) + 备注字符 bag-of-words。

**首页交互**：`LazyListState` 控制背景 alpha（顶部=0，滚动=1），顶部按钮层始终可见。返回手势：非首页 Tab→回首页，首页 Tab→双击退出。

**金额输入**：自定义计算器键盘（含运算自动求值），水平滑动查看完整金额。

**日期时间选择**：`TimeWheel` 无限循环齿轮（`totalItems = size * 10000`），`snapshotFlow` 检测滚动停止自动吸中，点击直接居中。

**记账天数**：增量维护（插入新日期 +1，删除最后一条 -1），存储在 `settings` 表。

**附件系统**：文件存储于 `AppDataPaths.accountingAttachments()`，支持从相册选取和拍照。删除附件时进入回收站（`attachment_trash` 表），支持恢复和永久清理。账单删除时级联更新回收站状态。

### Xposed 模块

- `xposed/XposedInit.kt`：继承 `XposedModule`（libxposed API），`onPackageLoaded()` / `onSystemServerLoaded()` 为 hook 入口
- `util/XposedDetector.kt`：检测模块是否激活（反射检查 `io.github.libxposed.api.XposedContext` + 读取 `mczj.xposed.active` 系统属性）
- 属性设置：`SystemProperties.set("mczj.xposed.active", timestamp)` 在模块加载时写入

---

## CI/CD

GitHub Actions workflow `.github/workflows/build.yml`:
- **触发**：仅手动 `workflow_dispatch`
- **环境**：JDK 21，Gradle cache disabled（确保干净构建）
- **预构建**：`python3 tools/gen_password_hashes.py` 生成 Native 认证哈希（`hashes.inc` + `obf_key.h`，均 gitignored）
- **签名**：release 签名通过 secrets 注入（`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`），缺失时自动回退到 debug 签名
- **ABI**：仅构建 `arm64-v8a`
- **版本号**：`versionCode` 基于 git commit count 动态生成，`versionName` 格式 `<major>.<minor>.<timestamp>`
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

### 开发原则
- **不确定就问**：遇到业务逻辑、字段含义、条件判断等不确定时，必须先问用户，禁止自行猜测后写入代码。例如不能把"报销账单"自行等同于"债务类型"，应先确认判断条件。
- **禁止兜底式修复**：遇到 bug 时，先追根溯源理解为什么会出错，而不是急着加 `coerce`、`coerceAtMost`、`?: fallback` 等兜底逻辑。例如 `Int.MAX_VALUE` 作为哨兵值导致下游计算产生极端数值，应改用合理的数据结构（如 `Int? = null` 表示"不存在"），而非在消费端加 `coerceAtMost` 压制症状。兜底掩盖了真实问题，让代码更难理解和维护。
- **数值兼容性**：规定 UI 数值时，优先使用兼容性变量（如 `circleRadius * 2`）或百分比方案（如 `screenWidthDp * 0.4f`），避免硬编码固定 dp 数值。这样在不同屏幕密度和尺寸下具有更好的适配性。
- **禁止主动使用 dp 限制尺寸**：UI 组件的尺寸约束必须使用百分比（`weight`、`fillMaxWidth`、`fillMaxHeight`）或相对于屏幕/父容器的方案，禁止使用 `Modifier.size(Xdp)`、`Modifier.width(Xdp)`、`Modifier.height(Xdp)` 等硬编码 dp 限制组件大小。除非用户明确指定"用 XX dp"，才能使用 dp 值。间距（`Spacer`、`padding`、`spacedBy`）不受此限制。
- **使用官方接口**：涉及日期、时间、数学、格式化等计算时，优先使用平台/语言官方 API（如 `Calendar.isLeapYear(year)`），不要自己写算法判断，避免边界情况遗漏。
- **Card 内边距**：卡片默认使用 `padding(horizontal = 16.dp, vertical = 12.dp)`，不要用过大的内边距（如 24dp），避免文字离卡片边缘太远显得空旷。

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
- **Xposed hook 逻辑**：`XposedInit.onPackageLoaded()` 中 TODO 待实现具体 hook

---

## 已知注意点

- `VaultOpenScreen` 的文件过滤目前排除了 `name_mapping.json.bak`，但实际写入的是 `name_mappings.json`（复数），无实际影响但需注意命名一致性
- `importVaultWithPassword()` 硬编码 `StorageLocation.EXTERNAL`，从 SAF URI 导入时路径解析依赖 `content://` → 绝对路径的转换，部分机型可能不准确
- `LaunchedEffect(Unit)` 中的 `Looper.loop()` 全局异常捕获仅在加密流程中生效，设计较激进，需注意主线程异常逃逸风险
