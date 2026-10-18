package com.nascon.league.ui.leagueadmin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.data.leagueadmin.SportOptionDto
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ArenaTextField
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.FilterPill
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.theme.Arena
import java.time.LocalDate

// ---------------------------------------------------------------- list (F02)

@Composable
fun LeagueListScreen(onOpen: (String) -> Unit, onNew: () -> Unit, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    if (!LeagueAdminAccess.canOpenHub(session)) {
        NoAccessState(onBack)
        return
    }
    val vm: LeagueListViewModel = viewModel(factory = LeagueListViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val canCreate = LeagueAdminAccess.canCreateLeague(session)
    RefreshOnReturn(vm::refresh)

    LoadContent(state, onBack = onBack, onRetry = vm::reload) { leagues ->
        HeaderSubtitle(plural(R.plurals.la_leagues_count, leagues.size))
        if (leagues.isEmpty()) {
            EmptyState(
                icon = Icons.Default.EmojiEvents,
                title = stringResource(R.string.la_list_empty_title),
                message = stringResource(if (canCreate) R.string.la_list_empty_message_admin else R.string.la_list_empty_message),
                actionLabel = if (canCreate) stringResource(R.string.la_new_league) else null,
                onAction = if (canCreate) onNew else null,
            )
        } else {
            Box(Modifier.fillMaxSize()) {
                ScreenColumn(bottomPadding = if (canCreate) 96 else 24) {
                    leagues.forEach { LeagueCard(it, onClick = { onOpen(it.leagueId) }) }
                }
                if (canCreate) {
                    ExtendedFloatingActionButton(
                        onClick = onNew,
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.la_new_league), style = MaterialTheme.typography.titleMedium) },
                        containerColor = Arena.colors.brand,
                        contentColor = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun statusChipFor(status: String?): Triple<String, androidx.compose.ui.graphics.vector.ImageVector, ChipTone>? = when (status) {
    "active" -> Triple(stringResource(R.string.la_status_active), Icons.Default.CheckCircle, ChipTone.Success)
    "pending" -> Triple(stringResource(R.string.la_status_pending), Icons.Default.HourglassTop, ChipTone.Warning)
    "rejected" -> Triple(stringResource(R.string.la_status_rejected), Icons.Default.Block, ChipTone.Error)
    "draft" -> Triple(stringResource(R.string.la_status_draft), Icons.Default.Edit, ChipTone.Neutral)
    "finished" -> Triple(stringResource(R.string.la_status_finished), Icons.Default.Flag, ChipTone.Neutral)
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeagueCard(l: LeagueSummary, onClick: () -> Unit) {
    val parts = buildList {
        l.season?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.la_season_label, it)) }
        l.teamCount?.takeIf { it > 0 }?.let { add(plural(R.plurals.la_teams_count, it)) }
        l.format?.let { add(formatLabel(it)) }
    }
    ArenaCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Text(l.name, style = MaterialTheme.typography.titleMedium, color = Arena.colors.ink)
        if (parts.isNotEmpty()) {
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Arena.colors.inkSoft)
        }
        val chip = statusChipFor(l.status)
        if (chip != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(chip.first, chip.second, tone = chip.third)
            }
        }
    }
}

// ---------------------------------------------------------------- detail (F03)

@Composable
fun LeagueDetailScreen(
    leagueId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onGenerator: () -> Unit,
    onBracket: () -> Unit,
    onStandingsRules: () -> Unit,
    onTeams: () -> Unit,
    onPromotion: () -> Unit,
    onSeasons: () -> Unit,
    onDivisions: () -> Unit,
) {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    if (!LeagueAdminAccess.canViewLeague(session, leagueId)) {
        NoAccessState(onBack)
        return
    }
    val vm: LeagueDetailViewModel = viewModel(factory = LeagueDetailViewModel.factory(container, leagueId))
    val state by vm.state.collectAsStateWithLifecycle()
    val canEdit = LeagueAdminAccess.canEditLeague(session, leagueId)
    val canRules = LeagueAdminAccess.canEditRules(session, leagueId)
    val canApprove = LeagueAdminAccess.canApproveLeague(session)
    val reviewing by vm.reviewing.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val approvedMsg = stringResource(R.string.la_approved_msg)
    val rejectedMsg = stringResource(R.string.la_rejected_msg)
    val reviewFailedMsg = stringResource(R.string.la_review_failed)
    val scope = rememberCoroutineScope()
    RefreshOnReturn(vm::refresh)

    LoadContent(state, onBack = onBack, onRetry = vm::reload) { league ->
        if (league == null) {
            EmptyState(
                icon = Icons.Default.SearchOff,
                title = stringResource(R.string.la_not_found_title),
                message = stringResource(R.string.la_not_found_message),
                actionLabel = stringResource(R.string.la_go_back),
                onAction = onBack,
            )
            return@LoadContent
        }
        val sub = buildList {
            league.season?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.la_season_label, it)) }
            league.teamCount?.takeIf { it > 0 }?.let { add(plural(R.plurals.la_teams_count, it)) }
        }.joinToString(" · ")
        HeaderSubtitle(league.name + if (sub.isNotEmpty()) " · $sub" else "")
        val x = league.extras
        val opens = parseIsoDate(x?.transferWindowOpens)
        val closes = parseIsoDate(x?.transferWindowCloses)
        ScreenColumn {
            Text(
                if (opens != null && closes != null) {
                    stringResource(R.string.la_window_line, formatShortDate(opens).orEmpty(), formatShortDate(closes).orEmpty())
                } else {
                    stringResource(R.string.la_window_none)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Arena.colors.inkSoft,
            )
            val waiting = league.status == "pending"
            val rejected = league.status == "rejected"
            if (waiting || rejected) {
                ArenaCard(modifier = Modifier.fillMaxWidth()) {
                    statusChipFor(league.status)?.let { StatusChip(it.first, it.second, tone = it.third) }
                    Text(
                        stringResource(
                            when {
                                rejected -> R.string.la_rejected_note
                                canApprove -> R.string.la_pending_admin_note
                                else -> R.string.la_pending_organizer_note
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Arena.colors.inkSoft,
                    )
                    if (waiting && canApprove) {
                        PrimaryButton(
                            text = stringResource(R.string.la_approve),
                            onClick = { vm.review(true) { ok -> scope.launch { snackbar.showSnackbar(if (ok) approvedMsg else reviewFailedMsg) } } },
                            loading = reviewing,
                            icon = Icons.Default.CheckCircle,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SecondaryButton(
                            text = stringResource(R.string.la_reject),
                            onClick = { vm.review(false) { ok -> scope.launch { snackbar.showSnackbar(if (ok) rejectedMsg else reviewFailedMsg) } } },
                            enabled = !reviewing,
                            icon = Icons.Default.Block,
                            destructive = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (canEdit) {
                SecondaryButton(
                    text = stringResource(R.string.la_edit_league),
                    onClick = onEdit,
                    icon = Icons.Default.Edit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Fixtures, rules and the rest only make sense once a league is approved.
            if (waiting || rejected) return@ScreenColumn
            if (canRules) {
                HubRow(
                    Icons.Default.SportsSoccer,
                    stringResource(R.string.la_hub_fixtures),
                    if (x != null && x.matchCount > 0) {
                        stringResource(R.string.la_hub_fixtures_sub, plural(R.plurals.la_matches_count, x.matchCount))
                    } else {
                        stringResource(R.string.la_hub_fixtures_sub_plain)
                    },
                    onGenerator,
                )
                HubRow(Icons.Default.Leaderboard, stringResource(R.string.la_hub_standings), stringResource(R.string.la_hub_standings_sub), onStandingsRules)
            }
            HubRow(
                Icons.Default.Groups,
                stringResource(R.string.la_hub_teams),
                if (x != null && x.teamCount > 0) {
                    stringResource(
                        R.string.la_hub_teams_sub,
                        plural(R.plurals.la_teams_count, x.teamCount),
                        plural(R.plurals.la_players_count, x.playerCount),
                    )
                } else {
                    stringResource(R.string.la_hub_teams_sub_plain)
                },
                onTeams,
            )
            if (canRules) {
                HubRow(Icons.Default.TrendingUp, stringResource(R.string.la_hub_promotion), stringResource(R.string.la_hub_promotion_sub), onPromotion)
                if (league.format == null || league.format.hasKnockout) {
                    HubRow(Icons.Default.AccountTree, stringResource(R.string.la_hub_bracket), stringResource(R.string.la_hub_bracket_sub), onBracket)
                }
                HubRow(Icons.Default.Layers, stringResource(R.string.la_hub_divisions), stringResource(R.string.la_hub_divisions_sub), onDivisions)
            }
            HubRow(Icons.Default.CalendarMonth, stringResource(R.string.la_hub_seasons), stringResource(R.string.la_hub_seasons_sub), onSeasons)
        }
    }
}

// ---------------------------------------------------------------- form (F04)

private const val PICK_NONE = ""
private const val PICK_OPENS = "opens"
private const val PICK_CLOSES = "closes"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LeagueFormScreen(leagueId: String?, onBack: () -> Unit, onDone: (String?) -> Unit) {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    val allowed = if (leagueId == null) LeagueAdminAccess.canCreateLeague(session) else LeagueAdminAccess.canEditLeague(session, leagueId)
    if (!allowed) {
        NoAccessState(onBack)
        return
    }
    val vm: LeagueFormViewModel = viewModel(factory = LeagueFormViewModel.factory(container, leagueId))
    val loadState by vm.loadState.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val save by vm.save.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val isOrganizer = session?.user?.role == com.nascon.league.domain.model.Role.LEAGUE_ORGANIZER
    val msg = stringResource(
        when {
            leagueId != null -> R.string.la_form_saved_updated
            isOrganizer -> R.string.la_form_submitted
            else -> R.string.la_form_saved_created
        },
    )
    LaunchedEffect(vm) {
        vm.saved.collect {
            snackbar.showSnackbar(msg)
            onDone(leagueId)
        }
    }
    var picking by rememberSaveable { mutableStateOf(PICK_NONE) }

    LoadContent(loadState, onBack = onBack, onRetry = vm::load) {
        val i = ui.input
        val errs = if (ui.showErrors) ui.errors else emptySet()
        ScreenColumn {
            ArenaTextField(
                value = i.name,
                onValueChange = { v -> vm.edit { it.copy(name = v) } },
                label = stringResource(R.string.la_form_name),
                placeholder = stringResource(R.string.la_form_name_hint),
                isError = LeagueFormError.NAME_REQUIRED in errs || LeagueFormError.NAME_TOO_LONG in errs,
                errorText = when {
                    LeagueFormError.NAME_REQUIRED in errs -> stringResource(R.string.la_err_name_required)
                    LeagueFormError.NAME_TOO_LONG in errs -> stringResource(R.string.la_err_name_long)
                    else -> null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Heading(stringResource(R.string.la_form_sport))
            val sports = ui.sports.ifEmpty {
                listOfNotNull(i.sportId?.let { SportOptionDto(it, it.replaceFirstChar { c -> c.uppercase() }) })
            }
            if (sports.isEmpty()) {
                Text(stringResource(R.string.la_form_sport_none), style = MaterialTheme.typography.bodySmall, color = Arena.colors.inkSoft)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sports.forEach { s ->
                        FilterPill(s.name.ifBlank { s.sportId }, selected = i.sportId == s.sportId, onClick = { vm.edit { it.copy(sportId = s.sportId) } })
                    }
                }
            }
            if (LeagueFormError.SPORT_REQUIRED in errs) {
                Text(stringResource(R.string.la_err_sport_required), style = MaterialTheme.typography.labelLarge, color = Arena.colors.liveFg)
            }

            Heading(stringResource(R.string.la_form_window))
            val windowError = when {
                LeagueFormError.WINDOW_INCOMPLETE in errs -> stringResource(R.string.la_err_window_incomplete)
                LeagueFormError.WINDOW_ORDER in errs -> stringResource(R.string.la_err_window_order)
                else -> null
            }
            DateField(stringResource(R.string.la_form_opens), i.windowOpens, null) { picking = PICK_OPENS }
            DateField(stringResource(R.string.la_form_closes), i.windowCloses, windowError) { picking = PICK_CLOSES }
            if (i.windowOpens != null || i.windowCloses != null) {
                SecondaryButton(
                    text = stringResource(R.string.la_form_clear_window),
                    onClick = { vm.edit { it.copy(windowOpens = null, windowCloses = null) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ArenaTextField(
                value = i.maxSquad,
                onValueChange = { v -> vm.edit { it.copy(maxSquad = v.filter(Char::isDigit).take(2)) } },
                label = stringResource(R.string.la_form_squad),
                isError = LeagueFormError.SQUAD_INVALID in errs,
                errorText = if (LeagueFormError.SQUAD_INVALID in errs) stringResource(R.string.la_err_squad) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Heading(stringResource(R.string.la_form_format))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LeagueFormat.entries.forEach { f ->
                    FilterPill(formatLabel(f), selected = i.format == f, onClick = { vm.edit { it.copy(format = f) } })
                }
            }

            Text(stringResource(R.string.la_form_note), style = MaterialTheme.typography.bodySmall, color = Arena.colors.inkSoft)
            SaveAlert(save)
            PrimaryButton(
                text = stringResource(if (leagueId == null) R.string.la_form_create else R.string.la_form_save),
                onClick = vm::submit,
                loading = save.saving,
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(text = stringResource(R.string.la_cancel), onClick = onBack, modifier = Modifier.fillMaxWidth())
        }

        if (picking != PICK_NONE) {
            val current: LocalDate? = if (picking == PICK_OPENS) i.windowOpens else i.windowCloses
            DatePickerHost(
                initial = current ?: i.windowOpens,
                onPick = { d ->
                    if (picking == PICK_OPENS) vm.edit { it.copy(windowOpens = d) } else vm.edit { it.copy(windowCloses = d) }
                },
                onDismiss = { picking = PICK_NONE },
            )
        }
    }
}
