package com.oldchat.material.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.oldchat.material.core.cache.DpiManager
import com.oldchat.material.core.cache.dataStore

/**
 * CompositionLocal for DPI scaling across the entire UI tree.
 */
val LocalFontScale = compositionLocalOf { 1.0f }
val LocalDisplayScale = compositionLocalOf { 1.0f }

/**
 * Helper to scale dp values by the current display scale.
 */
@Composable
fun Dp.scaled(): Dp = this * LocalDisplayScale.current

/**
 * Helper to scale sp values by the current font scale.
 */
@Composable
fun Dp.scaledSp(): TextUnit = (this.value * LocalFontScale.current).sp

private val DarkColorScheme = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    error = md_theme_dark_error,
    errorContainer = md_theme_dark_errorContainer,
    onError = md_theme_dark_onError,
    onErrorContainer = md_theme_dark_onErrorContainer,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
    outline = md_theme_dark_outline,
    inverseOnSurface = md_theme_dark_inverseOnSurface,
    inverseSurface = md_theme_dark_inverseSurface,
    inversePrimary = md_theme_dark_inversePrimary,
    surfaceTint = md_theme_dark_surfaceTint
)

private val LightColorScheme = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,
    error = md_theme_light_error,
    errorContainer = md_theme_light_errorContainer,
    onError = md_theme_light_onError,
    onErrorContainer = md_theme_light_onErrorContainer,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,
    outline = md_theme_light_outline,
    inverseOnSurface = md_theme_light_inverseOnSurface,
    inverseSurface = md_theme_light_inverseSurface,
    inversePrimary = md_theme_light_inversePrimary,
    surfaceTint = md_theme_light_surfaceTint
)

@Composable
fun OldChatMaterialTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    // Read user appearance preference (overrides system theme when set).
    val context = LocalContext.current
    val preferences = com.oldchat.material.OldChatApplication.instance.cacheManager.preferences
    val isDarkPref by preferences.isDarkMode.collectAsState(initial = darkTheme)
    // BUG-08：原来动态取色只是函数默认参数，设置里的开关写了 DataStore 但没人读 →
    // 开关完全无效。这里真正接上线。
    val dynamicColorPref by preferences.useDynamicColor.collectAsState(initial = true)

    // Read DPI scales from DataStore
    val dpiManager = remember { DpiManager(preferences) }
    val fontScale by dpiManager.fontScale.collectAsState(initial = DpiManager.DEFAULT_SCALE)
    val displayScale by dpiManager.displayScale.collectAsState(initial = DpiManager.DEFAULT_SCALE)

    val colorScheme = when {
        (dynamicColor && dynamicColorPref) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDarkPref) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDarkPref -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDarkPref
        }
    }

    // Apply DPI scales: font scale affects text (sp), display scale affects dp.
    val baseDensity = LocalDensity.current
    val scaledDensity = Density(
        density = baseDensity.density * displayScale,
        fontScale = baseDensity.fontScale * fontScale
    )

    CompositionLocalProvider(
        LocalFontScale provides fontScale,
        LocalDisplayScale provides displayScale,
        LocalDensity provides scaledDensity
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = OldChatTypography,
            content = content
        )
    }
}

