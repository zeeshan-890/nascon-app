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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nascon.league.R
import com.nascon.league.data.api.dto.RosterPlayerDto
import com.nascon.league.data.api.dto.SquadDto
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.ErrorOutline
import com.nascon.league.ui.components.ArenaTextField
import com.nascon.league.ui.theme.Arena
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
    rejection: RosterRejection? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var reasonTouched by rememberSaveable { mutableStateOf(false) }
    val type = draft.type

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
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
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (rejection != null) {
                RosterRejectionCard(
                    code = rejection.code,
                    details = rejection.details,
                    onBack = onDismiss,
                    onChangeDate = if (rejection.code == "TRANSFER_WINDOW_CLOSED") ({ picker = PICK_EFFECTIVE }) else null,
                )
            }
            if (!fromSquadName.isNullOrBlank()) {
                Text(
                    stringResource(R.string.f7_current_squad, fromSquadName),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (RosterLogic.showsOnLoanWarning(player)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Arena.colors.warnBg,
                    border = BorderStroke(1.dp, Arena.colors.warnBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(Spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Arena.colors.warnFg)
                        Text(
                            stringResource(R.string.f7_on_loan_warning),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Arena.colors.warnFg,
                        )
                    }
                }
            }

            // ---- destination squad ----
            if (type != MoveType.RELEASE) {
                Text(
                    stringResource(R.string.f7_field_squad),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                if (destinations.isEmpty()) {
                    Text(stringResource(R.string.f7_no_squads), style = MaterialTheme.typography.bodyLarge)
                } else {
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        destinations.forEach { opt ->
                            val selected = draft.toSquadId == opt.squad.squadId
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) Arena.colors.chipBg else Color.White,
                                border = BorderStroke(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) Arena.colors.fieldBorder else Arena.colors.cardBorder,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .selectable(
                                        selected = selected,
                                        role = Role.RadioButton,
                                        onClick = { onEdit { RosterDraftReducer.withToSquad(it, opt.squad.squadId) } },
                                    ),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                ) {
                                    RadioButton(selected = selected, onClick = null)
                                    Text(
                                        stringResource(R.string.f7_squad_option, opt.squad.name, opt.size),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Arena.colors.ink,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                errors.toSquad?.takeIf { it != RosterError.REQUIRED }?.let {
                    ErrorLine(errorText(it))
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
            ArenaTextField(
                value = draft.reason,
                onValueChange = {
                    reasonTouched = true
                    onEdit { d -> RosterDraftReducer.withReason(d, it) }
                },
                label = stringResource(R.string.f7_field_reason),
                isError = reasonError != null,
                errorText = reasonError?.let { errorText(it) },
                supportingText = stringResource(R.string.f7_reason_hint) + "  " +
                    stringResource(R.string.f7_reason_counter, draft.reason.length, ROSTER_REASON_MAX),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                singleLine = false,
                minLines = 2,
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
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        SecondaryButton(
            text = shown,
            onClick = onClick,
            icon = Icons.Default.CalendarMonth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics { contentDescription = cd },
        )
        if (error != null) {
            ErrorLine(error)
        }
    }
}

@Composable
private fun ErrorLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Arena.colors.liveFg, modifier = Modifier.size(20.dp))
        Text(text, color = Arena.colors.liveFg, style = MaterialTheme.typography.bodyMedium)
    }
}
