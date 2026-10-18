package io.github.baldborne94.baldmusic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFF7A59)
val Accent2 = Color(0xFFC026D3)
val AccentBrush = Brush.linearGradient(listOf(Accent, Accent2))

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Accent2,
    background = Color(0xFF0E0F13),
    onBackground = Color(0xFFF2F3F7),
    surface = Color(0xFF15171D),
    onSurface = Color(0xFFF2F3F7),
    surfaceVariant = Color(0xFF242834),
    onSurfaceVariant = Color(0xFF9AA1B2),
    surfaceContainer = Color(0xFF1B1E26),
    surfaceContainerHigh = Color(0xFF242834),
    outline = Color(0xFF2C3140),
    error = Color(0xFFFF5C6C),
)

@Composable
fun BaldMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
