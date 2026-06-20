package com.spendwise.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF7B00FF),
    secondary = Color(0xFF6B5B95),
    tertiary = Color(0xFF00B875),
    background = Color(0xFFF8F5FF),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF17102A),
    onSurface = Color(0xFF17102A),
    primaryContainer = Color(0xFFEFE2FF),
    secondaryContainer = Color(0xFFF3EDFF),
    tertiaryContainer = Color(0xFFE4FFF2)
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    secondary = Color(0xFFCBD5E1),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF111827),
    surface = Color(0xFF1F2937),
    onPrimary = Color(0xFF042F2E),
    onSecondary = Color(0xFF111827),
    onBackground = Color(0xFFF9FAFB),
    onSurface = Color(0xFFF9FAFB)
)

@Composable
fun SpendWiseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
