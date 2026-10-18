package com.nascon.league.ui.leagueadmin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.data.leagueadmin.TeamDto
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ArenaTextField
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Arena

/** Teams of one league: the organizer (or super admin) adds the teams, then generates fixtures from them. */
@Composable
fun LeagueTeamsScreen(leagueId: String, onBack: () -> Unit, onGenerate: () -> Unit) {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    if (!LeagueAdminAccess.canViewLeague(session, leagueId)) {
        NoAccessState(onBack)
        return
    }
    val vm: LeagueTeamsViewModel = viewModel(factory = LeagueTeamsViewModel.factory(container, leagueId))
    val state by vm.state.collectAsStateWithLifecycle()
    val save by vm.save.collectAsStateWithLifecycle()
    val canEdit = LeagueAdminAccess.canEditLeague(session, leagueId)
    val snackbar = LocalSnackbarHostState.current
    val msg = stringResource(R.string.la_team_updated)
    var dialog by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(vm) {
        vm.saved.collect {
            dialog = false
            name = ""
            snackbar.showSnackbar(msg)
        }
    }
    // A failed removal (for example a team that already has fixtures) has no dialog to show it in.
    LaunchedEffect(save.error, dialog) {
        val err = save.error
        if (err != null && !dialog) {
            snackbar.showSnackbar(err)
            vm.dismissSaveError()
        }
    }

    LoadContent(state, onBack = onBack, onRetry = vm::reload) { teams ->
        if (teams.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Groups,
                title = stringResource(R.string.la_teams_empty_title),
                message = stringResource(R.string.la_teams_empty_message),
                actionLabel = if (canEdit) stringResource(R.string.la_team_add) else null,
                onAction = if (canEdit) ({ dialog = true }) else null,
            )
        } else {
            Box(Modifier.fillMaxSize()) {
                ScreenColumn(bottomPadding = if (canEdit) 96 else 24) {
                    teams.forEach { TeamRow(it, canEdit) { vm.remove(it.teamId) } }
                    if (canEdit && teams.size >= 2) {
                        SecondaryButton(
                            text = stringResource(R.string.la_teams_generate),
                            onClick = onGenerate,
                            icon = Icons.Default.SportsSoccer,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (canEdit) {
                    ExtendedFloatingActionButton(
                        onClick = { dialog = true },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.la_team_add), style = MaterialTheme.typography.titleMedium) },
                        containerColor = Arena.colors.brand,
                        contentColor = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                    )
                }
            }
        }
    }

    if (dialog && canEdit) {
        AlertDialog(
            onDismissRequest = { dialog = false; vm.dismissSaveError() },
            title = { Text(stringResource(R.string.la_team_add), modifier = Modifier.semantics { heading() }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ArenaTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = stringResource(R.string.la_team_name),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SaveAlert(save)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.add(name) },
                    enabled = name.isNotBlank() && !save.saving,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.la_team_add)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = false; vm.dismissSaveError() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.la_cancel))
                }
            },
        )
    }
}

@Composable
private fun TeamRow(team: TeamDto, canEdit: Boolean, onRemove: () -> Unit) {
    ArenaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                team.name.ifBlank { team.teamId },
                style = MaterialTheme.typography.titleMedium,
                color = Arena.colors.ink,
                modifier = Modifier.weight(1f),
            )
            if (canEdit) {
                IconButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.la_team_remove_cd, team.name),
                        tint = Arena.colors.inkSoft,
                    )
                }
            }
        }
    }
}
