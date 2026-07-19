# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 文档版本

**基准哈希**：`4a1f5431bc9050c81304b7aec888d56dc009fde5`
**更新日期**：2026-07-19

> 更新 CLAUDE.md 前，先执行 `git diff <基准哈希>..HEAD -- '*.kt' '*.kts' '*.py' '*.sh' '*.yml'` 查看自上次记录以来的所有代码变更，确保文档与代码同步。更新后替换基准哈希为新的 HEAD。

## 工具使用规范

- **严禁使用 Explore 工具**，包括以下所有形式：
  - `Agent(subagent_type="Explore")` — 拒绝
  - 任何将 Explore 作为 Agent 的前置步骤单独调用 — 拒绝
- 所有需要探索/查找代码的任务，直接使用 `Agent(description, prompt)` 完成（不带 subagent_type，走默认 general-purpose agent）
- 简单查找优先用 `Bash(grep/find)` + `Read`

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
./gradlew :APP:app:assembleRelease
```

**Gradle 模块**：`:APP:app`（入口）、`:APP:core`（基础设施）、`:APP:Models:others`（主要功能）、`:APP:Models:accounting`（记账本）、`:libs:libxposed-api`

---

## 项目结构（多模块架构）

项目拆分为 4 个 Gradle 模块：`APP/app`（入口）、`APP/core`（基础设施）、`APP/Models/others`（主要功能）、`APP/Models/accounting`（记账本）。

### APP/app — 应用入口
```
src/main/java/com/whmdg/mczj/tools/
├── MainActivity.kt                    # 入口，启用 Edge-to-Edge
├── ToolsApp.kt                        # Application 类（初始化）
├── CrashActivity.kt                   # Native 崩溃显示界面（从 pipe 接收崩溃信息）
├── ErrorReportActivity.kt             # 错误报告界面
└── ui/
    └── HomeScreen.kt                  # 导航容器，路由到各功能模块的 ModuleScreen
```

### APP/core — 基础设施（被所有模块依赖）
```
src/main/java/com/whmdg/mczj/tools/
├── AppDataPaths.kt                    # 应用数据路径解析
├── auth/                              # 认证/授权模块（密钥→功能特性门控）
│   ├── Feature.kt                     # 功能枚举
│   ├── NativeAuth.kt                  # JNI 接口，调用 authcore 验证密码
│   ├── KeyProfile.kt                  # 密钥 ID → Feature 集合映射
│   ├── PermissionManager.kt           # 全局认证状态管理（StateFlow<AuthState>）
│   ├── TokenCodec.kt / TokenStorage.kt / KeystoreMaster.kt
│   ├── SecurityEnforcer.kt            # 业务层权限检查失败时的安全自杀
│   ├── LocalPermissionGate.kt / ReadOnlyGate.kt / PasswordDialog.kt
│   └── NoPermissionScreen.kt
├── encryption/                        # 加密模块
│   ├── core/                          # 密码学原语
│   │   ├── AesGcm.kt / Argon2id.kt / Pbkdf2.kt / KeyDerivation.kt
│   │   ├── FileCodec.kt / FileConstants.kt / FilenameCodec.kt
│   │   ├── HexCodec.kt / SecureRandom.kt / NailObfuscation.kt
│   ├── data/                          # 加密数据层
│   │   ├── VaultConfig.kt / VaultDb.kt / VaultPaths.kt / VaultRecord.kt
│   │   ├── CanonicalJson.kt / NameMapping.kt / FolderSizeDb.kt / StorageLocation.kt
│   ├── models/
│   │   └── EncryptionNode.kt
│   └── services/
│       ├── VaultService.kt / VaultSession.kt
│       └── CryptoService.kt / EncryptionTaskManager.kt
├── security/
│   ├── ShellExecutor.kt               # 统一 shell 执行入口
│   ├── TeeManager.kt                  # TEE 生物识别快速解锁
│   ├── SpecialPermissionVerifier.kt / ShizukuAuthorizer.kt / ShellService.kt
│   ├── AndroidPermissionLevel.kt / AccessibilityServiceBridge.kt / MyDeviceAdminReceiver.kt
│   └── FdProvider.kt                   # 文件描述符提供（跨进程 fd 传递）
├── util/                              # 基础工具类
│   ├── DiagnosticLog.kt / FormatUtils.kt
│   ├── FileAccessLevel.kt / FileAccessor.kt / FolderSizeCalculator.kt
│   ├── AppIconHelper.kt
│   ├── BinaryExtractor.kt / SevenZipCommand.kt / ArchiveBrowser.kt / CompressService.kt
│   └── XposedDetector.kt
└── ui/
    ├── Screen.kt                      # Screen sealed class（全局导航定义）
    ├── FileEntry.kt / ActivityRef.kt
    ├── components/
    │   ├── GlowCard.kt / ApkInfoDialog.kt / FileTypeIcon.kt
    ├── security/                      # 权限管理
    │   ├── SecurityRoute.kt / SecurityModuleScreen.kt / SecurityScreen.kt
    │   ├── PermissionSettingsScreen.kt / SpecialPermissionsScreen.kt
    │   ├── AppPermissionsScreen.kt / AuthManagementScreen.kt
    │   ├── PermissionManagementConfigScreen.kt / PermissionGuideViewModel.kt
    └── theme/
        └── Color.kt / Theme.kt / Type.kt
```

### APP/Models/others — 主要功能模块
```
src/main/java/com/whmdg/mczj/tools/
├── fileop/                            # 文件操作模块（参考 MaterialFiles 架构）
│   ├── FileOperator.kt                # 抽象接口（copy/move/delete/mkdir）
│   ├── ShellFileOperator.kt           # Root/Shizuku 文件操作实现
│   ├── FileOperationJob.kt / CopyJob.kt / DeleteJob.kt  # CopyJob 统一处理复制/移动，同分区 mv 快速路径
│   ├── FileOperationManager.kt        # 全局单例，StateFlow 驱动进度/冲突/错误弹窗
│   ├── FileOperationService.kt        # 前台 Service
│   ├── FileOpDiagnostics.kt           # 文件操作诊断
│   └── webdav/                        # WebDAV 客户端
│       ├── WebDavServerConfig.kt / WebDavServerStore.kt  # 服务器配置持久化
│       ├── WebDavFileClient.kt / WebDavPath.kt / WebDavAuthenticator.kt
│       └── client/                    # 底层 HTTP 客户端
│           ├── Client.kt / Protocol.kt / Authority.kt
│           ├── Authentication.kt / Authenticator.kt
│           ├── DavResourceCompat.kt / DavIOException.kt
│           ├── MemoryCookieJar.kt / ResponseExtensions.kt
├── xposed/
│   ├── XposedInit.kt                  # Xposed 模块入口
│   └── hooks/                         # 微信账单 Hook（WebView.evaluateJavascript 拦截）
│       ├── WechatBillHooker.kt        # Hook 注册 + Application 获取 + Tinker 禁用 + WebView 拦截 + 广播
│       └── BillHookParser.kt          # 账单 JSON 解析（金额/商户/时间/类型）
├── util/
│   └── JxlCoilDecoder.kt             # Coil 图片加载器 JPEG XL 解码器
└── ui/
    ├── SizeCalcManager.kt             # 大小统计进度管理
    ├── ErrorDialog.kt                 # 错误对话框组件
    ├── AboutScreen.kt                 # 关于页面
    ├── ChangelogScreen.kt             # 更新日志
    ├── filemanager/                   # 文件管理器
    │   ├── FileManagerRoute.kt        # sealed class: Home / TextEditor / ImageViewer
    │   ├── FileManagerModuleScreen.kt # Compose 导航容器
    │   ├── FileManagerScreen.kt       # 文件管理器主界面
    │   ├── FileManagerViewModel.kt    # ViewModel（shell 路由 + 大小统计 + 独立加密/解密）
    │   ├── ImageViewerScreen.kt       # 图片查看器
    │   ├── TextEditorScreen.kt        # 代码/文本编辑器
    │   ├── FileOperationDialogs.kt    # 文件操作冲突/错误弹窗
    │   └── WebDavEditDialog.kt        # WebDAV 服务器编辑对话框
    ├── encryption/                    # 加密 UI
    │   ├── EncryptionRoute.kt / EncryptionModuleScreen.kt
    │   ├── EncryptionHomeScreen.kt     # 保险箱卡片 UI（渐变+光晕）+ 保持打开计时器（JNI HMAC 防篡改）/ VaultCreateScreen.kt / VaultOpenScreen.kt
    │   ├── VaultChangePasswordScreen.kt / EncryptionSettings.kt
    │   └── EncryptionProgressIcon.kt / EncryptionProgressPanel.kt
    ├── diary/                         # 日记模块
    │   ├── DiaryRoute.kt / DiaryModuleScreen.kt
    │   ├── DiaryScreen.kt / DiaryBookScreen.kt / DiaryModels.kt
    ├── download/                      # 下载器模块
    │   ├── DownloaderRoute.kt / DownloaderModuleScreen.kt
    │   ├── BatchDownloaderScreen.kt / FADownloaderScreen.kt / FADownloaderViewModel.kt / FALoginScreen.kt
    │   └── Deviant/
    │       ├── DeviantDownloaderScreen.kt / DeviantDownloaderViewModel.kt
    │       └── DeviantLoginScreen.kt / DeviantModels.kt
    ├── rphub/                         # RP Hub 模块
    │   ├── RpHubRoute.kt / RpHubModuleScreen.kt
    │   ├── RpHubScreen.kt / RpHubServer.kt / RpHubTrafficPanel.kt / RpHubDownloadPanel.kt / RpHubDebugPanel.kt
    └── wifi/                          # WiFi 传输模块
        ├── WifiRoute.kt / WifiModuleScreen.kt
        └── WifiScreen.kt
```

### APP/Models/accounting — 记账本模块
```
src/main/java/com/whmdg/mczj/tools/ui/accounting/
├── AccountingRoute.kt / AccountingModuleScreen.kt
├── AccountingScreen.kt / AccountingDetailScreen.kt / AddAccountingScreen.kt
├── AccountingModels.kt / AccountingDatabase.kt / AccountingRepository.kt
├── CsvImportScreen.kt / NotePredictor.kt
├── ReimbursementAccountScreen.kt / ReimbursementAccountDetailScreen.kt / AddReimbursementAccountScreen.kt
├── TransferListScreen.kt / CapitalFlowScreen.kt   # 转账列表 / 资金流水
├── AssetDetailScreen.kt / AssetHistoryScreen.kt / FixedDepositScreen.kt  # 资产详情/历史/定期
├── CloudSyncScreen.kt                              # 云同步
├── BillOcrEngine.kt / BillOcrModels.kt             # 账单 OCR 识别引擎
├── OcrFloatingWindow.kt / OcrFloatingService.kt / OcrLifecycleObserver.kt  # 悬浮窗 OCR
├── HookFloatingWindow.kt / HookFloatingService.kt / HookResultReceiver.kt  # Hook 账单悬浮窗
├── MyAccessibilityService.kt                       # 无障碍服务
├── ColorIconRegistry.kt               # 279 个分类图标的 build_in_XXXX 编码映射
├── ColorIconImage.kt                  # 从 assets/color_icons/ 加载 PNG 的 Compose 组件
└── (assets/color_icons/ — 279 个分类图标 PNG，由 ColorIconImage 加载)
```

### 路由层模式（*Route.kt + *ModuleScreen.kt）

每个 UI 功能模块采用统一的内部路由模式：
- `*Route.kt`：sealed class 定义模块内子路由（如 `FileManagerRoute.Home` / `.TextEditor` / `.ImageViewer`）
- `*ModuleScreen.kt`：Compose 导航容器，管理 backStack，根据 Route 渲染对应 Screen
- `HomeScreen.kt` 通过 `Screen.*` sealed class 路由到各模块的 `*ModuleScreen`

**构建/工具** (`tools/`)：
```
gen_password_hashes.py                 # 预生成 Argon2id 密码哈希 → hashes.inc + obf_key.h
wait_and_download.sh                   # CI 产物下载辅助脚本
diagnose.sh                            # 白屏诊断脚本（在设备上 su -c sh 执行）
```

**Native 代码** (`APP/core/src/main/cpp/`)：
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

### 导航（Screen sealed class + 模块内 Route）

两级路由：`Screen` sealed class（core 模块）路由到各功能模块的 `*ModuleScreen`，模块内 `*Route` sealed class 管理子页面。

```
Screen.Home → HomeScreen（底部导航容器）
  ├── Screen.EncryptionHome → EncryptionModuleScreen
  │     ├── EncryptionRoute.VaultsList → 保险箱列表
  │     │     ├── 新建保险箱 (VaultCreate)
  │     │     ├── 打开保险箱 (VaultOpen)  ← 含 TEE 生物识别快速解锁
  │     │     └── 修改密码 (VaultChangePassword)
  │     ├── EncryptionRoute.CloudTab   ← 占位，待实现
  │     └── EncryptionRoute.Settings → 加密设置
  ├── Screen.Diary → DiaryModuleScreen
  │     └── DiaryRoute.BookDetail → 笔记本详情（日期时间线）
  ├── Screen.Accounting → AccountingModuleScreen
  │     ├── 记一笔 (AddAccounting)         ← 金额键盘 + 日期时间齿轮选择器
  │     ├── 账单详情 (AccountingDetail)    ← 报销操作
  │     ├── 报销账户 (ReimbursementAccount)
  │     └── 添加报销账户 (AddReimbursementAccount)
  ├── Screen.Wifi → WifiModuleScreen
  ├── Screen.BatchDownloader → DownloaderModuleScreen
  │     ├── FA 下载 (FADownloader / FALogin)
  │     └── DeviantArt 下载 (DeviantDownloader / DeviantLogin)
  ├── Screen.FileManager → FileManagerModuleScreen
  │     ├── FileManagerRoute.Home → 文件管理器主界面（含 WebDAV 快捷访问）
  │     ├── FileManagerRoute.TextEditor → 文本编辑器
  │     └── FileManagerRoute.ImageViewer → 图片查看器
  └── Screen.RpHub → RpHubModuleScreen

Screen.Settings → 设置
  ├── SecurityModuleScreen
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
[4字节 chunk长度] [12字节 IV] [加密数据块] × N  ← 每块 1MiB (FileConstants.CHUNK_SIZE)
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

### ShellExecutor 统一执行入口

所有 shell 命令必须通过 `ShellExecutor.execute()` 执行，禁止直接调用 `Runtime.exec`、`ProcessBuilder`、`Shell.cmd`。

```
ShellExecutor.execute(permission, command, debug)
    ├── Permission.APPLICANT → Runtime.exec("sh", "-c", cmd)（应用自身 uid）
    ├── Permission.ADB → 自动路由：Shizuku 可用 → Shizuku，否则 → 应用 shell
    ├── Permission.ROOT → libsu Shell.cmd()（需 Magisk 授权）
    ├── Permission.MAX → 读 SharedPreferences 获取最高已授权权限
    └── Permission.MIN → = Permission.APPLICANT

ShellException(command, permission, stderr, exitCode) → 任何失败抛出异常
```

**权限选择原则**：必须 root 才能执行的命令（rm、chmod、chown、chattr、pm、dumpsys）→ `Permission.ROOT`；权限由上层决定的 → `Permission.MAX`；普通命令 → `Permission.MIN`。

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
    └── ShellAccessor → ShellExecutor.execute(Permission.MAX, cmd)
```

**关键实现细节**：
- Shell 命令不用 `cd`（Shizuku 的 `cd` 对含括号/特殊字符路径失败）
- 目录列表使用 `find -printf` 直接输出字段（替代旧版 `ls -lap` 逐字符解析）
- 文件属性使用 `stat -c '%a|%U|%G|%u|%g'` 一次获取权限/用户名/组名/UID/GID
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

CopyJob（统一处理复制和移动）
    ├── CopyPurpose.COPY → 直接走复制链路
    ├── CopyPurpose.MOVE → partitionSourcesByDevice() 分区检测
    │     ├── 同分区 → moveWithMv() 快速 mv
    │     └── 跨分区 → 复制 + 删除源文件
    ├── 递归遍历 → 冲突检测（resolveConflictIfNeeded）→ 执行 → 进度回调
    └── 取消/异常 → 清理残留目标文件
```

**WebDAV 客户端**：基于 OkHttp + dav4jvm，配置持久化在 `AppDataPaths`，通过 `WebDavEditDialog` 编辑服务器信息。

### 日记模块

- `DiaryBook` 数据模型 + `DiaryDb` JSON 持久化（存储于 `AppDataPaths`）
- 导航：`Screen.Diary` → `DiaryModuleScreen` → `DiaryRoute.BookDetail`（笔记本详情）
- 笔记本详情页左侧日期时间线：Canvas 绘制竖线 + 空心圆圈，LazyColumn 前后各 10 年无限滚动
- 工具栏名称居中：`onSizeChanged` 动态测量按钮宽度，`widthIn(max)` 约束避免重叠

### 压缩包模块

基于 APK 内嵌的 `7zzs` 静态二进制（7-Zip 命令行），`ArchiveBrowser` 通过 `run7zs()` 使用 `Runtime.exec` 直接执行（绕过 ShellExecutor）。

```
BinaryExtractor                         # 直接使用 nativeLibraryDir/lib7zzs.so（APK 安装时已解压）
    └── ensureExtracted() → 校验文件存在，返回路径

SevenZipCommand                         # 命令行构建器（纯字符串拼接）
    ├── escape() / escapePassword()     # 单引号包裹 + '\'' 转义（行业标准 shell 路径转义）
    ├── build()                         # 压缩命令：a -t<format> -mx=<level> [-p'pwd' [-mem=AES256] [-mhe=on]] -bsp1
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
    ├── CompressOptions                  # sourcePaths, outputPath, format, compressionLevel, password, useAes, encryptNames
    ├── compress()                      # 压缩入口（ProgressCallback + cancelFlag）
    ├── extract()                       # 解压入口（基于 fileSizes 的真实字节级进度）
    └── 进度解析                          # 正则匹配 7zzs -bsp1 输出 "75% 1" 格式
```

**加密检测策略**（`checkPasswordRequired()`）：
1. 7z 二进制头部快速检测：偏移 32 == `0x17` (kEncodedHeader) → 头部加密（无需启动进程）。**注意**：此方法对部分加密 7z 文件不可靠（头部被加密后偏移 32 是密文而非明文标识），仅作快速路径
2. `7zzs l -slt` 输出含 `7zAES` → 内容加密
3. 输出含 `Encrypted = +` → 加密
4. exitCode=2 且为 7z 文件 → 头部加密（兜底）

**压缩格式支持**：zip、7z、tar、tar.gz、tar.bz2、tar.xz
- **zip**：支持 AES-256 加密（`-mem=AES256`），压缩对话框中有"ZIP AES-256"开关
- **7z**：支持内容加密 + 文件名加密（`-mhe=on` 隐藏文件列表），压缩对话框中有"加密文件名"开关
- **tar 系列**：不支持加密
- 加密开关需先输入密码才能启用，未输入密码时弹出 AlertDialog 提示

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

### 悬浮窗 OCR 架构（Overlay + Service + ProcessLifecycleOwner）

采用 **Service 托管悬浮窗 + ProcessLifecycleOwner 驱动生命周期** 的标准分层架构：

```
ProcessLifecycleOwner（应用级生命周期）
    │ ON_STOP → 延迟 300ms → startService
    │ ON_START → 延迟 300ms → stopService
    ▼
OcrFloatingService（普通 Service，非前台）
    │ onCreate → OcrFloatingWindow.show(context)
    │ onDestroy → OcrFloatingWindow.dismiss()
    ▼
OcrFloatingWindow（WindowManager 视图管理）
    │ TYPE_APPLICATION_OVERLAY
    │ 状态机：BUBBLE → MENU → LOADING → RESULT
    ▼
BillOcrEngine.recognizeNow()（无障碍节点树文字提取 + 关键词 + 正则）
```

**关键设计决策**：

- **防抖（Debounce）**：`ProcessLifecycleOwner` 的 `onStop`/`onStart` 在 Dialog、PopupWindow、系统权限弹窗出现时会短暂触发。使用 300ms `Handler.postDelayed` 延迟，配合 `cancelPending` 互斥取消，避免悬浮窗闪现。
- **Service 托管**：悬浮窗 View 的生命周期绑定到 Service 而非 Activity 或 Application。`startService`/`stopService` 是显式生命周期边界，比直接在 Observer 中操作 WindowManager 更可靠（系统保证 `onDestroy` 调用，避免窗口泄漏）。
- **普通 Service vs 前台 Service**：悬浮窗 Service 不需要常驻通知栏，使用普通 `startService` 即可。`TYPE_APPLICATION_OVERLAY` 不要求前台服务。
- **ProcessLifecycleOwner vs AccessibilityService 事件**：`TYPE_WINDOW_STATE_CHANGED` 会因悬浮窗自身获得焦点而触发（`packageName == 本应用`），导致自消失问题。`ProcessLifecycleOwner` 基于 Activity 可见性计数，不受 Overlay 窗口焦点影响，是检测应用级前后台切换的正确 API。

**权限**：
- `SYSTEM_ALERT_WINDOW`：悬浮窗权限，`Settings.canDrawOverlays()` 检测
- `BIND_ACCESSIBILITY_SERVICE`：无障碍权限，用于节点树文字提取

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
- **只推主分支**：`git push` 只允许推送到 `master`（主分支）。禁止创建临时 feature 分支。如果推送失败（网络问题、权限问题等），等待恢复后重试，不要创建其他分支绕过。
- **不确定就问**：遇到业务逻辑、字段含义、条件判断等不确定时，必须先问用户，禁止自行猜测后写入代码。例如不能把"报销账单"自行等同于"债务类型"，应先确认判断条件。
- **禁止兜底式修复**：遇到 bug 时，先追根溯源理解为什么会出错，而不是急着加 `coerce`、`coerceAtMost`、`?: fallback` 等兜底逻辑。例如 `Int.MAX_VALUE` 作为哨兵值导致下游计算产生极端数值，应改用合理的数据结构（如 `Int? = null` 表示"不存在"），而非在消费端加 `coerceAtMost` 压制症状。兜底掩盖了真实问题，让代码更难理解和维护。
- **数值兼容性**：规定 UI 数值时，优先使用兼容性变量（如 `circleRadius * 2`）或百分比方案（如 `screenWidthDp * 0.4f`），避免硬编码固定 dp 数值。这样在不同屏幕密度和尺寸下具有更好的适配性。
- **禁止主动使用 dp 限制尺寸**：UI 组件的尺寸约束必须使用百分比（`weight`、`fillMaxWidth`、`fillMaxHeight`）或相对于屏幕/父容器的方案，禁止使用 `Modifier.size(Xdp)`、`Modifier.width(Xdp)`、`Modifier.height(Xdp)` 等硬编码 dp 限制组件大小。除非用户明确指定"用 XX dp"，才能使用 dp 值。间距（`Spacer`、`padding`、`spacedBy`）不受此限制。
- **使用官方接口**：涉及日期、时间、数学、格式化等计算时，优先使用平台/语言官方 API（如 `Calendar.isLeapYear(year)`），不要自己写算法判断，避免边界情况遗漏。
- **Card 内边距**：卡片默认使用 `padding(horizontal = 16.dp, vertical = 12.dp)`，不要用过大的内边距（如 24dp），避免文字离卡片边缘太远显得空旷。
- **外部参数不可信**：函数必须自行校验传入参数的合法性，不依赖调用方保证。例如 ShellExecutor 接收 Permission 枚举，执行前必须校验对应权限是否真正可用，不能假设调用方已做过检查。校验失败时通过 DiagnosticLog 记录并抛出异常，由 UI 层统一通过 ErrorDialog 弹窗展示，便于开发阶段发现问题并修复传入参数。

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
