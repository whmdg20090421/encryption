package com.whmdg.mczj.tools.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.auth.PasswordDialog
import com.whmdg.mczj.tools.auth.PermissionManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthManagementScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authState by PermissionManager.state.collectAsState()

    var phase by remember { mutableIntStateOf(0) }
    var currentPw by remember { mutableStateOf<String?>(null) }
    var showSwitchDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("更改密钥授权") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (phase) {
                0 -> {
                    Text(
                        text = "请先验证当前密码",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Button(onClick = { showSwitchDialog = false; phase = 1 }) {
                        Text("输入密码")
                    }
                }
                1 -> {
                    PasswordDialog(
                        onDismiss = { phase = 0 },
                        onVerify = { pw ->
                            val res = PermissionManager.tryAuthenticate(context, pw)
                            if (res.isSuccess) {
                                val features = res.getOrNull() ?: emptySet()
                                currentPw = pw
                                resultMsg = if (DEBUG_AUTH) {
                                    val state = PermissionManager.state.value
                                    val keyId = (state as? PermissionManager.AuthState.Authed)?.keyId ?: "?"
                                    "密钥已激活: keyId=$keyId, features=${features.joinToString { it.name }}, state=$state"
                                } else {
                                    "密钥已激活，你拥有：${features.joinToString("、") { featureDisplayName(it) }}"
                                }
                                phase = 2
                                true
                            } else {
                                resultMsg = "密码错误: ${res.exceptionOrNull()?.message}"
                                phase = 0
                                false
                            }
                        }
                    )
                }
                2 -> {
                    Text(
                        text = "验证通过，请选择操作",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )

                    Button(
                        onClick = { showSwitchDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("切换密钥")
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("清除授权")
                    }
                }
            }

            resultMsg?.let { msg ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = msg,
                    color = if (msg == "密码错误") MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    if (showSwitchDialog) {
        PasswordDialog(
            onDismiss = { showSwitchDialog = false },
            onVerify = { newPw ->
                val ok = PermissionManager.switchKey(context, currentPw!!, newPw)
                showSwitchDialog = false
                if (ok) {
                    resultMsg = "密钥已切换"
                    phase = 2
                    true
                } else {
                    resultMsg = "新密码无效"
                    false
                }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("确认清除") },
            text = { Text("清除后 App 将回到从未鉴权的初始状态，所有模块将被锁定。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    scope.launch {
                        PermissionManager.clearAuth(context, currentPw!!)
                        resultMsg = "授权已清除"
                        phase = 0
                        currentPw = null
                    }
                }) {
                    Text("确认清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}
