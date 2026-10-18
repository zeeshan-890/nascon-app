package com.nascon.league.ui.leagueadmin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.dp
import com.nascon.league.R
import com.nascon.league.data.leagueadmin.DivisionDto
import com.nascon.league.data.leagueadmin.SeasonDto
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.AlertTone
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ArenaTextField
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.FilterPill
import com.nascon.league.ui.components.InlineAlert
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.navigation.LocalSnackbarHostState
import com.nascon.league.ui.theme.Arena
import java.time.LocalDate

// ---------------------------------------------------------------- seasons (F05)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeasonsScreen(leagueId: String, onBack: () -> Unit, onNew: () -> Unit) {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    if (!LeagueAdminAccess.canViewLeague(session, leagueId)) {
        NoAccessState(onBack)
        return
    }
    val vm: SeasonsViewModel = viewModel(factory = SeasonsViewModel.factory(container, leagueId))
    val state by vm.state.collectAsStateWithLifecycle()
    val canWrite = LeagueAdminAccess.canWriteSeasons(session)
    RefreshOnReturn(vm::refresh)
    LoadContent(state, onBack = onBack, onRetry = vm::reload) { seasons ->
        if (seasons.isEmpty()) {
            EmptyState(
                icon = Icons.Default.CalendarMonth,
                title = stringResource(R.string.la_seasons_empty_title),
                message = stringResource(R.string.la_seasons_empty_message),
                actionLabel = if (canWrite) stringResource(R.string.la_new_season) else null,
                onAction = if (canWrite) onNew else null,
            )
        } else {
            Box(Modifier.fillMaxSize()) {
                ScreenColumn(bottomPadding = if (canWrite) 96 else 24) { seasons.forEach { SeasonCard(it) } }
                if (canWrite) {
                    ExtendedFloatingActionButton(
                        onClick = onNew,
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.la_new_season), style = MaterialTheme.typography.titleMedium) },
                        containerColor = Arena.colors.brand,
                        contentColor = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeasonCard(s: SeasonDto) {
    val dates = stringResource(
        R.string.la_season_dates,
        formatDate(parseIsoDate(s.startDate)) ?: s.startDate,
        formatDate(parseIsoDate(s.endDate)) ?: s.endDate,
    )
    ArenaCard(modifier = Modifier.fillMaxWidth()) {
        Text(s.name, style = MaterialTheme.typography.titleMedium, color = Arena.colors.ink)
        Text(dates, style = MaterialTheme.typography.bodySmall, color = Arena.colors.inkSoft)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (s.status) {
                "current" -> StatusChip(stringResource(R.string.la_season_current), Icons.Default.CheckCircle, tone = ChipTone.Success)
                "finished" -> StatusChip(stringResource(R.string.la_season_finished), Icons.Default.Flag, tone = ChipTone.Neutral)
                else -> StatusChip(stringResource(R.string.la_season_upcoming), Icons.Default.Schedule, tone = ChipTone.Brand)
            }
            if (s.leagueCount > 0) {
                StatusChip(plural(R.plurals.la_leagues_count, s.leagueCount), Icons.Default.Flag, tone = ChipTone.Neutral)
            }
            if (s.divisionCount > 0) {
                StatusChip(plural(R.plurals.la_divisions_count, s.divisionCount), Icons.Default.Layers, tone = ChipTone.Neutral)
            }
            if (s.promotionApplied) {
                StatusChip(stringResource(R.string.la_season_promotion_applied), Icons.Default.Done, tone = ChipTone.Success)
            }
        }
    }
}

// ---------------------------------------------------------------- season form (F06)

private const val PICK_NONE = ""
private const val PICK_START = "start"
private const val PICK_END = "end"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeasonFormScreen(leagueId: String, onBack: () -> Unit, onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    if (!LeagueAdminAccess.canWriteSeasons(session)) {
        NoAccessState(onBack)
        return
    }
    val vm: SeasonFormViewModel = viewModel(factory = SeasonFormViewModel.factory(container, leagueId))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val save by vm.save.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val msg = stringResource(R.string.la_season_created)
    LaunchedEffect(vm) {
        vm.saved.collect {
            snackbar.showSnackbar(msg)
            onDone()
        }
    }
    var picking by rememberSaveable { mutableStateOf(PICK_NONE) }
    val errs = if (ui.showErrors) ui.errors else emptySet()

    ScreenColumn {
        ArenaTextField(
            value = ui.name,
            onValueChange = { v -> vm.edit { it.copy(name = v) } },
            label = stringResource(R.string.la_season_name),
            placeholder = stringResource(R.string.la_season_name_hint),
            isError = SeasonFormError.NAME_REQUIRED in errs,
            errorText = if (SeasonFormError.NAME_REQUIRED in errs) stringResource(R.string.la_err_season_name) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        DateField(
            stringResource(R.string.la_season_start), ui.start,
            if (SeasonFormError.START_REQUIRED in errs) stringResource(R.string.la_err_season_start) else null,
        ) { picking = PICK_START }
        DateField(
            stringResource(R.string.la_season_end), ui.end,
            when {
                SeasonFormError.END_REQUIRED in errs -> stringResource(R.string.la_err_season_end)
                SeasonFormError.END_NOT_AFTER_START in errs -> stringResource(R.string.la_err_season_order)
                else -> null
            },
        ) { picking = PICK_END }

        if (ui.seasons.isNotEmpty()) {
            Heading(stringResource(R.string.la_season_copy))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill(
                    stringResource(R.string.la_season_copy_none),
                    selected = ui.copyFromSeasonId == null,
                    onClick = { vm.edit { it.copy(copyFromSeasonId = null) } },
                )
                ui.seasons.forEach { s ->
                    FilterPill(s.name, selected = ui.copyFromSeasonId == s.seasonId, onClick = { vm.edit { it.copy(copyFromSeasonId = s.seasonId) } })
                }
            }
        }

        val promoLabel = stringResource(R.string.la_season_apply_promotion)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = ui.applyPromotion,
                    role = Role.Switch,
                    onValueChange = { v -> vm.edit { it.copy(applyPromotion = v) } },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(promoLabel, style = MaterialTheme.typography.bodyMedium, color = Arena.colors.ink, modifier = Modifier.weight(1f))
            Switch(checked = ui.applyPromotion, onCheckedChange = null)
        }

        SaveAlert(save)
        PrimaryButton(
            text = stringResource(R.string.la_season_create),
            onClick = vm::submit,
            loading = save.saving,
            modifier = Modifier.fillMaxWidth(),
        )
        SecondaryButton(text = stringResource(R.string.la_cancel), onClick = onBack, modifier = Modifier.fillMaxWidth())
    }

    if (picking != PICK_NONE) {
        val start = picking == PICK_START
        DatePickerHost(
            initial = (if (start) ui.start else ui.end) ?: ui.start,
            onPick = { d -> if (start) vm.edit { it.copy(start = d) } else vm.edit { it.copy(end = d) } },
            onDismiss = { picking = PICK_NONE },
        )
    }
}

// ---------------------------------------------------------------- divisions (F07)

@Composable
fun DivisionsScreen(leagueId: String, onBack: () -> Unit, onViewStandings: () -> Unit) {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    if (!LeagueAdminAccess.canViewLeague(session, leagueId)) {
        NoAccessState(onBack)
        return
    }
    val vm: DivisionsViewModel = viewModel(factory = DivisionsViewModel.factory(container, leagueId))
    val state by vm.state.collectAsStateWithLifecycle()
    val save by vm.save.collectAsStateWithLifecycle()
    val canEdit = LeagueAdminAccess.canEditRules(session, leagueId)
    val snackbar = LocalSnackbarHostState.current
    val msg = stringResource(R.string.la_division_created)
    var dialog by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(vm) {
        vm.saved.collect {
            dialog = false
            name = ""
            snackbar.showSnackbar(msg)
        }
    }

    LoadContent(state, onBack = onBack, onRetry = vm::reload) { divisions ->
        if (divisions.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Layers,
                title = stringResource(R.string.la_divisions_empty_title),
                message = stringResource(R.string.la_divisions_empty_message),
                actionLabel = if (canEdit) stringResource(R.string.la_new_division) else null,
                onAction = if (canEdit) ({ dialog = true }) else null,
            )
        } else {
            Box(Modifier.fillMaxSize()) {
                ScreenColumn(bottomPadding = if (canEdit) 96 else 24) {
                    divisions.sortedBy { it.tier }.forEach { DivisionCard(it, onViewStandings) }
                }
                if (canEdit) {
                    ExtendedFloatingActionButton(
                        onClick = { dialog = true },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.la_new_division), style = MaterialTheme.typography.titleMedium) },
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
            title = { Text(stringResource(R.string.la_new_division), modifier = Modifier.semantics { heading() }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ArenaTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = stringResource(R.string.la_division_name),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SaveAlert(save)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.create(name) },
                    enabled = name.isNotBlank() && !save.saving,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.la_division_add)) }
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
private fun DivisionCard(d: DivisionDto, onView: () -> Unit) {
    val tier = when (d.tier) {
        1 -> stringResource(R.string.la_division_tier_top)
        2 -> stringResource(R.string.la_division_tier_second)
        else -> stringResource(R.string.la_division_tier_n, d.tier)
    }
    ArenaCard(modifier = Modifier.fillMaxWidth()) {
        Text(d.name, style = MaterialTheme.typography.titleMedium, color = Arena.colors.ink)
        Text(
            stringResource(R.string.la_division_line, tier, plural(R.plurals.la_teams_count, d.teamCount)),
            style = MaterialTheme.typography.bodySmall,
            color = Arena.colors.inkSoft,
        )
        d.topTeams.forEach { t ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(t.name, style = MaterialTheme.typography.titleSmall, color = Arena.colors.ink, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.la_division_pts, t.points), style = MaterialTheme.typography.titleSmall, color = Arena.colors.ink)
            }
        }
        val more = d.teamCount - d.topTeams.size
        if (more > 0) {
            Text(stringResource(R.string.la_division_more, more), style = MaterialTheme.typography.bodySmall, color = Arena.colors.inkSoft)
        }
        val viewCd = stringResource(R.string.la_division_view_cd, d.name)
        TextButton(
            onClick = onView,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = viewCd },
        ) { Text(stringResource(R.string.la_division_view)) }
    }
}

// ---------------------------------------------------------------- promotion (F08)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PromotionScreen(leagueId: String, onBack: () -> Unit, onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    if (!LeagueAdminAccess.canEditRules(session, leagueId)) {
        NoAccessState(onBack)
        return
    }
    val vm: PromotionViewModel = viewModel(factory = PromotionViewModel.factory(container, leagueId))
    val state by vm.state.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val save by vm.save.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val msg = stringResource(R.string.la_promo_saved)
    LaunchedEffect(vm) {
        vm.saved.collect {
            snackbar.showSnackbar(msg)
            onDone()
        }
    }
    LoadContent(state, onBack = onBack, onRetry = vm::reload) { loaded ->
        val cur = draft ?: loaded
        val max = PromotionRules.maxSpots(cur.teamCount)
        ScreenColumn {
            val upLabel = stringResource(R.string.la_promo_up)
            val downLabel = stringResource(R.string.la_promo_down)
            StepperCard(upLabel, stringResource(R.string.la_promo_up_sub), cur.up, 0, max) { vm.setUp(loaded, it) }
            StepperCard(downLabel, stringResource(R.string.la_promo_down_sub), cur.down, 0, max) { vm.setDown(loaded, it) }

            val divName = cur.divisionName.ifBlank { stringResource(R.string.la_promo_top_division) }
            Heading(stringResource(R.string.la_promo_preview, divName, plural(R.plurals.la_teams_count, cur.teamCount)))
            PromotionRules.zones(cur.teamCount, cur.up, cur.down).forEach { z ->
                val range = if (z.from == z.to) z.from.toString() else stringResource(R.string.la_range, z.from, z.to)
                val (text, chipText, icon, tone) = when (z.kind) {
                    ZoneKind.PROMOTED -> ZoneUi(stringResource(R.string.la_promo_zone_up, range), stringResource(R.string.la_promo_zone_chip_up), Icons.Default.ArrowUpward, ChipTone.Success)
                    ZoneKind.MID -> ZoneUi(stringResource(R.string.la_promo_zone_mid, range), stringResource(R.string.la_promo_zone_chip_mid), Icons.Default.Remove, ChipTone.Neutral)
                    ZoneKind.RELEGATED -> ZoneUi(stringResource(R.string.la_promo_zone_down, range), stringResource(R.string.la_promo_zone_chip_down), Icons.Default.ArrowDownward, ChipTone.Error)
                }
                ArenaCard(modifier = Modifier.fillMaxWidth()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StatusChip(chipText, icon, tone = tone)
                        Text(text, style = MaterialTheme.typography.titleSmall, color = Arena.colors.ink)
                    }
                }
            }
            SaveAlert(save)
            PrimaryButton(
                text = stringResource(R.string.la_promo_save),
                onClick = { vm.submit(loaded) },
                loading = save.saving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private data class ZoneUi(val text: String, val chip: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val tone: ChipTone)

@Composable
private fun StepperCard(title: String, subtitle: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    ArenaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Arena.colors.ink)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Arena.colors.inkSoft)
            }
            Stepper(title, value, min, max, onChange)
        }
    }
}

// ---------------------------------------------------------------- standings rules (F30)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StandingsRulesScreen(leagueId: String, onBack: () -> Unit, onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    if (!LeagueAdminAccess.canEditRules(session, leagueId)) {
        NoAccessState(onBack)
        return
    }
    val vm: StandingsRulesViewModel = viewModel(factory = StandingsRulesViewModel.factory(container, leagueId))
    val state by vm.state.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val save by vm.save.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val msg = stringResource(R.string.la_rules_saved)
    LaunchedEffect(vm) {
        vm.saved.collect {
            snackbar.showSnackbar(msg)
            onDone()
        }
    }
    var editing by rememberSaveable { mutableStateOf(false) }

    LoadContent(state, onBack = onBack, onRetry = vm::reload) { loaded ->
        val cur = draft ?: vm.current(loaded)
        val pointErrors = StandingsRulesLogic.validatePoints(cur.pointsWin, cur.pointsDraw, cur.pointsLoss)
        ScreenColumn {
            Heading(stringResource(R.string.la_rules_points))
            ArenaCard(modifier = Modifier.fillMaxWidth()) {
                val win = stringResource(R.string.la_rules_win)
                val draw = stringResource(R.string.la_rules_draw)
                val loss = stringResource(R.string.la_rules_loss)
                PointsRow(win, cur.pointsWin) { v -> vm.edit(loaded) { it.copy(pointsWin = v) } }
                PointsRow(draw, cur.pointsDraw) { v -> vm.edit(loaded) { it.copy(pointsDraw = v) } }
                PointsRow(loss, cur.pointsLoss) { v -> vm.edit(loaded) { it.copy(pointsLoss = v) } }
            }
            if (PointsError.WIN_BELOW_DRAW in pointErrors) {
                InlineAlert(stringResource(R.string.la_err_points_win), AlertTone.ERROR, Modifier.fillMaxWidth())
            }
            if (PointsError.DRAW_BELOW_LOSS in pointErrors) {
                InlineAlert(stringResource(R.string.la_err_points_draw), AlertTone.ERROR, Modifier.fillMaxWidth())
            }

            ArenaCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.la_rules_qualify), style = MaterialTheme.typography.titleMedium, color = Arena.colors.ink)
                        Text(stringResource(R.string.la_rules_qualify_sub), style = MaterialTheme.typography.bodySmall, color = Arena.colors.inkSoft)
                    }
                    Stepper(
                        stringResource(R.string.la_rules_qualify), cur.qualificationSpots, 0, StandingsRulesLogic.MAX_QUALIFY,
                        { v -> vm.edit(loaded) { it.copy(qualificationSpots = StandingsRulesLogic.clampQualify(v)) } },
                    )
                }
            }

            Heading(stringResource(R.string.la_rules_preview))
            ArenaCard(modifier = Modifier.fillMaxWidth()) {
                sampleStandings.forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("${row.position}", style = MaterialTheme.typography.titleSmall, color = Arena.colors.ink)
                        Text(row.name, style = MaterialTheme.typography.titleSmall, color = Arena.colors.ink, modifier = Modifier.weight(1f))
                        if (row.position <= cur.qualificationSpots) {
                            StatusChip(stringResource(R.string.la_rules_qualifies), Icons.Default.CheckCircle, tone = ChipTone.Success)
                        }
                        Text(stringResource(R.string.la_rules_row_pts, row.points), style = MaterialTheme.typography.titleSmall, color = Arena.colors.ink)
                    }
                }
                Text(stringResource(R.string.la_rules_preview_note), style = MaterialTheme.typography.bodySmall, color = Arena.colors.inkSoft)
            }

            Heading(stringResource(R.string.la_rules_tiebreak))
            ArenaCard(modifier = Modifier.fillMaxWidth()) {
                val then = stringResource(R.string.la_rules_then)
                val labels = cur.tiebreaks.map { tiebreakLabel(it) }
                Text(labels.joinToString(then), style = MaterialTheme.typography.bodyMedium, color = Arena.colors.ink)
                SecondaryButton(
                    text = stringResource(if (editing) R.string.la_rules_done else R.string.la_rules_edit),
                    onClick = { editing = !editing },
                    icon = if (editing) Icons.Default.Done else Icons.Default.Edit,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (editing) {
                    TiebreakEditor(cur.tiebreaks) { new -> vm.edit(loaded) { it.copy(tiebreaks = new) } }
                }
            }

            SaveAlert(save)
            PrimaryButton(
                text = stringResource(R.string.la_rules_save),
                onClick = { vm.submit(loaded) },
                enabled = pointErrors.isEmpty(),
                loading = save.saving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PointsRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = Arena.colors.ink, modifier = Modifier.weight(1f))
        Stepper(label, value, 0, StandingsRulesLogic.MAX_POINTS, onChange)
    }
}

@Composable
private fun TiebreakEditor(order: List<String>, onChange: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        order.forEachIndexed { index, id ->
            val label = tiebreakLabel(id)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.la_rules_position, index + 1, order.size, label),
                    style = MaterialTheme.typography.titleSmall,
                    color = Arena.colors.ink,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onChange(StandingsRulesLogic.moveUp(order, index)) }, enabled = index > 0, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.la_rules_move_up, label), tint = Arena.colors.brand)
                }
                IconButton(onClick = { onChange(StandingsRulesLogic.moveDown(order, index)) }, enabled = index < order.size - 1, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.la_rules_move_down, label), tint = Arena.colors.brand)
                }
                if (order.size > 1) {
                    IconButton(onClick = { onChange(StandingsRulesLogic.remove(order, index)) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.la_rules_remove, label), tint = Arena.colors.liveFg)
                    }
                }
            }
        }
        val unused = StandingsRulesLogic.unused(order)
        if (unused.isNotEmpty()) {
            Text(stringResource(R.string.la_rules_unused), style = MaterialTheme.typography.labelLarge, color = Arena.colors.inkSoft)
            unused.forEach { id ->
                val label = tiebreakLabel(id)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.titleSmall, color = Arena.colors.ink, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onChange(StandingsRulesLogic.add(order, id)) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.la_rules_add, label), tint = Arena.colors.brand)
                    }
                }
            }
        }
    }
}
