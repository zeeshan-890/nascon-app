package com.nascon.league.ui.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.data.store.CartLine
import com.nascon.league.data.store.formatPkr
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Spacing

@Composable
fun CartScreen(
    onCheckout: () -> Unit,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val vm: CartViewModel = viewModel(factory = CartViewModel.factory(container))
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val undo = stringResource(R.string.f6_undo)
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        // Notice left by checkout (e.g. an item sold out while paying).
        CartNotice.take()?.let { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(vm) {
        vm.removed.collect { line ->
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(context.getString(R.string.f6_removed, line.name), actionLabel = undo)
            if (result == SnackbarResult.ActionPerformed) vm.undo(line)
        }
    }

    if (s.loaded && s.lines.isEmpty()) {
        EmptyState(
            icon = Icons.Default.ShoppingCart,
            title = stringResource(R.string.f6_cart_empty_title),
            message = stringResource(R.string.f6_cart_empty_message),
            actionLabel = stringResource(R.string.f6_browse_store),
            onAction = onBrowse,
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(s.lines, key = { it.variantId }) { line ->
                CartLineCard(
                    line = line,
                    maxQuantity = cartMaxQuantity(s.stockByVariant[line.variantId]),
                    onQuantity = { vm.setQuantity(line.variantId, it) },
                    onRemove = { vm.remove(line.variantId) },
                )
            }
        }
        Surface(color = androidx.compose.ui.graphics.Color.White, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.f6_subtotal),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(formatPkr(s.subtotalMinor), style = MaterialTheme.typography.titleLarge, color = Arena.colors.brand)
                }
                val canCheckout = checkoutEnabled(s.lines.size, s.hasBlockingProblem)
                if (!canCheckout) {
                    Text(
                        stringResource(R.string.f6_checkout_blocked),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                PrimaryButton(
                    text = stringResource(R.string.f6_checkout),
                    onClick = onCheckout,
                    enabled = canCheckout,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CartLineCard(
    line: CartLine,
    maxQuantity: Int,
    onQuantity: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val removeCd = stringResource(R.string.f6_remove_cd, line.name)
    ArenaCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ProductImage(line.imageUrl, line.name, Modifier.size(80.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    line.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Arena.colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    line.variantLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.f6_unit_price, formatPkr(line.unitPriceMinor)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Arena.colors.brand,
                )
                line.problem?.let { ProblemChip(it) }
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.Center,
        ) {
            QuantityStepper(
                quantity = line.quantity,
                min = 1,
                max = maxQuantity,
                onChange = onQuantity,
                decreaseCd = stringResource(R.string.f6_decrease_qty_of, line.name),
                increaseCd = stringResource(R.string.f6_increase_qty_of, line.name),
            )
            TextButton(
                onClick = onRemove,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.padding(end = Spacing.xs))
                Text(
                    stringResource(R.string.f6_remove),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = removeCd },
                )
            }
        }
    }
}
