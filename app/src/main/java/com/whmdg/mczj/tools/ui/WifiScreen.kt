package com.whmdg.mczj.tools.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val PREFS_NAME = "wifi_disclaimer"
private const val KEY_ACCEPTED = "accepted"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var disclaimerAccepted by remember { mutableStateOf(prefs.getBoolean(KEY_ACCEPTED, false)) }
    var showDisclaimer by remember { mutableStateOf(!disclaimerAccepted) }

    if (showDisclaimer) {
        DisclaimerDialog(
            onAccept = {
                prefs.edit().putBoolean(KEY_ACCEPTED, true).apply()
                disclaimerAccepted = true
                showDisclaimer = false
            },
            onDismiss = {
                onBack()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "WiFi 模块开发中",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DisclaimerDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Text("免责声明")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "本 WiFi 工具模块仅供网络安全测试、设备调试及学术研究等合法用途。使用本模块即表示您确认并同意以下条款：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "1. 您保证仅在自有网络或已获得明确授权的网络环境中使用本模块的各项功能。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "2. 您理解并同意，任何在未经授权的公共网络、他人私有网络或其他非法场景中使用本模块特定功能的行为，均可能违反《中华人民共和国网络安全法》《中华人民共和国刑法》等相关法律法规，相关法律后果由使用者自行承担。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "3. 开发者不对因使用本模块而产生的任何直接或间接损失承担责任，包括但不限于数据丢失、设备损坏、法律责任等。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "4. 若您不同意上述条款，请点击"取消"退出本模块。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "使用本模块即视为您已充分阅读、理解并同意本免责声明的全部内容。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text("同意")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
