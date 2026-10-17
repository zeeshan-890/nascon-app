package com.nascon.league.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascon.league.R
import com.nascon.league.data.receipts.ReceiptEntry
import com.nascon.league.data.store.formatPkr
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.receipt.ReceiptFormat
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing

/** Newest first (ISO timestamps sort lexically). */
fun sortOrdersNewestFirst(list: List<ReceiptEntry>): List<ReceiptEntry> = list.sortedByDescending { it.paidAt }

@Composable
fun OrdersScreen(
    onOpenOrder: (String) -> Unit,
    modifier: Modifier = Modifier,
    onBrowseStore: (() -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val flow = remember(container) { container.receiptStore.observeIndex() }
    val list by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val orders = remember(list) { sortOrdersNewestFirst(list) }

    if (orders.isEmpty()) {
        EmptyState(
            icon = Icons.Default.ShoppingCart,
            title = stringResource(R.string.ord_empty_title),
            message = stringResource(R.string.ord_empty_message),
            actionLabel = if (onBrowseStore != null) stringResource(R.string.ord_browse) else null,
            onAction = onBrowseStore,
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(orders, key = { it.orderId }) { o -> OrderRow(o, onOpenOrder) }
    }
}

@Composable
private fun OrderRow(o: ReceiptEntry, onOpen: (String) -> Unit) {
    val ref = ReceiptFormat.orderReference(o.orderId)
    val date = ReceiptFormat.paidDate(o.paidAt)
    val total = formatPkr(o.totalMinor)
    val cd = stringResource(R.string.ord_open_cd, ref, date ?: "", total)
    ArenaCard(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clearAndSetSemantics { contentDescription = cd },
        onClick = { onOpen(o.orderId) },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.ord_ref, ref),
                style = MaterialTheme.typography.titleMedium,
                color = Arena.colors.ink,
            )
            Text(total, style = MaterialTheme.typography.titleMedium, color = Arena.colors.brand)
        }
        if (date != null) {
            Text(
                stringResource(R.string.ord_paid_on, date),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusChip(stringResource(R.string.ord_status_paid), Icons.Default.Check, tone = ChipTone.Success)
    }
}
