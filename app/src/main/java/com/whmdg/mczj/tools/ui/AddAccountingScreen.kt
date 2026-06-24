package com.whmdg.mczj.tools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun AddAccountingScreen(onBack: () -> Unit, bookName: String) {
    var selectedType by remember { mutableIntStateOf(0) }
    val types = listOf("支出", "收入", "转账", "债务")
    var amount by remember { mutableStateOf("0") }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 50dp 功能栏
            Surface(
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                types.forEachIndexed { index, type ->
                    TextButton(onClick = { selectedType = index }) {
                        Text(
                            text = type,
                            color = if (selectedType == index)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = bookName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
            } // Surface

            // 金额显示区
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = amount,
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center
                )
            }

            // 键盘
            Surface(shadowElevation = 2.dp) {
            CalculatorKeyboard(
                onInput = { key ->
                    amount = when (key) {
                        "←" -> if (amount.length > 1) amount.dropLast(1) else "0"
                        "." -> if (!amount.contains(".")) "$amount." else amount
                        "再记" -> { "0" } // TODO: 保存记录
                        "完成" -> { onBack(); amount }
                        else -> if (amount == "0" && key !in listOf("+", "-", "*", "÷"))
                            key
                        else
                            amount + key
                    }
                }
            )
            } // Surface
        }
    }
}

@Composable
private fun CalculatorKeyboard(onInput: (String) -> Unit) {
    val keySpacing = 2.dp
    val keyShape = RoundedCornerShape(6.dp)
    val keyColor = MaterialTheme.colorScheme.surfaceVariant
    val isDark = isSystemInDarkTheme()
    val cyanText = if (isDark) Color(0xFF00838F) else Color(0xFF00BCD4)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(keySpacing),
        verticalArrangement = Arrangement.spacedBy(keySpacing)
    ) {
        // 第1行: 1 2 3 ←
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(keySpacing)
        ) {
            KeyButton("1", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton("2", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton("3", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton("←", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput, icon = true)
        }
        // 第2行: 4 5 6 [-|*]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(keySpacing)
        ) {
            KeyButton("4", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton("5", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton("6", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            // 左右分：- 和 *
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(keySpacing)) {
                KeyButton("-", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
                KeyButton("*", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            }
        }
        // 第3行: 7 8 9 [+|÷]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(keySpacing)
        ) {
            KeyButton("7", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton("8", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton("9", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            // 左右分：+ 和 ÷
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(keySpacing)) {
                KeyButton("+", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
                KeyButton("÷", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            }
        }
        // 第4行: 再记 0 . 完成
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(keySpacing)
        ) {
            KeyButton("再记", Modifier.weight(1f), keyShape, MaterialTheme.colorScheme.primaryContainer, cyanText, onInput)
            KeyButton("0", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton(".", Modifier.weight(1f), keyShape, keyColor, cyanText, onInput)
            KeyButton("完成", Modifier.weight(1f), keyShape, MaterialTheme.colorScheme.primaryContainer, cyanText, onInput)
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    modifier: Modifier,
    shape: RoundedCornerShape,
    containerColor: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    onInput: (String) -> Unit,
    icon: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val bgColor = if (isPressed)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    else
        containerColor

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = null) { onInput(label) },
        contentAlignment = Alignment.Center
    ) {
        if (icon) {
            Icon(
                Icons.Filled.Backspace,
                contentDescription = "退格",
                tint = textColor
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = textColor
            )
        }
    }
}
