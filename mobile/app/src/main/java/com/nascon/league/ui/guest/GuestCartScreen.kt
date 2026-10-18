package com.nascon.league.ui.guest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.R
import com.nascon.league.di.AppContainer
import com.nascon.league.matchlive.CartLine
import com.nascon.league.matchlive.GuestCart
import com.nascon.league.matchlive.MatchLiveLogic
import com.nascon.league.matchlive.MatchLiveTags
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val GUEST_CART_MAX_QTY = 10

class GuestCartViewModel(private val cart: GuestCart) : ViewModel() {
    val lines: StateFlow<List<CartLine>> =
        cart.lines.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val count: StateFlow<Int> =
        cart.lines.map { l -> l.sumOf { it.quantity } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val total: StateFlow<Long> =
        cart.lines.map { l -> l.sumOf { it.priceMinor * it.quantity } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private suspend fun current(variantId: String): CartLine? =
        cart.lines.first().firstOrNull { it.variantId == variantId }

    fun inc(variantId: String) {
        viewModelScope.launch {
            val line = current(variantId) ?: return@launch
            if (line.quantity < GUEST_CART_MAX_QTY) cart.setQuantity(variantId, line.quantity + 1)
        }
    }

    fun dec(variantId: String) {
        viewModelScope.launch {
            val line = current(variantId) ?: return@launch
            if (line.quantity <= 1) cart.remove(variantId) else cart.setQuantity(variantId, line.quantity - 1)
        }
    }

    fun remove(variantId: String) {
        viewModelScope.launch { cart.remove(variantId) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { GuestCartViewModel(container.guestCart) }
        }
    }
}

@Composable
fun GuestCartScreen(vm: GuestCartViewModel, onProceedToCheckout: () -> Unit, onBrowse: () -> Unit) {
    val lines by vm.lines.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()

    if (lines.isEmpty()) {
        EmptyState(
            icon = Icons.Default.ShoppingCart,
            title = stringResource(R.string.gs_cart_empty_title),
            message = stringResource(R.string.gs_cart_empty_message),
            actionLabel = stringResource(R.string.gs_cart_browse),
            onAction = onBrowse,
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(lines, key = { it.variantId }) { line ->
                LineCard(line, onInc = { vm.inc(line.variantId) }, onDec = { vm.dec(line.variantId) }, onRemove = { vm.remove(line.variantId) })
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.gs_cart_items), style = MaterialTheme.typography.bodyMedium, color = Arena.colors.inkMid)
            Text(
                count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag(MatchLiveTags.GUEST_CART_COUNT),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.gs_cart_subtotal), style = MaterialTheme.typography.bodyMedium, color = Arena.colors.inkMid)
            Text(MatchLiveLogic.formatPrice(total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        PrimaryButton(
            text = stringResource(R.string.gs_cart_checkout),
            onClick = onProceedToCheckout,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        )
    }
}

@Composable
private fun LineCard(line: CartLine, onInc: () -> Unit, onDec: () -> Unit, onRemove: () -> Unit) {
    ArenaCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(line.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(line.variantId, style = MaterialTheme.typography.labelSmall, color = Arena.colors.inkSoft)
                    Text(
                        stringResource(R.string.gs_cart_each, MatchLiveLogic.formatPrice(line.priceMinor)),
                        style = MaterialTheme.typography.bodySmall,
                        color = Arena.colors.inkMid,
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.gs_cart_remove, line.name))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDec, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.gs_cart_dec, line.name))
                    }
                    Text(line.quantity.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.sm))
                    IconButton(
                        onClick = onInc,
                        enabled = line.quantity < GUEST_CART_MAX_QTY,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.gs_cart_inc, line.name))
                    }
                }
                Text(
                    MatchLiveLogic.formatPrice(line.priceMinor * line.quantity),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
