package com.gijimemo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * GijiMemo 無印良品風テーマ
 *
 * コンセプト: 「素材感」「余白」「不要なものを削ぐ」
 * - ライトモード: オフホワイト背景 × チャコール文字
 * - フラットデザイン: 陰影を極力排除、自然な色合い
 * - Muji Red (#CC0000) を最小限のアクセントに
 */
private val MujiLightColorScheme = lightColorScheme(
    primary = MujiRed,
    onPrimary = Surface,
    primaryContainer = MujiRedLight,
    onPrimaryContainer = MujiRedDark,

    secondary = SurfaceVariant,
    onSecondary = TextPrimary,
    secondaryContainer = SurfaceVariant,
    onSecondaryContainer = TextSecondary,

    tertiary = TextTertiary,
    onTertiary = Surface,

    background = Background,
    onBackground = TextPrimary,

    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,

    outline = Outline,
    outlineVariant = Outline,

    error = Error,
    onError = Surface
)

private val MujiDarkColorScheme = darkColorScheme(
    primary = MujiRed,
    onPrimary = DarkBackground,
    primaryContainer = MujiRedDark,
    onPrimaryContainer = MujiRedLight,

    secondary = DarkSurface,
    onSecondary = DarkTextPrimary,
    secondaryContainer = DarkSurface,
    onSecondaryContainer = DarkTextSecondary,

    tertiary = DarkTextSecondary,
    onTertiary = DarkBackground,

    background = DarkBackground,
    onBackground = DarkTextPrimary,

    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkTextSecondary,

    outline = DarkSurface,
    outlineVariant = DarkSurface,

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
    val colorScheme = if (isDark) MujiDarkColorScheme else MujiLightColorScheme
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
