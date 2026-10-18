package com.nascon.league.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.data.orders.ClubOrderDto
import com.nascon.league.data.store.formatPkr
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.FeatureUnavailableState
import com.nascon.league.ui.components.LoadingState
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.receipt.ReceiptFormat
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing

fun orderStatusIcon(status: String?) = when (status?.trim()?.lowercase()) {
    "paid", "delivered" -> Icons.Default.Check
    "refunded", "cancelled", "canceled" -> Icons.Default.Close
    else -> Icons.Default.Info
}

/** E23: orders of one club, newest first. Needs the club orders endpoint (a contract addition). */
@Composable
fun ClubOrdersScreen(
    clubId: String,
    onOpenOrder: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val vm: ClubOrdersViewModel = viewModel(
        key = "club-orders-$clubId",
        factory = ClubOrdersViewModel.factory(container, clubId),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val orders = state.orders
    if (orders != null) HeaderSubtitle(stringResource(R.string.ordx_subtitle, orders.size))

    when {
        state.unavailable -> FeatureUnavailableState(modifier, onBack)
        state.loading && orders == null -> LoadingState(modifier = modifier)
        state.errorMessage != null && orders == null ->
            ErrorState(state.errorMessage.orEmpty(), onRetry = vm::load, modifier = modifier)
        orders.isNullOrEmpty() -> EmptyState(
            icon = Icons.Default.ShoppingCart,
            title = stringResource(R.string.ordx_empty_title),
            message = stringResource(R.string.ordx_empty_message),
            modifier = modifier,
        )
        else -> LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(orders, key = { it.orderId }) { o -> ClubOrderRow(o, onOpenOrder) }
        }
    }
}

@Composable
private fun ClubOrderRow(o: ClubOrderDto, onOpen: (String) -> Unit) {
    val ref = ReceiptFormat.orderReference(o.orderId)
    val date = o.paidAt?.let { ReceiptFormat.paidDate(it) }
    val total = formatPkr(o.totalMinor)
    val statusText = stringResource(orderStatusRes(o.status))
    val who = o.customerName?.takeIf { it.isNotBlank() }
    val title = if (who != null) stringResource(R.string.ordx_row_meta, ref, who) else ref
    val meta = listOfNotNull(date, total).joinToString(" · ")
    val cd = stringResource(R.string.ordx_row_cd, title, date ?: "", total, statusText)
    ArenaCard(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clearAndSetSemantics { contentDescription = cd },
        onClick = { onOpen(o.orderId) },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Arena.colors.ink)
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusChip(statusText, orderStatusIcon(o.status), tone = orderStatusTone(o.status))
        }
    }
}
