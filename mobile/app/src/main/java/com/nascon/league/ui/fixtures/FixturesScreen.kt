package com.nascon.league.ui.fixtures

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.FilterPill
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.theme.Arena
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.domain.model.Fixture
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.LastUpdatedLabel
import com.nascon.league.ui.components.RefreshableList
import com.nascon.league.ui.components.SavedDataBadge
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.match.isConnectivity
import com.nascon.league.ui.theme.Spacing
import kotlinx.coroutines.flow.Flow

@Composable
fun FixturesScreen(
    onOpenMatch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val vm: FixturesViewModel = viewModel(factory = FixturesViewModel.factory(container))
    val s by vm.state.collectAsStateWithLifecycle()
    FixturesContent(
        s = s,
        onFilter = vm::setFilter,
        onLeague = vm::selectLeague,
        onRefresh = vm::refresh,
        onOpenMatch = onOpenMatch,
        hasPending = vm::hasPending,
        modifier = modifier,
    )
}

@Composable
private fun FixturesContent(
    s: FixturesUiState,
    onFilter: (FixtureFilter) -> Unit,
    onLeague: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenMatch: (String) -> Unit,
    hasPending: (String) -> Flow<Boolean>,
    modifier: Modifier = Modifier,
) {
    HeaderSubtitle(s.selectedLeague?.name)
    Column(modifier.fillMaxSize()) {
        if (s.leagues.isNotEmpty()) {
            LeagueHeader(s, onLeague)
            FilterRow(s.filter, onFilter)
        }
        val cached = s.fixtures != null
        if (cached && s.refresh.refreshing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (cached) {
            val warn = s.offline || s.refresh.error != null
            val a = Arena.colors
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                    .then(
                        if (warn) Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(a.warnBg)
                            .padding(Spacing.sm)
                        else Modifier,
                    ),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (warn) SavedDataBadge()
                    LastUpdatedLabel(s.updatedAt)
                }
                if (s.refresh.error != null && !s.offline) {
                    Text(
                        stringResource(R.string.f3_refresh_failed_saved),
                        style = MaterialTheme.typography.bodyLarge,
                        color = a.warnFg,
                    )
                }
            }
        }
        RefreshableList(
            refreshing = s.refresh.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            FixturesBody(s, onFilter, onRefresh, onOpenMatch, hasPending)
        }
    }
}

@Composable
private fun LeagueHeader(s: FixturesUiState, onLeague: (String) -> Unit) {
    val name = s.selectedLeague?.name.orEmpty()
    if (s.leagues.size <= 1) return
    val a = Arena.colors
    var open by rememberSaveable { mutableStateOf(false) }
    val cd = stringResource(R.string.f3_league_selector_cd, name)
    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs)) {
        Surface(
            onClick = { open = true },
            shape = CircleShape,
            color = Color.White,
            border = BorderStroke(1.5.dp, a.cardBorder),
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = cd },
        ) {
            Row(
                Modifier.padding(start = Spacing.md, end = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = a.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = a.brand)
            }
        }
        if (open) {
            LeaguePickerSheet(
                leagues = s.leagues,
                selectedId = s.selectedLeagueId,
                onSelect = onLeague,
                onDismiss = { open = false },
            )
        }
    }
}

@Composable
private fun filterLabel(f: FixtureFilter): String = stringResource(
    when (f) {
        FixtureFilter.ALL -> R.string.f3_filter_all
        FixtureFilter.LIVE -> R.string.f3_filter_live
        FixtureFilter.UPCOMING -> R.string.f3_filter_upcoming
        FixtureFilter.COMPLETED -> R.string.f3_filter_completed
    },
)

@Composable
private fun FilterRow(selected: FixtureFilter, onFilter: (FixtureFilter) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FixtureFilter.entries.forEach { f ->
            val isSel = f == selected
            val label = filterLabel(f)
            val selectedCd = stringResource(R.string.f3_filter_selected_cd, label)
            FilterPill(
                text = label,
                selected = isSel,
                onClick = { onFilter(f) },
                modifier = Modifier.semantics { if (isSel) contentDescription = selectedCd },
            )
        }
    }
}

@Composable
private fun Spinner() {
    val cd = stringResource(R.string.f3_loading_cd)
    Box(Modifier.fillMaxSize().semantics { contentDescription = cd }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun NotSaved(onRetry: () -> Unit) {
    EmptyState(
        icon = Icons.Default.CloudOff,
        title = stringResource(R.string.f3_not_saved_title),
        message = stringResource(R.string.f3_not_saved_message),
        actionLabel = stringResource(R.string.f3_try_again),
        onAction = onRetry,
    )
}

@Composable
private fun FixturesBody(
    s: FixturesUiState,
    onFilter: (FixtureFilter) -> Unit,
    onRefresh: () -> Unit,
    onOpenMatch: (String) -> Unit,
    hasPending: (String) -> Flow<Boolean>,
) {
    // No league known yet
    if (s.selectedLeagueId == null) {
        when {
            s.leaguesRefresh.refreshing -> Spinner()
            s.leaguesRefresh.error != null && s.leaguesRefresh.error.isConnectivity() -> NotSaved(onRefresh)
            s.leaguesRefresh.error != null -> ErrorState(ErrorMapper.message(s.leaguesRefresh.error), onRefresh)
            else -> EmptyState(
                icon = Icons.Default.SportsSoccer,
                title = stringResource(R.string.f3_no_leagues_title),
                message = stringResource(R.string.f3_no_leagues_message),
                actionLabel = stringResource(R.string.f3_refresh),
                onAction = onRefresh,
            )
        }
        return
    }
    val fixtures = s.fixtures
    if (fixtures == null) {
        val err = s.refresh.error
        when {
            s.refresh.refreshing || err == null -> Spinner()
            err.isConnectivity() -> NotSaved(onRefresh)
            else -> ErrorState(ErrorMapper.message(err), onRefresh)
        }
        return
    }
    if (fixtures.isEmpty()) {
        FixturesEmpty(
            icon = Icons.Default.CalendarMonth,
            title = stringResource(R.string.f3_no_matches_title),
            message = stringResource(R.string.ms_empty_all_message),
            actionLabel = stringResource(R.string.f3_refresh),
            onAction = onRefresh,
            showPullHint = true,
        )
        return
    }
    val sections = remember(fixtures, s.filter) { groupAndSort(fixtures, s.filter) }
    if (sections.isEmpty()) {
        val title = when (s.filter) {
            FixtureFilter.LIVE -> R.string.f3_empty_live
            FixtureFilter.UPCOMING -> R.string.f3_empty_upcoming
            FixtureFilter.COMPLETED -> R.string.f3_empty_completed
            FixtureFilter.ALL -> R.string.f3_no_matches_title
        }
        val message = when (s.filter) {
            FixtureFilter.LIVE -> R.string.ms_empty_live_message
            FixtureFilter.UPCOMING -> R.string.ms_empty_upcoming_message
            FixtureFilter.COMPLETED -> R.string.ms_empty_completed_message
            FixtureFilter.ALL -> R.string.f3_empty_filter_message
        }
        FixturesEmpty(
            icon = when (s.filter) {
                FixtureFilter.LIVE -> Icons.Default.SportsSoccer
                FixtureFilter.COMPLETED -> Icons.Default.CheckCircle
                else -> Icons.Default.CalendarMonth
            },
            title = stringResource(title),
            message = stringResource(message),
            actionLabel = stringResource(R.string.f3_show_all),
            onAction = { onFilter(FixtureFilter.ALL) },
            showPullHint = true,
        )
        return
    }
    FixtureList(sections, onOpenMatch, hasPending)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FixtureList(
    sections: List<FixtureSection>,
    onOpenMatch: (String) -> Unit,
    hasPending: (String) -> Flow<Boolean>,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        sections.forEach { section ->
            stickyHeader(key = "header-" + section.group.name) {
                GroupHeader(section.group)
            }
            val runs = if (section.group == FixtureGroup.UPCOMING || section.group == FixtureGroup.COMPLETED) {
                splitByDay(section.items)
            } else {
                listOf(DayRun(null, section.items))
            }
            val dated = section.group == FixtureGroup.UPCOMING || section.group == FixtureGroup.COMPLETED
            runs.forEach { run ->
                if (dated) {
                    item(key = "day-" + section.group.name + "-" + (run.day?.toString() ?: "tbc")) {
                        DayHeader(run.day, done = section.group == FixtureGroup.COMPLETED)
                    }
                }
                items(run.items, key = { "m-" + it.matchId }) { f ->
                    val pending by produceState(false, f.matchId) {
                        hasPending(f.matchId).collect { value = it }
                    }
                    FixtureRow(
                        f = f,
                        pending = pending,
                        onClick = { onOpenMatch(f.matchId) },
                        modifier = Modifier.padding(horizontal = Spacing.md),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: java.time.LocalDate?, done: Boolean) {
    val tbc = stringResource(R.string.f3_time_tbc)
    val base = day?.let {
        java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault()).format(it)
    } ?: tbc
    val text = if (done) stringResource(R.string.ms_day_done, base) else base
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold),
        color = Arena.colors.inkSoft,
        modifier = Modifier.padding(horizontal = Spacing.md).semantics { heading() },
    )
}

@Composable
private fun FixturesEmpty(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    showPullHint: Boolean,
) {
    val colors = Arena.colors
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(colors.chipBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(44.dp), tint = colors.brand)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(message, style = MaterialTheme.typography.bodyLarge, color = colors.inkSoft, textAlign = TextAlign.Center)
        if (showPullHint) {
            StatusChip(
                text = stringResource(R.string.ms_pull_hint),
                icon = Icons.Default.ArrowDownward,
                tone = ChipTone.Neutral,
            )
        }
        com.nascon.league.ui.components.PrimaryButton(text = actionLabel, onClick = onAction)
    }
}

@Composable
private fun GroupHeader(group: FixtureGroup) {
    val res = when (group) {
        FixtureGroup.LIVE -> R.string.f3_group_live
        FixtureGroup.UPCOMING -> R.string.f3_group_upcoming
        FixtureGroup.COMPLETED -> R.string.f3_group_completed
        FixtureGroup.OTHER -> R.string.f3_group_other
    }
    Surface(color = Arena.colors.bg, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(res),
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold),
            color = Arena.colors.inkMid,
            modifier = Modifier
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .semantics { heading() },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FixtureRow(
    f: Fixture,
    pending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = statusKind(f.status)
    val showScore = (kind == StatusKind.LIVE || kind == StatusKind.COMPLETED) &&
        f.homeScore != null && f.awayScore != null
    val tbc = stringResource(R.string.f3_time_tbc)
    val time = FixtureTime.format(f.scheduledAt, fallback = tbc)
    val statusText = stringResource(kind.labelRes())
    val pendingText = stringResource(R.string.f3_row_pending_cd)
    val base = if (showScore) {
        stringResource(
            R.string.f3_row_cd_score, f.homeName, f.awayName, statusText,
            f.homeScore.toString(), f.awayScore.toString(), time,
        )
    } else {
        stringResource(R.string.f3_row_cd_noscore, f.homeName, f.awayName, statusText, time)
    }
    val cd = if (pending) "$base $pendingText" else base
    val vs = stringResource(R.string.f3_vs)
    val openLabel = stringResource(R.string.f3_open_match)

    val a = Arena.colors
    val topLine = f.venueName?.takeIf { it.isNotBlank() }.orEmpty()
    ArenaCard(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = cd },
        onClick = onClick,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                topLine,
                style = MaterialTheme.typography.bodyLarge,
                color = a.inkSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = Spacing.sm),
            )
            FixtureStatusChip(kind, timeText = if (kind == StatusKind.UPCOMING) time else null)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(
                f.homeName,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                color = a.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (showScore) "${f.homeScore} – ${f.awayScore}" else vs,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = if (showScore) 26.sp else 17.sp,
                    fontWeight = if (showScore) FontWeight.ExtraBold else FontWeight.SemiBold,
                ),
                color = if (showScore) a.brandDeep else a.inkSoft,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.sm),
            )
            Text(
                f.awayName,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                color = a.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        if (kind != StatusKind.UPCOMING) {
            Text(time, style = MaterialTheme.typography.bodyLarge, color = a.inkSoft)
        }
        if (pending) {
            StatusChip(
                text = stringResource(R.string.f3_pending_changes),
                icon = Icons.Default.HourglassEmpty,
                tone = ChipTone.Warning,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}
