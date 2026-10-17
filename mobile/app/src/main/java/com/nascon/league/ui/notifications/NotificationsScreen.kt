package com.nascon.league.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.data.store.formatPkr
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing

/** Content only; the app shell supplies the top bar and bottom navigation. */
@Composable
fun NotificationsScreen(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val vm: NotificationsViewModel = viewModel(factory = NotificationsViewModel.factory(container))
    val list by vm.items.collectAsStateWithLifecycle()
    val now = remember(list) { System.currentTimeMillis() }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (list.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.notif_empty_title),
                    message = stringResource(R.string.notif_empty_message),
                )
            }
        } else {
            items(list, key = { it.id }) { n -> NotificationRow(n, now) }
        }
    }
}

@Composable
private fun NotificationRow(n: NotificationItem, now: Long) {
    val c = Arena.colors
    val icon: ImageVector
    val iconDesc: String
    val title: String
    val chip: String
    val tone: ChipTone
    var detail = n.detail
    when (n.kind) {
        NotificationKind.SAVED -> {
            icon = Icons.Default.CheckCircle
            iconDesc = stringResource(R.string.notif_icon_saved)
            title = stringResource(R.string.notif_saved_title)
            chip = stringResource(R.string.notif_chip_saved)
            tone = ChipTone.Success
        }
        NotificationKind.NOT_APPLIED -> {
            icon = Icons.Default.Warning
            iconDesc = stringResource(R.string.notif_icon_not_applied)
            title = stringResource(R.string.notif_rejected_title)
            chip = stringResource(R.string.notif_chip_not_applied)
            tone = ChipTone.Error
        }
        NotificationKind.ALREADY_SAVED -> {
            icon = Icons.Default.Info
            iconDesc = stringResource(R.string.notif_icon_info)
            title = stringResource(R.string.notif_duplicate_title)
            chip = stringResource(R.string.notif_chip_already)
            tone = ChipTone.Neutral
            detail = stringResource(R.string.notif_duplicate_detail, n.detail)
        }
        NotificationKind.ORDER_PAID -> {
            icon = Icons.Default.ShoppingCart
            iconDesc = stringResource(R.string.notif_icon_order)
            title = stringResource(R.string.notif_order_title)
            chip = stringResource(R.string.notif_chip_paid)
            tone = ChipTone.Success
            detail = if (n.totalMinor != null) {
                stringResource(R.string.notif_order_detail_amount, n.detail, formatPkr(n.totalMinor))
            } else {
                stringResource(R.string.notif_order_detail, n.detail)
            }
        }
    }
    val tileBg = when (tone) {
        ChipTone.Success -> c.successBg
        ChipTone.Error -> c.liveBg
        else -> c.chipBg
    }
    ArenaCard(Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(tileBg),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = iconDesc, tint = c.ink, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(detail, style = MaterialTheme.typography.bodyLarge, color = c.inkMid)
                Text(relativeTime(n.timeMs, now), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                Spacer(Modifier.size(Spacing.xs))
                StatusChip(chip, icon, tone = tone)
            }
        }
    }
}
