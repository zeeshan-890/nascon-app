package com.nascon.league.ui.fixturegen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascon.league.R
import com.nascon.league.data.fixturegen.FixtureGenRules
import com.nascon.league.data.fixturegen.GenFormat
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.AlertTone
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.EmptyStateCard
import com.nascon.league.ui.components.FeatureUnavailableState
import com.nascon.league.ui.components.FilterPill
import com.nascon.league.ui.components.InlineAlert
import com.nascon.league.ui.components.LoadingState
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat

/** Hides the whole flow from anyone who may not generate fixtures for this league. */
@Composable
fun GenGate(leagueId: String, content: @Composable () -> Unit) {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    if (FixtureGenAccess.canGenerate(session, leagueId)) {
        content()
    } else {
        GenCentered {
            EmptyState(
                icon = Icons.Default.Lock,
                title = stringResource(R.string.fg_denied_title),
                message = stringResource(R.string.fg_denied_message),
            )
        }
    }
}

/** Collects one-shot navigation events from the shared view model. */
@Composable
fun GenNavEffect(vm: FixtureGenViewModel, onGo: (GenNav) -> Unit) {
    LaunchedEffect(vm) { vm.events.collect { onGo(it) } }
}

/**
 * Shared frame of the option screens (F10 to F13, F18): header subtitle, team loading states, team picker,
 * error alert, Back and primary buttons.
 */
@Composable
fun GenOptionsFrame(
    vm: FixtureGenViewModel,
    leagueId: String,
    format: GenFormat,
    stepSubtitle: String,
    primaryLabel: String,
    onGo: (GenNav) -> Unit,
    onBack: () -> Unit,
    content: @Composable ColumnScope.(state: GenUiState, editTeams: () -> Unit) -> Unit,
) {
    GenGate(leagueId) {
        val s by vm.state.collectAsStateWithLifecycle()
        var picker by rememberSaveable { mutableStateOf(false) }
        HeaderSubtitle(stepSubtitle)
        LaunchedEffect(format) { if (vm.state.value.format != format) vm.chooseFormat(format) }
        GenNavEffect(vm, onGo)
        when {
            s.generatorUnavailable -> FeatureUnavailableState(onBack = vm::dismissGeneratorUnavailable)
            s.loadingTeams -> LoadingState(stringResource(R.string.fg_loading_teams))
            s.available.isEmpty() -> EmptyStateCard(
                icon = Icons.Default.Groups,
                title = stringResource(R.string.fg_no_teams_title),
                message = stringResource(R.string.fg_no_teams_message),
                actionLabel = stringResource(R.string.fg_back),
                onAction = onBack,
            )
            else -> GenScaffold(
                bottom = {
                    SecondaryButton(stringResource(R.string.fg_back), onClick = onBack, modifier = Modifier.weight(0.45f))
                    PrimaryButton(primaryLabel, onClick = vm::generate, loading = s.busy, modifier = Modifier.weight(1f))
                },
            ) {
                s.error?.let { InlineAlert(it, AlertTone.ERROR) }
                content(s) { picker = true }
            }
        }
        if (picker) {
            TeamPickerDialog(
                teams = s.available, selected = s.selected, onToggle = vm::toggleTeam,
                onAll = vm::selectAllTeams, onNone = vm::clearTeams, onDone = { picker = false },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// F09 choose a format
// ---------------------------------------------------------------------------------------------

@Composable
fun GenFormatScreen(vm: FixtureGenViewModel, leagueId: String, onNext: (GenFormat) -> Unit) {
    GenGate(leagueId) {
        val s by vm.state.collectAsStateWithLifecycle()
        HeaderSubtitle(stringResource(R.string.fg_step1))
        val options = listOf(
            Triple(GenFormat.ROUND_ROBIN, R.string.fg_fmt_rr, R.string.fg_fmt_rr_desc),
            Triple(GenFormat.SWISS, R.string.fg_fmt_swiss, R.string.fg_fmt_swiss_desc),
            Triple(GenFormat.SINGLE_ELIM, R.string.fg_fmt_single, R.string.fg_fmt_single_desc),
            Triple(GenFormat.DOUBLE_ELIM, R.string.fg_fmt_double, R.string.fg_fmt_double_desc),
            Triple(GenFormat.HYBRID, R.string.fg_fmt_hybrid, R.string.fg_fmt_hybrid_desc),
        )
        when {
            s.loadingTeams -> LoadingState(stringResource(R.string.fg_loading_teams))
            s.available.isEmpty() -> EmptyStateCard(
                icon = Icons.Default.Groups,
                title = stringResource(R.string.fg_no_teams_title),
                message = stringResource(R.string.fg_no_teams_message),
            )
            else -> GenScaffold(
                bottom = {
                    PrimaryButton(
                        stringResource(R.string.fg_next),
                        onClick = { s.format?.let(onNext) },
                        enabled = s.format != null,
                        modifier = Modifier.weight(1f),
                    )
                },
            ) {
                options.forEach { (f, title, desc) ->
                    GenChoiceCard(
                        title = stringResource(title), description = stringResource(desc),
                        selected = s.format == f, onSelect = { vm.chooseFormat(f) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// F10 round robin
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenRoundRobinScreen(vm: FixtureGenViewModel, leagueId: String, onGo: (GenNav) -> Unit, onBack: () -> Unit) {
    var addDialog by rememberSaveable { mutableStateOf(false) }
    GenOptionsFrame(
        vm, leagueId, GenFormat.ROUND_ROBIN,
        stepSubtitle = stringResource(R.string.fg_step2_rr),
        primaryLabel = stringResource(R.string.fg_preview_fixtures),
        onGo = onGo, onBack = onBack,
    ) { s, editTeams ->
        GenTeamsRow(s.selected.size, editTeams)
        GenScheduleSection(s.startDate, s.matchDays, vm::setStartDate, vm::toggleMatchDay)
        GenSwitchRow(
            title = stringResource(R.string.fg_double_rr),
            subtitle = stringResource(R.string.fg_double_rr_desc),
            checked = s.legs == 2,
            onChange = { vm.setLegs(if (it) 2 else 1) },
        )
        GenLabel(stringResource(R.string.fg_withdrawals))
        GenBody(stringResource(R.string.fg_withdrawals_desc))
        val rounds = FixtureGenRules.roundRobinRounds(s.selected.size, s.legs)
        s.withdrawals.entries.sortedBy { it.key }.forEach { (team, after) ->
            val name = teamLabel(team, s.names)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .border(1.dp, Arena.colors.cardBorder, RoundedCornerShape(14.dp))
                    .padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.fg_withdraws_after, name, after),
                    color = Arena.colors.ink, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                IconButton(onClick = { vm.setWithdrawal(team, null) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.fg_remove_withdrawal, name))
                }
            }
        }
        if (s.withdrawals.size < s.selected.size) {
            SecondaryButton(
                stringResource(R.string.fg_add_withdrawal), onClick = { addDialog = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (s.withdrawals.isNotEmpty()) {
            GenLabel(stringResource(R.string.fg_policy))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterPill(
                    stringResource(R.string.fg_policy_forfeit), s.withdrawalPolicy == "forfeit_remaining",
                    { vm.setPolicy("forfeit_remaining") },
                )
                FilterPill(
                    stringResource(R.string.fg_policy_void), s.withdrawalPolicy == "void_all",
                    { vm.setPolicy("void_all") },
                )
            }
            GenBody(
                stringResource(
                    if (s.withdrawalPolicy == "void_all") R.string.fg_policy_void_desc else R.string.fg_policy_forfeit_desc,
                ),
            )
        }
        InfoCard(stringResource(R.string.fg_rr_info))
        if (addDialog) {
            WithdrawalDialog(
                candidates = s.selected.filter { it !in s.withdrawals }.map { TeamOpt(it, teamLabel(it, s.names)) },
                maxRound = rounds,
                onAdd = { id, after -> vm.setWithdrawal(id, after); addDialog = false },
                onDismiss = { addDialog = false },
            )
        }
    }
}

@Composable
private fun WithdrawalDialog(
    candidates: List<TeamOpt>,
    maxRound: Int,
    onAdd: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var team by rememberSaveable { mutableStateOf(candidates.firstOrNull()?.id.orEmpty()) }
    var after by rememberSaveable { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.fg_add_withdrawal),
                fontFamily = Montserrat, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                candidates.forEach { t ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = t.id == team, role = Role.RadioButton, onClick = { team = t.id }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = t.id == team, onClick = null)
                        Text(t.name, fontFamily = Montserrat, fontSize = 14.sp)
                    }
                }
                GenStepper(
                    label = stringResource(R.string.fg_after_round), value = after, range = 0..maxRound,
                    onChange = { after = it.coerceIn(0, maxRound) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (team.isNotEmpty()) onAdd(team, after) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.fg_add), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.fg_cancel)) }
        },
    )
}

@Composable
fun InfoCard(text: String) {
    InlineAlert(text, AlertTone.INFO)
}

// ---------------------------------------------------------------------------------------------
// F11 swiss
// ---------------------------------------------------------------------------------------------

@Composable
fun GenSwissScreen(vm: FixtureGenViewModel, leagueId: String, onGo: (GenNav) -> Unit, onBack: () -> Unit) {
    GenOptionsFrame(
        vm, leagueId, GenFormat.SWISS,
        stepSubtitle = stringResource(R.string.fg_step2_swiss),
        primaryLabel = stringResource(R.string.fg_generate_round1),
        onGo = onGo, onBack = onBack,
    ) { s, editTeams ->
        GenTeamsRow(s.selected.size, editTeams)
        val range = FixtureGenRules.swissRoundsRange(s.selected.size)
        GenStepper(
            label = stringResource(R.string.fg_swiss_rounds),
            value = s.swissTotalEffective.coerceIn(range),
            range = range,
            onChange = { vm.setSwissTotal(it.coerceIn(range)) },
        )
        GenBody(stringResource(R.string.fg_swiss_first_round))
        InfoCard(stringResource(R.string.fg_swiss_info))
        GenScheduleSection(s.startDate, s.matchDays, vm::setStartDate, vm::toggleMatchDay)
    }
}

// ---------------------------------------------------------------------------------------------
// F12 single elimination
// ---------------------------------------------------------------------------------------------

@Composable
fun GenSingleScreen(vm: FixtureGenViewModel, leagueId: String, onGo: (GenNav) -> Unit, onBack: () -> Unit) {
    GenOptionsFrame(
        vm, leagueId, GenFormat.SINGLE_ELIM,
        stepSubtitle = stringResource(R.string.fg_step2_single),
        primaryLabel = stringResource(R.string.fg_preview_bracket),
        onGo = onGo, onBack = onBack,
    ) { s, editTeams ->
        val n = s.selected.size
        GenTeamsRow(n, editTeams)
        val size = FixtureGenRules.nextPow2(n.coerceAtLeast(1))
        if (n >= 2 && size != n) {
            InfoCard(
                stringResource(R.string.fg_pad_info, n, size, byeSeeds(n).joinToString(", ")),
            )
        }
        GenLabel(stringResource(R.string.fg_seed_order))
        GenBody(stringResource(R.string.fg_seed_order_desc))
        GenSeedList(s.selected, s.names, vm::moveTeam, knockout = true)
        GenSwitchRow(
            title = stringResource(R.string.fg_third_place),
            subtitle = stringResource(R.string.fg_third_place_desc),
            checked = s.thirdPlace,
            onChange = vm::setThirdPlace,
        )
        GenScheduleSection(s.startDate, s.matchDays, vm::setStartDate, vm::toggleMatchDay)
    }
}

// ---------------------------------------------------------------------------------------------
// F13 double elimination
// ---------------------------------------------------------------------------------------------

@Composable
fun GenDoubleScreen(vm: FixtureGenViewModel, leagueId: String, onGo: (GenNav) -> Unit, onBack: () -> Unit) {
    GenOptionsFrame(
        vm, leagueId, GenFormat.DOUBLE_ELIM,
        stepSubtitle = stringResource(R.string.fg_step2_double),
        primaryLabel = stringResource(R.string.fg_preview_bracket),
        onGo = onGo, onBack = onBack,
    ) { s, editTeams ->
        GenTeamsRow(s.selected.size, editTeams)
        InfoCard(stringResource(R.string.fg_double_info))
        GenSwitchRow(
            title = stringResource(R.string.fg_gf_reset),
            subtitle = stringResource(R.string.fg_gf_reset_desc),
            checked = s.bracketReset,
            onChange = vm::setBracketReset,
        )
        GenLabel(stringResource(R.string.fg_seed_order))
        GenBody(stringResource(R.string.fg_seed_order_desc))
        GenSeedList(s.selected, s.names, vm::moveTeam, knockout = true)
        GenScheduleSection(s.startDate, s.matchDays, vm::setStartDate, vm::toggleMatchDay)
    }
}

// ---------------------------------------------------------------------------------------------
// F18 hybrid: seed a knockout from the league table
// ---------------------------------------------------------------------------------------------

@Composable
fun GenHybridScreen(vm: FixtureGenViewModel, leagueId: String, onGo: (GenNav) -> Unit, onBack: () -> Unit) {
    GenOptionsFrame(
        vm, leagueId, GenFormat.HYBRID,
        stepSubtitle = stringResource(R.string.fg_step2_hybrid),
        primaryLabel = stringResource(R.string.fg_create_bracket),
        onGo = onGo, onBack = onBack,
    ) { s, _ ->
        val c = Arena.colors
        val table = s.ranked.mapNotNull { id -> s.available.firstOrNull { it.id == id } }
        val maxAdv = table.size.coerceAtLeast(2)
        val adv = s.advancing.coerceIn(2, maxAdv)
        GenLabel(stringResource(R.string.fg_hybrid_table))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White)
                .border(1.dp, c.cardBorder, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            table.forEachIndexed { i, t ->
                val advances = i < adv
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "${i + 1} ${t.name}",
                        color = c.ink, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (advances) {
                        StatusChip(stringResource(R.string.fg_advances), Icons.Default.CheckCircle, tone = ChipTone.Success)
                    }
                    t.points?.let {
                        Text(
                            pluralStringResource(R.plurals.fg_points, it, it),
                            color = c.inkSoft, fontFamily = Montserrat, fontSize = 13.sp,
                        )
                    }
                }
            }
        }
        GenStepper(
            label = stringResource(R.string.fg_advancing),
            value = adv, range = 2..maxAdv, onChange = { vm.setAdvancing(it.coerceIn(2, maxAdv)) },
        )
        InfoCard(stringResource(R.string.fg_hybrid_info))
        GenSwitchRow(
            title = stringResource(R.string.fg_third_place),
            subtitle = stringResource(R.string.fg_third_place_desc),
            checked = s.thirdPlace,
            onChange = vm::setThirdPlace,
        )
        GenScheduleSection(s.startDate, s.matchDays, vm::setStartDate, vm::toggleMatchDay)
    }
}
