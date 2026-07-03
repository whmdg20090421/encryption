package com.whmdg.mczj.tools.ui.accounting

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 加载已保存的配置
    val savedConfig = remember { AccountingRepository.getWebDavConfig(context) }
    var serverUrl by remember { mutableStateOf(savedConfig.first) }
    var username by remember { mutableStateOf(savedConfig.second) }
    var password by remember { mutableStateOf(savedConfig.third) }
    var passwordVisible by remember { mutableStateOf(false) }

    // 自动同步
    var autoSync by remember { mutableStateOf(AccountingRepository.getWebDavAutoSync(context)) }
    val lastSync = remember { mutableStateOf(AccountingRepository.getWebDavLastSync(context)) }

    // 测试/上传/下载状态
    var isLoading by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("云同步") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // ── 卡片1：服务器配置 ──
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        // 服务器地址
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🌐", modifier = Modifier.padding(end = 8.dp))
                            Text("地址", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(40.dp))
                            BasicTextField(
                                value = serverUrl,
                                onValueChange = { serverUrl = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (serverUrl.isEmpty()) {
                                            Text("https://dav.example.com/dav/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                        // 账号
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("👤", modifier = Modifier.padding(end = 8.dp))
                            Text("账号", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(40.dp))
                            BasicTextField(
                                value = username,
                                onValueChange = { username = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (username.isEmpty()) {
                                            Text("请输入账号", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                        // 密码
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🔒", modifier = Modifier.padding(end = 8.dp))
                            Text("密码", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(40.dp))
                            BasicTextField(
                                value = password,
                                onValueChange = { password = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (password.isEmpty()) {
                                            Text("授权密码，非登录密码", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                            IconButton(onClick = { passwordVisible = !passwordVisible }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                        // 测试连接按钮
                        Button(
                            onClick = {
                                // 先保存配置
                                AccountingRepository.setWebDavConfig(context, serverUrl.trim(), username.trim(), password)
                                isLoading = true
                                resultMessage = null
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        AccountingRepository.testWebDavConnection(context)
                                    }
                                    resultMessage = result
                                    isLoading = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isLoading && serverUrl.isNotBlank() && username.isNotBlank()
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("测试连接")
                        }

                        // 测试结果
                        if (resultMessage != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = resultMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (resultMessage!!.startsWith("连接成功")) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }

            // ── 卡片2：同步操作 ──
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        // 自动同步
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("自动同步", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "每次记账后自动上传数据",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = autoSync,
                                onCheckedChange = {
                                    autoSync = it
                                    AccountingRepository.setWebDavAutoSync(context, it)
                                }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                        // 上传 / 下载按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    AccountingRepository.setWebDavConfig(context, serverUrl.trim(), username.trim(), password)
                                    isLoading = true
                                    resultMessage = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            AccountingRepository.uploadToWebDav(context)
                                        }
                                        resultMessage = result
                                        lastSync.value = AccountingRepository.getWebDavLastSync(context)
                                        isLoading = false
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isLoading && serverUrl.isNotBlank() && username.isNotBlank()
                            ) {
                                Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("立即上传")
                            }
                            OutlinedButton(
                                onClick = {
                                    AccountingRepository.setWebDavConfig(context, serverUrl.trim(), username.trim(), password)
                                    isLoading = true
                                    resultMessage = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            AccountingRepository.downloadFromWebDav(context)
                                        }
                                        resultMessage = result
                                        lastSync.value = AccountingRepository.getWebDavLastSync(context)
                                        isLoading = false
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isLoading && serverUrl.isNotBlank() && username.isNotBlank()
                            ) {
                                Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("立即下载")
                            }
                        }

                        // 操作结果
                        if (resultMessage != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = resultMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (resultMessage!!.contains("成功")) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                        // 最后同步时间
                        Text(
                            text = "最后同步：${lastSync.value ?: "从未同步"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
