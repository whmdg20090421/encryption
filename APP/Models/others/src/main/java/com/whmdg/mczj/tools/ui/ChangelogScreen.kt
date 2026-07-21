package com.whmdg.mczj.tools.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class ChangelogVersion(
    val version: String,
    val date: String,
    val items: List<String>
)

private val CHANGELOG = listOf(
    ChangelogVersion(
        version = "V4.0",
        date = "2026-07-21",
        items = listOf(
            // ── 项目架构 ──
            "多模块架构拆分：项目拆分为 core（基础设施）/ accounting（记账本）/ others（主要功能）/ app（入口）四模块，编译隔离更清晰",
            "目录重组：四模块统一移入 APP/ 目录，删除模板测试，FileChildInfo 合并为 DirEntry",
            "ShellExecutor 统一 shell 执行入口：所有 shell 命令统一走 ShellExecutor，禁止直接调用 Runtime.exec / Shell.cmd",
            "R8 字节码优化 + 资源压缩（无损，不裁剪代码）",
            "Xposed 模块全部中文化：文件名/类名/方法名/常量统一改为中文",
            "合并 WebViewBillHooker 到 WechatBillHooker，Xposed API 升级到 102.0.0",
            "Gradle 编译加 -w 参数，只输出 warning/error",
            // ── 文件操作 ──
            "文件操作进度条：扫描实时统计 + 字节级进度 + 优雅取消",
            "PV 文件操作统一路由到 ShellExecutor，pv --force 实时进度 + 复制/移动确认对话框",
            "文件复制/移动改用 Shizuku PFD 字节驱动进度，移除 pv 依赖，与 MT 管理器对齐",
            "文件操作全链路走 Permission.MAX，FD 获取重构",
            "文件操作诊断报告 + 全局超时检测 + buffer 优化",
            "文件操作强制取消 + 冲突弹窗 RadioButton 重设计",
            "文件操作统一复制/移动逻辑，修复取消后残留文件问题",
            "文件移动进度显示真实字节数",
            // ── 文件管理器加密 ──
            "文件管理器独立加密功能：纯 AES-256-GCM，加密/解密改用 Argon2id，结果弹窗与重试",
            "加密保险箱挂载到文件管理器双面板：vault 可直接在文件管理器中浏览",
            "vault 解锁时显示「正在解密」加载弹窗，Argon2id 派生移到 IO 线程防止主线程 ANR",
            "删除文件管理器内置加密/解密，统一使用独立加密模块",
            "修复保险箱文件管理器返回时导航状态重置",
            // ── 7z 压缩包 ──
            "7z 无密码压缩包支持直接浏览，有密码/损坏仍弹信息弹窗",
            "7z 文件点击弹出加密状态信息弹窗，检测失败时显示诊断信息+复制按钮",
            "7z 压缩增加加密文件名开关 + 压缩加密选项需密码提示",
            "ArchiveBrowser 所有 7z 命令改用 Runtime.exec 直接执行，提取 run7zs 辅助函数",
            "所有 ls -lap 解析替换为 find -printf / stat -c 直接字段输出",
            // ── 记账本 ──
            "统计 Tab 实现：数据汇总 + 柱状图 + 折线图，数据库层级存储重构，月度收支汇总自动维护",
            "日历 Tab 实现：日历视图 + 多选操作栏优化",
            "记账本多选操作栏改版：长按进入多选模式，4 格布局 + ModalBottomSheet",
            "内置彩色图标库：279 个分类图标，替换记账本分类占位符",
            "分类 ID 改为 A/B 编号 + CSV 导入新增分类方案选择步骤，记录存储中文分类名",
            "资产详情页 4 菜单：流水对账 / 资产明细 / 转账记录 / 定期存款",
            "统一余额计算 + 调整类型 + 报销后金额自动计算，每笔账单存储滚动余额",
            "资产页面重构 + 收支统计 + 数据自动化 + 记账自动定位",
            "记账本底部导航栏透明悬浮效果",
            "资产详情页增强 + 报销账户详情页",
            "账户新增 currentBalance 字段，余额显示和修改直接读写该字段",
            "数据修复功能 + 首页异常账单检测显示",
            "CSV 导入支持多行引号字段（报销明细跨行导致列错位）",
            // ── 账单识别 ──
            "账单 OCR 识别改为悬浮窗主动触发方案，完整实现无障碍账单识别引擎",
            "截图 OCR 识别 + 缩小飞入动画，识别错误细化 + 调试信息显示",
            "移除 OCR 截图识别，改用无障碍节点树提取账单文字",
            "微信账单 Hook 模式：LSPosed 自动拦截支付数据，Tinker 禁用 + 广播包名 + Application 获取",
            // ── 其他 ──
            "云同步功能",
            "通知栏自动记账控制：前台服务 + 点击切换",
            "ANR 看门狗：主线程 3 秒无响应时持续采样栈并写入日志",
            "软链接相关逻辑移除，点击软链接弹窗提示暂不支持跳转"
        )
    ),
    ChangelogVersion(
        version = "V3.3",
        date = "2026-06-28",
        items = listOf(
            "CSV 导入全面增强：退款/优惠/报销/余额/其他标志/地址字段解析，BigDecimal 精确优惠三值计算（优惠前/优惠金额/优惠后）",
            "CSV 导入分类模糊匹配：双向子串匹配+歧义检测，单候选自动匹配、多候选放弃让用户选择",
            "备注输入智能辅助：编辑距离模糊匹配+百分比匹配度，AI 小型 MLP 在线学习+embedding 最近邻预测",
            "添加记账键盘交互优化：键盘弹起布局自适应+动画过渡+分割线精确对齐键盘顶边+操作栏/计算器自动隐藏",
            "报销流程完整闭环：报销弹窗+报销状态/金额存储+报销后金额字段+余额 CASE WHEN 计算",
            "资产卡片重设计：左半净资产+右半总资产/负资产，VerticalDivider 分隔",
            "记账属性开关：单条记录可设「不计入收支」「不计入预算」",
            "DB 升级 v12→v13：新增 discount_off、discount_after、reimburse_after_amount 三列"
        )
    ),
    ChangelogVersion(
        version = "V3.2",
        date = "2026-06-26",
        items = listOf(
            "「我的」页面头部卡片：头像+诗意短句+签名+统计",
            "报销界面全面升级：分组选择、分组卡片、层级计算框架、金额实时显示",
            "报销账户完整集成：添加/保存/显示/金额计算，悬浮底部导航栏",
            "记账本 UI 优化：优惠卡片化、报销金额实时显示、分割线间距、输入框自动焦点",
            "数据管理独立页面：JSON/CSV 双格式导出导入、导出确认弹窗",
            "记账本附件功能：支持拍照/相册多选/文件多选，FileProvider 集成",
            "附件回收站：软删除机制、记录删除级联状态更新、防止孤立引用",
            "记账本主题色修复：selectedTab 状态提升、暗色模式背景跟随主题色"
        )
    ),
    ChangelogVersion(
        version = "V3.1",
        date = "2026-06-25",
        items = listOf(
            "记账模块 Repository 模式重构：统一数据访问层（AccountingRepository），AccountingDatabase 标记 internal，编译时强制检查 DB 访问规范",
            "记账模块全面跟随主题色：所有非背景颜色统一使用记账设置中的主题色（category_icon_color），支出/收入保持红绿不变",
            "记账首页资产标签页：账户管理（新增/编辑/删除）、资产构成饼图、分组卡片、月度统计卡片",
            "记一笔优惠计算功能：优惠前金额、优惠金额、优惠后金额自动计算，支持百分比优惠模式，开关状态持久化存储",
            "记一笔布局优化：日期添加日历图标、统一间距变量（elementSpacing）、账户选择即点即选（移除确认按钮）",
            "数据迁移优化：迁移完成后自动清理旧 JSON 文件和 SharedPreferences，启动时清理 databases/ 目录残留 DB"
        )
    ),
    ChangelogVersion(
        version = "V3.0",
        date = "2026-06-24",
        items = listOf(
            "记账本模块全新上线：底部导航栏、添加记账页面、自定义计算器键盘（含运算自动求值）、金额水平滑动查看",
            "记账本分类系统：参考 BeeCount 二级分类模式，22 个支出分类 + 14 个收入分类，FlowRow 自适应布局（每行 5 个，行间距 10dp）",
            "记账本二级分类数据：每个一级分类下含 3-8 个子分类，JSON 持久化存储，预留二级分类 UI 扩展空间",
            "日期时间选择器：TimeWheel 无限循环齿轮（totalItems = size * 10000），snapshotFlow 检测滚动停止自动吸中，点击直接居中",
            "Material Symbols 图标升级：35 个图标从 Material Icons 替换为 Material Symbols (Outlined 风格)，视觉更现代",
            "压缩包功能增强：点击压缩包直接浏览目录结构（7zzs l -ba），支持密码压缩包，会话状态持久化，显示压缩后大小",
            "文件压缩功能重写：通过 7zzs 静态二进制实现，支持 zip/7z/tar/tar.gz/tar.bz2/tar.xz，ZIP 支持 AES-256 加密",
            "LSPosed 模块框架集成：集成 libxposed API 100，通过系统属性检测 Xposed 状态",
            "数据存储规范统一：所有 SharedPreferences 通过 AppDataPaths 管理，禁止硬编码"
        )
    ),
    ChangelogVersion(
        version = "V2.9",
        date = "2026-06-23",
        items = listOf(
            "文件管理器解压功能：支持 zip/7z/tar 等格式，真实字节级进度条（7zzs l 预获取文件大小 + -bsp1 实时解析），三种权限路径",
            "压缩包密码检测重构：使用 7zzs l -slt 的 Encrypted 字段直接判断加密状态，替代不可靠的错误信息关键词匹配",
            "解压完成后自动刷新文件列表：聚焦面板无条件刷新，非聚焦面板在压缩包所在目录或解压目录时刷新",
            "历史记录优化：按路径去重，上限 100 条 FIFO 淘汰（MAX_HISTORY_SIZE 可配置）",
            "压缩包浏览功能：点击压缩包直接浏览目录结构（7zzs l -ba 构建目录树），支持密码压缩包，会话状态持久化（后台回收后自动恢复），显示文件/目录的压缩后大小",
            "文件压缩功能重写：通过 7zzs 静态二进制实现，支持 zip/7z/tar/tar.gz/tar.bz2/tar.xz 格式，ZIP 支持 AES-256 加密开关",
            "LSPosed 模块框架集成：集成 libxposed API 100，通过系统属性检测 Xposed 状态"
        )
    ),
    ChangelogVersion(
        version = "V2.8",
        date = "2026-06-19",
        items = listOf(
            "日记模块：创建日记本、卡片列表、独立详情页，DiaryDb 双副本存储（参照 VaultDb 模式），状态栏间距统一处理",
            "文件管理器 WebDAV 服务器快捷访问：添加 WebDAV 连接配置，支持远程文件浏览",
            "WiFi 模块增强：WiFi 扫描（含排序）、已保存密码查看（Root）、WiFi 连接功能",
            "APK 信息弹窗：点击 APK 文件可查看应用名称、版本、包名、签名等详细信息",
            "图片查看器重构：PhotoView 替换为 Compose 原生方案（ZoomableImageState），集成 telephoto 库",
            "文件操作架构重构：复制/移动/删除参考 MaterialFiles 架构重写，支持进度系统与前台服务",
            "解压弹窗重构：自定义 Dialog + 路径编辑 + 另一窗口路径选择",
            "文件夹大小统计算法优化：双内存 A/B 架构，统计过程可取消",
            "Root 挂载空间状态：进入文件管理器时显示各挂载点容量信息",
            "压缩包浏览与解压功能已移除（仅保留工具箱占位按钮）",
            "WiFi 破解模块已移除（仅保留扫描和连接）"
        )
    ),
    ChangelogVersion(
        version = "V2.7",
        date = "2026-06-15",
        items = listOf(
            "文件管理器多选功能：滑动选中（25% 触发 / 50% 极限）+ 范围选中 + 批量操作（复制/移动/删除/属性），底部工具栏动画（+ 旋转变 × 平移到正中），5 个功能按钮（全选/反选/取消/筛选/唤醒）",
            "移动/复制确认弹窗：选择目标操作后弹出确认框，显示文件数量，取消/确认双按钮",
            "扩展属性 (chattr/lsattr) 支持：批量读取目录内文件的 i/a 标志并在列表中显示，属性面板可编辑扩展属性，FUSE 路径自动转换为真实路径",
            "UID/GID 选择器重构：内置完整 AOSP AID 映射（116 条系统 UID/GID），pm list packages 动态获取应用 UID，当前值排序到顶部并高亮",
            "软链接全面支持：识别、导航、stat 回退、类型检测统一使用 Os.stat() 穿透，ls 权限位作为辅助判断",
            "新增「网络」模块及 WiFi 子模块（含免责声明）",
            "权限诊断功能：Debug 模式下文件管理器 Root 诊断，应用启动时权限有效性自动检测，Root 权限申请触发 Magisk 授权弹窗",
            "文件管理器工具栏重组 + 属性面板递归统计（文件/文件夹数量、总大小）",
            "后退/前进按钮逻辑重构 + 滚动位置恢复优化（key() 重建 LazyListState 消除闪帧）",
            "权限配置统一：合并 security_prefs 与 special_permissions 为单一配置源",
            "时间戳统一使用中国时间 (UTC+8)，诊断信息 Debug/非Debug 弹窗分离 + 5 秒自动消失"
        )
    ),
    ChangelogVersion(
        version = "V2.6",
        date = "2026-06-12",
        items = listOf(
            "文件管理器 Shell 路由重构：受保护路径统一走 Shell（Root/Shizuku/普通三路路由），ls -lap 解析改用逐字符定位精确保留多空格",
            "文件夹大小统计全面重写：全量+差异自底向上算法，BFS 无条件遍历所有目录后从叶子逐级 mtime 对比 + delta 冒泡，确保深层变化不被遗漏",
            "大小统计持久 Shell 优化：单次 find 递归扫描替代逐目录 listChildren，Binder IPC 延迟 >300ms 时 5 秒冷却倒计时保护",
            "大小统计全程状态提示：正在统计→进度条→完成三个阶段，支持取消和部分结果保存",
            "大小统计中断保护：BFS 中断时跳过清理阶段避免误删缓存，PermissionDenied 弹出保存对话框",
            "大小统计长按选项：长按「大小刷新」弹出三选项——删除缓存并刷新/忽略缓存重新统计/取消",
            "formatBytes 统一 1024 进制 + 返回上级目录恢复滚动位置"
        )
    ),
    ChangelogVersion(
        version = "V2.5",
        date = "2026-06-09",
        items = listOf(
            "Shizuku UserService 迁移：从已废弃的 newProcess（fork+exec ~50ms）迁移到 UserService（Binder IPC ~1ms），异步绑定不阻塞主线程，首次连接 Toast 提示耗时",
            "Android/data & obb 完整访问：Shizuku/Root 双引擎支持受保护目录浏览、文件夹大小统计、进入时自动后台计算",
            "Shell 诊断日志：设置 debug_mode 开关控制，写入外部存储 shell_debug.log，记录命令/原始 Base64/解码结果，用于排查 Shizuku 执行问题",
            "文件属性增强：显示用户/组名称，ls -lap 解析正确跳过 7 个元数据字段",
            "文件夹大小显示优化：受保护目录 Shizuku 可用时显示 -- 而非红色 ✕",
            "JPEG XL 图片压缩：支持 .jxl 文件查看、JPEG 转 JXL 无损压缩、JXL 打包 ZIP",
            "压缩包内置浏览：ZIP/7z/RAR/TAR 浏览，50MB 按需解压阈值，内存/磁盘双策略",
            "图片查看器增强：双击 1x↔2x 切换、边缘滑动翻页、缩放时禁用翻页防冲突",
            "外部打开警告对话框：非图片/视频/音频/文本文件打开前二次确认",
            "历史记录优化：文件点击导航到父目录并高亮目标，外部打开使用应用选择器",
            "CrashActivity Material 配色 + 错误提示区分「文件夹不存在」和「权限不足」"
        )
    ),
    ChangelogVersion(
        version = "V2.4",
        date = "2026-06-05",
        items = listOf(
            "回收站功能：删除文件默认移至回收站（Android/media 卸载保留），支持子文件夹导航、永久删除、恢复到原位置，同名冲突自动追加时间戳",
            "文件管理器左侧抽屉菜单：汉堡按钮展开 70% 宽度侧栏，本地/工具分区，工具区含回收站入口",
            "权限编辑弹窗：九宫格 checkbox 编辑 rwx 权限，用户/组选择器，chmod/chown 应用（需 Root）",
            "文件/文件夹属性弹窗：长按→属性查看名称、路径、类型、大小、权限、所有者、用户组、文件数等",
            "多格式压缩功能：支持 ZIP/7z/TAR/TAR.GZ/TAR.BZ2/TAR.XZ，ZIP 支持 AES-256 和 ZipCrypto 两种加密方式，实时进度条+取消+残余文件清理",
            "长按操作面板优化：第三行大小刷新/属性+压缩，第四行关于+分享，行排列更合理",
            "排序前大小统计提醒：以大小排序时检测未统计文件夹，提示先刷新再排序",
            "设置菜单新增「刷新当前列表大小」：统计当前目录下所有文件夹大小",
            "属性弹窗权限信息合并显示：所有者和用户组纯文本，权限行统一入口编辑",
            "所有弹窗和工具栏宽度统一为屏幕 80%",
            "卸载保留数据：android:hasFragileUserData 卸载时询问是否保留应用数据",
            "Coil 3 异步图片加载适配 + 大小排序文件夹分组优化"
        )
    ),
    ChangelogVersion(
        version = "V2.3",
        date = "2026-06-05",
        items = listOf(
            "内置文本编辑器：集成 Sora Editor，支持 Java/Kotlin/XML 等语法高亮，替代外部编辑器跳转",
            "图片查看器：PhotoView 手势缩放 + HorizontalPager 左右翻页，底部半透明页码指示器，缩放时禁用翻页防冲突",
            "Coil 异步图片加载：替换 BitmapFactory 同步解码，内存+磁盘双缓存（100MB），消除 500+ 图片列表卡顿",
            "文件/文件夹属性弹窗：长按→「关于」查看名称、路径、类型、大小、权限、所有者、用户组等详细信息",
            "排序菜单重新设计：居中弹窗替代下拉菜单，按名称/大小/修改时间排序，升降序独立标签",
            "大小排序优化：文件夹与文件分组排列（文件夹在前），使用 folderSizeDb 缓存数据加速",
            "导航自动刷新：切换目录时自动刷新文件列表，修复返回后文件状态不更新的关键 bug",
            "0 字节文件显示「0 B」而非空白",
            "文件管理器顶栏 6dp 阴影，与底部工具栏视觉统一",
            "文件管理器滚动保持 + libsu 迁移 + safeDelete 安全删除"
        )
    ),
    ChangelogVersion(
        version = "V2.2.1",
        date = "2026-06-04",
        items = listOf(
            "文件管理器排序菜单：级联子菜单，按名称/大小/修改时间/创建时间排序，升降序上下文标签（A到Z/小到大等），设置持久化",
            "文件管理器书签功能：设置菜单添加书签，底部面板「书签」标签与「历史记录」并列，长按删除，新书签置顶，持久化存储",
            "历史/书签面板动画优化：左右滑动切换标签，AnimatedContent 丝滑滑入滑出动画，标题栏空白区域触摸拦截",
            "全模块存储路径统一收归 AppDataPaths：SharedPreferences 常量、FolderSizeDb、crash_tmp 迁移至规范路径，双标志位平滑迁移",
            "返回上一级独立为专属条目：不再混入文件列表，点击不记录历史记录",
            "底部栏6图标均匀分布修复：改用 weight(1f) 保证等宽分配"
        )
    ),
    ChangelogVersion(
        version = "V2.2",
        date = "2026-06-02",
        items = listOf(
            "RP-Hub 全面适配：NanoHTTPD 本地服务器方案，CDN 按需检测与自动回退，数据目录迁移至内部存储，vendor 本地化及运行时更新机制",
            "RP-Hub Debug 面板：从 WebView JS 层迁移至 Android 应用层，支持网络请求监控、资源状态诊断、错误堆栈捕获",
            "RP-Hub 下载管理：内置下载管理器，更新公告抑制（patches 注入），WebView Cookie 启用",
            "文件管理器彩色图标：按后缀显示 7 种类型彩色矢量图标（文档/图片/视频/音频/压缩包/APK/代码），APK 文件读取真实应用图标",
            "文件管理器布局优化：行高/对齐/文件名左对齐、底部信息全宽对齐、刷新按钮仅刷新对焦侧、文件夹大小显示规则统一",
            "文件夹大小计算系统：首次打开自动统计，刷新后自动更新列表，空文件夹显示 0MB，权限不足显示红色✕",
            "保险箱体验优化：保持打开时长、交互改为居中弹窗、文件夹显示日期+大小、时长选择器动画线性平滑",
            "WebView 文件选择器 + PNG 角色卡自动备份",
            "全局未捕获异常兜底：ErrorReportActivity 替代闪退，DEBUG_MODE 功能权限仅密钥1可访问",
            "关于页面与更新日志：设置→开发者→关于，可折叠版本更新日志列表"
        )
    ),
    ChangelogVersion(
        version = "V2.1",
        date = "2026-05-31",
        items = listOf(
            "保险箱存储用量统计：首次打开自动统计，导入后增量更新，删除后自动减去，列表显示真实大小",
            "密码校验兜底：KDF 自动回退（Argon2id / PBKDF2），友好错误提示",
            "FA 下载器匹配模式：新增关键词匹配和正则表达式两种过滤模式",
            "FA 下载器缓存重构：「使用缓存」改为「记录缓存」，缓存格式升级，下载前检查弹窗",
            "DeviantArt 批量下载器：全新模块，含登录页、下载器、ViewModel",
            "加密进度 UI 修复：进度条边框，导入后弹窗提示，文件夹导入走 TaskManager",
            "圆角 UI 改造：导入对话框 24dp 圆角，修改密码页包裹圆角 Card",
            "光晕效果调优：降低 30%，浅色主题修复"
        )
    ),
    ChangelogVersion(
        version = "V2.0",
        date = "2026-05-30",
        items = listOf(
            "加密进度监控系统：顶部进度条（4色状态）、底部面板、后台任务管理",
            "统一光晕卡片设计系统：提取为独立组件库，5个可复用组件，9个屏幕改造",
            "Native 崩溃处理器：SIGSEGV/SIGABRT 信号捕获，pipe 传递崩溃信息",
            "保险箱功能增强：卡片显示最后修改/打开时间，SAF URI + DocumentTree 导入",
            "FA 下载器优化：系列检测（按物种区分）、部分下载、HashMap O(1) 性能优化"
        )
    ),
    ChangelogVersion(
        version = "V1.9",
        date = "2026-05-30",
        items = listOf(
            "FA 下载器：网络状态检测、预扫描功能、页面汇总置顶、每页进度实时显示",
            "重排序优化：按标题分组排序，消除 O(n²) 调用，整体降至 O(n log n)",
            "首次启动申请通知权限，修复双作者名问题",
            "修复翻页正则匹配、图片 URL 作者校验、重排序 FAID 解析"
        )
    ),
    ChangelogVersion(
        version = "V1.8",
        date = "2026-05-30",
        items = listOf(
            "FA 下载器四项重构：缓存过滤、下载后重命名、失败重试、智能差量重排序",
            "增量下载：FAID+作者+标题三重匹配跳过已下载",
            "目录冲突检测兼容大小写不敏感存储",
            "修复命名模式持久化、缓存标题补全问题"
        )
    ),
    ChangelogVersion(
        version = "V1.7",
        date = "2026-05-30",
        items = listOf(
            "FA 下载器增强：自定义编号命名、作者历史记录、收集逻辑重构、暂停/继续",
            "HTTP 层统一，修复 CDN 403 下载失败",
            "修复 Cookie 传递、ConcurrentHashMap 并发、实时日志、文件夹大小写匹配"
        )
    ),
    ChangelogVersion(
        version = "V1.6",
        date = "2026-05-29",
        items = listOf(
            "鉴权系统：NDK/JNI/Keystore 三道防线，漏洞修复与业务层防御增强",
            "FA 下载器优化：登录/预览/边下边收集，按作者分目录 + 多线程 + 命名模式",
            "修复 WebView 缓存、Cookie 检测、密码验证、StrongBox 异常"
        )
    ),
    ChangelogVersion(
        version = "V1.5",
        date = "2026-05-28",
        items = listOf(
            "全新 FA (FurAffinity) 批量下载器：按作者分目录、多线程有序下载、自定义命名",
            "FA 登录优化：缓存 Cookie 优先，失效提示重新登录",
            "Shizuku 集成：特权 shell 命令执行，重命名权限级别",
            "修复 WRITE_SECURE_SETTINGS 检测、DocumentFile 参数类型"
        )
    ),
    ChangelogVersion(
        version = "V1.4",
        date = "2026-05-27",
        items = listOf(
            "特殊权限管理系统：引导向导，5种权限级别，实际校验",
            "三种应用权限管理模式：普通、AppOps、高级",
            "文件管理器重新设计：权限显示、浮动工具栏、创建文件/文件夹、导航历史",
            "设置页面优化：紧凑 UI、分组卡片设计",
            "日间/夜间主题切换"
        )
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("更新日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            itemsIndexed(CHANGELOG) { index, version ->
                ChangelogVersionCard(
                    version = version,
                    initiallyExpanded = index == 0
                )
            }
        }
    }
}

@Composable
private fun ChangelogVersionCard(
    version: ChangelogVersion,
    initiallyExpanded: Boolean
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "arrow_rotation"
    )

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            // 版本头部：点击展开/收起
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = version.version,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = version.date,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 展开内容
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(bottom = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    version.items.forEach { item ->
                        Row(
                            modifier = Modifier.padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
