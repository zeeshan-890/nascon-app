package com.nascon.league.ui.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.data.api.dto.ProductDto
import com.nascon.league.data.api.dto.VariantDto
import com.nascon.league.data.store.StockText
import com.nascon.league.data.store.StoreLogic
import com.nascon.league.data.store.formatPkr
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.FilterPill
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Spacing

@Composable
fun ProductDetailScreen(
    productId: String,
    onOpenCart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val vm: ProductDetailViewModel = viewModel(
        key = "product-$productId",
        factory = ProductDetailViewModel.factory(container, productId),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val addedMessage = stringResource(R.string.f6_added_to_cart)
    val viewCart = stringResource(R.string.f6_view_cart)
    LaunchedEffect(vm) {
        vm.added.collect {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(addedMessage, actionLabel = viewCart) == SnackbarResult.ActionPerformed) {
                onOpenCart()
            }
        }
    }

    val product = s.product
    when {
        product == null && s.notFound -> EmptyState(
            icon = Icons.Default.ShoppingBag,
            title = stringResource(R.string.f6_unavailable_title),
            message = stringResource(R.string.f6_unavailable_message),
            modifier = modifier,
        )
        product == null -> {
            val err = s.catalogue.error
            if (err == null) {
                val cd = stringResource(R.string.f6_loading_cd)
                Box(modifier.fillMaxSize().semantics { contentDescription = cd }, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (err.isConnectionProblem()) {
                EmptyState(
                    icon = Icons.Default.CloudOff,
                    title = stringResource(R.string.f6_not_saved_title),
                    message = stringResource(R.string.f6_not_saved_message),
                    actionLabel = stringResource(R.string.f6_try_again),
                    onAction = vm::refresh,
                    modifier = modifier,
                )
            } else {
                ErrorState(ErrorMapper.message(err), vm::refresh, modifier)
            }
        }
        else -> ProductDetailContent(
            product = product,
            variant = s.variant,
            quantity = s.quantity,
            onVariant = vm::selectVariant,
            onQuantity = vm::setQuantity,
            onAdd = vm::addToCart,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProductDetailContent(
    product: ProductDto,
    variant: VariantDto?,
    quantity: Int,
    onVariant: (String) -> Unit,
    onQuantity: (Int) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showPrices = pricesDiffer(product)
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            ProductImage(product.imageUrl, product.name, Modifier.fillMaxWidth().height(240.dp))
            Text(
                product.name,
                style = MaterialTheme.typography.headlineSmall,
                color = Arena.colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            if (product.category.isNotBlank()) {
                Text(
                    StoreLogic.capitalizeLabel(product.category),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (variant != null) {
                Text(formatPkr(variant.priceMinor), style = MaterialTheme.typography.titleLarge, color = Arena.colors.brand)
            } else {
                Text(
                    stringResource(R.string.f6_out_of_stock),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (product.variants.isNotEmpty()) {
                Text(
                    stringResource(R.string.f6_choose_option),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    product.variants.forEach { v ->
                        VariantChip(
                            v = v,
                            selected = v.variantId == variant?.variantId,
                            showPrice = showPrices,
                            onClick = { onVariant(v.variantId) },
                        )
                    }
                }
            }

            if (variant != null) {
                StockLine(variant.stock)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(stringResource(R.string.f6_quantity), style = MaterialTheme.typography.titleMedium, color = Arena.colors.ink)
                    QuantityStepper(
                        quantity = quantity,
                        min = 1,
                        max = clampQuantity(Int.MAX_VALUE, variant.stock),
                        onChange = onQuantity,
                        decreaseCd = stringResource(R.string.f6_decrease_qty),
                        increaseCd = stringResource(R.string.f6_increase_qty),
                    )
                }
            }
        }
        Surface(color = androidx.compose.ui.graphics.Color.White, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(
                text = stringResource(R.string.f6_add_to_cart),
                onClick = onAdd,
                enabled = variant != null,
                icon = Icons.Default.AddShoppingCart,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md)
                    .heightIn(min = 56.dp),
            )
        }
    }
}

@Composable
private fun StockLine(stock: Int) {
    val text = when (val t = StoreLogic.stockText(stock)) {
        is StockText.InStock -> stringResource(R.string.f6_in_stock)
        is StockText.OnlyLeft -> stringResource(R.string.f6_only_left, t.n)
        is StockText.SoldOut -> stringResource(R.string.f6_sold_out)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        val low = StoreLogic.stockText(stock) !is StockText.InStock
        Icon(
            if (low) Icons.Default.Info else Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (low) Arena.colors.warnFg else Arena.colors.successFg,
        )
        Text(text, style = MaterialTheme.typography.bodyLarge, color = if (low) Arena.colors.warnFg else Arena.colors.successFg)
    }
}

@Composable
private fun VariantChip(v: VariantDto, selected: Boolean, showPrice: Boolean, onClick: () -> Unit) {
    val label = StoreLogic.variantLabel(v.size, v.color)
    val soldOut = v.stock <= 0
    val text = when {
        soldOut -> stringResource(R.string.f6_variant_sold_out, label)
        showPrice -> stringResource(R.string.f6_variant_with_price, label, formatPkr(v.priceMinor))
        else -> label
    }
    val cd = if (selected) stringResource(R.string.f6_selected_cd, text) else text
    FilterPill(
        text = text,
        selected = selected,
        onClick = { if (!soldOut) onClick() },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .alpha(if (soldOut) 0.5f else 1f)
            .semantics {
                contentDescription = cd
                if (soldOut) disabled()
            },
    )
}
