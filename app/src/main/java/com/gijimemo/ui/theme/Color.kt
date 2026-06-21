package com.gijimemo.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * GijiMemo 無印良品風テーマカラー
 *
 * コンセプト: 「素材感」「余白」「不要なものを削ぐ」
 * - オフホワイト / ベージュ基調の明るい背景
 * - 文字は濃いチャコールで読みやすく
 * - Muji レッド (#CC0000) を最小限のアクセントに
 * - フラットで陰影を極力排除
 */

// 背景 (Light)
val Background = Color(0xFFF5F3F0)      // 温かみのあるオフホワイト（紙質感）
val Surface = Color(0xFFFFFEFC)          // カード面（白に近い生成り）
val SurfaceVariant = Color(0xFFEBE8E0)  // セクション区切りなど
val Outline = Color(0xFFD4CFC8)         // ボーダー（極細・薄め）

// テキスト
val TextPrimary = Color(0xFF000000)      // 黒（本文）
val TextSecondary = Color(0xFF000000)    // 黒（補足も黒で統一）
val TextTertiary = Color(0xFF000000)     // 黒（ヒントも黒で統一）

// ブランド: Muji Red（最小限のアクセント）
val MujiRed = Color(0xFFCC0000)          // CTA / 強調
val MujiRedLight = Color(0xFFF5E0DC)     // 薄い赤（コンテナ背景）
val MujiRedDark = Color(0xFF990000)      // 濃い赤（押下時）

// セマンティック
val Success = Color(0xFF5B8C5A)          // 自然な緑（苔色）
val Error = Color(0xFFCC0000)            // Muji 赤（エラーも兼用）
val Recording = Color(0xFFCC0000)        // 録音中表示

// ダークテーマ用
val DarkBackground = Color(0xFF1A1A1A)      // 非常に濃いグレー
val DarkSurface = Color(0xFF2C2C2C)          // 濃いグレー（カード）
val DarkSurfaceVariant = Color(0xFF3A3A3A)   // やや明るいグレー（区切り）
val DarkTextPrimary = Color(0xFFF0F0F0)      // ほぼ白（本文）
val DarkTextSecondary = Color(0xFFCCCCCC)    // 明るいグレー（補足）
val DarkTextTertiary = Color(0xFFAAAAAA)     // 中程度グレー（ヒント）
