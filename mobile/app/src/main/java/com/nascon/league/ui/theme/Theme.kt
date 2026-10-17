package com.nascon.league.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.nascon.league.domain.model.Tenant

private val NasconShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** The Arena360 design is light-only; [darkTheme] is accepted for source compatibility. */
@Composable
fun NasconTheme(
    tenant: Tenant?,
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val arena = remember(tenant?.primaryColor) { ArenaColors.forTenant(tenant) }
    val scheme = remember(arena) { arenaColorScheme(arena) }
    CompositionLocalProvider(LocalArenaColors provides arena) {
        MaterialTheme(
            colorScheme = scheme,
            typography = NasconTypography,
            shapes = NasconShapes,
            content = content,
        )
    }
}

object Arena {
    val colors: ArenaColors
        @Composable get() = LocalArenaColors.current
}
