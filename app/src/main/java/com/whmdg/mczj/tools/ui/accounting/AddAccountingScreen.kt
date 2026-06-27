package com.whmdg.mczj.tools.ui.accounting

import com.whmdg.mczj.tools.MainActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.twotone.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import coil3.compose.AsyncImage
import java.io.File
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountingScreen(onBack: () -> Unit, bookName: String, recordId: String? = null) {
    val types = listOf("支出", "收入", "转账", "债务")
    val context = LocalContext.current
    // 初始化 AI 预测器
    LaunchedEffect(Unit) { NotePredictor.ensureInitialized(context) }

    // 动态设置 adjustNothing：进入时禁用系统自动调整，退出时恢复
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        val originalMode = window?.attributes?.softInputMode
        window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        onDispose {
            originalMode?.let { window?.setSoftInputMode(it) }
        }
    }

    // 编辑模式：加载已有记录
    val editingRecord = remember(recordId) {
        if (recordId != null) {
            AccountingRecordDb.load(context).records.find { it.id == recordId }
        } else null
    }

    var selectedType by remember { mutableIntStateOf(
        editingRecord?.let { types.indexOf(it.type).coerceAtLeast(0) } ?: 0
    ) }
    var amount by remember { mutableStateOf(editingRecord?.amount ?: "0") }
    var note by remember { mutableStateOf(editingRecord?.note ?: "") }
    val initCal = remember(editingRecord) {
        Calendar.getInstance().apply {
            if (editingRecord != null) timeInMillis = editingRecord.happenedAt
        }
    }
    var selectedYear by remember { mutableIntStateOf(initCal.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableIntStateOf(initCal.get(Calendar.MONTH)) }
    var selectedDay by remember { mutableIntStateOf(initCal.get(Calendar.DAY_OF_MONTH)) }
    var selectedHour by remember { mutableIntStateOf(initCal.get(Calendar.HOUR_OF_DAY)) }
    var selectedMinute by remember { mutableIntStateOf(initCal.get(Calendar.MINUTE)) }
    val selectedDate = remember(selectedYear, selectedMonth, selectedDay, selectedHour, selectedMinute) {
        "%04d-%02d-%02d %02d:%02d".format(selectedYear, selectedMonth + 1, selectedDay, selectedHour, selectedMinute)
    }
    var showDatePicker by remember { mutableStateOf(false) }
    var showDiscountDialog by remember { mutableStateOf(false) }
    // 优惠数据：null 表示未设置
    var discountBefore by remember { mutableStateOf<String?>(editingRecord?.discountBefore) }
    var discountOff by remember { mutableStateOf<String?>(null) }
    var discountAfter by remember { mutableStateOf<String?>(null) }
    val hasDiscount = discountAfter != null
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val infoRowHeight = screenHeight * 0.05f
    // 元素间距（统一调整用）
    val elementSpacing = 10.dp

    // 账户选择
    val accounts = remember { AccountingRepository.getAllAccounts(context) }
    val accountMap = remember(accounts) { accounts.associateBy { it.id } }
    // 上次使用的账户
    val lastAccountId = AccountingRepository.getLastAccountId(context)
    var selectedAccountId by remember {
        mutableStateOf(editingRecord?.accountId ?: lastAccountId)
    }
    var showAccountDialog by remember { mutableStateOf(false) }
    // 当前选中的账户名称和图标
    val selectedAccount = selectedAccountId?.let { accountMap[it] }
    val selectedAccountName = selectedAccount?.name ?: "账户"
    val selectedAccountSvg = selectedAccount?.let { accountTypeConfigs[it.type]?.svgPath }

    // 报销账户选择
    val reimbursementAccounts = remember { AccountingRepository.getReimbursementAccounts(context) }
    val reimbursementAccountMap = remember(reimbursementAccounts) { reimbursementAccounts.associateBy { it.id } }
    var selectedReimbursementId by remember {
        mutableStateOf<String?>(editingRecord?.reimbursementAccountId)
    }
    var showReimbursementDialog by remember { mutableStateOf(false) }

    // 附件
    var attachments by remember { mutableStateOf(editingRecord?.attachments ?: emptyList()) }
    var showAttachmentSheet by remember { mutableStateOf(false) }

    // 属性设置
    var excludeFromStats by remember { mutableStateOf(editingRecord?.excludeFromStats ?: false) }
    var excludeFromBudget by remember { mutableStateOf(editingRecord?.excludeFromBudget ?: false) }
    var showAttrSheet by remember { mutableStateOf(false) }
    // 拍照临时 URI
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }

    // 拍照启动器（先定义，供权限回调引用）
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraPhotoUri != null) {
            val info = AccountingRepository.storeAttachmentFromCamera(context, cameraPhotoUri!!)
            attachments = attachments + info
        }
    }
    // 相机权限启动器
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val tmpFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", tmpFile
            )
            cameraPhotoUri = uri
            cameraLauncher.launch(uri)
        } else {
            Toast.makeText(context, "相机权限被拒绝，无法拍照", Toast.LENGTH_SHORT).show()
        }
    }
    // 相册多选启动器
    val albumLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        for (uri in uris) {
            val name = getFileName(context, uri)
            val info = AccountingRepository.storeAttachment(context, uri, name)
            attachments = attachments + info
        }
    }
    // 文件多选启动器
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        for (uri in uris) {
            val name = getFileName(context, uri)
            val info = AccountingRepository.storeAttachment(context, uri, name)
            attachments = attachments + info
        }
    }

    // 从 JSON 动态加载分类数据（跟随选中的记账类型切换）
    val categoryDb = remember { AccountingCategoryDb.ensureDefault(context) }
    // 分类 ID → (名称, 图标) 查找表
    val categoryLookup = remember(categoryDb) {
        val map = mutableMapOf<String, Pair<String, String>>()
        for ((_, typeMap) in categoryDb.pages) {
            for ((_, cats) in typeMap) {
                for (cat in cats) {
                    map[cat.id] = cat.name to cat.icon
                    for (child in cat.children) {
                        map[child.id] = child.name to child.icon
                    }
                }
            }
        }
        map
    }

    val currentType = types[selectedType]
    val categories = remember(selectedType) {
        categoryDb.getCategories("记账页", currentType)
    }
    // 编辑模式：预选分类（优先二级分类 id）
    var selectedCategory by remember {
        mutableStateOf<String?>(
            editingRecord?.subcategoryId ?: editingRecord?.categoryId
        )
    }
    var expandedCategory by remember {
        // 如果有二级分类，展开其父分类
        val initRecord = editingRecord
        mutableStateOf<String?>(
            if (initRecord != null && initRecord.subcategoryId != null) initRecord.categoryId else null
        )
    }
    // 备注建议（含关联分类）
    data class NoteSuggestion(val note: String, val catId: String, val subId: String?, val catName: String, val subName: String?, val score: Int)
    // 分类滚动目标（点击建议后设置）
    var scrollTarget by remember { mutableStateOf<String?>(null) }
    // 展开/折叠标记：仅在用户点击分类时为 true，防止键盘弹出时误触发滚动
    var isTogglingCategory by remember { mutableStateOf(false) }

    // 保存记录的辅助函数
    fun saveCurrentRecord(): Boolean {
        if (amount == "0" || amount.isEmpty()) {
            Toast.makeText(context, "请输入金额", Toast.LENGTH_SHORT).show()
            return false
        }
        if (selectedCategory == null) {
            Toast.makeText(context, "请选择分类", Toast.LENGTH_SHORT).show()
            return false
        }
        // 计算最终金额（处理运算表达式如 "15+30"）
        val finalAmount = run {
            val ops = listOf("+", "-", "*", "÷")
            val op = ops.firstOrNull { it in amount.drop(1) }
            if (op != null) {
                val idx = amount.indexOf(op, 1)
                val n1 = amount.substring(0, idx).toDoubleOrNull()
                val n2 = amount.substring(idx + 1).toDoubleOrNull()
                if (n1 != null && n2 != null) {
                    val r = when (op) {
                        "+" -> n1 + n2
                        "-" -> n1 - n2
                        "*" -> n1 * n2
                        "÷" -> if (n2 != 0.0) n1 / n2 else n1
                        else -> n1
                    }
                    if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
                } else amount
            } else amount
        }
        if (finalAmount == "0") {
            Toast.makeText(context, "请输入有效金额", Toast.LENGTH_SHORT).show()
            return false
        }
        // 查找一级分类 id 和二级分类 id
        var parentId: String? = null
        var subId: String? = null
        for (cat in categories) {
            if (cat.id == selectedCategory) {
                parentId = cat.id
                break
            }
            for (child in cat.children) {
                if (child.id == selectedCategory) {
                    parentId = cat.id
                    subId = child.id
                    break
                }
            }
            if (parentId != null) break
        }
        val cal = Calendar.getInstance()
        cal.set(selectedYear, selectedMonth, selectedDay, selectedHour, selectedMinute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val record = AccountingRecord(
            id = editingRecord?.id ?: java.util.UUID.randomUUID().toString(),
            bookName = bookName,
            type = currentType,
            amount = finalAmount,
            categoryId = parentId ?: selectedCategory!!,
            subcategoryId = subId,
            note = note,
            happenedAt = cal.timeInMillis,
            accountId = selectedAccountId,
            discountBefore = discountBefore,
            reimbursementAccountId = selectedReimbursementId,
            attachments = attachments,
            excludeFromStats = excludeFromStats,
            excludeFromBudget = excludeFromBudget
        )
        val db = AccountingRecordDb.load(context)
        if (editingRecord != null) {
            db.update(record).save(context)
        } else {
            db.add(record).save(context)
        }
        // AI 训练：用当前记录更新模型
        if (note.isNotEmpty()) {
            NotePredictor.train(context, record)
        }
        // 保存本次使用的账户 id
        if (selectedAccountId != null) {
            AccountingRepository.setLastAccountId(context, selectedAccountId!!)
        }
        return true
    }

    // 读取图标主题色设置
    val iconColorHex = remember { getCategoryIconColor(context) }
    val iconThemeColor = remember(iconColorHex) {
        try { Color(android.graphics.Color.parseColor(iconColorHex)) }
        catch (_: Exception) { Color(0xFF00BCD4) }
    }

    // 切换记账类型时重置选中和展开（跳过首次，保留编辑模式预选值）
    var typeChanged by remember { mutableStateOf(false) }
    LaunchedEffect(selectedType) {
        if (typeChanged) {
            selectedCategory = null
            isTogglingCategory = true
            expandedCategory = null
        }
        typeChanged = true
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 50dp 功能栏
            Surface(
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                types.forEachIndexed { index, type ->
                    TextButton(onClick = { selectedType = index }) {
                        Text(
                            text = type,
                            color = if (selectedType == index)
                                iconThemeColor
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = bookName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
            } // Surface

            // 分类选择区（参考 BeeCount：4列网格 + 二级分类原地展开）
            val itemsPerRow = 4
            val primaryIconSize = 56.dp
            val subIconSize = 48.dp
            val screenWidth = LocalConfiguration.current.screenWidthDp.dp
            val itemWidth = screenWidth / itemsPerRow
            val scrollState = rememberScrollState()

            // 点击建议后自动展开并滚动到目标分类
            val densityForScroll = LocalDensity.current
            LaunchedEffect(scrollTarget) {
                val target = scrollTarget ?: return@LaunchedEffect
                scrollTarget = null
                delay(250)
                val idx = categories.indexOfFirst { cat ->
                    cat.id == target || cat.children.any { it.id == target }
                }
                if (idx < 0) return@LaunchedEffect
                val d = densityForScroll
                val rowIdx = idx / itemsPerRow
                val iconSizePx = with(d) { primaryIconSize.roundToPx() }
                val labelPx = with(d) { 16.dp.roundToPx() }
                val spacingPx = with(d) { 16.dp.roundToPx() }
                val subCardPx = with(d) { 80.dp.roundToPx() }
                val offset = rowIdx * (iconSizePx + labelPx + spacingPx) + if (expandedCategory != null) subCardPx else 0
                scrollState.animateScrollTo(offset)
            }

            // 展开/折叠二级分类时自动滚动，保证内容不被裁剪、不留空白
            // 仅在用户展开/折叠分类时触发，键盘弹出导致的高度变化不触发
            var prevMaxValue by remember { mutableIntStateOf(0) }
            LaunchedEffect(scrollState) {
                snapshotFlow { scrollState.maxValue }
                    .collect { newMax ->
                        val oldMax = prevMaxValue
                        prevMaxValue = newMax
                        if (!isTogglingCategory) return@collect
                        isTogglingCategory = false
                        if (oldMax == 0) return@collect
                        if (newMax < oldMax) {
                            if (scrollState.value > newMax) {
                                scrollState.animateScrollTo(newMax)
                            }
                        } else {
                            delay(150)
                            scrollState.animateScrollTo(oldMax)
                        }
                    }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(vertical = 8.dp, horizontal = 12.dp)
            ) {
                // 按每行4个分组显示一级分类
                var i = 0
                while (i < categories.size) {
                    val rowEnd = (i + itemsPerRow).coerceAtMost(categories.size)
                    val rowItems = categories.subList(i, rowEnd)

                    // 一级分类行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        rowItems.forEach { cat ->
                            val isSelected = selectedCategory == cat.id
                            val isExpanded = expandedCategory == cat.id
                            val hasChildren = cat.children.isNotEmpty()

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(itemWidth)
                                    .clickable {
                                        if (hasChildren) {
                                            isTogglingCategory = true
                                            expandedCategory = if (isExpanded) null else cat.id
                                        } else {
                                            selectedCategory = if (isSelected) null else cat.id
                                            isTogglingCategory = true
                                            expandedCategory = null
                                        }
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    // 图标容器
                                    Box(
                                        modifier = Modifier
                                            .size(primaryIconSize)
                                            .clip(RoundedCornerShape(28.dp))
                                            .background(
                                                if (isSelected || isExpanded)
                                                    iconThemeColor.copy(alpha = 0.25f)
                                                else
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CategoryIcon(
                                            icon = cat.icon,
                                            size = 24.dp,
                                            tint = iconThemeColor
                                        )
                                    }
                                    // 有子分类标记：右下角三个点
                                    if (hasChildren) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .offset(x = 4.dp, y = 4.dp)
                                                .size(18.dp)
                                                .clip(RoundedCornerShape(9.dp))
                                                .background(
                                                    if (isExpanded)
                                                        iconThemeColor.copy(alpha = 0.25f)
                                                    else
                                                        MaterialTheme.colorScheme.surfaceVariant
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.MoreHoriz,
                                                contentDescription = "展开",
                                                modifier = Modifier.size(12.dp),
                                                tint = if (isExpanded)
                                                    iconThemeColor
                                                else
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = cat.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    color = if (isSelected || isExpanded)
                                        iconThemeColor
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // 补齐空位（保持对齐）
                        repeat(itemsPerRow - rowItems.size) {
                            Spacer(Modifier.width(itemWidth))
                        }
                    }

                    // 检查这一行中是否有展开的分类，显示二级分类卡片
                    val expandedInRow = rowItems.find { it.id == expandedCategory && it.children.isNotEmpty() }
                    if (expandedInRow != null) {
                        Spacer(Modifier.height(8.dp))
                        SubcategoryCard(
                            parentName = expandedInRow.name,
                            children = expandedInRow.children,
                            selectedId = selectedCategory,
                            themeColor = iconThemeColor,
                            onSelect = { childId ->
                                selectedCategory = childId
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                    } else {
                        Spacer(Modifier.height(12.dp))
                    }

                    i = rowEnd
                }
            }

            // 第一行：左侧20%空 | 中间60%备注输入 | 右侧20%金额
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(infoRowHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.fillMaxHeight().weight(0.2f))
                // 备注建议（包含关联分类）
                var noteSuggestions by remember { mutableStateOf<List<NoteSuggestion>>(emptyList()) }
                var showSuggestions by remember { mutableStateOf(false) }
                // 用户点击建议后置 true，跳过下一次计算
                var justSelected by remember { mutableStateOf(false) }
                var noteBoxWidthPx by remember { mutableIntStateOf(0) }
                val noteDensity = LocalDensity.current
                LaunchedEffect(note) {
                    if (note.isEmpty()) {
                        noteSuggestions = emptyList()
                        showSuggestions = false
                        return@LaunchedEffect
                    }
                    if (justSelected) {
                        justSelected = false
                        showSuggestions = false
                        return@LaunchedEffect
                    }
                    delay(100)
                    val predictions = if (NotePredictor.hasEnoughData()) {
                        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                        val currentAmount = amount.toFloatOrNull() ?: 0f
                        val list = NotePredictor.predict(
                            types[selectedType], selectedCategory ?: "",
                            currentAmount, currentHour, note
                        ).toMutableList()
                        if (list.isEmpty() || list.first().score < 50) {
                            val aiNote = NotePredictor.createAiNote(
                                types[selectedType], selectedCategory ?: "",
                                currentAmount, currentHour, note
                            )
                            if (aiNote != null) {
                                list.add(0, NotePredictor.Prediction(aiNote, 50, true))
                            }
                        }
                        list
                    } else {
                        NotePredictor.predictFromRecent(context, note)
                    }
                    // 按备注文本收集关联分类（每个备注的每个分类组合占一行）
                    val rawPredictions = predictions.take(10)
                    val allRecords = AccountingRepository.getAllRecords(context)
                    val suggestions = mutableListOf<NoteSuggestion>()
                    for (pred in rawPredictions) {
                        val matching = allRecords.filter { it.note == pred.note }
                        val seenPairs = mutableSetOf<Pair<String, String?>>()
                        for (r in matching) {
                            val key = r.categoryId to r.subcategoryId
                            if (seenPairs.add(key)) {
                                val catName = categoryLookup[r.categoryId]?.first ?: r.categoryId
                                val subName = r.subcategoryId?.let { categoryLookup[it]?.first }
                                suggestions.add(NoteSuggestion(pred.note, r.categoryId, r.subcategoryId, catName, subName, pred.score))
                            }
                        }
                    }
                    noteSuggestions = suggestions
                    showSuggestions = noteSuggestions.isNotEmpty()
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.6f)
                        .padding(horizontal = 4.dp)
                        .onGloballyPositioned { noteBoxWidthPx = it.size.width },
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            if (note.isEmpty()) {
                                Text(
                                    "点击输入备注",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    )
                    // AI 预测弹出层
                    if (showSuggestions && noteSuggestions.isNotEmpty()) {
                        Popup(
                            alignment = Alignment.TopStart,
                            onDismissRequest = { showSuggestions = false }
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                shadowElevation = 8.dp,
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .width(with(noteDensity) { noteBoxWidthPx.toDp() })
                                    .heightIn(max = screenHeight * 0.3f)
                            ) {
                                LazyColumn(
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    items(noteSuggestions.size) { idx ->
                                        val s = noteSuggestions[idx]
                                        val displayCat = if (s.subName != null) "${s.catName}-${s.subName}" else s.catName
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    NotePredictor.recordHit(s.note)
                                                    justSelected = true
                                                    showSuggestions = false
                                                    note = s.note
                                                    selectedCategory = s.subId ?: s.catId
                                                    isTogglingCategory = true
                                                    expandedCategory = if (s.subId != null) s.catId else null
                                                    scrollTarget = s.subId ?: s.catId
                                                }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = displayCat,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.weight(0.7f),
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${s.score}%",
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.weight(0.3f),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                                color = when {
                                                    s.score >= 80 -> Color(0xFF4CAF50)
                                                    s.score >= 60 -> MaterialTheme.colorScheme.primary
                                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.2f),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    val scrollState = rememberScrollState()
                    // 金额变化时自动滚到最右
                    LaunchedEffect(amount) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                    Text(
                        text = amount,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier
                            .horizontalScroll(scrollState)
                            .padding(end = 8.dp)
                    )
                }
            }

            val selectedReimb = selectedReimbursementId?.let { reimbursementAccountMap[it] }
            HorizontalDivider(
                color = iconThemeColor,
                thickness = 1.dp,
                modifier = Modifier.onGloballyPositioned { coords ->
                    MainActivity.element4TopPx = coords.localToWindow(Offset.Zero).y
                }
            )

            // 第二行：5 个元素均分，SpaceEvenly 自动分配间距
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(infoRowHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // 日期时间
                Row(
                    modifier = Modifier.weight(1f).clickable { showDatePicker = true },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = "选择日期", modifier = Modifier.size(16.dp), tint = iconThemeColor)
                    Spacer(Modifier.width(4.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%02d/%02d".format(selectedMonth + 1, selectedDay), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text("%02d:%02d".format(selectedHour, selectedMinute), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // 支付账户
                Row(
                    modifier = Modifier.weight(1f).clickable { showAccountDialog = true },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedAccountSvg != null) {
                        AsyncImage(model = selectedAccountSvg, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(selectedAccountName, style = MaterialTheme.typography.labelSmall.copy(fontSize = 15.sp), color = if (selectedAccount != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 优惠
                Row(
                    modifier = Modifier.weight(1f).clickable { showDiscountDialog = true },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Discount, contentDescription = "优惠", modifier = Modifier.size(16.dp), tint = iconThemeColor)
                    Spacer(Modifier.width(2.dp))
                    Text(if (hasDiscount) "-${discountOff}" else "优惠", style = MaterialTheme.typography.labelSmall, color = iconThemeColor)
                }
                // 报销账户
                Row(
                    modifier = Modifier.weight(1f).clickable { showReimbursementDialog = true },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Receipt, contentDescription = "报销账户", modifier = Modifier.size(16.dp), tint = iconThemeColor)
                    Spacer(Modifier.width(2.dp))
                    Text(selectedReimb?.name ?: "不报销", style = MaterialTheme.typography.labelSmall, color = if (selectedReimb != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 附件
                Row(
                    modifier = Modifier.weight(1f).clickable { showAttachmentSheet = true },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Link, contentDescription = "附件", modifier = Modifier.size(16.dp), tint = iconThemeColor)
                    Spacer(Modifier.width(2.dp))
                    Text(if (attachments.isNotEmpty()) "附件(${attachments.size})" else "附件", style = MaterialTheme.typography.labelSmall, color = if (attachments.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 属性
                Row(
                    modifier = Modifier.weight(1f).clickable { showAttrSheet = true },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = "属性", modifier = Modifier.size(16.dp), tint = iconThemeColor)
                    Spacer(Modifier.width(2.dp))
                    val attrActive = excludeFromStats || excludeFromBudget
                    Text("属性", style = MaterialTheme.typography.labelSmall, color = if (attrActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 附件选择 BottomSheet
            if (showAttachmentSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showAttachmentSheet = false },
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // 相机
                        ListItem(
                            headlineContent = { Text("拍照") },
                            leadingContent = {
                                Icon(Icons.Outlined.CameraAlt, contentDescription = null, tint = iconThemeColor)
                            },
                            modifier = Modifier.clickable {
                                showAttachmentSheet = false
                                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                            }
                        )
                        // 相册
                        ListItem(
                            headlineContent = { Text("从相册选择") },
                            leadingContent = {
                                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = iconThemeColor)
                            },
                            modifier = Modifier.clickable {
                                showAttachmentSheet = false
                                albumLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        )
                        // 文件
                        ListItem(
                            headlineContent = { Text("选择文件") },
                            leadingContent = {
                                Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = iconThemeColor)
                            },
                            modifier = Modifier.clickable {
                                showAttachmentSheet = false
                                fileLauncher.launch(arrayOf("*/*"))
                            }
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }

            // 属性 BottomSheet
            if (showAttrSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showAttrSheet = false },
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // 不计入收支
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { excludeFromStats = !excludeFromStats }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = excludeFromStats,
                                onCheckedChange = { excludeFromStats = it },
                                colors = CheckboxDefaults.colors(checkedColor = iconThemeColor)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("不计入收支", style = MaterialTheme.typography.bodyLarge)
                                Text("收入和支出统计将跳过此记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        // 不计入预算
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { excludeFromBudget = !excludeFromBudget }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = excludeFromBudget,
                                onCheckedChange = { excludeFromBudget = it },
                                colors = CheckboxDefaults.colors(checkedColor = iconThemeColor)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("不计入预算", style = MaterialTheme.typography.bodyLarge)
                                Text("此记录不影响预算计算", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }

            // 键盘
            val canFinish = selectedAccountId != null && amount != "0" && amount.isNotEmpty() && selectedCategory != null
            Surface(shadowElevation = 2.dp) {
            CalculatorKeyboard(
                onInput = { key ->
                    amount = when (key) {
                        "←" -> if (amount.length > 1) amount.dropLast(1) else "0"
                        "." -> if (!amount.contains(".")) "$amount." else amount
                        "再记" -> {
                            if (saveCurrentRecord()) {
                                note = ""
                                "0"
                            } else amount
                        }
                        "完成" -> {
                            if (amount != "0" && amount.isNotEmpty() && selectedCategory != null) {
                                saveCurrentRecord()
                            }
                            onBack()
                            amount
                        }
                        "+", "-", "*", "÷" -> {
                            val ops = listOf("+", "-", "*", "÷")
                            val existingOp = ops.firstOrNull { it in amount.drop(1) }
                            if (existingOp != null) {
                                val idx = amount.indexOf(existingOp, 1)
                                val num1 = amount.substring(0, idx).toDoubleOrNull()
                                val num2 = amount.substring(idx + 1).toDoubleOrNull()
                                if (num1 != null && num2 != null) {
                                    val result = when (existingOp) {
                                        "+" -> num1 + num2
                                        "-" -> num1 - num2
                                        "*" -> num1 * num2
                                        "÷" -> if (num2 != 0.0) {
                                            val r = num1 / num2
                                            if (r == r.toLong().toDouble()) r.toLong().toDouble() else
                                                "%.1f".format(r).toDouble()
                                        } else num1
                                        else -> num1
                                    }
                                    val display = if (result == result.toLong().toDouble())
                                        result.toLong().toString() else result.toString()
                                    "$display$key"
                                } else {
                                    amount + key
                                }
                            } else {
                                amount + key
                            }
                        }
                        else -> if (amount == "0") key else amount + key
                    }
                },
                finishEnabled = canFinish
            )
            } // Surface
            } // Column

        // 日期时间选择弹窗
        if (showDatePicker) {
            DateTimePickerDialog(
                initYear = selectedYear,
                initMonth = selectedMonth,
                initDay = selectedDay,
                initHour = selectedHour,
                initMinute = selectedMinute,
                onDismiss = { showDatePicker = false },
                onConfirm = { y, m, d, h, min ->
                    selectedYear = y
                    selectedMonth = m
                    selectedDay = d
                    selectedHour = h
                    selectedMinute = min
                    showDatePicker = false
                }
            )
        }

        // 账户选择弹窗
        if (showAccountDialog) {
            ModalBottomSheet(
                onDismissRequest = { showAccountDialog = false },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    "选择账户",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = screenHeight * 0.4f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp)
                ) {
                    accounts.forEach { account ->
                        val isSelected = selectedAccountId == account.id
                        val svgPath = accountTypeConfigs[account.type]?.svgPath
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedAccountId = account.id
                                    showAccountDialog = false
                                }
                                .background(
                                    if (isSelected) iconThemeColor.copy(alpha = 0.3f)
                                    else Color.Transparent
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (svgPath != null) {
                                AsyncImage(
                                    model = svgPath,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(
                                text = account.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${String.format("%.2f", account.initialAmount)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 优惠弹窗
        if (showDiscountDialog) {
            val inputBefore = remember { mutableStateOf(discountBefore ?: "") }
            val inputOff = remember { mutableStateOf(discountOff ?: "") }
            val inputAfter = remember { mutableStateOf(discountAfter ?: (if (amount != "0" && amount.isNotEmpty()) amount else "")) }
            // 从 Repository 读取持久化的开关状态
            val autoCalc = remember { mutableStateOf(AccountingRepository.getSetting(context, "discount_auto_calc") != "false") }
            val percentMode = remember { mutableStateOf(AccountingRepository.getSetting(context, "discount_percent_mode") == "true") }

            /** 限制金额输入：最多两位小数 */
            fun filterAmount(raw: String): String {
                val filtered = raw.filter { it.isDigit() || it == '.' }
                val dotIdx = filtered.indexOf('.')
                return if (dotIdx >= 0) filtered.substring(0, minOf(dotIdx + 3, filtered.length))
                else filtered
            }

            /** 限制百分比输入：仅整数 */
            fun filterPercent(raw: String): String {
                return raw.filter { it.isDigit() }
            }

            // 自动计算第三个字段
            fun recalc(changed: String) {
                if (!autoCalc.value) return
                val b = inputBefore.value.toDoubleOrNull()
                val o = inputOff.value.toDoubleOrNull()
                val a = inputAfter.value.toDoubleOrNull()
                if (percentMode.value) {
                    // 百分比模式：优惠前 × 折扣% = 优惠后
                    val pct = inputOff.value.toDoubleOrNull()  // 百分比值，如 80 表示打八折
                    when (changed) {
                        "before" -> {
                            if (b != null && pct != null) inputAfter.value = String.format("%.2f", b * pct / 100)
                            else if (b != null && a != null && pct == null) inputOff.value = if (b != 0.0) String.format("%.0f", a / b * 100) else ""
                        }
                        "off" -> {
                            if (b != null && pct != null) inputAfter.value = String.format("%.2f", b * pct / 100)
                            else if (pct != null && a != null && b == null) inputBefore.value = String.format("%.2f", a * 100 / pct)
                        }
                        "after" -> {
                            if (b != null && a != null) inputOff.value = if (b != 0.0) String.format("%.0f", a / b * 100) else ""
                            else if (pct != null && a != null) inputBefore.value = String.format("%.2f", a * 100 / pct)
                        }
                    }
                } else {
                    // 金额模式：优惠前 - 优惠 = 优惠后
                    when (changed) {
                        "before" -> {
                            if (b != null && o != null) inputAfter.value = String.format("%.2f", b - o)
                            else if (b != null && a != null) inputOff.value = String.format("%.2f", b - a)
                        }
                        "off" -> {
                            if (b != null && o != null) inputAfter.value = String.format("%.2f", b - o)
                            else if (o != null && a != null) inputBefore.value = String.format("%.2f", o + a)
                        }
                        "after" -> {
                            if (b != null && a != null) inputOff.value = String.format("%.2f", b - a)
                            else if (o != null && a != null) inputBefore.value = String.format("%.2f", o + a)
                        }
                    }
                }
            }

            val focusBefore = remember { androidx.compose.ui.focus.FocusRequester() }
            val focusOff = remember { androidx.compose.ui.focus.FocusRequester() }
            val focusAfter = remember { androidx.compose.ui.focus.FocusRequester() }

            ModalBottomSheet(
                onDismissRequest = { showDiscountDialog = false },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    "优惠计算",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { focusBefore.requestFocus() },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("优惠前", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
                                BasicTextField(
                                    value = inputBefore.value,
                                    onValueChange = { inputBefore.value = filterAmount(it); recalc("before") },
                                    modifier = Modifier.weight(1f).focusRequester(focusBefore),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                                    decorationBox = { inner ->
                                        if (inputBefore.value.isEmpty()) Text("0", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        inner()
                                    }
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { focusOff.requestFocus() },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (percentMode.value) "打折%" else "优惠", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
                                BasicTextField(
                                    value = inputOff.value,
                                    onValueChange = { inputOff.value = if (percentMode.value) filterPercent(it) else filterAmount(it); recalc("off") },
                                    modifier = Modifier.weight(1f).focusRequester(focusOff),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (percentMode.value) androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Decimal),
                                    decorationBox = { inner ->
                                        if (inputOff.value.isEmpty()) Text("0", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        inner()
                                    }
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { focusAfter.requestFocus() },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("优惠后", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
                                BasicTextField(
                                    value = inputAfter.value,
                                    onValueChange = { inputAfter.value = filterAmount(it); recalc("after") },
                                    modifier = Modifier.weight(1f).focusRequester(focusAfter),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                                    decorationBox = { inner ->
                                        if (inputAfter.value.isEmpty()) Text("0", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        inner()
                                    }
                                )
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = autoCalc.value, onCheckedChange = { autoCalc.value = it })
                            Spacer(Modifier.width(4.dp))
                            Text("自动计算", style = MaterialTheme.typography.labelSmall)
                        }
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = percentMode.value, onCheckedChange = { percentMode.value = it })
                            Spacer(Modifier.width(4.dp))
                            Text("百分比优惠", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showDiscountDialog = false }) { Text("取消") }
                        TextButton(onClick = {
                            discountBefore = inputBefore.value.ifEmpty { null }
                            discountOff = inputOff.value.ifEmpty { null }
                            discountAfter = inputAfter.value.ifEmpty { null }
                            if (discountAfter != null) amount = discountAfter!!
                            AccountingRepository.setSetting(context, "discount_auto_calc", autoCalc.value.toString())
                            AccountingRepository.setSetting(context, "discount_percent_mode", percentMode.value.toString())
                            showDiscountDialog = false
                        }) { Text("确认") }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // 报销账户选择弹窗
        if (showReimbursementDialog) {
            ModalBottomSheet(
                onDismissRequest = { showReimbursementDialog = false },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    "选择报销账户",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = screenHeight * 0.4f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp)
                ) {
                    val isNone = selectedReimbursementId == null
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedReimbursementId = null
                                showReimbursementDialog = false
                            }
                            .background(
                                if (isNone) iconThemeColor.copy(alpha = 0.3f)
                                else Color.Transparent
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Receipt, contentDescription = null, modifier = Modifier.size(24.dp), tint = iconThemeColor)
                        Spacer(Modifier.width(12.dp))
                        Text("不报销", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider()
                    reimbursementAccounts.forEach { account ->
                        val isSelected = selectedReimbursementId == account.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedReimbursementId = account.id
                                    showReimbursementDialog = false
                                }
                                .background(
                                    if (isSelected) iconThemeColor.copy(alpha = 0.3f)
                                    else Color.Transparent
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Receipt, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text(account.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DateTimePickerDialog(
    initYear: Int, initMonth: Int, initDay: Int,
    initHour: Int, initMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (year: Int, month: Int, day: Int, hour: Int, minute: Int) -> Unit
) {
    val context = LocalContext.current
    val cyan = remember { Color(android.graphics.Color.parseColor(getCategoryIconColor(context))) }
    var calYear by remember { mutableIntStateOf(initYear) }
    var calMonth by remember { mutableIntStateOf(initMonth) }
    var selDay by remember { mutableIntStateOf(initDay) }
    var selHour by remember { mutableIntStateOf(initHour) }
    var selMinute by remember { mutableIntStateOf(initMinute) }

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val daysInMonth = remember(calYear, calMonth) {
        val cal = Calendar.getInstance()
        cal.set(calYear, calMonth, 1)
        cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }
    val firstDayOfWeek = remember(calYear, calMonth) {
        val cal = Calendar.getInstance()
        cal.set(calYear, calMonth, 1)
        (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday=0
    }

    val dialogWidth = screenWidth * 0.80f
    val dialogHeight = screenHeight * 0.7f

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .width(dialogWidth)
                    .height(dialogHeight)
                    .padding(16.dp)
            ) {
                // 上半：日历（weight均分）
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // 月份导航
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            if (calMonth == 0) { calMonth = 11; calYear-- } else calMonth--
                        }) {
                            Icon(Icons.Outlined.ChevronLeft, "上月", tint = cyan)
                        }
                        Text(
                            text = "%04d-%02d".format(calYear, calMonth + 1),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        IconButton(onClick = {
                            if (calMonth == 11) { calMonth = 0; calYear++ } else calMonth++
                        }) {
                            Icon(Icons.Outlined.ChevronRight, "下月", tint = cyan)
                        }
                    }
                    // 星期标题
                    Row(modifier = Modifier.fillMaxWidth()) {
                        listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                            Text(
                                text = it,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 日期网格
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(7),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        items(firstDayOfWeek) { Spacer(Modifier.aspectRatio(1f)) }
                        items(daysInMonth) { day ->
                            val d = day + 1
                            val isSelected = d == selDay
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (isSelected) cyan else Color.Transparent)
                                    .clickable { selDay = d },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$d",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) Color.White
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // 青色分割线
                HorizontalDivider(color = cyan, thickness = 1.dp)

                // 下半：时间齿轮（weight均分）
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    TimeWheel(
                        range = 0..23,
                        selected = selHour,
                        onSelect = { selHour = it },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = { "%02d".format(it) }
                    )
                    TimeWheel(
                        range = 0..59,
                        selected = selMinute,
                        onSelect = { selMinute = it },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = { "%02d".format(it) }
                    )
                }

                // 确认按钮
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onConfirm(calYear, calMonth, selDay, selHour, selMinute) }) {
                        Text("确定", color = cyan)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeWheel(
    range: IntRange,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    label: (Int) -> String
) {
    val context = LocalContext.current
    val cyan = remember { Color(android.graphics.Color.parseColor(getCategoryIconColor(context))) }
    val size = range.last - range.first + 1
    val totalItems = size * 10000
    val initialIndex = totalItems / 2 + (selected - range.first)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val itemHeight = 36.dp
    val itemSpacing = 4.dp
    val totalItemHeight = itemHeight + itemSpacing
    val density = LocalDensity.current

    // 首次布局：用 scrollToItem（无动画）直接居中，避免闪烁
    var firstLayout by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        if (firstLayout) {
            snapshotFlow { listState.layoutInfo.viewportEndOffset }
                .first { it > 0 }
            val vpH = listState.layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset }
            val ih = with(density) { itemHeight.roundToPx() }
            listState.scrollToItem(initialIndex, -((vpH - ih) / 2))
            firstLayout = false
        }
    }

    // 滚动停止时自动吸中（动画）
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }
            .collect {
                val info = listState.layoutInfo
                val items = info.visibleItemsInfo
                if (items.isEmpty()) return@collect
                val vpH = info.viewportEndOffset - info.viewportStartOffset
                val centerY = vpH / 2f
                // 找离视口中心最近的 item
                val best = items.minByOrNull {
                    kotlin.math.abs(it.offset + it.size / 2f - centerY)
                } ?: return@collect
                // 让该 item 居中：offset = -(vpH - itemHeight) / 2
                val scrollOffset = -((vpH - best.size) / 2).toInt()
                listState.animateScrollToItem(best.index, scrollOffset)
                onSelect(range.first + (best.index % size + size) % size)
            }
    }

    val scope = rememberCoroutineScope()
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(totalItems) { index ->
                val value = range.first + (index % size + size) % size
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .padding(vertical = itemSpacing / 2)
                        .clickable {
                            onSelect(value)
                            scope.launch {
                                val info = listState.layoutInfo
                                val vpH = info.viewportEndOffset - info.viewportStartOffset
                                val ih = (itemHeight.value * density.density).toInt()
                                listState.animateScrollToItem(index, -((vpH - ih) / 2))
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label(value),
                        style = if (value == selected) MaterialTheme.typography.titleLarge
                        else MaterialTheme.typography.bodyLarge,
                        color = if (value == selected) cyan
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        // 视口中心青色选择条
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(totalItemHeight)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(4.dp))
                .background(cyan.copy(alpha = 0.12f))
        )
    }
}

@Composable
private fun CalculatorKeyboard(onInput: (String) -> Unit, finishEnabled: Boolean = true) {
    val keySpacing = 2.dp
    val context = LocalContext.current
    val keyShape = RoundedCornerShape(6.dp)
    val keyColor = MaterialTheme.colorScheme.surfaceVariant
    val themeColor = remember { Color(android.graphics.Color.parseColor(getCategoryIconColor(context))) }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val rowHeight = screenHeight * 0.06f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(keySpacing),
        verticalArrangement = Arrangement.spacedBy(keySpacing)
    ) {
        // 第1行: 1 2 3 ←
        Row(
            modifier = Modifier.fillMaxWidth().height(rowHeight),
            horizontalArrangement = Arrangement.spacedBy(keySpacing)
        ) {
            KeyButton("1", Modifier.weight(1f), keyShape, keyColor, themeColor, onInput)
            KeyButton("2", Modifier.weight(1f), keyShape, keyColor, themeColor, onInput)
            KeyButton("3", Modifier.weight(1f), keyShape, keyColor, themeColor, onInput)
            KeyButton("←", Modifier.weight(1f), keyShape, keyColor, themeColor, onInput, icon = true)
        }
        // 第2行: 4 5 6 [-|*]
        Row(
            modifier = Modifier.fillMaxWidth().height(rowHeight),
            horizontalArrangement = Arrangement.spacedBy(keySpacing)
        ) {
            KeyButton("4", Modifier.weight(1f), keyShape, keyColor, themeColor, onInput)
            KeyButton("5", Modifier.weight(1f), keyShape, keyColor, themeColor, onInput)
            KeyButton("6", Modifier.weight(1f), keyShape, keyColor, themeColor, onInput)
            // 左右分：- 和 *
            Row(modifier = Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(keySpacing)) {
                KeyButton("-", Modifier.weight(1f), keyShape, keyColor, themeColor, onInput)
                KeyButton("*", Modifier.weight(1f), keyShape, keyColor, themeColor, onInput)
            }
        }
        // 第3行: 7 8 9 [+|÷]
        Row(
            modifier = Modifier.fillMaxWidth().height(rowHeight),
            horizontalArrangement = Arrangement.spacedBy(keySpacing)
        ) {
            KeyButton("7", Modifier.weight(1f), keyShape, keyColor, themeColor, onInput)
            KeyButton("8", Modifier.weight(1f), keyShape, keyColor, themeColor, onInput)
            KeyButton("9", Modifier.weight(1f), keyShape, keyColor, themeColor, onInput)
            // 左右分：+ 和 ÷
            Row(modifier = Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(keySpacing)) {
                KeyButton("+", Modifier.weight(1f), keyShape, keyColor, themeColor, onInput)
                KeyButton("÷", Modifier.weight(1f), keyShape, keyColor, themeColor, onInput)
            }
        }
        // 第4行: 再记 0 . 完成
        Row(
            modifier = Modifier.fillMaxWidth().height(rowHeight),
            horizontalArrangement = Arrangement.spacedBy(keySpacing)
        ) {
            KeyButton("再记", Modifier.weight(1f), keyShape, themeColor.copy(alpha = 0.15f), themeColor, onInput)
            KeyButton("0", Modifier.weight(1f), keyShape, keyColor, themeColor, onInput)
            KeyButton(".", Modifier.weight(1f), keyShape, keyColor, themeColor, onInput)
            KeyButton("完成", Modifier.weight(1f), keyShape,
                if (finishEnabled) themeColor.copy(alpha = 0.15f) else keyColor,
                if (finishEnabled) themeColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                if (finishEnabled) onInput else { _ -> })
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    modifier: Modifier,
    shape: RoundedCornerShape,
    containerColor: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    onInput: (String) -> Unit,
    icon: Boolean = false
) {
    val context = LocalContext.current
    val pressColor = remember { Color(android.graphics.Color.parseColor(getCategoryIconColor(context))).copy(alpha = 0.12f) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val bgColor = if (isPressed) pressColor else containerColor

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = null) { onInput(label) },
        contentAlignment = Alignment.Center
    ) {
        if (icon) {
            Icon(
                Icons.Outlined.Backspace,
                contentDescription = "退格",
                tint = textColor
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = textColor
            )
        }
    }
}

/** 图标标识 → Material Icons 映射（TwoTone 双色调风格） */
internal fun materialIcon(icon: String): ImageVector = when (icon) {
    // 餐饮美食
    "restaurant" -> Icons.TwoTone.Restaurant
    "fastfood" -> Icons.TwoTone.Fastfood
    "local_cafe" -> Icons.TwoTone.LocalCafe
    "local_bar" -> Icons.TwoTone.LocalBar
    "cake" -> Icons.TwoTone.Cake
    "coffee" -> Icons.TwoTone.Coffee
    "free_breakfast" -> Icons.TwoTone.FreeBreakfast
    "lunch_dining" -> Icons.TwoTone.LunchDining
    "dinner_dining" -> Icons.TwoTone.DinnerDining
    "icecream" -> Icons.TwoTone.Icecream
    "bakery_dining" -> Icons.TwoTone.BakeryDining
    "liquor" -> Icons.TwoTone.Liquor
    "set_meal" -> Icons.TwoTone.SetMeal
    "ramen_dining" -> Icons.TwoTone.RamenDining
    "delivery_dining" -> Icons.TwoTone.DeliveryDining
    "dining" -> Icons.TwoTone.Restaurant
    // 水果零食
    "eco" -> Icons.TwoTone.Eco
    "apple" -> Icons.TwoTone.Eco
    "sports_cricket" -> Icons.TwoTone.SportsCricket
    "circle" -> Icons.TwoTone.Circle
    "bubble_chart" -> Icons.TwoTone.BubbleChart
    "pie_chart" -> Icons.TwoTone.PieChart
    "cookie" -> Icons.TwoTone.Cookie
    "candy" -> Icons.TwoTone.Icecream
    "chocolate" -> Icons.TwoTone.Cake
    "grain" -> Icons.TwoTone.Grain
    // 饮品
    "juice" -> Icons.TwoTone.LocalCafe
    "water_drop" -> Icons.TwoTone.WaterDrop
    // 食材
    "kitchen" -> Icons.TwoTone.Kitchen
    "yard" -> Icons.TwoTone.Yard
    "blender" -> Icons.TwoTone.Blender
    // 购物
    "shopping_cart" -> Icons.TwoTone.ShoppingCart
    "shopping_bag" -> Icons.TwoTone.ShoppingBag
    "storefront" -> Icons.TwoTone.Storefront
    "watch" -> Icons.TwoTone.Watch
    "accessibility" -> Icons.TwoTone.Accessibility
    // 宠物
    "pets" -> Icons.TwoTone.Pets
    "pet_supplies" -> Icons.TwoTone.Pets
    "inventory_2" -> Icons.TwoTone.Inventory2
    "medical_services" -> Icons.TwoTone.MedicalServices
    "shower" -> Icons.TwoTone.Shower
    // 交通出行
    "directions_car" -> Icons.TwoTone.DirectionsCar
    "directions_bus" -> Icons.TwoTone.DirectionsBus
    "directions_subway" -> Icons.TwoTone.DirectionsSubway
    "directions_bike" -> Icons.TwoTone.DirectionsBike
    "local_taxi" -> Icons.TwoTone.LocalTaxi
    "local_parking" -> Icons.TwoTone.LocalParking
    "local_gas_station" -> Icons.TwoTone.LocalGasStation
    // 汽车
    "build" -> Icons.TwoTone.Build
    "handyman" -> Icons.TwoTone.Handyman
    "security" -> Icons.TwoTone.Security
    "local_car_wash" -> Icons.TwoTone.LocalCarWash
    "report_problem" -> Icons.TwoTone.ReportProblem
    // 服饰
    "checkroom" -> Icons.TwoTone.Checkroom
    "diamond" -> Icons.TwoTone.Diamond
    "auto_awesome" -> Icons.TwoTone.AutoAwesome
    "hiking" -> Icons.TwoTone.Hiking
    // 日用品
    "local_laundry_service" -> Icons.TwoTone.LocalLaundryService
    "receipt" -> Icons.TwoTone.Receipt
    "cleaning_services" -> Icons.TwoTone.CleaningServices
    // 教育
    "school" -> Icons.TwoTone.School
    "model_training" -> Icons.TwoTone.ModelTraining
    "menu_book" -> Icons.TwoTone.MenuBook
    "edit" -> Icons.TwoTone.Edit
    "business_center" -> Icons.TwoTone.BusinessCenter
    // 投资
    "trending_down" -> Icons.TwoTone.TrendingDown
    "show_chart" -> Icons.TwoTone.ShowChart
    "money_off" -> Icons.TwoTone.MoneyOff
    // 娱乐
    "movie" -> Icons.TwoTone.Movie
    "mic" -> Icons.TwoTone.Mic
    "attractions" -> Icons.TwoTone.Attractions
    "celebration" -> Icons.TwoTone.Celebration
    // 游戏
    "sports_esports" -> Icons.TwoTone.SportsEsports
    "payments" -> Icons.TwoTone.Payments
    "workspace_premium" -> Icons.TwoTone.WorkspacePremium
    // 保健
    "medication" -> Icons.TwoTone.Medication
    "biotech" -> Icons.TwoTone.Biotech
    "health_and_safety" -> Icons.TwoTone.HealthAndSafety
    // 订阅
    "subscriptions" -> Icons.TwoTone.Subscriptions
    "play_circle" -> Icons.TwoTone.PlayCircle
    "music_note" -> Icons.TwoTone.MusicNote
    "cloud" -> Icons.TwoTone.Cloud
    // 运动
    "fitness_center" -> Icons.TwoTone.FitnessCenter
    "sports" -> Icons.TwoTone.Sports
    "sports_martial_arts" -> Icons.TwoTone.SportsMartialArts
    // 住房居家
    "home_work" -> Icons.TwoTone.HomeWork
    "home" -> Icons.TwoTone.Home
    "construction" -> Icons.TwoTone.Construction
    "weekend" -> Icons.TwoTone.Weekend
    "devices" -> Icons.TwoTone.Devices
    "palette" -> Icons.TwoTone.Palette
    "bed" -> Icons.TwoTone.Bed
    // 美容
    "face" -> Icons.TwoTone.Face
    "face_retouching_natural" -> Icons.TwoTone.FaceRetouchingNatural
    "content_cut" -> Icons.TwoTone.ContentCut
    "back_hand" -> Icons.TwoTone.BackHand
    // 收入分类
    "work" -> Icons.TwoTone.Work
    "account_balance" -> Icons.TwoTone.AccountBalance
    "card_giftcard" -> Icons.TwoTone.CardGiftcard
    "emoji_events" -> Icons.TwoTone.EmojiEvents
    "star" -> Icons.TwoTone.Star
    "schedule" -> Icons.TwoTone.Schedule
    "access_time" -> Icons.TwoTone.AccessTime
    "monetization_on" -> Icons.TwoTone.MonetizationOn
    "savings" -> Icons.TwoTone.Savings
    "military_tech" -> Icons.TwoTone.MilitaryTech
    "flight" -> Icons.TwoTone.Flight
    "attach_money" -> Icons.TwoTone.AttachMoney
    "undo" -> Icons.TwoTone.Undo
    "trending_up" -> Icons.TwoTone.TrendingUp
    "sell" -> Icons.TwoTone.Sell
    "favorite" -> Icons.TwoTone.Favorite
    "child_care" -> Icons.TwoTone.ChildCare
    "receipt_long" -> Icons.TwoTone.ReceiptLong
    "description" -> Icons.TwoTone.Description
    "account_balance_wallet" -> Icons.TwoTone.AccountBalanceWallet
    // 兜底
    else -> Icons.TwoTone.Category
}

/** 分类 ID → 主题色（每个分类不同颜色） */
private fun categoryColor(id: String): Color = when (id) {
    // 支出分类
    "dining" -> Color(0xFFFF6B6B)        // 红色 - 餐饮
    "snacks" -> Color(0xFFFF9F43)        // 橙色 - 零食
    "fruit" -> Color(0xFF2ED573)         // 绿色 - 水果
    "beverage" -> Color(0xFF7C4DFF)      // 紫色 - 饮品
    "pastry" -> Color(0xFFFF6F91)        // 粉色 - 糕点
    "cooking" -> Color(0xFF4CAF50)       // 深绿 - 做饭食材
    "shopping" -> Color(0xFF42A5F5)      // 蓝色 - 购物
    "pets" -> Color(0xFFAB47BC)          // 紫色 - 宠物
    "transport" -> Color(0xFF5C6BC0)     // 靛蓝 - 交通
    "car" -> Color(0xFF1E88E5)           // 深蓝 - 汽车
    "clothing" -> Color(0xFFEC407A)      // 粉红 - 服饰
    "daily_goods" -> Color(0xFF26A69A)   // 青色 - 日用品
    "education" -> Color(0xFF42A5F5)     // 蓝色 - 教育
    "invest_loss" -> Color(0xFFEF5350)   // 红色 - 投资亏损
    "entertainment" -> Color(0xFFAB47BC) // 紫色 - 娱乐
    "game" -> Color(0xFF7E57C2)          // 深紫 - 游戏
    "health_products" -> Color(0xFF66BB6A) // 绿色 - 保健品
    "subscription" -> Color(0xFF29B6F6)  // 浅蓝 - 订阅服务
    "sports" -> Color(0xFFEF5350)        // 红色 - 运动
    "housing" -> Color(0xFF8D6E63)       // 棕色 - 住房
    "home" -> Color(0xFFFFA726)          // 橙色 - 居家
    "beauty" -> Color(0xFFEC407A)        // 粉红 - 美容
    // 收入分类
    "salary" -> Color(0xFF4CAF50)        // 绿色 - 工资
    "investment" -> Color(0xFF2196F3)    // 蓝色 - 理财
    "red_packet" -> Color(0xFFF44336)    // 红色 - 红包
    "bonus" -> Color(0xFFFF9800)         // 橙色 - 奖金
    "reimbursement" -> Color(0xFF9C27B0) // 紫色 - 报销
    "part_time" -> Color(0xFF00BCD4)     // 青色 - 兼职
    "gift" -> Color(0xFFE91E63)          // 粉红 - 礼物
    "interest" -> Color(0xFF4CAF50)      // 绿色 - 利息
    "refund" -> Color(0xFFFF5722)        // 深橙 - 退款
    "invest_income" -> Color(0xFF2196F3) // 蓝色 - 投资收益
    "second_hand" -> Color(0xFF795548)   // 棕色 - 二手交易
    "social_benefit" -> Color(0xFF607D8B) // 灰蓝 - 社会福利
    "tax_refund" -> Color(0xFF009688)    // 青色 - 退税
    "provident_fund" -> Color(0xFF3F51B5) // 靛蓝 - 公积金
    // 兜底
    else -> Color(0xFF9E9E9E)            // 灰色
}

/** 分类图标渲染（TwoTone 双色调风格） */
@Composable
private fun CategoryIcon(
    icon: String,
    size: androidx.compose.ui.unit.Dp,
    tint: Color
) {
    Icon(
        imageVector = materialIcon(icon),
        contentDescription = null,
        modifier = Modifier.size(size),
        tint = tint
    )
}

/** 二级分类选择卡片（参考 BeeCount _SubcategorySelectorCard） */
@Composable
private fun SubcategoryCard(
    parentName: String,
    children: List<AccountingCategory>,
    selectedId: String?,
    themeColor: Color,
    onSelect: (String) -> Unit
) {
    val itemsPerRow = 4

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        BoxWithConstraints(modifier = Modifier.padding(12.dp)) {
            val subItemWidth = maxWidth / itemsPerRow
            Column {
                var i = 0
                while (i < children.size) {
                    val rowEnd = (i + itemsPerRow).coerceAtMost(children.size)
                    val rowItems = children.subList(i, rowEnd)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        rowItems.forEach { child ->
                            val isSelected = selectedId == child.id

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(subItemWidth)
                                    .clickable { onSelect(child.id) }
                                    .padding(vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(
                                            if (isSelected)
                                                themeColor.copy(alpha = 0.25f)
                                            else
                                                MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CategoryIcon(
                                        icon = child.icon,
                                        size = 20.dp,
                                        tint = themeColor
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = child.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    color = if (isSelected)
                                        themeColor
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        repeat(itemsPerRow - rowItems.size) {
                            Spacer(Modifier.width(subItemWidth))
                        }
                    }

                    if (rowEnd < children.size) {
                        Spacer(Modifier.height(8.dp))
                    }
                    i = rowEnd
                }
            }
        }
    }
}

/** 从 ContentResolver URI 获取原始文件名 */
private fun getFileName(context: android.content.Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    return cursor?.use {
        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        it.moveToFirst()
        if (nameIndex >= 0) it.getString(nameIndex) else null
    }
}


