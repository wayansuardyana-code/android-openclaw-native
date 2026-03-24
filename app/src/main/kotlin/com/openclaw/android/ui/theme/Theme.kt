package com.openclaw.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF58A6FF),
    secondary = Color(0xFF3FB950),
    tertiary = Color(0xFFD2A8FF),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    onPrimary = Color(0xFFF0F6FC),
    onSecondary = Color(0xFFF0F6FC),
    onBackground = Color(0xFFF0F6FC),
    onSurface = Color(0xFFC9D1D9),
    error = Color(0xFFF85149),
    outline = Color(0xFF30363D)
)

@Composable
fun OpenClawTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
