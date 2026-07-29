package com.bydmapcam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Only primary/secondary used to be set, so every *container* colour fell back to Material3's
// baseline purple — which is what tinted the buttons. The scheme is spelled out end to end now,
// all of it in the same blue family as the speed readout.
private val LightColors = lightColorScheme(
    primary = Color(0xFF0D47A1),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E3FF),
    onPrimaryContainer = Color(0xFF001C39),
    secondary = Color(0xFF1E88E5),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E7F8),
    onSecondaryContainer = Color(0xFF0B2942),
    tertiary = Color(0xFF00668B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC3E8FF),
    onTertiaryContainer = Color(0xFF001E2C),
    background = Color(0xFFFBFCFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFBFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA6C8FF),
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF00468A),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF90CAF9),
    onSecondary = Color(0xFF00325A),
    secondaryContainer = Color(0xFF24425D),
    onSecondaryContainer = Color(0xFFCDE5FF),
    tertiary = Color(0xFF7CD0FF),
    onTertiary = Color(0xFF003549),
    tertiaryContainer = Color(0xFF004C67),
    onTertiaryContainer = Color(0xFFC3E8FF),
    background = Color(0xFF121417),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF121417),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF)
)

@Composable
fun BydMapCamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
