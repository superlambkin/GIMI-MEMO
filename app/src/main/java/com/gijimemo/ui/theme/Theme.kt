package com.gijimemo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * GijiMemo テーマ
 * - ライトモード: 全テキスト黒(#000000)
 * - ダークモード: 全テキスト白系統
 */
private val AppLightColorScheme = lightColorScheme(
    primary = MujiRed,
    onPrimary = Surface,
    primaryContainer = MujiRedLight,
    onPrimaryContainer = MujiRedDark,

    secondary = SurfaceVariant,
    onSecondary = TextPrimary,

    tertiary = TextPrimary,
    onTertiary = Surface,

    background = Background,
    onBackground = TextPrimary,

    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextPrimary,  // ← 黒に統一

    outline = Outline,

    error = Error,
    onError = Surface
)

private val AppDarkColorScheme = darkColorScheme(
    primary = MujiRed,
    onPrimary = DarkBackground,
    primaryContainer = MujiRedDark,
    onPrimaryContainer = MujiRedLight,

    secondary = DarkSurface,
    onSecondary = DarkTextPrimary,

    tertiary = DarkTextPrimary,
    onTertiary = DarkBackground,

    background = DarkBackground,
    onBackground = DarkTextPrimary,

    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,

    outline = DarkSurfaceVariant,

    error = MujiRed,
    onError = DarkBackground
)

/**
 * @param themeMode 0=システム追従, 1=ライト固定, 2=ダーク固定
 */
@Composable
fun GijiMemoTheme(
    themeMode: Int = 0,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (isDark) AppDarkColorScheme else AppLightColorScheme
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
