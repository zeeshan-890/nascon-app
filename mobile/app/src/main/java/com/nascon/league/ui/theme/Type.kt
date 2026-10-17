package com.nascon.league.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nascon.league.R

@OptIn(ExperimentalTextApi::class)
val Montserrat = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        Font(
            R.font.montserrat,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    },
)

private fun style(size: Int, weight: FontWeight = FontWeight.Normal, line: Float = 1.35f) =
    TextStyle(fontFamily = Montserrat, fontSize = size.sp, fontWeight = weight, lineHeight = (size * line).sp)

/** Arena360 type scale, matched to the mockups (body 14, captions 12-13). */
val NasconTypography = Typography(
    displayLarge = style(30, FontWeight.ExtraBold, 1.15f),
    displayMedium = style(28, FontWeight.ExtraBold, 1.15f),
    displaySmall = style(24, FontWeight.ExtraBold, 1.15f),
    headlineLarge = style(24, FontWeight.ExtraBold, 1.2f),
    headlineMedium = style(20, FontWeight.ExtraBold, 1.2f),
    headlineSmall = style(18, FontWeight.Bold, 1.25f),
    titleLarge = style(22, FontWeight.ExtraBold, 1.2f),
    titleMedium = style(15, FontWeight.Bold),
    titleSmall = style(14, FontWeight.Bold),
    bodyLarge = style(14, FontWeight.Medium),
    bodyMedium = style(14, FontWeight.Medium),
    bodySmall = style(12, FontWeight.Medium),
    labelLarge = style(13, FontWeight.Bold),
    labelMedium = style(12, FontWeight.SemiBold),
    labelSmall = style(12, FontWeight.SemiBold),
)

val ScoreTextStyle = TextStyle(fontFamily = Montserrat, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 40.sp)
