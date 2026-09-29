package org.lumina.reader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import org.lumina.reader.data.preferences.AppThemeMode
import org.lumina.reader.data.preferences.DarkThemeStyle

private val LightColorScheme = lightColorScheme(
    primary = LuminaPrimary,
    onPrimary = LuminaOnPrimary,
    primaryContainer = LuminaPrimaryContainer,
    onPrimaryContainer = LuminaOnPrimaryContainer,
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0)
)

private val SlateDarkColorScheme = darkColorScheme(
    primary = LuminaSlatePrimary,
    onPrimary = Color(0xFF00354E),
    primaryContainer = LuminaSlatePrimaryContainer,
    onPrimaryContainer = LuminaSlateOnPrimaryContainer,
    background = LuminaSlateBackground,
    surface = LuminaSlateSurface,
    surfaceVariant = LuminaSlateSurfaceVariant,
    onBackground = LuminaSlateOnSurface,
    onSurface = LuminaSlateOnSurface,
    onSurfaceVariant = LuminaSlateOnSurfaceVariant,
    outline = LuminaSlateOutline,
    outlineVariant = LuminaSlateOutlineVariant
)

private val AmoledDarkColorScheme = darkColorScheme(
    primary = LuminaAmoledPrimary,
    onPrimary = Color(0xFF00354E),
    primaryContainer = LuminaAmoledPrimaryContainer,
    onPrimaryContainer = LuminaAmoledOnPrimaryContainer,
    background = LuminaAmoledBackground,
    surface = LuminaAmoledSurface,
    surfaceVariant = LuminaAmoledSurfaceVariant,
    onBackground = LuminaAmoledOnSurface,
    onSurface = LuminaAmoledOnSurface,
    onSurfaceVariant = LuminaAmoledOnSurfaceVariant,
    outline = LuminaAmoledOutline,
    outlineVariant = LuminaAmoledOutlineVariant
)

@Composable
fun LuminaTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    darkThemeStyle: DarkThemeStyle = DarkThemeStyle.SLATE,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> systemInDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> {
            when (darkThemeStyle) {
                DarkThemeStyle.SLATE -> SlateDarkColorScheme
                DarkThemeStyle.AMOLED -> AmoledDarkColorScheme
            }
        }
        else -> LightColorScheme
    }

    // 状态栏与导航栏图标自适应明暗
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
