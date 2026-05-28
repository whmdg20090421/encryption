package com.whmdg.mczj.tools.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun PasswordDialog(
    onDismiss: () -> Unit,
    onResult: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var errorCount by remember { mutableIntStateOf(0) }
    var lockedUntil by remember { mutableLongStateOf(0L) }
    var isProcessing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        title = { Text("输入密钥") },
        text = {
            Column {
                val now = System.currentTimeMillis()
                if (lockedUntil > now) {
                    Text(
                        "操作过于频繁，请稍后再试",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            if (!isProcessing && password.isNotBlank()) {
                                isProcessing = true
                                onResult(password)
                            }
                        }),
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!isProcessing && password.isNotBlank()) {
                        isProcessing = true
                        onResult(password)
                    }
                },
                enabled = !isProcessing && password.isNotBlank()
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isProcessing) {
                Text("取消")
            }
        }
    )
}

@Composable
fun NoPermissionDialog(
    feature: Feature,
    onDismiss: () -> Unit,
    onEnter: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("权限不足") },
        text = { Text("当前密钥无此模块权限，请使用更高级别密钥或联系作者。") },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
