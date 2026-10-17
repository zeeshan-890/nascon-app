package com.nascon.league.ui.store

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nascon.league.R
import com.nascon.league.data.store.formatPkr
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing

@Composable
fun OrderSuccessScreen(
    orderId: String,
    onViewReceipt: (String) -> Unit,
    onContinueShopping: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = OrderResultHolder.totalFor(orderId)
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(104.dp).clip(CircleShape).background(Arena.colors.headerBrush),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = Color.White,
            )
        }
        Text(
            stringResource(R.string.f6_order_placed),
            style = MaterialTheme.typography.headlineMedium,
            color = Arena.colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.f6_order_ref, shortOrderRef(orderId)),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (total != null) {
            Text(
                stringResource(R.string.f6_order_total, formatPkr(total)),
                style = MaterialTheme.typography.titleLarge,
                color = Arena.colors.brand,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            stringResource(R.string.f6_order_thanks),
            style = MaterialTheme.typography.bodyLarge,
            color = Arena.colors.inkSoft,
            textAlign = TextAlign.Center,
        )
        PrimaryButton(
            text = stringResource(R.string.f6_view_receipt),
            onClick = { onViewReceipt(orderId) },
            icon = Icons.Default.Receipt,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        )
        SecondaryButton(
            text = stringResource(R.string.f6_continue_shopping),
            onClick = onContinueShopping,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
