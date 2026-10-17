package com.nascon.league.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

val NasconPrimary = Color(0xFF0B5FFF)
val NasconSecondary = Color(0xFFFFB800)

object ColorUtil {
    /** Parses #RGB, #RRGGBB, #AARRGGBB (leading # optional). Returns null when invalid. */
    fun parseHex(hex: String?): Color? {
        if (hex == null) return null
        var s = hex.trim()
        if (s.startsWith("#")) s = s.substring(1)
        if (s.isEmpty() || !s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return try {
            when (s.length) {
                3 -> {
                    val r = s.substring(0, 1).repeat(2).toInt(16)
                    val g = s.substring(1, 2).repeat(2).toInt(16)
                    val b = s.substring(2, 3).repeat(2).toInt(16)
                    Color(r, g, b, 255)
                }
                6 -> Color(
                    s.substring(0, 2).toInt(16),
                    s.substring(2, 4).toInt(16),
                    s.substring(4, 6).toInt(16),
                    255,
                )
                8 -> Color(
                    s.substring(2, 4).toInt(16),
                    s.substring(4, 6).toInt(16),
                    s.substring(6, 8).toInt(16),
                    s.substring(0, 2).toInt(16),
                )
                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun channel(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
    }

    fun relativeLuminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    fun onColorFor(background: Color): Color =
        if (contrastRatio(background, Color.White) >= contrastRatio(background, Color.Black)) Color.White
        else Color.Black

    internal fun blend(from: Color, to: Color, fraction: Float): Color {
        val f = fraction.coerceIn(0f, 1f)
        return Color(
            from.red + (to.red - from.red) * f,
            from.green + (to.green - from.green) * f,
            from.blue + (to.blue - from.blue) * f,
            1f,
        )
    }
}
