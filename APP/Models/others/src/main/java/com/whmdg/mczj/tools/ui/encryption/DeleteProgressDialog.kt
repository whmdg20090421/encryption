package com.whmdg.mczj.tools.ui.encryption

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whmdg.mczj.tools.ui.theme.LocalIsDarkMode

@Composable
fun DeleteProgressDialog(
    phase: String,
    localProgress: Float,
    cloudProgress: Float,
    showLocalProgress: Boolean,
    showCloudProgress: Boolean,
    isComplete: Boolean,
    onDismiss: () -> Unit
) {
    val isDarkMode = LocalIsDarkMode.current
    val textColor = if (isDarkMode) Color(0xFFE2E8F0) else Color(0xFF1E293B)
    val subTextColor = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)

    AlertDialog(
        onDismissRequest = { if (isComplete) onDismiss() },
        title = { Text(if (isComplete) "删除完成" else "正在删除") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(phase, fontSize = 14.sp, color = textColor)

                if (showLocalProgress) {
                    Text("本地删除进度", fontSize = 13.sp, color = subTextColor)
                    LinearProgressIndicator(
                        progress = { localProgress },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = Color(0xFF3B82F6),
                        trackColor = if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0)
                    )
                    Text(
                        "${(localProgress * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = subTextColor,
                        modifier = Modifier.align(Alignment.End)
                    )
                }

                if (showCloudProgress) {
                    if (showLocalProgress) Spacer(Modifier.height(4.dp))
                    Text("云端删除进度", fontSize = 13.sp, color = subTextColor)
                    LinearProgressIndicator(
                        progress = { cloudProgress },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = Color(0xFF10B981),
                        trackColor = if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0)
                    )
                    Text(
                        "${(cloudProgress * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = subTextColor,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        },
        confirmButton = {
            if (isComplete) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {}
    )
}
