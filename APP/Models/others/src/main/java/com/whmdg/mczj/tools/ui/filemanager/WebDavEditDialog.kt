package com.whmdg.mczj.tools.ui.filemanager

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.fileop.webdav.WebDavAuthenticator
import com.whmdg.mczj.tools.fileop.webdav.WebDavFileClient
import com.whmdg.mczj.tools.fileop.webdav.WebDavServerConfig
import com.whmdg.mczj.tools.fileop.webdav.WebDavServerStore
import com.whmdg.mczj.tools.fileop.webdav.client.Authority
import com.whmdg.mczj.tools.fileop.webdav.client.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WebDAV server edit dialog.
 * Follows MaterialFiles' EditWebDavServerFragment logic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavEditDialog(
    existingConfig: WebDavServerConfig? = null,
    onDismiss: () -> Unit,
    onSaved: (WebDavServerConfig) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var protocol by remember { mutableStateOf(existingConfig?.protocol ?: "davs") }
    var host by remember { mutableStateOf(existingConfig?.host ?: "") }
    var port by remember { mutableStateOf(existingConfig?.port?.toString() ?: "") }
    var path by remember { mutableStateOf(existingConfig?.relativePath ?: "") }
    var authType by remember { mutableStateOf(existingConfig?.authType ?: "password") }
    var username by remember { mutableStateOf(existingConfig?.username ?: "") }
    var password by remember { mutableStateOf(existingConfig?.password ?: "") }
    var name by remember { mutableStateOf(existingConfig?.name ?: "") }

    var isConnecting by remember { mutableStateOf(false) }
    var hostError by remember { mutableStateOf<String?>(null) }
    var portError by remember { mutableStateOf<String?>(null) }
    var usernameError by remember { mutableStateOf<String?>(null) }

    val defaultPort = if (protocol == "dav") 80 else 443

    fun updateNamePlaceholder(): String {
        val proto = if (protocol == "dav") Protocol.DAV else Protocol.DAVS
        val portInt = port.toIntOrNull() ?: defaultPort
        val userStr = if (authType == "password" && username.isNotEmpty()) "$username@" else ""
        val portStr = if (portInt != proto.defaultPort) ":$portInt" else ""
        val base = "$userStr${host.ifEmpty { "..." }}$portStr"
        return if (path.isNotEmpty()) "$base/$path" else base
    }

    fun buildConfig(): WebDavServerConfig? {
        // Validate
        hostError = null
        portError = null
        usernameError = null

        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) {
            hostError = "主机名不能为空"
            return null
        }

        val portInt = port.trim().let {
            if (it.isEmpty()) defaultPort
            else it.toIntOrNull()?.also { p ->
                if (p !in 1..65535) { portError = "端口无效"; return null }
            } ?: run { portError = "端口无效"; return null }
        }

        val trimmedUsername = username.trim()
        if (authType == "password" && trimmedUsername.isEmpty()) {
            usernameError = "用户名不能为空"
            return null
        }

        return WebDavServerConfig(
            id = existingConfig?.id ?: System.currentTimeMillis(),
            name = name.trim().ifEmpty { updateNamePlaceholder() },
            protocol = protocol,
            host = trimmedHost,
            port = portInt,
            username = trimmedUsername,
            password = password,
            authType = authType,
            relativePath = path.trim()
        )
    }

    fun connectAndAdd() {
        val config = buildConfig() ?: return
        isConnecting = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Register auth temporarily for connection test
                    WebDavAuthenticator.addTransientServer(config)
                    try {
                        val client = WebDavFileClient(config)
                        client.testConnection()
                    } finally {
                        WebDavAuthenticator.removeTransientServer(config)
                    }
                }
                // Connection succeeded, save
                WebDavServerStore.save(context, config)
                onSaved(config)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "连接成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "连接失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                isConnecting = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isConnecting) onDismiss() },
        title = {
            Text(if (existingConfig != null) "编辑 WebDAV 服务器" else "添加 WebDAV 服务器")
        },
        text = {
            if (isConnecting) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("正在连接...")
                }
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // Protocol
                    var protocolExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = protocolExpanded,
                        onExpandedChange = { protocolExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = if (protocol == "davs") "DAVS (HTTPS)" else "DAV (HTTP)",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("协议") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(protocolExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = protocolExpanded,
                            onDismissRequest = { protocolExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("DAVS (HTTPS)") },
                                onClick = { protocol = "davs"; protocolExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("DAV (HTTP)") },
                                onClick = { protocol = "dav"; protocolExpanded = false }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Host
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it; hostError = null },
                        label = { Text("主机名") },
                        singleLine = true,
                        isError = hostError != null,
                        supportingText = hostError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    // Port
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it; portError = null },
                        label = { Text("端口") },
                        placeholder = { Text(defaultPort.toString()) },
                        singleLine = true,
                        isError = portError != null,
                        supportingText = portError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    // Path
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("路径") },
                        placeholder = { Text("/") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    // Authentication type
                    var authExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = authExpanded,
                        onExpandedChange = { authExpanded = it }
                    ) {
                        val authLabel = when (authType) {
                            "password" -> "密码"
                            "token" -> "访问令牌"
                            else -> "无"
                        }
                        OutlinedTextField(
                            value = authLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("认证类型") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(authExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = authExpanded,
                            onDismissRequest = { authExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("密码") },
                                onClick = { authType = "password"; authExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("访问令牌") },
                                onClick = { authType = "token"; authExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("无") },
                                onClick = { authType = "none"; authExpanded = false }
                            )
                        }
                    }

                    // Username (only for password auth)
                    AnimatedVisibility(authType == "password") {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it; usernameError = null },
                                label = { Text("用户名") },
                                singleLine = true,
                                isError = usernameError != null,
                                supportingText = usernameError?.let { { Text(it) } },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Password / Token
                    when (authType) {
                        "password" -> {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("密码") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        "token" -> {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("访问令牌") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("自定义名称") },
                        placeholder = { Text(updateNamePlaceholder()) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (!isConnecting) {
                TextButton(onClick = { connectAndAdd() }) {
                    Text(if (existingConfig != null) "保存" else "连接并添加")
                }
            }
        },
        dismissButton = {
            if (!isConnecting) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}
