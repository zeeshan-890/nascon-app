package com.nascon.league.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.nascon.league.domain.model.Tenant

internal fun tenantInitials(name: String?): String {
    val words = name.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "N"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

@Composable
private fun InitialsCircle(initials: String, size: Dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                initials,
                style = MaterialTheme.typography.labelLarge,
                fontSize = (size.value * 0.4f).coerceAtLeast(14f).sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun TenantLogo(tenant: Tenant?, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val initials = tenantInitials(tenant?.name)
    val logoUrl = tenant?.logoUrl?.takeIf { it.isNotBlank() }
    val desc = tenant?.let { "${it.name} logo" }

    Box(modifier = modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        if (logoUrl == null) {
            InitialsCircle(initials, size)
        } else {
            SubcomposeAsyncImage(
                model = logoUrl,
                contentDescription = desc,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop,
                loading = { InitialsCircle(initials, size) },
                error = { InitialsCircle(initials, size) },
            )
        }
    }
}
