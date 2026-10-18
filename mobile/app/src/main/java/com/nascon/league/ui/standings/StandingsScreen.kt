package com.nascon.league.ui.standings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.ui.failure.userMessage
import com.nascon.league.data.api.dto.StandingRowDto
import com.nascon.league.data.api.dto.StandingsDto
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.LastUpdatedLabel
import com.nascon.league.ui.components.RefreshableList
import com.nascon.league.ui.components.AlertTone
import com.nascon.league.ui.components.InlineAlert
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.theme.Arena
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import com.nascon.league.ui.match.isConnectivity
import com.nascon.league.ui.theme.Spacing

private val RankW: Dp = 40.dp
private val TeamW: Dp = 156.dp
private val NumW: Dp = 48.dp
private val DiffW: Dp = 76.dp
private val PtsW: Dp = 56.dp

@Composable
fun StandingsScreen(
    onViewFixtures: (leagueId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val vm: StandingsViewModel = viewModel(factory = StandingsViewModel.factory(container))
    val s by vm.state.collectAsStateWithLifecycle()

    // Realtime subscription only while the screen is visible.
    LifecycleStartEffect(vm) {
        vm.setActive(true)
        onStopOrDispose { vm.setActive(false) }
    }

    StandingsContent(
        s = s,
        onLeague = vm::selectLeague,
        onRefresh = vm::refresh,
        onOpenSheet = vm::openSheet,
        onViewFixtures = onViewFixtures,
        modifier = modifier,
    )
}

@Composable
private fun StandingsContent(
    s: StandingsUiState,
    onLeague: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenSheet: (String?) -> Unit,
    onViewFixtures: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        if (s.leagues.isNotEmpty()) LeagueHeader(s, onLeague)
        val cached = s.standings != null
        if (cached && s.refresh.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (cached) {
            if (s.offline) {
                // B14: amber saved-copy strip with the "Updated ..." label inside it
                InlineAlert(
                    text = stringResource(R.string.cs_offline_saved),
                    tone = AlertTone.WARNING,
                    icon = Icons.Default.CloudOff,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    content = { LastUpdatedLabel(s.updatedAt) },
                )
            } else if (s.refresh.error != null) {
                InlineAlert(
                    text = stringResource(R.string.f4_refresh_failed_saved),
                    tone = AlertTone.WARNING,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    content = { LastUpdatedLabel(s.updatedAt) },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (!s.offline && s.refresh.error == null) LastUpdatedLabel(s.updatedAt)
                }
                val leagueId = s.selectedLeagueId
                if (leagueId != null) {
                    TextButton(
                        onClick = { onViewFixtures(leagueId) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.f4_view_fixtures), style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
        RefreshableList(
            refreshing = s.refresh.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            StandingsBody(s, onRefresh, onOpenSheet)
        }
    }
}

@Composable
private fun LeagueHeader(s: StandingsUiState, onLeague: (String) -> Unit) {
    val name = s.selectedLeague?.name.orEmpty()
    if (s.leagues.size <= 1) {
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .semantics { heading() },
        )
        return
    }
    var open by remember { mutableStateOf(false) }
    val cd = stringResource(R.string.f4_league_selector_cd, name)
    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm)) {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = cd },
        ) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            s.leagues.forEach { l ->
                DropdownMenuItem(
                    text = { Text(l.name, style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        open = false
                        onLeague(l.leagueId)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    leadingIcon = {
                        if (l.leagueId == s.selectedLeagueId) Icon(Icons.Default.Check, contentDescription = null)
                    },
                )
            }
        }
    }
}

@Composable
private fun Spinner() {
    val cd = stringResource(R.string.f4_loading_cd)
    Box(Modifier.fillMaxSize().semantics { contentDescription = cd }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun NotSaved(onRetry: () -> Unit) {
    EmptyState(
        icon = Icons.Default.CloudOff,
        title = stringResource(R.string.f4_not_saved_title),
        message = stringResource(R.string.f4_not_saved_message),
        actionLabel = stringResource(R.string.f4_try_again),
        onAction = onRetry,
    )
}

@Composable
private fun StandingsBody(
    s: StandingsUiState,
    onRefresh: () -> Unit,
    onOpenSheet: (String?) -> Unit,
) {
    if (s.selectedLeagueId == null) {
        val err = s.leaguesRefresh.error
        when {
            s.leaguesRefresh.refreshing -> Spinner()
            err != null && err.isConnectivity() -> NotSaved(onRefresh)
            err != null -> ErrorState(userMessage(err), onRefresh)
            else -> EmptyState(
                icon = Icons.Default.Leaderboard,
                title = stringResource(R.string.f4_no_leagues_title),
                message = stringResource(R.string.f4_no_leagues_message),
                actionLabel = stringResource(R.string.f4_refresh),
                onAction = onRefresh,
            )
        }
        return
    }
    val standings = s.standings
    if (standings == null) {
        val err = s.refresh.error
        when {
            s.refresh.refreshing || err == null -> Spinner()
            err.isConnectivity() -> NotSaved(onRefresh)
            else -> ErrorState(userMessage(err), onRefresh)
        }
        return
    }
    if (standings.table.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Leaderboard,
            title = stringResource(R.string.f4_empty_title),
            message = stringResource(R.string.cs_empty_message),
            actionLabel = stringResource(R.string.f4_refresh),
            onAction = onRefresh,
        )
        return
    }
    // keyed by league id so scroll positions and the sheet never leak between leagues
    key(s.selectedLeagueId) {
        StandingsTable(standings, onOpenSheet)
        val sheetTeam = s.sheetTeamId
        if (sheetTeam != null && standings.table.any { it.teamId == sheetTeam }) {
            H2hSheet(standings, sheetTeam, onDismiss = { onOpenSheet(null) })
        }
    }
}

private sealed interface TableItem {
    data class TeamRow(val row: StandingRowDto) : TableItem
    data object Line : TableItem
}

private fun buildItems(standings: StandingsDto): List<TableItem> {
    val sorted = dedupeStandingRows(standings.table).sortedBy { it.rank }
    val after = qualificationLineAfter(standings.qualificationSpots, sorted.size)
    val out = ArrayList<TableItem>(sorted.size + 1)
    sorted.forEachIndexed { i, r ->
        out += TableItem.TeamRow(r)
        if (after != null && i + 1 == after) out += TableItem.Line
    }
    return out
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StandingsTable(standings: StandingsDto, onOpenSheet: (String?) -> Unit) {
    val tableItems = remember(standings) { buildItems(standings) }
    val hScroll = rememberScrollState()
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        stickyHeader(key = "header") { HeaderRow(hScroll) }
        items(
            tableItems,
            key = { if (it is TableItem.TeamRow) "t-" + it.row.teamId else "line" },
        ) { item ->
            when (item) {
                is TableItem.TeamRow -> TeamRow(
                    row = item.row,
                    name = teamDisplayName(standings.teamNames, item.row.teamId),
                    hScroll = hScroll,
                    onClick = { onOpenSheet(item.row.teamId) },
                )
                TableItem.Line -> QualificationLine()
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, cd: String, width: Dp) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.width(width).semantics { contentDescription = cd },
    )
}

@Composable
private fun HeaderRow(hScroll: ScrollState) {
    Surface(
        color = Arena.colors.chipBg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderCell(stringResource(R.string.f4_col_rank_short), stringResource(R.string.f4_col_rank), RankW)
            Text(
                stringResource(R.string.f4_col_team),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(TeamW).padding(horizontal = Spacing.sm),
            )
            Row(
                Modifier.weight(1f).horizontalScroll(hScroll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderCell(stringResource(R.string.f4_col_played), stringResource(R.string.f4_col_played_cd), NumW)
                HeaderCell(stringResource(R.string.f4_col_won), stringResource(R.string.f4_col_won_cd), NumW)
                HeaderCell(stringResource(R.string.f4_col_drawn), stringResource(R.string.f4_col_drawn_cd), NumW)
                HeaderCell(stringResource(R.string.f4_col_lost), stringResource(R.string.f4_col_lost_cd), NumW)
                HeaderCell(stringResource(R.string.f4_col_diff), stringResource(R.string.f4_col_diff_cd), DiffW)
                HeaderCell(stringResource(R.string.f4_col_points), stringResource(R.string.f4_col_points_cd), PtsW)
            }
        }
    }
}

private data class StatusUi(val text: Int, val icon: ImageVector, val tone: ChipTone)

private fun statusUi(status: QualStatus): StatusUi = when (status) {
    QualStatus.QUALIFIED -> StatusUi(R.string.f4_status_qualified, Icons.Default.Check, ChipTone.Success)
    QualStatus.OUT -> StatusUi(R.string.f4_status_out, Icons.Default.Close, ChipTone.Error)
    QualStatus.IN_CONTENTION -> StatusUi(R.string.f4_status_in_contention, Icons.Default.HourglassEmpty, ChipTone.Warning)
    QualStatus.UNKNOWN -> StatusUi(R.string.f4_status_unavailable, Icons.Default.Info, ChipTone.Neutral)
}

private fun tieBreakRes(t: TieBreak): Int = when (t) {
    TieBreak.H2H_POINTS -> R.string.f4_tb_h2h_points
    TieBreak.H2H_DIFF -> R.string.f4_tb_h2h_diff
    TieBreak.OVERALL_DIFF -> R.string.f4_tb_overall_diff
    TieBreak.SCORED -> R.string.f4_tb_scored
    TieBreak.FAIR_PLAY -> R.string.f4_tb_fair_play
    TieBreak.DRAW_LOTS -> R.string.f4_tb_draw_lots
}

@Composable
private fun NumCell(text: String, width: Dp, bold: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (bold) FontWeight.Bold else null,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.width(width),
    )
}

@Composable
private fun TeamRow(
    row: StandingRowDto,
    name: String,
    hScroll: ScrollState,
    onClick: () -> Unit,
) {
    val status = statusUi(qualStatus(row.status))
    val statusText = stringResource(status.text)
    val tieBreak = tieBreakOf(row.resolvedBy)
    val cd = rowDescription(
        template = stringResource(R.string.f4_row_cd),
        rank = row.rank,
        name = name,
        played = row.played,
        won = row.won,
        drawn = row.drawn,
        lost = row.lost,
        spokenDiff = spokenDiff(row.diff, stringResource(R.string.f4_plus), stringResource(R.string.f4_minus)),
        points = row.points,
        statusText = statusText,
    )
    val full = if (tieBreak != null) cd + ". " + stringResource(tieBreakRes(tieBreak)) else cd
    val openLabel = stringResource(R.string.f4_row_open_h2h)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Arena.colors.cardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = openLabel, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = full },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumCell(row.rank.toString(), RankW, bold = true)
            Column(
                Modifier.width(TeamW).padding(horizontal = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusChip(text = statusText, icon = status.icon, tone = status.tone)
                if (tieBreak != null) {
                    Text(
                        stringResource(tieBreakRes(tieBreak)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                Modifier.weight(1f).horizontalScroll(hScroll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NumCell(row.played.toString(), NumW)
                NumCell(row.won.toString(), NumW)
                NumCell(row.drawn.toString(), NumW)
                NumCell(row.lost.toString(), NumW)
                NumCell(formatDiff(row.diff), DiffW)
                NumCell(row.points.toString(), PtsW, bold = true)
            }
        }
    }
}

@Composable
private fun QualificationLine() {
    val label = stringResource(R.string.f4_qualification_line)
    val cd = stringResource(R.string.f4_qualification_line_cd)
    val color = Arena.colors.brand
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .semantics(mergeDescendants = true) { contentDescription = cd },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), thickness = 2.dp, color = color)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = Spacing.sm),
        )
        HorizontalDivider(Modifier.weight(1f), thickness = 2.dp, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun H2hSheet(standings: StandingsDto, teamId: String, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val others = remember(standings, teamId) { standings.table.sortedBy { it.rank }.map { it.teamId } }
    val entries = remember(standings, teamId) { parseH2h(standings.h2h, teamId, others) }
    val teamName = teamDisplayName(standings.teamNames, teamId)
    val explanation = remember(standings, teamId) { tieExplanation(standings.table, teamId) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
            // single scroller: explanation + per-team list scroll together (B13)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            ) {
                if (explanation != null) {
                    item(key = "why") { TieExplainBlock(standings, explanation) }
                }
                item(key = "title") {
                    Column {
                        Text(
                            stringResource(if (explanation != null) R.string.cs_h2h_all_title else R.string.f4_h2h_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = Spacing.sm).semantics { heading() },
                        )
                        Text(
                            stringResource(R.string.f4_h2h_subtitle, teamName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.sm))
                    }
                }
                if (entries.isEmpty()) {
                    item(key = "none") {
                        Text(
                            stringResource(R.string.f4_h2h_none),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(vertical = Spacing.md),
                        )
                    }
                } else {
                    items(entries, key = { it.otherTeamId }) { e ->
                        val otherName = teamDisplayName(standings.teamNames, e.otherTeamId)
                        val detail = if (e.neverMet) {
                            stringResource(R.string.f4_h2h_not_played)
                        } else {
                            stringResource(R.string.f4_h2h_line, e.played, e.points, e.scored, e.conceded)
                        }
                        val cd = stringResource(R.string.f4_h2h_row_cd, otherName, detail)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(vertical = Spacing.sm)
                                .semantics(mergeDescendants = true) { contentDescription = cd },
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                otherName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(detail, style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider()
                    }
                }
            }
            com.nascon.league.ui.components.SecondaryButton(
                text = stringResource(R.string.f4_h2h_close),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.md))
        }
    }
}
