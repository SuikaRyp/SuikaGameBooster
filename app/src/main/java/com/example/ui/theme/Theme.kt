package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Ember,
    onPrimary = TextDark,
    primaryContainer = EmberDeep,
    onPrimaryContainer = TextOnSurface,
    secondary = Ok,
    onSecondary = TextDark,
    secondaryContainer = OkDeep,
    onSecondaryContainer = TextOnSurface,
    background = InkBg,
    onBackground = TextOnSurface,
    surface = InkSurface,
    onSurface = TextOnSurface,
    surfaceVariant = InkCard,
    onSurfaceVariant = TextMuted,
    outline = Color(0xFF3A4350),
    outlineVariant = HairLine,
    error = ErrorRed
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
