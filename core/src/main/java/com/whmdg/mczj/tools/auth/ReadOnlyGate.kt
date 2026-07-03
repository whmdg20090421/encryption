package com.whmdg.mczj.tools.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 只读模式包装组件。
 * 当 LocalPermissionGate.current 为 false 时：
 * 1. 显示"只读模式"提示
 * 2. 在内容上方覆盖透明遮罩，拦截所有触摸事件
 * 3. 返回按钮需要单独处理（不在这个组件内）
 */
@Composable
fun ReadOnlyWrapper(
    content: @Composable () -> Unit
) {
    val isReadOnly = !LocalPermissionGate.current

    Box(modifier = Modifier.fillMaxSize()) {
        content()

        if (isReadOnly) {
            // 透明遮罩层，拦截所有触摸事件
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // 拦截所有触摸事件，阻止传递给下层组件
                        awaitEachGesture {
                            awaitFirstDown(pass = PointerEventPass.Initial)
                            // 不消费事件，但阻止传递给子组件
                        }
                    }
            )

            // 只读模式提示（显示在顶部）
            Surface(
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "当前为只读模式，请使用更高级别密钥解锁完整功能",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}
