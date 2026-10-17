package com.nascon.league.ui.fixtures

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.nascon.league.R
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.LiveDotChip
import com.nascon.league.ui.components.StatusChip

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

/** Icon + text chip for every status; Live shows a dot and the word. [timeText] replaces the label for upcoming. */
@Composable
fun FixtureStatusChip(kind: StatusKind, modifier: Modifier = Modifier, timeText: String? = null) {
    val text = stringResource(kind.labelRes())
    when (kind) {
        StatusKind.LIVE -> LiveDotChip(text = text, modifier = modifier)
        StatusKind.COMPLETED -> StatusChip(text, kind.icon(), modifier, ChipTone.Success)
        StatusKind.UPCOMING -> StatusChip(timeText ?: text, kind.icon(), modifier, ChipTone.Neutral)
        StatusKind.POSTPONED, StatusKind.ABANDONED, StatusKind.CANCELLED ->
            StatusChip(text, kind.icon(), modifier, ChipTone.Warning)
        StatusKind.UNKNOWN -> StatusChip(text, kind.icon(), modifier, ChipTone.Neutral)
    }
}
