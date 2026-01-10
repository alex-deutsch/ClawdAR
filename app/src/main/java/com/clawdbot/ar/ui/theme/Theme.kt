package com.clawdbot.ar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// High-contrast colors optimized for AR glasses display
private val ARColorScheme = darkColorScheme(
    primary = Color(0xFF00FF88),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF003D21),
    onPrimaryContainer = Color(0xFF00FF88),

    secondary = Color(0xFF00AAFF),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF003D5C),
    onSecondaryContainer = Color(0xFF00AAFF),

    tertiary = Color(0xFFFFAA00),
    onTertiary = Color.Black,

    background = Color.Black,
    onBackground = Color.White,

    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFE0E0E0),

    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFCCCCCC),

    error = Color(0xFFFF4444),
    onError = Color.Black,

    outline = Color(0xFF444444)
)

@Composable
fun ClawdARTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ARColorScheme,
        typography = Typography(),
        content = content
    )
}
