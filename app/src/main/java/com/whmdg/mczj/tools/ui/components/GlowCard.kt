package com.whmdg.mczj.tools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.whmdg.mczj.tools.ui.theme.LocalIsDarkMode
import com.whmdg.mczj.tools.ui.theme.LocalIsGlowEnabled

/**
 * 光晕扩散效果 Modifier
 * 使用 BlurMaskFilter 在 Canvas 上越界绘制，全版本可用
 */
fun Modifier.glowEffect(
    glowColor: Color,
    glowRadius: Dp = 11.dp,
    cornerRadius: Dp = 20.dp,
    glowAlpha: Float = 0.42f,
) = this.drawWithContent {
    drawIntoCanvas { canvas ->
        val glowPx = glowRadius.toPx()
        val cornerPx = cornerRadius.toPx()

        val paint = Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            maskFilter = android.graphics.BlurMaskFilter(
                glowPx,
                android.graphics.BlurMaskFilter.Blur.NORMAL
            )
            color = glowColor.copy(alpha = glowAlpha).toArgb()
        }

        // 越界绘制：向外扩展 glowPx/2
        canvas.nativeCanvas.drawRoundRect(
            -glowPx / 2,
            -glowPx / 2,
            size.width + glowPx / 2,
            size.height + glowPx / 2,
            cornerPx,
            cornerPx,
            paint
        )

        // 绘制原有内容
        drawContent()
    }
}

/**
 * 基础光晕卡片容器
 * 提供光晕扩散效果、边框、渐变背景
 */
@Composable
fun GlowCard(
    modifier: Modifier = Modifier,
    glowColor: Color = Color(0xFF00C8FF),
    cornerRadius: Dp = 20.dp,
    content: @Composable () -> Unit
) {
    val glowEnabled = LocalIsGlowEnabled.current
    val isDarkMode = LocalIsDarkMode.current

    // 根据主题模式选择颜色
    val borderAlpha = if (isDarkMode) 0.35f else 0.25f
    val outerGlowAlpha = if (isDarkMode) 0.09f else 0.06f
    val backgroundColors = if (isDarkMode) {
        // 暗青色渐变到黑色
        listOf(
            Color(0xFF0A2525),
            Color(0xFF061A1A),
            Color(0xFF020E0E)
        )
    } else {
        // 亮青色渐变到白色
        listOf(
            Color(0xFFE0F7FA),
            Color(0xFFE8F5E9),
            Color(0xFFF5F5F5)
        )
    }
    val shadowAlpha = if (isDarkMode) 4.dp else 2.dp

    Box(
        modifier = modifier.padding(
            horizontal = 16.dp,
            vertical = if (glowEnabled) 14.dp else 6.dp
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (glowEnabled) {
                        Modifier.glowEffect(
                            glowColor = glowColor,
                            glowRadius = 11.dp,
                            cornerRadius = cornerRadius
                        )
                    } else {
                        Modifier
                    }
                )
                .drawBehind {
                    // 边框
                    val borderColor = if (isDarkMode) Color(0xFF00D2FF) else Color(0xFF00BCD4)
                    drawRoundRect(
                        color = borderColor.copy(alpha = borderAlpha),
                        cornerRadius = CornerRadius(cornerRadius.toPx()),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    // 外晕（仅开启光晕时显示）
                    if (glowEnabled) {
                        val outerGlowColor = if (isDarkMode) Color(0xFF008CC8) else Color(0xFF00838F)
                        drawRoundRect(
                            color = outerGlowColor.copy(alpha = outerGlowAlpha),
                            cornerRadius = CornerRadius((cornerRadius + 1.5.dp).toPx()),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                },
            shape = RoundedCornerShape(cornerRadius),
            color = Color.Transparent,
            shadowElevation = shadowAlpha
        ) {
            Box(
                modifier = Modifier.background(
                    Brush.linearGradient(colors = backgroundColors)
                )
            ) {
                content()
            }
        }
    }
}

/**
 * 光晕分组容器（替代 SettingsSection 的 Card 部分）
 * 带标题和图标，内部包含多个设置项
 */
@Composable
fun GlowSection(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        GlowCard(modifier = modifier) {
            Column(
                modifier = Modifier.padding(12.dp),
                content = content
            )
        }
    }
}

/**
 * 光晕列表项（替代 Material 3 ListItem）
 * 用于设置页面的可点击项
 */
@Composable
fun GlowListItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Color(0xFF38D4F5),
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val isDarkMode = LocalIsDarkMode.current
    val alpha = if (enabled) 1f else 0.5f

    // 根据主题模式选择颜色
    val titleColor = if (isDarkMode) Color(0xFFE8F4FF) else Color(0xFF1E293B)
    val subtitleColor = if (isDarkMode) Color(0x9964B4D2) else Color(0x9964748B)
    val arrowColor = if (isDarkMode) Color(0xFF38D4F5) else Color(0xFF0EA5E9)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .alpha(alpha)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) iconTint else iconTint.copy(alpha = 0.38f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) titleColor else titleColor.copy(alpha = 0.38f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) subtitleColor else subtitleColor.copy(alpha = 0.38f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = if (enabled) arrowColor else arrowColor.copy(alpha = 0.38f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 光晕切换项（带 Switch 的设置项）
 */
@Composable
fun GlowToggleItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val isDarkMode = LocalIsDarkMode.current

    // 根据主题模式选择颜色
    val iconColor = if (isDarkMode) Color(0xFF38D4F5) else Color(0xFF0EA5E9)
    val titleColor = if (isDarkMode) Color(0xFFE8F4FF) else Color(0xFF1E293B)
    val subtitleColor = if (isDarkMode) Color(0x9964B4D2) else Color(0x9964748B)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) iconColor else iconColor.copy(alpha = 0.38f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) titleColor else titleColor.copy(alpha = 0.38f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) subtitleColor else subtitleColor.copy(alpha = 0.38f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/**
 * 光晕信息行（键值对显示）
 */
@Composable
fun GlowInfoRow(label: String, value: String) {
    val isDarkMode = LocalIsDarkMode.current

    // 根据主题模式选择颜色
    val labelColor = if (isDarkMode) Color(0x9964B4D2) else Color(0x9964748B)
    val valueColor = if (isDarkMode) Color(0xFFA8D4F0) else Color(0xFF0EA5E9)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = labelColor,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.03.em
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
