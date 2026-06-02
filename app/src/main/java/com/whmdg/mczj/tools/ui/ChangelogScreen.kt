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
            "FA 下载器缓存重构："使用缓存"改为"记录缓存"，缓存格式升级，下载前检查弹窗",
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
