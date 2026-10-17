package com.nascon.league.ui.fixtures

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nascon.league.R
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.theme.Spacing

@StringRes
fun StatusKind.labelRes(): Int = when (this) {
    StatusKind.UPCOMING -> R.string.f3_status_upcoming
    StatusKind.LIVE -> R.string.f3_status_live
    StatusKind.COMPLETED -> R.string.f3_status_completed
    StatusKind.POSTPONED -> R.string.f3_status_postponed
    StatusKind.ABANDONED -> R.string.f3_status_abandoned
    StatusKind.CANCELLED -> R.string.f3_status_cancelled
    StatusKind.UNKNOWN -> R.string.f3_status_unknown
}

fun StatusKind.icon(): ImageVector = when (this) {
    StatusKind.UPCOMING -> Icons.Default.Schedule
    StatusKind.LIVE -> Icons.Default.FiberManualRecord
    StatusKind.COMPLETED -> Icons.Default.CheckCircle
    StatusKind.POSTPONED -> Icons.Default.EventBusy
    StatusKind.ABANDONED -> Icons.Default.Block
    StatusKind.CANCELLED -> Icons.Default.Cancel
    StatusKind.UNKNOWN -> Icons.Default.Info
}

/** Icon + text chip for every status; Live is filled and has a pulsing dot (always also spelled out as text). */
@Composable
fun FixtureStatusChip(kind: StatusKind, modifier: Modifier = Modifier) {
    val text = stringResource(kind.labelRes())
    if (kind != StatusKind.LIVE) {
        StatusChip(text = text, icon = kind.icon(), modifier = modifier)
        return
    }
    val pulse = rememberInfiniteTransition(label = "live-pulse")
    val alpha = pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "live-alpha",
    )
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                kind.icon(),
                contentDescription = null,
                modifier = Modifier.size(18.dp).alpha(alpha.value),
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
