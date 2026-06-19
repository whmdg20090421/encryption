package com.whmdg.mczj.tools.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatTime(millis: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

@Composable
fun DiaryScreen(onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var db by remember { mutableStateOf(DiaryDb.empty()) }
    val configuration = LocalConfiguration.current
    val menuWidth = (configuration.screenWidthDp * 0.4f).dp

    LaunchedEffect(Unit) {
        db = withContext(Dispatchers.IO) { DiaryDb.load(context) }
    }

    if (showCreateDialog) {
        CreateBookDialog(
            existingNames = db.books.map { it.name }.toSet(),
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                val now = System.currentTimeMillis()
                val newBook = DiaryBook(name = name, createdAt = now, lastEditedAt = now)
                db.addBook(newBook)
                db = db.copy() // 触发重组
                showCreateDialog = false
                Thread { db.save(context) }.start()
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部工具栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Home, contentDescription = "返回主页")
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { showMenu = !showMenu }) {
                    Icon(Icons.Default.Add, contentDescription = "添加")
                }
            }

            // 弹出面板（在 Box 作用域内，可用 Alignment）
            AnimatedVisibility(
                visible = showMenu,
                enter = fadeIn(tween(200)) + slideInVertically(tween(250)) { -it },
                exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { -it },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 8.dp)
            ) {
                Card(
                    modifier = Modifier
                        .width(menuWidth)
                        .shadow(8.dp, RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = "添加日记本",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showMenu = false
                                    showCreateDialog = true
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }

        // 主内容区域
        if (db.books.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无日记本", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(db.books) { book ->
                    DiaryBookCard(book) {
                        onNavigate(Screen.DiaryBookDetail(book.name, book.createdAt, book.lastEditedAt))
                    }
                }
            }
        }

        // 底部工具栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text("底部工具栏", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DiaryBookCard(book: DiaryBook, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = book.name,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "创建：${formatTime(book.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "最后编辑：${formatTime(book.lastEditedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CreateBookDialog(
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建日记本") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { raw ->
                        input = raw
                        error = null
                    },
                    label = { Text("日记本名称") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val name = input.trim()
                    when {
                        name.isEmpty() -> error = "名称不能为空"
                        name in existingNames -> error = "已存在同名日记本"
                        else -> onConfirm(name)
                    }
                }
            ) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
