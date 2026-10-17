package com.nascon.league.ui.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PriceChange
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RemoveShoppingCart
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.nascon.league.R
import com.nascon.league.data.store.CartProblem
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.theme.Spacing

@Composable
internal fun ImagePlaceholder(modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.ShoppingBag,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Product photo with a bag-icon placeholder while loading, on failure, or when there is no URL. */
@Composable
internal fun ProductImage(url: String?, name: String, modifier: Modifier = Modifier) {
    val clipped = modifier.clip(MaterialTheme.shapes.small)
    if (url.isNullOrBlank()) {
        ImagePlaceholder(clipped)
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = stringResource(R.string.f6_image_cd, name),
        contentScale = ContentScale.Crop,
        modifier = clipped,
        loading = { ImagePlaceholder(Modifier.fillMaxSize()) },
        error = { ImagePlaceholder(Modifier.fillMaxSize()) },
    )
}

@Composable
internal fun QuantityStepper(
    quantity: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    decreaseCd: String,
    increaseCd: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val valueCd = stringResource(R.string.f6_quantity_value_cd, quantity)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        IconButton(
            onClick = { onChange((quantity - 1).coerceAtLeast(min)) },
            enabled = enabled && quantity > min,
            modifier = Modifier.size(48.dp),
        ) { Icon(Icons.Default.Remove, contentDescription = decreaseCd) }
        Text(
            quantity.toString(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { contentDescription = valueCd },
        )
        IconButton(
            onClick = { onChange((quantity + 1).coerceAtMost(max)) },
            enabled = enabled && quantity < max,
            modifier = Modifier.size(48.dp),
        ) { Icon(Icons.Default.Add, contentDescription = increaseCd) }
    }
}

/** Text chip (icon + words, never colour alone) for a cart reconciliation problem. */
@Composable
internal fun ProblemChip(problem: CartProblem, modifier: Modifier = Modifier) {
    when (problem) {
        is CartProblem.PriceUpdated ->
            StatusChip(stringResource(R.string.f6_problem_price), Icons.Default.PriceChange, modifier)
        is CartProblem.OnlyLeft ->
            StatusChip(stringResource(R.string.f6_only_left, problem.n), Icons.Default.Info, modifier)
        is CartProblem.NoLongerAvailable ->
            StatusChip(stringResource(R.string.f6_problem_unavailable), Icons.Default.Block, modifier)
        is CartProblem.SoldOut ->
            StatusChip(stringResource(R.string.f6_sold_out), Icons.Default.RemoveShoppingCart, modifier)
    }
}

@Composable
internal fun CartIconButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cd = stringResource(R.string.f6_cart_cd, count)
    IconButton(onClick = onClick, modifier = modifier.size(48.dp).semantics { contentDescription = cd }) {
        BadgedBox(badge = { if (count > 0) Badge { Text(if (count > 99) "99+" else count.toString()) } }) {
            Icon(Icons.Default.ShoppingCart, contentDescription = null)
        }
    }
}
