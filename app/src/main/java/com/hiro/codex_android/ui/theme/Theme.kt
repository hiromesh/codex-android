package com.hiro.codex_android.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = AccentWhite,
    onPrimary = AccentOnWhite,
    secondary = DarkOnSurfaceVariant,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkSurfaceElevated,
    error = ErrorRed,
    onError = AccentOnWhite,
)

/** 强制深色主题（不跟随系统、不用动态取色） */
@Composable
fun CodexandroidTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    // The external chat overlay is hosted directly by WindowManager and therefore has an
    // application Context rather than an Activity.  The Material theme is still useful there;
    // only system-bar configuration is Activity-specific.
    val activity = view.context as? Activity
    if (!view.isInEditMode && activity != null) {
        SideEffect {
            val window = activity.window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content,
    )
}
