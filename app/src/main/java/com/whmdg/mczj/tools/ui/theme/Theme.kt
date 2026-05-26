package com.whmdg.mczj.tools.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

val LocalIsDarkMode = compositionLocalOf { true }
val LocalOnToggleTheme = compositionLocalOf<((Boolean) -> Unit)> { {} }

val LocalCustomBgEnabled = compositionLocalOf { false }
val LocalBgImagePath = compositionLocalOf<String?> { null }
val LocalBgImageAlpha = compositionLocalOf { 1f }
val LocalBgUiAlpha = compositionLocalOf { 1f }
val LocalOnSetCustomBg = compositionLocalOf<(Boolean) -> Unit> { {} }
val LocalOnSetBgImage = compositionLocalOf<(String?) -> Unit> { {} }
val LocalOnSetBgImageAlpha = compositionLocalOf<(Float) -> Unit> { {} }
val LocalOnSetBgUiAlpha = compositionLocalOf<(Float) -> Unit> { {} }

private val dynamicColor = true

@Composable
fun 工具箱Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}