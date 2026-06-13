package com.gijimemo.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * GimiMemo テーマカラー
 *
 * Authority Navy + Premium Gold (ui-ux-pro-max 推奨)
 * - OLED 最適化: 深いネイビー背景で目に優しく、バッテリーに優しい
 * - Gold アクセント: 録音中・CTA など「動き」のある要素を強調
 * - 高級感のあるダーク UI: 業務系/生産性アプリに最適
 */

// 背景階調 (Dark)
val NavyBackground = Color(0xFF0B1120)   // 最深部 (Scaffold)
val NavySurface = Color(0xFF1E293B)      // Card / raised
val NavySurfaceVariant = Color(0xFF334155)  // さらに一段高い
val NavyOutline = Color(0xFF475569)      // divider / border

// テキスト階調
val TextPrimary = Color(0xFFF8FAFC)      // ほぼ白
val TextSecondary = Color(0xFFCBD5E1)    // muted
val TextTertiary = Color(0xFF94A3B8)     // hint / disabled

// ブランド: Premium Gold
val Gold500 = Color(0xFFCA8A04)          // CTA / primary action
val Gold400 = Color(0xFFEAB308)          // hover / active
val Gold200 = Color(0xFFFDE68A)          // gold-tinted text / icon

// セマンティック
val Success = Color(0xFF22C55E)
val Error = Color(0xFFEF4444)
val Recording = Color(0xFFEF4444)        // 録音中（赤で注意喚起）

// Light テーマ（将来拡張用、今回は基本ダークのみで運用）
val NavyOnLight = Color(0xFF0F172A)
