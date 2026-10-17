package com.nascon.league.ui.scoring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascon.league.R
import com.nascon.league.ui.components.ConfirmDialog
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Spacing

/** Hosts the snackbar messages and confirmation dialogs for a [ScoringViewModel]. Place once per screen. */
@Composable
fun ScoringHost(vm: ScoringViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val ctx = LocalContext.current
    LaunchedEffect(vm) {
        vm.messageFlow.collect { msg ->
            val res = when (msg) {
                ScoringMessage.SCORE_ADDED -> R.string.f8_msg_score_added
                ScoringMessage.SCORE_SAVED_OFFLINE -> R.string.f8_msg_score_offline
                ScoringMessage.CHANGE_SAVED -> R.string.f8_msg_change_saved
                ScoringMessage.CHANGE_SAVED_OFFLINE -> R.string.f8_msg_change_offline
                ScoringMessage.FAILED -> R.string.f8_msg_failed
            }
            snackbar.showSnackbar(ctx.getString(res))
        }
    }

    if (s.showEndConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.f8_end_title),
            message = stringResource(R.string.f8_end_message),
            confirmLabel = stringResource(R.string.f8_end_confirm),
            onConfirm = vm::confirmEnd,
            onDismiss = vm::dismissEnd,
            destructive = true,
            dismissLabel = stringResource(R.string.f8_cancel),
        )
    }
    val undoId = s.undoEventId
    if (undoId != null) {
        UndoDialog(
            eventId = undoId,
            error = s.reasonError,
            onReasonChange = vm::onReasonChange,
            onConfirm = vm::confirmUndo,
            onDismiss = vm::dismissUndo,
        )
    }
}

@Composable
fun UndoDialog(
    eventId: String,
    error: ReasonError?,
    onReasonChange: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by rememberSaveable(eventId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.f8_undo_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.f8_undo_message), style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    value = reason,
                    onValueChange = {
                        reason = it
                        onReasonChange(it)
                    },
                    label = { Text(stringResource(R.string.f8_reason_label)) },
                    isError = error != null,
                    supportingText = {
                        Text(
                            when (error) {
                                ReasonError.EMPTY -> stringResource(R.string.f8_reason_empty)
                                ReasonError.TOO_LONG -> stringResource(R.string.f8_reason_too_long)
                                null -> stringResource(R.string.f8_reason_counter, reason.length)
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.f8_undo_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.f8_cancel))
            }
        },
    )
}
