package com.nascon.league.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.nascon.league.domain.model.Tenant

fun tenantColorScheme(tenant: Tenant?, dark: Boolean): ColorScheme {
    val basePrimary = ColorUtil.parseHex(tenant?.primaryColor) ?: NasconPrimary
    val baseSecondary = ColorUtil.parseHex(tenant?.secondaryColor) ?: NasconSecondary
    return if (dark) {
        val primary = ColorUtil.blend(basePrimary, Color.White, 0.25f)
        val secondary = ColorUtil.blend(baseSecondary, Color.White, 0.25f)
        val primaryContainer = ColorUtil.blend(basePrimary, Color.Black, 0.55f)
        val secondaryContainer = ColorUtil.blend(baseSecondary, Color.Black, 0.55f)
        darkColorScheme(
            primary = primary,
            onPrimary = ColorUtil.onColorFor(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = ColorUtil.onColorFor(primaryContainer),
            secondary = secondary,
            onSecondary = ColorUtil.onColorFor(secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = ColorUtil.onColorFor(secondaryContainer),
        )
    } else {
        val primaryContainer = ColorUtil.blend(basePrimary, Color.White, 0.80f)
        val secondaryContainer = ColorUtil.blend(baseSecondary, Color.White, 0.75f)
        lightColorScheme(
            primary = basePrimary,
            onPrimary = ColorUtil.onColorFor(basePrimary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = ColorUtil.onColorFor(primaryContainer),
            secondary = baseSecondary,
            onSecondary = ColorUtil.onColorFor(baseSecondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = ColorUtil.onColorFor(secondaryContainer),
        )
    }
}
