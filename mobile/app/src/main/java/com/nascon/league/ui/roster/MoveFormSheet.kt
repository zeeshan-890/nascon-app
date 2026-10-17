package com.nascon.league.ui.roster

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nascon.league.R
import com.nascon.league.data.api.dto.RosterPlayerDto
import com.nascon.league.data.api.dto.SquadDto
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.theme.Spacing
import androidx.compose.foundation.text.KeyboardOptions

/** A selectable destination squad and its current size. */
data class SquadOption(val squad: SquadDto, val size: Int)

private const val PICK_EFFECTIVE = "effective"
private const val PICK_LOAN_END = "loan_end"

@Composable
private fun errorText(error: RosterError): String = when (error) {
    RosterError.REQUIRED -> stringResource(R.string.f7_err_required)
    RosterError.LOAN_END_NOT_AFTER_START -> stringResource(R.string.f7_err_loan_end)
    RosterError.REASON_TOO_LONG -> stringResource(R.string.f7_err_reason_long)
    RosterError.SAME_SQUAD -> stringResource(R.string.f7_err_same_squad)
    RosterError.INVALID_DATE -> stringResource(R.string.f7_err_invalid_date)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveFormSheet(
    player: RosterPlayerDto,
    fromSquadName: String?,
    draft: RosterDraft,
    destinations: List<SquadOption>,
    errors: RosterFormErrors,
    canSubmit: Boolean,
    submitting: Boolean,
    onEdit: ((RosterDraft) -> RosterDraft) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var reasonTouched by rememberSaveable { mutableStateOf(false) }
    val type = draft.type

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val title = when (type) {
                MoveType.TRANSFER -> stringResource(R.string.f7_form_title_transfer, player.name)
                MoveType.LOAN -> stringResource(R.string.f7_form_title_loan, player.name)
                MoveType.RELEASE -> stringResource(R.string.f7_form_title_release, player.name)
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (!fromSquadName.isNullOrBlank()) {
                Text(
                    stringResource(R.string.f7_current_squad, fromSquadName),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (RosterLogic.showsOnLoanWarning(player)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.f7_on_loan_warning), style = MaterialTheme.typography.bodyLarge)
                }
            }

            // ---- destination squad ----
            if (type != MoveType.RELEASE) {
                Text(
                    stringResource(R.string.f7_field_squad),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (destinations.isEmpty()) {
                    Text(stringResource(R.string.f7_no_squads), style = MaterialTheme.typography.bodyLarge)
                } else {
                    Column(Modifier.selectableGroup()) {
                        destinations.forEach { opt ->
                            val selected = draft.toSquadId == opt.squad.squadId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(
                                        selected = selected,
                                        role = Role.RadioButton,
                                        onClick = { onEdit { RosterDraftReducer.withToSquad(it, opt.squad.squadId) } },
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Text(
                                    stringResource(R.string.f7_squad_option, opt.squad.name, opt.size),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                errors.toSquad?.takeIf { it != RosterError.REQUIRED }?.let {
                    Text(errorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // ---- effective date ----
            DateField(
                label = stringResource(R.string.f7_field_effective),
                value = formatRosterDate(draft.effectiveDate),
                error = errors.effectiveDate?.let { errorText(it) },
                onClick = { picker = PICK_EFFECTIVE },
            )

            // ---- loan end ----
            if (type == MoveType.LOAN) {
                DateField(
                    label = stringResource(R.string.f7_field_loan_end),
                    value = draft.loanEndDate?.let { formatRosterDate(it) },
                    error = if (draft.loanEndDate != null) errors.loanEnd?.let { errorText(it) } else null,
                    onClick = { picker = PICK_LOAN_END },
                )
            }

            // ---- reason ----
            val reasonError = errors.reason?.takeIf { reasonTouched || draft.reason.isNotEmpty() }
            OutlinedTextField(
                value = draft.reason,
                onValueChange = {
                    reasonTouched = true
                    onEdit { d -> RosterDraftReducer.withReason(d, it) }
                },
                label = { Text(stringResource(R.string.f7_field_reason)) },
                isError = reasonError != null,
                supportingText = {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            reasonError?.let { errorText(it) } ?: stringResource(R.string.f7_reason_hint),
                            modifier = Modifier.weight(1f),
                        )
                        Text(stringResource(R.string.f7_reason_counter, draft.reason.length, ROSTER_REASON_MAX))
                    }
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            PrimaryButton(
                text = stringResource(R.string.f7_review),
                onClick = onSubmit,
                enabled = canSubmit && !submitting,
                loading = submitting,
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(
                text = stringResource(R.string.f7_back),
                onClick = onBack,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    val pickerKey = picker
    if (pickerKey != null) {
        val initial = when (pickerKey) {
            PICK_LOAN_END -> draft.loanEndDate ?: draft.effectiveDate.plusMonths(1)
            else -> draft.effectiveDate
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = dateToPickerMillis(initial))
        DatePickerDialog(
            onDismissRequest = { picker = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { ms ->
                            val date = pickerMillisToDate(ms)
                            if (pickerKey == PICK_LOAN_END) {
                                onEdit { RosterDraftReducer.withLoanEnd(it, date) }
                            } else {
                                onEdit { RosterDraftReducer.withEffectiveDate(it, date) }
                            }
                        }
                        picker = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.f7_date_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { picker = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.f7_cancel))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun DateField(label: String, value: String?, error: String?, onClick: () -> Unit) {
    val shown = value ?: stringResource(R.string.f7_date_unset)
    val cd = stringResource(R.string.f7_date_cd, label, shown)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics { contentDescription = cd },
        ) {
            Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                shown,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(start = Spacing.sm),
            )
        }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
