package com.nascon.league.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.nascon.league.domain.model.Tenant

private val NasconShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun NasconTheme(
    tenant: Tenant?,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = remember(tenant?.primaryColor, tenant?.secondaryColor, darkTheme) {
        tenantColorScheme(tenant, darkTheme)
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = NasconTypography,
        shapes = NasconShapes,
        content = content,
    )
}
