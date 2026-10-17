package com.nascon.league.ui.store

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.core.network.ApiError
import com.nascon.league.data.api.dto.ProductDto
import com.nascon.league.data.store.StoreLogic
import com.nascon.league.data.store.formatPkr
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.LastUpdatedLabel
import com.nascon.league.ui.components.RefreshableList
import com.nascon.league.ui.components.SavedDataBadge
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.theme.Spacing

@Composable
fun StoreScreen(
    onOpenProduct: (String) -> Unit,
    onOpenCart: () -> Unit,
    onOpenReceipts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val vm: StoreViewModel = viewModel(factory = StoreViewModel.factory(container))
    val s by vm.state.collectAsStateWithLifecycle()
    StoreContent(
        s = s,
        onCategory = vm::selectCategory,
        onRefresh = vm::refresh,
        onOpenProduct = onOpenProduct,
        onOpenCart = onOpenCart,
        onOpenReceipts = onOpenReceipts,
        modifier = modifier,
    )
}

private fun ApiError.isOfflineLike(): Boolean = isConnectionProblem()

@Composable
private fun StoreContent(
    s: StoreUiState,
    onCategory: (String?) -> Unit,
    onRefresh: () -> Unit,
    onOpenProduct: (String) -> Unit,
    onOpenCart: () -> Unit,
    onOpenReceipts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cat = s.catalogue
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onOpenReceipts, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.Receipt, contentDescription = null, modifier = Modifier.padding(end = Spacing.xs))
                Text(stringResource(R.string.f6_my_receipts), style = MaterialTheme.typography.bodyLarge)
            }
            CartIconButton(s.cartCount, onOpenCart)
        }
        if (cat.products != null && cat.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (s.categories.isNotEmpty()) CategoryRow(s.categories, s.category, onCategory)
        if (cat.products != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (s.offline || cat.error != null) SavedDataBadge()
                LastUpdatedLabel(cat.updatedAt)
            }
        }
        RefreshableList(
            refreshing = cat.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            StoreBody(s, onCategory, onRefresh, onOpenProduct)
        }
    }
}

@Composable
private fun CategoryRow(keys: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    val all = stringResource(R.string.f6_category_all)
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "__all") { CategoryChip(all, selected == null) { onSelect(null) } }
        items(keys, key = { it }) { key ->
            CategoryChip(StoreLogic.capitalizeLabel(key), selected == key) { onSelect(key) }
        }
    }
}

@Composable
private fun CategoryChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val cd = if (isSelected) stringResource(R.string.f6_selected_cd, label) else label
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = if (isSelected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else null,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = cd },
    )
}

@Composable
private fun StoreBody(
    s: StoreUiState,
    onCategory: (String?) -> Unit,
    onRefresh: () -> Unit,
    onOpenProduct: (String) -> Unit,
) {
    val products = s.catalogue.products
    if (products == null) {
        val err = s.catalogue.error
        when {
            err == null -> {
                val loadingCd = stringResource(R.string.f6_loading_cd)
                Box(
                    Modifier.fillMaxSize().semantics { contentDescription = loadingCd },
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            err.isOfflineLike() -> EmptyState(
                icon = Icons.Default.CloudOff,
                title = stringResource(R.string.f6_not_saved_title),
                message = stringResource(R.string.f6_not_saved_message),
                actionLabel = stringResource(R.string.f6_try_again),
                onAction = onRefresh,
            )
            else -> ErrorState(ErrorMapper.message(err), onRefresh)
        }
        return
    }
    if (products.isEmpty()) {
        EmptyState(
            icon = Icons.Default.ShoppingBag,
            title = stringResource(R.string.f6_no_products_title),
            message = stringResource(R.string.f6_no_products_message),
            actionLabel = stringResource(R.string.f6_refresh),
            onAction = onRefresh,
        )
        return
    }
    val visible = s.visible
    if (visible.isEmpty()) {
        EmptyState(
            icon = Icons.Default.ShoppingBag,
            title = stringResource(R.string.f6_no_products_in_category),
            message = stringResource(R.string.f6_no_products_in_category_message),
            actionLabel = stringResource(R.string.f6_category_all),
            onAction = { onCategory(null) },
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(visible, key = { it.productId }) { p ->
            ProductCard(p) { onOpenProduct(p.productId) }
        }
    }
}

@Composable
private fun ProductCard(p: ProductDto, onClick: () -> Unit) {
    val soldOut = StoreLogic.isSoldOut(p)
    val min = StoreLogic.minPrice(p)
    val priceText = min?.let { stringResource(R.string.f6_from_price, formatPkr(it)) }
    val stockWord = stringResource(if (soldOut) R.string.f6_out_of_stock else R.string.f6_in_stock)
    val cd = listOfNotNull(
        p.name,
        p.category.takeIf { it.isNotBlank() }?.let { StoreLogic.capitalizeLabel(it) },
        priceText,
        stockWord,
    ).joinToString(", ")
    val openLabel = stringResource(R.string.f6_open_product)

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = openLabel, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = cd },
    ) {
        Column(Modifier.padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ProductImage(p.imageUrl, p.name, Modifier.fillMaxWidth().height(120.dp))
            Text(
                p.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (soldOut) {
                StatusChip(stringResource(R.string.f6_out_of_stock), Icons.Default.Block)
            } else if (priceText != null) {
                Text(priceText, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
