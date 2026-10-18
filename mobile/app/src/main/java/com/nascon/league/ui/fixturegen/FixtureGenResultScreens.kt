package com.nascon.league.ui.fixturegen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascon.league.R
import com.nascon.league.data.fixturegen.GenFormat
import com.nascon.league.data.fixturegen.GenMatchDto
import com.nascon.league.data.fixturegen.GenRoundDto
import com.nascon.league.data.fixturegen.GeneratedDto
import com.nascon.league.ui.components.AlertTone
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.EmptyStateCard
import com.nascon.league.ui.components.FeatureUnavailableState
import com.nascon.league.ui.components.FilterPill
import com.nascon.league.ui.components.InlineAlert
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.SecondaryButton
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Montserrat

@Composable
private fun NothingToShow(onBack: () -> Unit) {
    EmptyStateCard(
        icon = Icons.Default.Info,
        title = stringResource(R.string.fg_nothing_title),
        message = stringResource(R.string.fg_nothing_message),
        actionLabel = stringResource(R.string.fg_back),
        onAction = onBack,
    )
}

/** Alerts shown above the action row: save errors and the saved confirmation. */
@Composable
private fun SaveAlerts(s: GenUiState, onDismissError: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        s.error?.let { InlineAlert(it, AlertTone.ERROR) }
        s.saveError?.let { InlineAlert(it, AlertTone.ERROR) }
        s.savedCount?.let {
            InlineAlert(pluralStringResource(R.plurals.fg_saved, it, it), AlertTone.SUCCESS)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// F14 fixtures preview (round robin)
// ---------------------------------------------------------------------------------------------

private sealed interface PreviewRow {
    data class Header(val round: Int, val dateText: String?) : PreviewRow
    data class Game(val m: GenMatchDto) : PreviewRow
    data class Byes(val names: List<String>) : PreviewRow
}

@Composable
fun GenPreviewScreen(
    vm: FixtureGenViewModel,
    leagueId: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    GenGate(leagueId) {
        val s by vm.state.collectAsStateWithLifecycle()
        val dto = s.result
        if (dto == null || dto.format != "round_robin") {
            NothingToShow(onBack)
            return@GenGate
        }
        val matches = remember(dto) { playableMatches(dto) }
        HeaderSubtitle(stringResource(R.string.fg_step3_preview, matches.size, dto.roundCount))
        if (s.saveUnavailable) {
            FeatureUnavailableState(onBack = vm::dismissSaveUnavailable)
            return@GenGate
        }
        val start = GenSchedule.parseDate(s.startDate)
        val dates = remember(dto, s.startDate, s.matchDays) {
            start?.let { GenSchedule.roundDates(it, s.matchDays, dto.rounds.size) }.orEmpty()
        }
        val rows = remember(dto, dates) {
            buildList {
                dto.rounds.forEachIndexed { i, r ->
                    add(PreviewRow.Header(r.round, dates.getOrNull(i)?.let { GenSchedule.format(it) }))
                    r.matches.forEach { add(PreviewRow.Game(it)) }
                    if (r.byes.isNotEmpty()) add(PreviewRow.Byes(r.byes))
                }
            }
        }
        val check = remember(dto) { checkRoundRobin(dto) }
        Column(Modifier.fillMaxSize().background(Arena.colors.bg)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows) { row ->
                    when (row) {
                        is PreviewRow.Header -> Text(
                            if (row.dateText != null) stringResource(R.string.fg_round_date, row.round, row.dateText)
                            else stringResource(R.string.fg_round_n, row.round),
                            color = Arena.colors.inkMid, fontFamily = Montserrat, fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(top = 6.dp).semantics { heading() },
                        )
                        is PreviewRow.Game -> PreviewMatchCard(row.m, s.names)
                        is PreviewRow.Byes -> Text(
                            stringResource(R.string.fg_byes, row.names.joinToString(", ") { teamLabel(it, s.names) }),
                            color = Arena.colors.inkSoft, fontFamily = Montserrat, fontSize = 13.sp,
                        )
                    }
                }
                item {
                    if (check.clean) {
                        InlineAlert(stringResource(R.string.fg_no_conflicts), AlertTone.SUCCESS)
                    } else {
                        InlineAlert(stringResource(R.string.fg_conflicts), AlertTone.WARNING)
                    }
                }
            }
            SaveAlerts(s, vm::dismissError)
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryButton(stringResource(R.string.fg_back), onClick = onBack, modifier = Modifier.weight(0.45f))
                if (s.savedCount != null) {
                    PrimaryButton(stringResource(R.string.fg_done), onClick = onDone, modifier = Modifier.weight(1f))
                } else {
                    PrimaryButton(
                        pluralStringResource(R.plurals.fg_save_matches, matches.size, matches.size),
                        onClick = vm::save, loading = s.saving, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewMatchCard(m: GenMatchDto, names: Map<String, String>) {
    val c = Arena.colors
    val shape = RoundedCornerShape(16.dp)
    val home = teamLabel(m.home, names)
    val away = teamLabel(m.away, names)
    val text = stringResource(R.string.fg_match_vs, home, away)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White)
            .border(1.dp, c.cardBorder, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, color = c.ink, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        when (m.status) {
            "forfeit" -> StatusChip(
                stringResource(R.string.fg_forfeit, teamLabel(m.awardedTo, names)),
                Icons.Default.Warning, tone = ChipTone.Warning,
            )
            "voided" -> StatusChip(stringResource(R.string.fg_voided), Icons.Default.Info, tone = ChipTone.Neutral)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared bracket match card
// ---------------------------------------------------------------------------------------------

@Composable
fun BracketMatchCard(
    m: GenMatchDto,
    names: Map<String, String>,
    sources: Map<String, Pair<Boolean, String>>,
    onPick: ((String, String) -> Unit)?,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    val c = Arena.colors
    val shape = RoundedCornerShape(14.dp)
    val canPick = onPick != null && m.home != null && m.away != null && !m.bye
    Column(
        modifier = modifier
            .clip(shape)
            .background(Color.White)
            .border(1.5.dp, c.cardBorder, shape),
    ) {
        if (caption != null) {
            Text(
                caption, color = c.inkMid, fontFamily = Montserrat, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp),
            )
        }
        BracketSlot(m, "home", m.home, m.homeSeed, names, sources, canPick, onPick)
        BracketSlot(m, "away", m.away, m.awaySeed, names, sources, canPick, onPick)
    }
}

@Composable
private fun BracketSlot(
    m: GenMatchDto,
    slot: String,
    team: String?,
    seed: Int?,
    names: Map<String, String>,
    sources: Map<String, Pair<Boolean, String>>,
    canPick: Boolean,
    onPick: ((String, String) -> Unit)?,
) {
    val c = Arena.colors
    val won = team != null && m.winner == team
    val label = when {
        team != null -> teamLabel(team, names)
        m.bye && slot == "away" -> stringResource(R.string.fg_bye)
        else -> sources["${m.matchId}:$slot"]?.let { (loser, from) ->
            stringResource(if (loser) R.string.fg_loser_of else R.string.fg_winner_of, shortMatchId(from))
        } ?: stringResource(R.string.fg_tbd)
    }
    val advanceLabel = stringResource(R.string.fg_advance_action, label)
    val wonText = stringResource(R.string.fg_advances)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (won) c.chipBg else Color.Transparent)
            .then(
                if (canPick && team != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = advanceLabel) { onPick?.invoke(m.matchId, team) }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {
                if (won) contentDescription = "$label, $wonText"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (seed != null) {
            Text(seed.toString(), color = c.inkSoft, fontFamily = Montserrat, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            label,
            color = if (team == null) c.inkSoft else c.ink,
            fontFamily = Montserrat, fontSize = 13.sp,
            fontWeight = if (won) FontWeight.ExtraBold else FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (won) Icon(Icons.Default.Check, contentDescription = null, tint = c.brand)
    }
}

@Composable
private fun roundTitle(round: Int, total: Int): String = when (roundKind(round, total)) {
    RoundKind.FINAL -> stringResource(R.string.fg_final)
    RoundKind.SEMI -> stringResource(R.string.fg_semi)
    RoundKind.QUARTER -> stringResource(R.string.fg_quarter)
    RoundKind.ROUND -> stringResource(R.string.fg_round_n, round)
}

// ---------------------------------------------------------------------------------------------
// F15 single elimination bracket
// ---------------------------------------------------------------------------------------------

@Composable
fun GenBracketSingleScreen(
    vm: FixtureGenViewModel,
    leagueId: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    GenGate(leagueId) {
        val s by vm.state.collectAsStateWithLifecycle()
        val dto = s.result
        if (dto == null || dto.format != "single_elim") {
            NothingToShow(onBack)
            return@GenGate
        }
        HeaderSubtitle(stringResource(R.string.fg_bracket_subtitle_single, s.bracketTeams.size))
        if (s.saveUnavailable) {
            FeatureUnavailableState(onBack = vm::dismissSaveUnavailable)
            return@GenGate
        }
        val all = remember(dto) { dto.rounds.flatMap { it.matches } }
        val sources = remember(dto) { slotSources(all) }
        val count = remember(dto) { playableMatches(dto).size }
        Column(Modifier.fillMaxSize().background(Arena.colors.bg)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GenBody(stringResource(R.string.fg_bracket_hint), Modifier.padding(horizontal = 24.dp))
                // Own horizontal scroller for the bracket; the vertical scroller is the page.
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    dto.rounds.forEach { r ->
                        BracketRoundColumn(r, dto.rounds.size, s.names, sources, vm::pickWinner)
                    }
                }
                if (s.busy) GenBody(stringResource(R.string.fg_updating), Modifier.padding(horizontal = 24.dp))
            }
            SaveAlerts(s, vm::dismissError)
            BracketActions(s, count, vm, onBack, onDone)
        }
    }
}

@Composable
private fun BracketRoundColumn(
    r: GenRoundDto,
    total: Int,
    names: Map<String, String>,
    sources: Map<String, Pair<Boolean, String>>,
    onPick: (String, String) -> Unit,
) {
    Column(
        modifier = Modifier.width(168.dp).fillMaxHeight(),
    ) {
        Text(
            roundTitle(r.round, total),
            color = Arena.colors.inkMid, fontFamily = Montserrat, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(bottom = 6.dp).semantics { heading() },
        )
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.SpaceAround) {
            r.matches.forEach { m ->
                BracketMatchCard(
                    m, names, sources, onPick, Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    caption = if (m.bracket == "third_place") stringResource(R.string.fg_third_place_caption) else null,
                )
            }
        }
    }
}

@Composable
private fun BracketActions(s: GenUiState, count: Int, vm: FixtureGenViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SecondaryButton(stringResource(R.string.fg_back), onClick = onBack, modifier = Modifier.weight(0.45f))
        if (s.results.isNotEmpty() && s.savedCount == null) {
            SecondaryButton(stringResource(R.string.fg_reset_results), onClick = vm::resetBracketResults, modifier = Modifier.weight(0.6f))
        }
        if (s.savedCount != null) {
            PrimaryButton(stringResource(R.string.fg_done), onClick = onDone, modifier = Modifier.weight(1f))
        } else {
            PrimaryButton(
                pluralStringResource(R.plurals.fg_save_matches, count, count),
                onClick = vm::save, loading = s.saving, modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// F16 double elimination bracket
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenBracketDoubleScreen(
    vm: FixtureGenViewModel,
    leagueId: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    GenGate(leagueId) {
        val s by vm.state.collectAsStateWithLifecycle()
        val dto = s.result
        if (dto == null || dto.format != "double_elim") {
            NothingToShow(onBack)
            return@GenGate
        }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        HeaderSubtitle(stringResource(R.string.fg_bracket_subtitle_double, s.bracketTeams.size))
        if (s.saveUnavailable) {
            FeatureUnavailableState(onBack = vm::dismissSaveUnavailable)
            return@GenGate
        }
        val all = remember(dto) { (dto.winners + dto.losers).flatMap { it.matches } + dto.grandFinal }
        val sources = remember(dto) { slotSources(all) }
        val count = remember(dto) { playableMatches(dto).size }
        Column(Modifier.fillMaxSize().background(Arena.colors.bg)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(stringResource(R.string.fg_tab_winners), tab == 0, { tab = 0 })
                    FilterPill(stringResource(R.string.fg_tab_losers), tab == 1, { tab = 1 })
                    FilterPill(stringResource(R.string.fg_tab_grand), tab == 2, { tab = 2 })
                }
                GenBody(stringResource(R.string.fg_bracket_hint))
                when (tab) {
                    0 -> dto.winners.forEach { r ->
                        DoubleRound(stringResource(R.string.fg_winners_round, r.round), r, s.names, sources, vm::pickWinner)
                    }
                    1 -> dto.losers.forEach { r ->
                        DoubleRound(stringResource(R.string.fg_losers_round, r.round), r, s.names, sources, vm::pickWinner)
                    }
                    else -> {
                        Text(
                            stringResource(R.string.fg_tab_grand), color = Arena.colors.inkMid, fontFamily = Montserrat,
                            fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                        )
                        dto.grandFinal.forEach { m ->
                            if (m.conditional) {
                                StatusChip(stringResource(R.string.fg_reset_if_needed), Icons.Default.Info, tone = ChipTone.Warning)
                            }
                            BracketMatchCard(
                                m, s.names, sources, vm::pickWinner, Modifier.fillMaxWidth(),
                                caption = stringResource(if (m.conditional) R.string.fg_gf2 else R.string.fg_gf1),
                            )
                        }
                        val champ = dto.grandFinal.firstOrNull { !it.conditional }?.winner
                        if (champ != null && (dto.grandFinal.getOrNull(1)?.required != true)) {
                            StatusChip(
                                stringResource(R.string.fg_champion, teamLabel(champ, s.names)),
                                Icons.Default.EmojiEvents, tone = ChipTone.Success,
                            )
                        }
                    }
                }
                if (s.busy) GenBody(stringResource(R.string.fg_updating))
            }
            SaveAlerts(s, vm::dismissError)
            BracketActions(s, count, vm, onBack, onDone)
        }
    }
}

@Composable
private fun DoubleRound(
    title: String,
    r: GenRoundDto,
    names: Map<String, String>,
    sources: Map<String, Pair<Boolean, String>>,
    onPick: (String, String) -> Unit,
) {
    Text(
        title, color = Arena.colors.inkMid, fontFamily = Montserrat, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.padding(top = 6.dp).semantics { heading() },
    )
    r.matches.forEach { m -> BracketMatchCard(m, names, sources, onPick, Modifier.fillMaxWidth()) }
}

// ---------------------------------------------------------------------------------------------
// F17 swiss rounds
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenSwissRoundsScreen(
    vm: FixtureGenViewModel,
    leagueId: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    GenGate(leagueId) {
        val s by vm.state.collectAsStateWithLifecycle()
        val rounds = s.swissRounds
        if (rounds.isEmpty()) {
            NothingToShow(onBack)
            return@GenGate
        }
        val total = s.swissTotalEffective
        val view = s.swissView.coerceIn(1, rounds.size)
        val dto = rounds[view - 1]
        val editable = view == rounds.size
        HeaderSubtitle(stringResource(R.string.fg_swiss_subtitle, s.selected.size, rounds.size, total))
        if (s.saveUnavailable) {
            FeatureUnavailableState(onBack = vm::dismissSaveUnavailable)
            return@GenGate
        }
        val scores = remember(dto) { dto.standings.associate { it.teamId to it.score } }
        val canNext = rounds.size < total && swissRoundComplete(rounds.last(), s.swissResults) && !s.busy
        Column(Modifier.fillMaxSize().background(Arena.colors.bg)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rounds.forEach { r ->
                        FilterPill(stringResource(R.string.fg_round_short, r.round), r.round == view, { vm.viewSwissRound(r.round) })
                    }
                }
                GenBody(stringResource(R.string.fg_swiss_hint))
                dto.pairings.forEach { p ->
                    val home = teamLabel(p.home, s.names)
                    val away = teamLabel(p.away, s.names)
                    SwissPairingCard(
                        home = "$home (${formatScore(scores[p.home] ?: 0.0)})",
                        away = "$away (${formatScore(scores[p.away] ?: 0.0)})",
                        homeName = home, awayName = away,
                        result = s.swissResults[p.matchId],
                        editable = editable,
                        onResult = { vm.setSwissResult(p.matchId, it) },
                    )
                }
                dto.bye?.let { b ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)
                            .border(1.dp, Arena.colors.cardBorder, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.fg_gets_bye, teamLabel(b, s.names)),
                            color = Arena.colors.ink, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                        StatusChip(stringResource(R.string.fg_bye_point), Icons.Default.Info, tone = ChipTone.Warning)
                    }
                }
                GenLabel(stringResource(R.string.fg_standings_before), Modifier.padding(top = 8.dp))
                dto.standings.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${row.rank} ${teamLabel(row.teamId, s.names)}", color = Arena.colors.ink, fontFamily = Montserrat,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.fg_score_buchholz, formatScore(row.score), formatScore(row.buchholz)),
                            color = Arena.colors.inkSoft, fontFamily = Montserrat, fontSize = 13.sp,
                        )
                    }
                }
            }
            SaveAlerts(s, vm::dismissError)
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryButton(stringResource(R.string.fg_back), onClick = onBack, modifier = Modifier.weight(0.45f))
                if (s.savedCount != null) {
                    PrimaryButton(stringResource(R.string.fg_done), onClick = onDone, modifier = Modifier.weight(1f))
                } else {
                    SecondaryButton(
                        stringResource(R.string.fg_save_round, view), onClick = vm::save, enabled = !s.saving,
                        modifier = Modifier.weight(0.8f),
                    )
                    PrimaryButton(
                        stringResource(
                            if (rounds.size >= total) R.string.fg_all_rounds else R.string.fg_generate_round_n,
                            rounds.size + 1,
                        ),
                        onClick = vm::generateNextSwissRound, enabled = canNext, loading = s.busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwissPairingCard(
    home: String,
    away: String,
    homeName: String,
    awayName: String,
    result: String?,
    editable: Boolean,
    onResult: (String) -> Unit,
) {
    val c = Arena.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier.fillMaxWidth().clip(shape).background(Color.White).border(1.dp, c.cardBorder, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.fg_match_vs, home, away),
            color = c.ink, fontFamily = Montserrat, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        )
        val labels = listOf(
            "home" to stringResource(R.string.fg_result_home, homeName),
            "draw" to stringResource(R.string.fg_result_draw),
            "away" to stringResource(R.string.fg_result_away, awayName),
        )
        if (editable) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.forEach { (key, text) -> FilterPill(text, result == key, { onResult(key) }) }
            }
        } else {
            val shown = labels.firstOrNull { it.first == result }?.second
            if (shown != null) StatusChip(shown, Icons.Default.CheckCircle, tone = ChipTone.Success)
            else StatusChip(stringResource(R.string.fg_no_result), Icons.Default.Info, tone = ChipTone.Neutral)
        }
    }
}
