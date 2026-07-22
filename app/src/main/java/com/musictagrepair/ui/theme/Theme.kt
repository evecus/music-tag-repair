package com.musictagrepair.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import android.os.Build

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB69CFF),
    secondary = Color(0xFFCBC2DC),
    tertiary = Color(0xFFEFB8C8),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6B4EFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DEFF),
    onPrimaryContainer = Color(0xFF1F0061),
    secondary = Color(0xFF605C71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6DFF1),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    background = Color(0xFFFEF7FF),
    surface = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B20),
    onSurface = Color(0xFF1D1B20),
)

@Composable
fun MusicTagRepairTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // 设置状态栏颜色
    val view = (LocalContext.current as? Activity)?.window
    view?.statusBarColor = colorScheme.primary.toArgb()

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
