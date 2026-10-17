package com.nascon.league.ui.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.data.store.PaymentMethod
import com.nascon.league.data.store.formatPkr
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.ConfirmDialog
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.theme.Spacing

@Composable
fun CheckoutScreen(
    onOrderPlaced: (orderId: String) -> Unit,
    onBackToCart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val vm: CheckoutViewModel = viewModel(factory = CheckoutViewModel.factory(container))
    val s by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(s.placedOrderId) { s.placedOrderId?.let(onOrderPlaced) }
    LaunchedEffect(s.backToCart) {
        if (s.backToCart) {
            vm.backToCartHandled()
            onBackToCart()
        }
    }

    // While the order is being accepted the cart is cleared; do not flash the "empty" state.
    if (s.placedOrderId != null || !s.cartLoaded) {
        val cd = stringResource(R.string.f6_loading_cd)
        Box(modifier.fillMaxSize().semantics { contentDescription = cd }, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (s.lines.isEmpty() && !s.inFlight) {
        EmptyState(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.f6_cart_empty_title),
            message = stringResource(R.string.f6_cart_empty_message),
            actionLabel = stringResource(R.string.f6_back_to_cart),
            onAction = onBackToCart,
            modifier = modifier,
        )
        return
    }

    CheckoutContent(
        s = s,
        onDiscount = vm::onDiscountChange,
        onMethod = vm::onMethodChange,
        onPay = vm::onPayTapped,
        onBackToCart = onBackToCart,
        modifier = modifier,
    )
    if (s.confirmOpen) {
        ConfirmDialog(
            title = stringResource(R.string.f6_confirm_title, formatPkr(s.subtotalMinor)),
            message = stringResource(R.string.f6_confirm_message),
            confirmLabel = stringResource(R.string.f6_confirm_pay),
            onConfirm = vm::confirmPay,
            onDismiss = vm::dismissConfirm,
            dismissLabel = stringResource(R.string.f6_cancel),
        )
    }
}

@Composable
private fun CheckoutContent(
    s: CheckoutUiState,
    onDiscount: (String) -> Unit,
    onMethod: (PaymentMethod) -> Unit,
    onPay: () -> Unit,
    onBackToCart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // Order summary
            Text(
                stringResource(R.string.f6_order_summary),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    s.lines.forEach { line ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Column(Modifier.weight(1f)) {
                                Text(line.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(R.string.f6_summary_detail, line.variantLabel, line.quantity),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                formatPkr(line.unitPriceMinor * line.quantity),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.f6_subtotal),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(formatPkr(s.subtotalMinor), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            // Discount
            OutlinedTextField(
                value = s.discountCode,
                onValueChange = onDiscount,
                enabled = !s.inFlight,
                singleLine = true,
                label = { Text(stringResource(R.string.f6_discount_label)) },
                isError = s.discountError,
                supportingText = {
                    Text(
                        stringResource(if (s.discountError) R.string.f6_discount_invalid else R.string.f6_discount_helper),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth(),
            )

            // Payment method
            Text(
                stringResource(R.string.f6_payment_method),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Column(Modifier.selectableGroup()) {
                PaymentMethod.entries.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(
                                selected = s.method == m,
                                enabled = !s.inFlight,
                                role = Role.RadioButton,
                                onClick = { onMethod(m) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s.method == m, onClick = null, enabled = !s.inFlight)
                        Text(
                            stringResource(methodLabel(m)),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = Spacing.md),
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.f6_demo_note),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            s.banner?.let { PayBanner(it, s.bannerMessage) }
        }

        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.f6_estimated_total),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(formatPkr(s.subtotalMinor), style = MaterialTheme.typography.titleLarge)
                }
                when (s.hint) {
                    PayHint.Offline -> Text(
                        stringResource(R.string.f6_pay_offline),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    PayHint.Problems -> {
                        Text(
                            stringResource(R.string.f6_checkout_blocked),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        SecondaryButton(
                            text = stringResource(R.string.f6_back_to_cart),
                            onClick = onBackToCart,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> Unit
                }
                val payingCd = stringResource(R.string.f6_paying_cd)
                PrimaryButton(
                    text = stringResource(R.string.f6_pay_button, formatPkr(s.subtotalMinor)),
                    onClick = onPay,
                    loading = s.inFlight,
                    enabled = s.payEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .semantics { if (s.inFlight) contentDescription = payingCd },
                )
            }
        }
    }
}

private fun methodLabel(m: PaymentMethod): Int = when (m) {
    PaymentMethod.APPROVED -> R.string.f6_method_approved
    PaymentMethod.DECLINED -> R.string.f6_method_declined
    PaymentMethod.CAPTURE_FAIL -> R.string.f6_method_capture_fail
}

@Composable
private fun PayBanner(kind: BannerKind, message: String?) {
    val text = when (kind) {
        BannerKind.Declined -> stringResource(R.string.f6_banner_declined)
        BannerKind.CaptureFailed -> stringResource(R.string.f6_banner_capture)
        BannerKind.ConnectionLost -> stringResource(R.string.f6_banner_connection)
        BannerKind.TryAgain -> stringResource(R.string.f6_banner_try_again)
        BannerKind.Other -> message ?: stringResource(R.string.f6_banner_generic)
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Row(Modifier.padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
