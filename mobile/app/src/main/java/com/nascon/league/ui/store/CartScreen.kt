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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import com.nascon.league.data.store.CartProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

    // Notice left by checkout (e.g. an item sold out while paying); shown as a card, kept over rotation.
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        CartNotice.take()?.let { notice = it }
    }
    LaunchedEffect(vm) {
        vm.removed.collect { line ->
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(context.getString(R.string.f6_removed, line.name), actionLabel = undo)
            if (result == SnackbarResult.ActionPerformed) vm.undo(line)
        }
    }

    if (s.loaded && s.lines.isEmpty()) {
        StoreStateCard(
            icon = Icons.Outlined.ShoppingCart,
            title = stringResource(R.string.storestates_cart_empty_title),
            message = stringResource(R.string.storestates_cart_empty_message),
            primaryLabel = stringResource(R.string.f6_browse_store),
            onPrimary = onBrowse,
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
            val noticeText = notice
            if (noticeText != null && s.hasBlockingProblem) {
                item(key = "__notice") {
                    StoreNoticeStrip(
                        text = noticeText,
                        icon = Icons.Default.ErrorOutline,
                        background = Arena.colors.liveBg,
                        foreground = Arena.colors.liveFg,
                        border = Color(0xFFE7A6A0),
                        live = true,
                    )
                }
            }
            items(s.lines, key = { it.variantId }) { line ->
                CartLineCard(
                    line = line,
                    maxQuantity = cartMaxQuantity(s.stockByVariant[line.variantId]),
                    onQuantity = { vm.setQuantity(line.variantId, it) },
                    onRemove = { vm.remove(line.variantId) },
                )
            }
            item(key = "__subtotal") {
                ArenaCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.f6_subtotal),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Arena.colors.inkSoft,
                            modifier = Modifier.weight(1f),
                        )
                        Text(formatPkr(s.subtotalMinor), style = MaterialTheme.typography.titleMedium, color = Arena.colors.ink)
                    }
                }
            }
        }
        Surface(color = Color.White, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
            val blocking = s.lines.filter { it.problem == CartProblem.SoldOut || it.problem == CartProblem.NoLongerAvailable }
            if (blocking.isNotEmpty()) {
                PrimaryButton(
                    text = stringResource(
                        if (blocking.size == 1) R.string.storestates_remove_sold_out else R.string.storestates_remove_sold_out_many,
                    ),
                    onClick = { blocking.forEach { vm.remove(it.variantId) } },
                    modifier = Modifier.fillMaxWidth().padding(Spacing.md).heightIn(min = 56.dp),
                )
            } else {
                PrimaryButton(
                    text = stringResource(R.string.storestates_go_to_checkout),
                    onClick = onCheckout,
                    enabled = checkoutEnabled(s.lines.size, s.hasBlockingProblem),
                    modifier = Modifier.fillMaxWidth().padding(Spacing.md).heightIn(min = 56.dp),
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
            ProductImage(line.imageUrl, line.name, Modifier.size(52.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    if (line.variantLabel == com.nascon.league.data.store.variantLabel(null, null)) line.name
                    else stringResource(R.string.storestates_name_variant, line.name, line.variantLabel),
                    style = MaterialTheme.typography.titleMedium,
                    color = Arena.colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatPkr(line.unitPriceMinor),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Arena.colors.inkSoft,
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
