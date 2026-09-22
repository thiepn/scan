package com.thiepn.scan.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF5252CC),
    secondary = Color(0xFF5E5E71),
    tertiary = Color(0xFF006B5B),
    surface = Color(0xFFFAF9FF),
    background = Color(0xFFFAF9FF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC1C0FF),
    secondary = Color(0xFFC8C6D8),
    tertiary = Color(0xFF73DBC6)
)

@Composable
fun ScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
