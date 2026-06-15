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
