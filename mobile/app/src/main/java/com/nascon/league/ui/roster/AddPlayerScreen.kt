package com.nascon.league.ui.roster

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.AlertTone
import com.nascon.league.ui.components.ArenaTextField
import com.nascon.league.ui.components.FilterPill
import com.nascon.league.ui.components.InlineAlert
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing

private val ROLE_LABELS = mapOf(
    "captain" to R.string.rp_role_captain,
    "vice_captain" to R.string.rp_role_vice_captain,
    "starter" to R.string.rp_role_starter,
    "substitute" to R.string.rp_role_substitute,
)

/** E15 - register a new player with the club (online only; this is never queued). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddPlayerScreen(clubId: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val vm: AddPlayerViewModel = viewModel(
        key = "addplayer-$clubId",
        factory = AddPlayerViewModel.factory(container, clubId),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val cached by remember(clubId) { container.clubRepository.observe(clubId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val club = cached?.data
    val snackbar = LocalSnackbarHostState.current
    val savedFmt = stringResource(R.string.rp_add_saved, "%s")
    LaunchedEffect(vm, savedFmt) {
        vm.events.collect { e ->
            if (e is AddPlayerEvent.Added) {
                snackbar.showSnackbar(savedFmt.replace("%s", e.name))
                onDone()
            }
        }
    }

    val squads = club?.squads.orEmpty()
    val roster = club?.roster.orEmpty()
    val leagueId = roster.firstNotNullOfOrNull { it.leagueId }
    val suggestions = remember(roster) { AddPlayerLogic.positionSuggestions(roster.map { it.position }) }
    val errors = AddPlayerLogic.errors(s.form)
    val c = Arena.colors

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ArenaTextField(
            value = s.form.name,
            onValueChange = { v -> vm.edit { it.copy(name = v.take(AddPlayerLogic.TEXT_MAX)) } },
            label = stringResource(R.string.rp_add_name),
            errorText = if (s.submitted && AddPlayerError.NAME in errors) stringResource(R.string.rp_add_err_name) else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            enabled = !s.submitting,
            modifier = Modifier.fillMaxWidth(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ArenaTextField(
                value = s.form.position,
                onValueChange = { v -> vm.edit { it.copy(position = v.take(AddPlayerLogic.TEXT_MAX)) } },
                label = stringResource(R.string.rp_add_position),
                errorText = if (s.submitted && AddPlayerError.POSITION in errors) stringResource(R.string.rp_add_err_position) else null,
                enabled = !s.submitting,
                modifier = Modifier.fillMaxWidth(),
            )
            if (suggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    suggestions.forEach { p ->
                        FilterPill(p, selected = s.form.position == p, onClick = { vm.edit { it.copy(position = p) } })
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                stringResource(R.string.rp_add_squad),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (squads.isEmpty()) {
                Text(stringResource(R.string.rp_add_no_squads), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                squads.forEach { sq ->
                    val size = RosterLogic.squadSize(roster, sq.squadId)
                    FilterPill(
                        stringResource(R.string.rp_add_squad_option, sq.name, size),
                        selected = s.form.squadId == sq.squadId,
                        onClick = { vm.edit { it.copy(squadId = sq.squadId) } },
                    )
                }
            }
            if (s.submitted && AddPlayerError.SQUAD in errors) {
                Text(stringResource(R.string.rp_add_err_squad), style = MaterialTheme.typography.bodyMedium, color = c.liveFg)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                stringResource(R.string.rp_add_role),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                ROLE_LABELS.forEach { (key, label) ->
                    FilterPill(stringResource(label), selected = s.form.role == key, onClick = { vm.edit { it.copy(role = key) } })
                }
            }
        }

        when (val f = s.failure) {
            null -> Unit
            AddPlayerFailure.Offline -> InlineAlert(stringResource(R.string.rp_add_offline), AlertTone.WARNING)
            AddPlayerFailure.AlreadyRegistered -> InlineAlert(stringResource(R.string.rp_add_already), AlertTone.ERROR)
            AddPlayerFailure.NoLeague -> InlineAlert(stringResource(R.string.rp_add_err_league), AlertTone.ERROR)
            is AddPlayerFailure.Rule -> RosterRejectionCard(f.rejection.code, f.rejection.details)
            is AddPlayerFailure.Other -> InlineAlert(f.message, AlertTone.ERROR)
        }
        InlineAlert(stringResource(R.string.rp_add_info), AlertTone.WARNING)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.rp_add_cancel), onDone, enabled = !s.submitting)
            PrimaryButton(
                text = stringResource(R.string.rp_add_submit),
                onClick = { vm.submit(leagueId) },
                loading = s.submitting,
                enabled = !s.submitting,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
