package com.whmdg.mczj.tools.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.whmdg.mczj.tools.ui.theme.DialogWidthFraction
import kotlinx.coroutines.launch

/**
 * 密码输入弹窗，带错误计数和锁定机制。
 *
 * @param onDismiss 关闭弹窗回调
 * @param onVerify 验证密码回调，返回 true 表示验证成功，false 表示失败
 */
@Composable
fun PasswordDialog(
    title: String = "输入密钥",
    onDismiss: () -> Unit,
    onVerify: suspend (String) -> Boolean
) {
    var password by remember { mutableStateOf("") }
    var errorCount by remember { mutableIntStateOf(0) }
    var lockedUntil by remember { mutableLongStateOf(0L) }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val maxErrors = 5
    val lockDurationMs = 30_000L

    fun handleSubmit() {
        if (isProcessing || password.isBlank()) return

        val now = System.currentTimeMillis()
        if (lockedUntil > now) {
            errorMsg = "操作过于频繁，请稍后再试"
            return
        }

        isProcessing = true
        errorMsg = null

        scope.launch {
            val success = onVerify(password)
            isProcessing = false

            if (success) {
                onDismiss()
            } else {
                errorCount++
                password = ""
                if (errorCount >= maxErrors) {
                    lockedUntil = System.currentTimeMillis() + lockDurationMs
                    errorMsg = "错误次数过多，请等待30秒后重试"
                    errorCount = 0
                } else {
                    errorMsg = "密码错误，还剩${maxErrors - errorCount}次机会"
                }
            }
        }
    }

    Dialog(onDismissRequest = { if (!isProcessing) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.fillMaxWidth(DialogWidthFraction),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)

                val now = System.currentTimeMillis()
                if (lockedUntil > now) {
                    Text(
                        "操作过于频繁，请稍后再试",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                errorMsg?.let { msg ->
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMsg = null },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { handleSubmit() }),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                            )
                        }
                    },
                    enabled = !isProcessing && lockedUntil <= System.currentTimeMillis(),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !isProcessing) {
                        Text("取消")
                    }
                    TextButton(
                        onClick = { handleSubmit() },
                        enabled = !isProcessing && password.isNotBlank() && lockedUntil <= System.currentTimeMillis()
                    ) {
                        Text("确认")
                    }
                }
            }
        }
    }
}

@Composable
fun NoPermissionDialog(
    feature: Feature,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("权限不足") },
        text = { Text("当前密钥无此模块权限，请使用更高级别密钥解锁") },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        }
    )
}
