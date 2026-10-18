package com.nascon.league.ui.clubadmin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nascon.league.R
import com.nascon.league.data.clubadmin.FormError
import com.nascon.league.ui.components.ConfirmDialog
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.FeatureUnavailableState
import com.nascon.league.ui.components.InlineAlert
import com.nascon.league.ui.components.AlertTone
import com.nascon.league.ui.components.LoadingState

/** Plain-language text for a validation problem. */
@Composable
fun FormError.text(): String = stringResource(
    when (this) {
        FormError.Required -> R.string.ca_err_required
        FormError.TooLong -> R.string.ca_err_too_long
        FormError.NotANumber -> R.string.ca_err_number
        FormError.LatRange -> R.string.ca_err_lat
        FormError.LngRange -> R.string.ca_err_lng
        FormError.BothCoordinates -> R.string.ca_err_both_coords
        FormError.BadUrl -> R.string.ca_err_url
        FormError.PriceInvalid -> R.string.ca_err_price
        FormError.StockInvalid -> R.string.ca_err_stock
        FormError.Duplicate -> R.string.ca_err_duplicate
        FormError.TooMany -> R.string.ca_err_too_many
        FormError.NoVariants -> R.string.ca_err_no_variants
    },
)

/** Scrolling form with a fixed button bar underneath. Buttons share the width. */
@Composable
fun FormLayout(
    modifier: Modifier = Modifier,
    bottom: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = bottom,
        )
    }
}

/** Shows the right placeholder for a screen that is not ready. Returns true when the caller should draw its content. */
@Composable
fun PhaseGate(
    phase: LoadPhase,
    error: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
): Boolean {
    when (phase) {
        LoadPhase.Ready -> return true
        LoadPhase.Loading -> LoadingState(modifier = modifier)
        LoadPhase.Unavailable -> FeatureUnavailableState(modifier = modifier, onBack = onBack)
        LoadPhase.Error -> ErrorState(message = error.orEmpty(), onRetry = onRetry, modifier = modifier)
    }
    return false
}

@Composable
fun NoAccessState(modifier: Modifier = Modifier, onBack: () -> Unit) {
    EmptyState(
        icon = Icons.Default.Lock,
        title = stringResource(R.string.ca_no_access_title),
        message = stringResource(R.string.ca_no_access_message),
        modifier = modifier,
        actionLabel = stringResource(R.string.ca_cancel),
        onAction = onBack,
    )
}

/** Holds the "discard changes?" flow: back press and Cancel both go through [attemptLeave]. */
class DiscardGuard(val showDialog: Boolean, val attemptLeave: () -> Unit)

@Composable
fun rememberDiscardGuard(dirty: Boolean, onLeave: () -> Unit): DiscardGuard {
    var show by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = dirty) { show = true }
    if (show) {
        ConfirmDialog(
            title = stringResource(R.string.ca_discard_title),
            message = stringResource(R.string.ca_discard_message),
            confirmLabel = stringResource(R.string.ca_discard_confirm),
            dismissLabel = stringResource(R.string.ca_keep_editing),
            destructive = true,
            onConfirm = { show = false; onLeave() },
            onDismiss = { show = false },
        )
    }
    return DiscardGuard(show) { if (dirty) show = true else onLeave() }
}

@Composable
fun SaveErrorAlert(message: String?) {
    if (message != null) InlineAlert(message, AlertTone.ERROR)
}
