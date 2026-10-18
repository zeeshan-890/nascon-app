package com.nascon.league.ui.platformadmin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.data.platformadmin.SportDefinitionDto
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.AlertTone
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ArenaTextField
import com.nascon.league.ui.components.ConfirmDialog
import com.nascon.league.ui.components.InlineAlert
import com.nascon.league.ui.components.LoadingState
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.theme.Arena
import kotlinx.serialization.json.JsonObject

@Composable
private fun pointsText(n: Int): String =
    LocalContext.current.resources.getQuantityString(R.plurals.pa_points, n, n)

@Composable
private fun eventsCountText(n: Int): String =
    LocalContext.current.resources.getQuantityString(R.plurals.pa_scoring_events, n, n)

/** G09: sports configured on the server. */
@Composable
fun AdminSportsScreen(onSport: (String) -> Unit, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: AdminSportsViewModel = viewModel(factory = AdminSportsViewModel.factory(container))
    val s by vm.state.collectAsStateWithLifecycle()
    val failure = s.failure
    when {
        s.sports.isEmpty() && s.loading -> LoadingState()
        s.sports.isEmpty() && failure != null -> PaFailure(failure, onRetry = vm::load, onBack = onBack)
        else -> PaPage {
            if (s.readOnlyFallback) InlineAlert(stringResource(R.string.pa_sports_fallback), AlertTone.INFO)
            s.sports.forEach { sport ->
                ArenaCard(Modifier.fillMaxWidth(), onClick = { onSport(sport.sportId) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(sport.displayName(), style = MaterialTheme.typography.titleMedium, color = Arena.colors.ink)
                            val draws = stringResource(if (sport.drawAllowed) R.string.pa_draws_allowed else R.string.pa_no_draws)
                            Text(
                                stringResource(R.string.pa_sport_summary, eventsCountText(sport.eventCount()), draws),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                color = Arena.colors.inkSoft,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Arena.colors.brand)
                    }
                }
            }
            PaNote(stringResource(R.string.pa_sports_note))
        }
    }
}

/** G10: one sport's configuration. Reads the real `GET /api/v1/sports/{id}`. */
@Composable
fun SportConfigScreen(
    sportId: String,
    onEvent: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: SportConfigViewModel = viewModel(key = "pa_sport_$sportId", factory = SportConfigViewModel.factory(container, sportId))
    val s by vm.state.collectAsStateWithLifecycle()
    val sport = s.shown
    HeaderSubtitle(sport?.displayName())

    val lf = s.loadFailure
    if (sport == null) {
        if (s.loading) LoadingState() else if (lf != null) PaFailure(lf, onRetry = vm::load, onBack = onBack)
        return
    }
    PaFormPage(
        bottomBar = {
            PrimaryButton(
                text = stringResource(R.string.pa_save_sport),
                onClick = vm::save,
                loading = s.saving,
                enabled = s.dirty,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        if (lf != null) InlineAlert(lf.message, AlertTone.WARNING)
        val sf = s.saveFailure
        if (sf != null) {
            InlineAlert(if (sf.unavailable) stringResource(R.string.pa_edit_unavailable) else sf.message, if (sf.unavailable) AlertTone.INFO else AlertTone.ERROR)
        }
        if (s.saved) InlineAlert(stringResource(R.string.pa_saved), AlertTone.SUCCESS)

        PaSwitchRow(stringResource(R.string.pa_draws_allowed_label), sport.drawAllowed, vm::setDraw)
        if (sport.scoringModel.isNotBlank()) PaInfoRow(stringResource(R.string.pa_scoring_model), humanizeKey(sport.scoringModel))

        val periods = periodRows(sport.periods)
        if (periods.isNotEmpty()) {
            PaHeading(stringResource(R.string.pa_periods))
            periods.forEach { (k, v) -> PaInfoRow(k, v) }
        }
        val overtime = overtimeText(sport.overtime)
        if (overtime.isNotBlank()) PaInfoRow(stringResource(R.string.pa_overtime), overtime)

        if (sport.tieBreakOrder.isNotEmpty()) {
            PaHeading(stringResource(R.string.pa_tie_break))
            ArenaCard(Modifier.fillMaxWidth()) {
                sport.tieBreakOrder.forEachIndexed { i, k ->
                    Text(
                        "${i + 1}. ${humanizeKey(k)}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Arena.colors.ink,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }

        PaHeading(stringResource(R.string.pa_roster))
        RosterStepperCard(
            label = stringResource(R.string.pa_squad_min),
            value = sport.roster.minSquadSize,
            min = 0, max = SportConfigViewModel.MAX_SQUAD,
            onChange = vm::setMinSquad,
        )
        RosterStepperCard(
            label = stringResource(R.string.pa_squad_max),
            value = sport.roster.maxSquadSize,
            min = sport.roster.minSquadSize, max = SportConfigViewModel.MAX_SQUAD,
            onChange = vm::setMaxSquad,
        )
        sport.roster.requiredPositions.forEach { (k, v) ->
            PaInfoRow(stringResource(R.string.pa_required_position, humanizeKey(k)), jsonDisplay(v))
        }

        PaHeading(stringResource(R.string.pa_scoring_events_heading))
        sport.scoringEvents.forEach { (key, ev) ->
            val obj = ev as? JsonObject
            ArenaCard(Modifier.fillMaxWidth(), onClick = { onEvent(key) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(eventLabel(key, obj), style = MaterialTheme.typography.titleSmall, color = Arena.colors.ink, modifier = Modifier.weight(1f))
                    Text(pointsText(eventPoints(obj)), style = MaterialTheme.typography.bodyMedium, color = Arena.colors.inkSoft)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Arena.colors.brand)
                }
            }
        }
        SecondaryButton(
            text = stringResource(R.string.pa_add_event),
            onClick = { onEvent(null) },
            icon = Icons.Default.Add,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RosterStepperCard(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    ArenaCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Arena.colors.inkSoft, modifier = Modifier.weight(1f))
            PaStepper(
                value = value, onChange = onChange, min = min, max = max,
                decreaseDescription = stringResource(R.string.pa_decrease_named, label),
                increaseDescription = stringResource(R.string.pa_increase_named, label),
            )
        }
    }
}

/** G11: add or edit one scoring event of a sport. */
@Composable
fun ScoringEventScreen(
    sportId: String,
    eventKey: String?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: ScoringEventViewModel = viewModel(
        key = "pa_event_${sportId}_${eventKey.orEmpty()}",
        factory = ScoringEventViewModel.factory(container, sportId, eventKey),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.done.collect { onDone() } }

    val lf = s.loadFailure
    if (s.sport == null) {
        if (s.loading) LoadingState() else if (lf != null) PaFailure(lf, onRetry = vm::load, onBack = onCancel)
        return
    }
    val sf = s.saveFailure
    if (sf?.unavailable == true) {
        PaFailure(sf, onRetry = null, onBack = onCancel)
        return
    }
    HeaderSubtitle(s.sport?.displayName())
    PaFormPage(
        bottomBar = {
            PaButtonPair(
                secondaryText = stringResource(R.string.pa_cancel),
                onSecondary = onCancel,
                primaryText = stringResource(R.string.pa_save_event),
                onPrimary = vm::save,
                primaryLoading = s.saving,
                secondaryEnabled = !s.saving,
            )
        },
    ) {
        if (sf != null) InlineAlert(sf.message, AlertTone.ERROR)
        ArenaTextField(
            value = s.label, onValueChange = vm::onLabel, label = stringResource(R.string.pa_event_label),
            errorText = fieldError(s.labelProblem),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            enabled = !s.saving,
        )
        ArenaTextField(
            value = s.code, onValueChange = vm::onCode, label = stringResource(R.string.pa_event_code),
            supportingText = stringResource(if (vm.isEditing) R.string.pa_event_code_locked else R.string.pa_event_code_hint),
            errorText = fieldError(s.codeProblem, badFormat = R.string.pa_err_code_format, duplicate = R.string.pa_err_event_duplicate),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            enabled = !s.saving && !vm.isEditing,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pa_event_points), style = MaterialTheme.typography.labelLarge, color = Arena.colors.ink)
            PaStepper(
                value = s.points, onChange = vm::onPoints, min = 0, max = MAX_EVENT_POINTS,
                decreaseDescription = stringResource(R.string.pa_decrease_named, stringResource(R.string.pa_event_points)),
                increaseDescription = stringResource(R.string.pa_increase_named, stringResource(R.string.pa_event_points)),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        PaSwitchRow(stringResource(R.string.pa_event_needs_player), s.needsPlayer, vm::onNeedsPlayer)
        if (vm.isEditing) {
            SecondaryButton(
                text = stringResource(R.string.pa_remove_event),
                onClick = { vm.askRemove(true) },
                icon = Icons.Default.Delete,
                destructive = true,
                enabled = !s.saving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (s.confirmRemove) {
        ConfirmDialog(
            title = stringResource(R.string.pa_remove_event_title),
            message = stringResource(R.string.pa_remove_event_message, s.label),
            confirmLabel = stringResource(R.string.pa_remove),
            onConfirm = vm::remove,
            onDismiss = { vm.askRemove(false) },
            destructive = true,
            dismissLabel = stringResource(R.string.pa_cancel),
        )
    }
}
