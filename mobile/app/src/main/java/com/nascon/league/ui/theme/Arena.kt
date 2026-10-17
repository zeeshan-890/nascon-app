package com.nascon.league.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.nascon.league.domain.model.Tenant

/** Arena360 design tokens (from the screens/ mockups). Tenant primary colour tints the brand ramp. */
data class ArenaColors(
    val brand: Color,
    val brandDeep: Color,
    val brandLight: Color,
    val plum: Color,
    val bg: Color = Color(0xFFF3F4FB),
    val ink: Color = Color(0xFF0F1B4C),
    val inkMid: Color = Color(0xFF3B4780),
    val inkSoft: Color = Color(0xFF4B5A94),
    val fieldBorder: Color = Color(0xFF2E48B0),
    val cardBorder: Color = Color(0xFFD5DBF3),
    val chipBg: Color = Color(0xFFE3E8FA),
    val surfaceAlt: Color = Color(0xFFEEF0F8),
    val liveBg: Color = Color(0xFFFDE8E6),
    val liveFg: Color = Color(0xFFA31F16),
    val successBg: Color = Color(0xFFE4F5EC),
    val successFg: Color = Color(0xFF0B5A38),
    val warnBg: Color = Color(0xFFFFF1DC),
    val warnBorder: Color = Color(0xFFE8B56B),
    val warnFg: Color = Color(0xFF6B3A00),
    val onlineDot: Color = Color(0xFF7CF0B0),
) {
    /** 135-degree header gradient. */
    val headerBrush: Brush
        get() = Brush.linearGradient(
            colorStops = arrayOf(0f to brand, 0.45f to brandDeep, 1f to plum),
            start = Offset.Zero,
            end = Offset(900f, 900f),
        )

    /** Horizontal button gradient. */
    val buttonBrush: Brush
        get() = Brush.horizontalGradient(colorStops = arrayOf(0f to brandLight, 0.55f to brandDeep, 1f to plum))

    companion object {
        val Default = ArenaColors(
            brand = Color(0xFF1E3FAE),
            brandDeep = Color(0xFF0E2C9E),
            brandLight = Color(0xFF4A6FD6),
            plum = Color(0xFF2A1A4E),
        )

        fun forTenant(tenant: Tenant?): ArenaColors {
            val primary = ColorUtil.parseHex(tenant?.primaryColor) ?: return Default
            return Default.copy(
                brand = primary,
                brandDeep = ColorUtil.blend(primary, Color.Black, 0.35f),
                brandLight = ColorUtil.blend(primary, Color.White, 0.25f),
            )
        }
    }
}

val LocalArenaColors = staticCompositionLocalOf { ArenaColors.Default }

fun arenaColorScheme(a: ArenaColors): ColorScheme = lightColorScheme(
    primary = a.brand,
    onPrimary = Color.White,
    primaryContainer = a.chipBg,
    onPrimaryContainer = a.brand,
    secondary = a.brandDeep,
    onSecondary = Color.White,
    secondaryContainer = a.chipBg,
    onSecondaryContainer = a.brand,
    tertiary = a.plum,
    onTertiary = Color.White,
    tertiaryContainer = a.warnBg,
    onTertiaryContainer = a.warnFg,
    background = a.bg,
    onBackground = a.ink,
    surface = Color.White,
    onSurface = a.ink,
    surfaceVariant = a.surfaceAlt,
    onSurfaceVariant = a.inkSoft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = a.surfaceAlt,
    surfaceContainerHighest = a.chipBg,
    outline = a.fieldBorder,
    outlineVariant = a.cardBorder,
    error = a.liveFg,
    onError = Color.White,
    errorContainer = a.liveBg,
    onErrorContainer = a.liveFg,
)
