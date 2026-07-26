package io.fenjoon.app.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = FenjoonPrimary,
    onPrimary = FenjoonDarkForeground,
    secondary = FenjoonDarkSoftForeground,
    onSecondary = FenjoonDarkBackground,
    tertiary = FenjoonWarning,
    onTertiary = FenjoonDarkBackground,
    background = FenjoonDarkBackground,
    onBackground = FenjoonDarkForeground,
    surface = FenjoonDarkBackground,
    onSurface = FenjoonDarkForeground,
    surfaceVariant = FenjoonDarkSoftBackground,
    onSurfaceVariant = FenjoonDarkSoftForeground,
    outline = FenjoonDarkBorder,
    error = FenjoonDanger,
    onError = FenjoonDarkForeground
)

private val LightColorScheme = lightColorScheme(
    primary = FenjoonPrimary,
    onPrimary = FenjoonLightSoftBackground,
    secondary = FenjoonLightSoftForeground,
    onSecondary = FenjoonLightSoftBackground,
    tertiary = FenjoonWarning,
    onTertiary = FenjoonLightForeground,
    background = FenjoonLightBackground,
    onBackground = FenjoonLightForeground,
    surface = FenjoonLightBackground,
    onSurface = FenjoonLightForeground,
    surfaceVariant = FenjoonLightSoftBackground,
    onSurfaceVariant = FenjoonLightSoftForeground,
    outline = FenjoonLightBorder,
    error = FenjoonDanger,
    onError = FenjoonLightSoftBackground
)

@Composable
fun FenjoonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            val window = (context as Activity).window
            val bgColor = colorScheme.background.toArgb()
            window.statusBarColor = bgColor
            window.navigationBarColor = bgColor
            window.setBackgroundDrawable(ColorDrawable(bgColor))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }

            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
