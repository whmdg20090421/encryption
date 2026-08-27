# 压缩包浏览路径重构计划

## 问题

当前压缩包浏览有两条独立的状态链路：
- `panel.path` — 文件系统路径（压缩包模式下保持为压缩包所在目录）
- `archiveSession.currentPath` — 压缩包内部虚拟路径

路径栏读 `archiveSession.currentPath`，导航操作读 `panel.path`，两者各自更新，导致路径栏滞后。

## 目标

统一为单一真实路径：`panel.path` 始终存储当前浏览的绝对路径。路径栏显示时根据模式做虚拟化映射。

## 核心思路

- 压缩包模式下，`panel.path` 存储压缩包内部路径（如 `/storage/0/test.zip/subdir`）
- `panel.entries` 始终是当前目录的条目列表
- `archiveSession` 保留，用于：提取文件、知道压缩包根路径、密码缓存
- 路径栏从 `panel.path` + `archiveSession` 上下文派生虚拟显示

## 需要修改的文件和具体位置

### 1. `FileManagerViewModel.kt`（FilePaneController 部分）

**`enterArchiveMode()`**（约 line 1737-1745）
- 加一行：`panel.path = session.currentPath`（让 panel.path 指向压缩包根）

**`navigateInArchive()`**（约 line 1748-1759）
- 加一行：`panel.path = newSession.currentPath`（panel.path 跟随压缩包内导航）

**`archiveGoUp()`**（约 line 1762-1776）
- 加一行：`panel.path = newSession.currentPath`（返回上级时 panel.path 同步更新）
- 注意：`exitArchive()` 内部已有 `panel.path = session.originalPath`，无需额外改

**`goUp()`**（约 line 1064-1071）
- 加判断：如果 `isInArchiveMode`，先调用 `archiveGoUp()` 再返回（或直接返回 null 让上层处理）
- 实际上 `goUp()` 不需要改，因为 `saveScrollAndGoUp()` 已经在 archive mode 下走 `archiveGoUp()` 了

### 2. `FileManagerViewModel.kt`（PanelCoordinator 部分）

**`initialize()`**（约 line 2519-2535，恢复缓存会话）
- 已有 `targetCtrl.state.path = session.originalPath`
- 需改为：`targetCtrl.state.path = session.currentPath`（恢复时 panel.path 指向压缩包内当前路径）

### 3. `FileManagerScreen.kt`（路径栏显示）

**`titleText`**（约 line 674-691）
- 压缩包模式分支简化：从 `panel.path`（现在就是压缩包内部路径）+ `archiveSession` 派生
- 去掉对 `archiveSession.currentPath` 的直接读取，改为读 `vm.currentPath`（即 `panel.path`）

### 4. `FileManagerScreen.kt`（`saveScrollAndGoUp`）

**`saveScrollAndGoUp`**（约 line 505-529）
- 移除 `isInArchiveMode` 分支（line 507-508）
- 原因：`goUp()` 现在能正确处理压缩包模式（因为 panel.path 就是压缩包内路径，`substringBeforeLast('/')` 会正确计算压缩包内的父路径）
- 等等，这不对——`goUp()` 计算的是 panel.path 的父路径，压缩包内路径的父路径不是文件系统路径，不能用 `ls` 列出
- **结论**：`saveScrollAndGoUp` 中的 `isInArchiveMode` 分支需要保留，但逻辑可以简化

### 5. 实际改动方案（简化版）

与其大改 `goUp()`/`loadDirectory()` 让它们支持压缩包虚拟路径，不如：

**只改状态同步，不改导航机制**：

1. `enterArchiveMode()`: 加 `panel.path = session.currentPath`
2. `navigateInArchive()`: 加 `panel.path = newSession.currentPath`
3. `archiveGoUp()`: 加 `panel.path = newSession.currentPath`
4. `initialize()` 恢复缓存: `panel.path = session.currentPath`
5. 路径栏 `titleText`: 压缩包模式下从 `panel.path`（现在是内部路径）+ `archiveSession.archivePath` 派生显示
6. `saveScrollAndGoUp` 中 `isInArchiveMode` 分支保留（因为 `goUp()` + `loadDirectory()` 不适用于压缩包内导航）

**效果**：`panel.path` 和 `archiveSession.currentPath` 始终同步，路径栏从 `panel.path` 读取即可。未来如果需要重构 `goUp()` 支持压缩包，`panel.path` 已经就位。

## 关键原则

- `panel.path` 是唯一的真实路径源
- `archiveSession` 保留用于压缩包特定操作（提取、密码、缓存）
- `isInArchiveMode` 保留用于 UI 分支（禁用按钮等）
- 路径栏显示用虚拟化映射函数，不直接读 `archiveSession.currentPath`

## 验证方式

1. 进入压缩包 → 路径栏显示压缩包名（根目录）
2. 在压缩包内进入子目录 → 路径栏显示 `压缩包名 / 子目录`
3. 点击"返回上一级"卡片 → 路径栏同步更新
4. 系统右滑返回 → 路径栏同步更新
5. 在压缩包根目录点返回 → 退出压缩包，路径栏恢复为外部目录路径
6. 重启 app → 压缩包会话恢复，路径正确
