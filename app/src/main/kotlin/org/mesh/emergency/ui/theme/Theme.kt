package org.mesh.emergency.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Slate950 = Color(0xFF090D16)
val Slate900 = Color(0xFF0F172A)
val Slate800 = Color(0xFF1E293B)
val Slate700 = Color(0xFF334155)
val Slate600 = Color(0xFF475569)
val Slate400 = Color(0xFF94A3B8)
val Slate200 = Color(0xFFE2E8F0)

val Emerald500 = Color(0xFF10B981)
val Emerald600 = Color(0xFF059669)
val Cyan400 = Color(0xFF22D3EE)
val Cyan500 = Color(0xFF06B6D4)
val Amber500 = Color(0xFFF59E0B)
val Rose500 = Color(0xFFF43F5E)
val Purple400 = Color(0xFFC084FC)

private val DarkColorScheme = darkColorScheme(
    primary = Cyan400,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF0E3A4A),
    onPrimaryContainer = Cyan400,
    secondary = Emerald500,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF0D3E2E),
    onSecondaryContainer = Emerald500,
    tertiary = Purple400,
    background = Slate950,
    onBackground = Slate200,
    surface = Slate900,
    onSurface = Slate200,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate400,
    error = Rose500,
    onError = Color.Black,
    errorContainer = Color(0xFF4C1D24),
    onErrorContainer = Rose500,
    outline = Slate600
)

@Composable
fun MeshTheme(
    darkTheme: Boolean = true, // Emergency mesh default is dark for battery conservation
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
