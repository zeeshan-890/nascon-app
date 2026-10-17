package com.nascon.league.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascon.league.R
import com.nascon.league.data.api.dto.OrderDetailDto
import com.nascon.league.data.receipts.ReceiptEntry
import com.nascon.league.data.repo.Resource
import com.nascon.league.data.store.formatPkr
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.receipt.ReceiptFormat
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing

@Composable
fun OrderDetailScreen(
    orderId: String,
    onOpenReceipt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val repo = container.orderRepository
    val resFlow = remember(repo, orderId) { repo.observeResource(orderId) }
    val res by resFlow.collectAsStateWithLifecycle(initialValue = Resource.Loading(null))
    val indexFlow = remember(container) { container.receiptStore.observeIndex() }
    val index by indexFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(orderId) { repo.refresh(orderId) }

    val detail: OrderDetailDto? = when (val r = res) {
        is Resource.Success -> r.data
        is Resource.Loading -> r.cached
        is Resource.Error -> r.cached
    }
    val entry = index.firstOrNull { it.orderId == orderId }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (detail != null) DetailBody(orderId, detail) else FallbackBody(orderId, entry, res is Resource.Loading)
        PrimaryButton(
            text = stringResource(R.string.ord_view_receipt),
            onClick = { onOpenReceipt(orderId) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        )
    }
}

@Composable
private fun StatusHeader(orderId: String, status: String?, date: String?) {
    Text(
        stringResource(R.string.ord_ref, ReceiptFormat.orderReference(orderId)),
        style = MaterialTheme.typography.titleLarge,
        color = Arena.colors.ink,
    )
    if (date != null) {
        Text(
            stringResource(R.string.ord_paid_on, date),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val known = orderStatusRes(status) != R.string.ord_status_unknown
    StatusChip(
        stringResource(orderStatusRes(status)),
        if (known) Icons.Default.Check else Icons.Default.Info,
        tone = orderStatusTone(status),
    )
}

@Composable
private fun DetailBody(orderId: String, d: OrderDetailDto) {
    val date = (d.paidAt ?: d.createdAt)?.let { ReceiptFormat.paidDate(it) }
    ArenaCard(Modifier.fillMaxWidth()) { StatusHeader(orderId, d.status, date) }
    ArenaCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ord_items), style = MaterialTheme.typography.titleMedium, color = Arena.colors.ink)
        d.items.forEach { item ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ord_item_fallback),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Arena.colors.ink,
                    )
                    Text(
                        stringResource(R.string.ord_qty_each, item.quantity, formatPkr(item.unitPriceMinor)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    formatPkr(item.unitPriceMinor * item.quantity),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Arena.colors.ink,
                )
            }
        }
    }
    ArenaCard(Modifier.fillMaxWidth()) {
        MoneyRow(stringResource(R.string.ord_subtotal), formatPkr(d.subtotalMinor), false)
        if (d.discountMinor > 0) MoneyRow(stringResource(R.string.ord_discount), "-" + formatPkr(d.discountMinor), false)
        MoneyRow(stringResource(R.string.ord_total), formatPkr(d.totalMinor), true)
    }
}

@Composable
private fun MoneyRow(label: String, value: String, strong: Boolean) {
    val style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = style, color = Arena.colors.ink, modifier = Modifier.weight(1f))
        Text(value, style = style, color = if (strong) Arena.colors.brand else Arena.colors.ink)
    }
}

@Composable
private fun FallbackBody(orderId: String, entry: ReceiptEntry?, loading: Boolean) {
    val date = entry?.let { ReceiptFormat.paidDate(it.paidAt) }
    ArenaCard(Modifier.fillMaxWidth()) {
        // A receipt exists only for paid orders.
        StatusHeader(orderId, if (entry != null) "paid" else null, date)
        if (entry != null) MoneyRow(stringResource(R.string.ord_total), formatPkr(entry.totalMinor), true)
        Text(
            stringResource(if (loading) R.string.ord_loading else R.string.ord_saved_copy),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
