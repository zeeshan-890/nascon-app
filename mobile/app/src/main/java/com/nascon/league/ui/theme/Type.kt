package com.nascon.league.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun style(size: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontSize = size.sp, fontWeight = weight, lineHeight = (size * 1.4f).sp)

/** display 32, title 22, subtitle 18, body 16, label 14. Nothing smaller than 14sp. */
val NasconTypography = Typography(
    displayLarge = style(32, FontWeight.Bold),
    displayMedium = style(32, FontWeight.Bold),
    displaySmall = style(32, FontWeight.Bold),
    headlineLarge = style(32, FontWeight.Bold),
    headlineMedium = style(22, FontWeight.SemiBold),
    headlineSmall = style(22, FontWeight.SemiBold),
    titleLarge = style(22, FontWeight.SemiBold),
    titleMedium = style(18, FontWeight.SemiBold),
    titleSmall = style(18, FontWeight.Medium),
    bodyLarge = style(16),
    bodyMedium = style(16),
    bodySmall = style(14),
    labelLarge = style(14, FontWeight.Medium),
    labelMedium = style(14, FontWeight.Medium),
    labelSmall = style(14, FontWeight.Medium),
)

val ScoreTextStyle = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold)
