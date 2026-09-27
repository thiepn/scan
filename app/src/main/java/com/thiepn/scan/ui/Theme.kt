package com.thiepn.scan.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF4D57C8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2E4FF),
    onPrimaryContainer = Color(0xFF151B62),
    secondary = Color(0xFF5B6278),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E6F8),
    onSecondaryContainer = Color(0xFF181E31),
    tertiary = Color(0xFF006C60),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9EF2DF),
    onTertiaryContainer = Color(0xFF00201B),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE5E6EE),
    onSurfaceVariant = Color(0xFF454650),
    outline = Color(0xFF767781),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBEC2FF),
    onPrimary = Color(0xFF1B2374),
    primaryContainer = Color(0xFF343C9B),
    onPrimaryContainer = Color(0xFFE2E4FF),
    secondary = Color(0xFFC3C6DD),
    onSecondary = Color(0xFF2D3142),
    secondaryContainer = Color(0xFF43485A),
    onSecondaryContainer = Color(0xFFE0E6F8),
    tertiary = Color(0xFF82D5C3),
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF005047),
    onTertiaryContainer = Color(0xFF9EF2DF),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE4E2E8),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE4E2E8),
    surfaceVariant = Color(0xFF454650),
    onSurfaceVariant = Color(0xFFC6C6D0),
    outline = Color(0xFF90909A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun ScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) {
            DarkColors
        } else {
            LightColors
        },
        content = content
    )
}
