package com.whmdg.mczj.tools.ui.accounting

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whmdg.mczj.tools.ui.theme.LocalIsDarkMode
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddReimbursementAccountScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    // 状态
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var showNameField by remember { mutableStateOf(false) }
    var showNoteField by remember { mutableStateOf(false) }
    val nameFocusRequester = remember { FocusRequester() }
    val noteFocusRequester = remember { FocusRequester() }
    var iconUri by remember { mutableStateOf<Uri?>(null) }
    var showIconMenu by remember { mutableStateOf(false) }

    // 记账本选择状态
    val bookList = remember { AccountingRepository.getBookList(context) }
    // "全部账本"是否勾选
    var selectAll by remember { mutableStateOf(true) }
    // 各账本勾选状态（默认不勾选，由"全部账本"统管）
    val bookChecked = remember {
        mutableStateMapOf<String, Boolean>().apply {
            bookList.forEach { put(it, false) }
        }
    }
    var showBookDialog by remember { mutableStateOf(false) }

    // 分组状态
    val groupList = remember { mutableStateListOf("报销") }
    var currentGroup by remember { mutableStateOf("报销") }
    var showGroupDialog by remember { mutableStateOf(false) }
    var showAddGroupDialog by remember { mutableStateOf(false) }
    var addGroupName by remember { mutableStateOf("") }

    // 分组选择弹窗（点击即选，无确认/取消）
    if (showGroupDialog) {
        AlertDialog(
            onDismissRequest = { showGroupDialog = false },
            title = { Text("选择分组", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    groupList.forEach { group ->
                        Text(
                            text = group,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentGroup = group
                                    showGroupDialog = false
                                }
                                .padding(vertical = 8.dp),
                            color = if (group == currentGroup) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "+ 添加分组",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showGroupDialog = false
                                showAddGroupDialog = true
                            }
                            .padding(vertical = 8.dp)
                    )
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }

    // 添加分组弹窗
    if (showAddGroupDialog) {
        AlertDialog(
            onDismissRequest = { showAddGroupDialog = false; addGroupName = "" },
            title = { Text("添加分组", style = MaterialTheme.typography.titleMedium) },
            text = {
                BasicTextField(
                    value = addGroupName,
                    onValueChange = { addGroupName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    decorationBox = { innerTextField ->
                        Box {
                            if (addGroupName.isEmpty()) {
                                Text("请输入分组名称",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            innerTextField()
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (addGroupName.isNotBlank()) {
                        groupList.add(addGroupName)
                        currentGroup = addGroupName
                        addGroupName = ""
                        showAddGroupDialog = false
                    }
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showAddGroupDialog = false; addGroupName = "" }) { Text("取消") }
            }
        )
    }

    // 输入框自动请求焦点
    if (showNameField) {
        LaunchedEffect(showNameField) { nameFocusRequester.requestFocus() }
    }
    if (showNoteField) {
        LaunchedEffect(showNoteField) { noteFocusRequester.requestFocus() }
    }

    // 图片选择器
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) iconUri = uri
    }

    // 记账本选择弹窗
    if (showBookDialog) {
        AlertDialog(
            onDismissRequest = { showBookDialog = false },
            title = { Text("选择生效记账本", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    // 顶格：全部账本
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectAll = !selectAll }
                            .padding(vertical = 8.dp)
                    ) {
                        Checkbox(
                            checked = selectAll,
                            onCheckedChange = { selectAll = it }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("全部账本", style = MaterialTheme.typography.bodyLarge)
                    }
                    // 空两行
                    Spacer(Modifier.height(32.dp))
                    // 分隔线
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp
                    )
                    Spacer(Modifier.height(8.dp))
                    // 各账本
                    for (book in bookList) {
                        val checked = bookChecked[book] == true
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!selectAll) {
                                        bookChecked[book] = !checked
                                    }
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Checkbox(
                                checked = if (selectAll) true else checked,
                                onCheckedChange = {
                                    if (!selectAll) bookChecked[book] = it
                                },
                                enabled = !selectAll
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = book,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selectAll) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                val hasSelection = selectAll || bookChecked.values.any { it }
                TextButton(
                    onClick = { showBookDialog = false },
                    enabled = hasSelection
                ) {
                    Text("确认", color = if (hasSelection) MaterialTheme.colorScheme.primary
                          else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBookDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "添加报销账户",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (LocalIsDarkMode.current) Color(0xFF2A2A2A) else Color(0xFFF5F5F5)
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 基础设置
            Text(
                text = "基础设置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            // 第一个卡片：名称 + 备注
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    // 名称行
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showNameField = true }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = "名称",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(16.dp))
                        if (showNameField) {
                            BasicTextField(
                                value = name,
                                onValueChange = { name = it },
                                modifier = Modifier.weight(1f).focusRequester(nameFocusRequester),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (name.isEmpty()) {
                                            Text(
                                                "添加报销账户的名称",
                                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        } else {
                            Text(
                                text = name.ifEmpty { "添加报销账户的名称" },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (name.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp
                    )

                    // 备注行
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showNoteField = true }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = "备注",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(16.dp))
                        if (showNoteField) {
                            BasicTextField(
                                value = note,
                                onValueChange = { note = it },
                                modifier = Modifier.weight(1f).focusRequester(noteFocusRequester),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (note.isEmpty()) {
                                            Text(
                                                "请输入备注",
                                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        } else {
                            Text(
                                text = note.ifEmpty { "请输入备注" },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (note.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 属性设置
            Text(
                text = "属性设置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))

            // 第二个卡片：图标 + 生效记账本
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    // 报销账户图标行
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = "报销账户图标",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        // 图标按钮
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showIconMenu = true }
                                .padding(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Receipt,
                                contentDescription = "报销账户图标",
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // 图标选择弹出框（左下角弹出）
                    if (showIconMenu) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                            Popup(
                                alignment = Alignment.BottomEnd,
                                onDismissRequest = { showIconMenu = false },
                                properties = PopupProperties(focusable = true)
                            ) {
                                Card(
                                    modifier = Modifier
                                        .width(screenWidth * 0.4f)
                                        .padding(top = 4.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "从相册中选取",
                                            style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    showIconMenu = false
                                                    photoPickerLauncher.launch(
                                                        PickVisualMediaRequest(PickVisualMedia.ImageOnly)
                                                    )
                                                }
                                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                        )
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                            thickness = 0.5.dp
                                        )
                                        Text(
                                            text = "恢复默认值",
                                            style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    showIconMenu = false
                                                    iconUri = null
                                                }
                                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp
                    )

                    // 选择生效记账本行
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showBookDialog = true }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = "选择生效记账本",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        if (selectAll) {
                            Text(
                                text = "全部账本",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            val selected = bookList.filter { bookChecked[it] == true }
                            Text(
                                text = selected.joinToString("、"),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.widthIn(max = screenWidth * 0.45f)
                            )
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp
                    )

                    // 所属分组行
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showGroupDialog = true }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = "所属分组",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = currentGroup,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
