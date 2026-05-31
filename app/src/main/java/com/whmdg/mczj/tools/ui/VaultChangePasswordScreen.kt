package com.whmdg.mczj.tools.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.encryption.data.VaultRecord
import com.whmdg.mczj.tools.encryption.services.VaultService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultChangePasswordScreen(
    vaultService: VaultService,
    vault: VaultRecord,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var oldPwd by remember { mutableStateOf("") }
    var newPwd1 by remember { mutableStateOf("") }
    var newPwd2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun onSubmit() {
        if (oldPwd.isEmpty() || newPwd1.isEmpty() || newPwd2.isEmpty()) {
            Toast.makeText(context, "请填齐所有必需字段", Toast.LENGTH_SHORT).show()
            return
        }
        if (newPwd1 != newPwd2) {
            Toast.makeText(context, "两次新密码输入不一致", Toast.LENGTH_SHORT).show()
            return
        }

        busy = true
        coroutineScope.launch(Dispatchers.Default) {
            try {
                vaultService.changePassword(
                    id = vault.id,
                    oldPassword = oldPwd,
                    newPassword = newPwd1
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "密码已修改", Toast.LENGTH_SHORT).show()
                    onBack()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "修改失败: ${e.message}", Toast.LENGTH_LONG).show()
                    busy = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("修改密码 · ${vault.name}") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = oldPwd,
                        onValueChange = { oldPwd = it },
                        label = { Text("旧密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    OutlinedTextField(
                        value = newPwd1,
                        onValueChange = { newPwd1 = it },
                        label = { Text("新密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    OutlinedTextField(
                        value = newPwd2,
                        onValueChange = { newPwd2 = it },
                        label = { Text("再次输入新密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }

                item {
                    Button(
                        onClick = { onSubmit() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (busy) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("确定修改")
                    }
                }
            }
        }
    }
}
