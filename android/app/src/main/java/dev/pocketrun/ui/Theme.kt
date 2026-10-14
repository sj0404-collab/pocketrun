package dev.pocketrun.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Fixed dark palette matched to the launcher background (#12161F). The platform
 * theme (android:Theme.Material) is dark, so the Compose layer follows suit on
 * every device instead of depending on the system setting.
 */
private val PocketRunColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF274069),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFF81C995),
    onSecondary = Color(0xFF0B391C),
    tertiary = Color(0xFFFDD663),
    error = Color(0xFFF28B82),
    onError = Color(0xFF5C1A14),
    background = Color(0xFF12161F),
    onBackground = Color(0xFFE3E6EE),
    surface = Color(0xFF12161F),
    onSurface = Color(0xFFE3E6EE),
    surfaceVariant = Color(0xFF1C2230),
    onSurfaceVariant = Color(0xFFB6BCCB),
    outline = Color(0xFF474E60),
)

@Composable
fun PocketRunTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PocketRunColors, content = content)
}
