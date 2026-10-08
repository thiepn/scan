package com.thiepn.scan.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Scan 2.0: restrained paper-and-ink visual language.
 *
 * Surfaces are neutral so document previews, not chrome, carry the visual weight.
 * Corners are intentionally tighter than the default Material 3 pill/card styling.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF126B64),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4EEE9),
    onPrimaryContainer = Color(0xFF123F3A),
    secondary = Color(0xFF586360),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4EBE8),
    onSecondaryContainer = Color(0xFF25312E),
    tertiary = Color(0xFF405B73),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCE8F1),
    onTertiaryContainer = Color(0xFF1B364A),
    background = Color(0xFFF8F9F7),
    onBackground = Color(0xFF1B2422),
    surface = Color(0xFFFCFDFB),
    onSurface = Color(0xFF1B2422),
    surfaceVariant = Color(0xFFE9EFEC),
    onSurfaceVariant = Color(0xFF53615D),
    outline = Color(0xFF7B8984),
    outlineVariant = Color(0xFFD5DFDA),
    surfaceContainerLow = Color(0xFFF3F7F4),
    surfaceContainer = Color(0xFFEDF2EF),
    surfaceContainerHigh = Color(0xFFE6EEE9),
    error = Color(0xFFAE3436),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD8),
    onErrorContainer = Color(0xFF4A1415)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF87DAD0),
    onPrimary = Color(0xFF003D38),
    primaryContainer = Color(0xFF245A54),
    onPrimaryContainer = Color(0xFFD4EEE9),
    secondary = Color(0xFFB8C9C2),
    onSecondary = Color(0xFF273730),
    secondaryContainer = Color(0xFF394A43),
    onSecondaryContainer = Color(0xFFE1EEE6),
    tertiary = Color(0xFFBDD4E9),
    onTertiary = Color(0xFF233F55),
    tertiaryContainer = Color(0xFF344E62),
    onTertiaryContainer = Color(0xFFDCE8F1),
    background = Color(0xFF101816),
    onBackground = Color(0xFFE4EDE8),
    surface = Color(0xFF151E1B),
    onSurface = Color(0xFFE4EDE8),
    surfaceVariant = Color(0xFF2D3833),
    onSurfaceVariant = Color(0xFFAFBFB7),
    outline = Color(0xFF8B9C93),
    outlineVariant = Color(0xFF3B4A43),
    surfaceContainerLow = Color(0xFF1A2420),
    surfaceContainer = Color(0xFF202D27),
    surfaceContainerHigh = Color(0xFF283730),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val ScanShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp)
)

@Composable
fun ScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = ScanShapes,
        content = content
    )
}
