package com.nascon.league.ui.orders

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.core.network.ConnState
import com.nascon.league.data.api.dto.OrderDetailDto
import com.nascon.league.data.store.formatPkr
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.AlertTone
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ConfirmDialog
import com.nascon.league.ui.components.InlineAlert
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.receipt.ReceiptFormat
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing

@StringRes
internal fun actionLabelRes(to: String): Int = when (to) {
    "paid" -> R.string.ordx_action_paid
    "packed" -> R.string.ordx_action_packed
    "shipped" -> R.string.ordx_action_shipped
    "delivered" -> R.string.ordx_action_delivered
    "cancelled" -> R.string.ordx_action_cancelled
    else -> R.string.ordx_action_refund
}

/** E23/E24: status actions and refund for roles with order.manage. Online only, nothing is queued. */
@Composable
fun ManageOrderSection(orderId: String, order: OrderDetailDto, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    if (!canManageOrders(session)) return

    val conn by container.connectivity.state.collectAsStateWithLifecycle()
    val vm: OrderActionsViewModel = viewModel(
        key = "order-actions-$orderId",
        factory = OrderActionsViewModel.factory(container, orderId),
    )
    val st by vm.state.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = LocalSnackbarHostState.current
    val updatedMsg = stringResource(R.string.ordx_done_updated)
    val refundedMsg = stringResource(R.string.ordx_done_refunded)
    LaunchedEffect(vm) {
        vm.done.collect { r ->
            snackbar.showSnackbar(
                if (r == OrderActionResult.REFUNDED) refundedMsg else updatedMsg,
                duration = SnackbarDuration.Short,
            )
        }
    }

    val actions = OrderStateMachine.statusActions(order.status)
    val canRefund = OrderStateMachine.canRefund(order.status)
    val offline = conn == ConnState.OFFLINE
    val ref = ReceiptFormat.orderReference(orderId)

    ArenaCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                stringResource(R.string.ordx_manage_title),
                style = MaterialTheme.typography.titleMedium,
                color = Arena.colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            when {
                actions.isEmpty() && !canRefund ->
                    Text(
                        stringResource(R.string.ordx_no_actions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                offline -> InlineAlert(stringResource(R.string.ordx_offline), AlertTone.INFO)
                else -> {
                    val enabled = !st.busy
                    actions.forEach { to ->
                        SecondaryButton(
                            text = stringResource(actionLabelRes(to)),
                            onClick = { if (to == "cancelled") confirm = to else vm.changeStatus(to) },
                            enabled = enabled,
                            destructive = to == "cancelled",
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        )
                    }
                    if (canRefund) {
                        SecondaryButton(
                            text = stringResource(R.string.ordx_action_refund),
                            onClick = { confirm = "refunded" },
                            enabled = enabled,
                            destructive = true,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        )
                    }
                    if (st.busy) {
                        Text(
                            stringResource(R.string.ordx_working),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            st.errorMessage?.let { msg ->
                InlineAlert(if (st.illegal) stringResource(R.string.ordx_illegal) else msg, AlertTone.ERROR)
            }
        }
    }

    when (confirm) {
        "refunded" -> {
            val restock = OrderStateMachine.restockOn(order.status, "refunded")
            ConfirmDialog(
                title = stringResource(R.string.ordx_confirm_refund_title, ref),
                message = stringResource(
                    if (restock) R.string.ordx_confirm_refund_message else R.string.ordx_confirm_refund_message_nostock,
                    formatPkr(order.totalMinor),
                ),
                confirmLabel = stringResource(R.string.ordx_confirm_refund_yes),
                onConfirm = { confirm = null; vm.refund() },
                onDismiss = { confirm = null },
                destructive = true,
                dismissLabel = stringResource(R.string.ordx_keep),
            )
        }
        "cancelled" -> ConfirmDialog(
            title = stringResource(R.string.ordx_confirm_cancel_title, ref),
            message = stringResource(R.string.ordx_confirm_cancel_message),
            confirmLabel = stringResource(R.string.ordx_confirm_cancel_yes),
            onConfirm = { confirm = null; vm.changeStatus("cancelled") },
            onDismiss = { confirm = null },
            destructive = true,
            dismissLabel = stringResource(R.string.ordx_keep),
        )
    }
}
