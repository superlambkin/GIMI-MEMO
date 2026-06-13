package com.gijimemo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * GimiMemo はダークテーマ固定（業務系高級感 UI）。
 * 動的カラー (Material You) は使用しない — ブランドの一貫性を優先。
 */
private val GimiMemoDarkColorScheme = darkColorScheme(
    primary = Gold500,
    onPrimary = NavyBackground,
    primaryContainer = NavySurface,
    onPrimaryContainer = Gold200,

    secondary = NavySurfaceVariant,
    onSecondary = TextPrimary,
    secondaryContainer = NavySurface,
    onSecondaryContainer = TextSecondary,

    tertiary = Gold400,
    onTertiary = NavyBackground,

    background = NavyBackground,
    onBackground = TextPrimary,

    surface = NavySurface,
    onSurface = TextPrimary,
    surfaceVariant = NavySurfaceVariant,
    onSurfaceVariant = TextSecondary,

    outline = NavyOutline,
    outlineVariant = NavyOutline,

    error = Error,
    onError = TextPrimary
)

@Composable
fun GijiMemoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // ブランド一貫性のため、ダーク固定
    val colorScheme = GimiMemoDarkColorScheme
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
