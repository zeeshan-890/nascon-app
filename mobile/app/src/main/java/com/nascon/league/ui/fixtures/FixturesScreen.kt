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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (s.offline || s.refresh.error != null) SavedDataBadge()
                LastUpdatedLabel(s.updatedAt)
            }
            if (s.refresh.error != null && !s.offline) {
                Text(
                    stringResource(R.string.f3_refresh_failed_saved),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md),
                )
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
    val cd = stringResource(R.string.f3_league_selector_cd, name)
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
            FilterChip(
                selected = isSel,
                onClick = { onFilter(f) },
                label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                leadingIcon = if (isSel) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { if (isSel) contentDescription = selectedCd },
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
        EmptyState(
            icon = Icons.Default.SportsSoccer,
            title = stringResource(R.string.f3_no_matches_title),
            message = stringResource(R.string.f3_no_matches_message),
            actionLabel = stringResource(R.string.f3_refresh),
            onAction = onRefresh,
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
        EmptyState(
            icon = Icons.Default.SportsSoccer,
            title = stringResource(title),
            message = stringResource(R.string.f3_empty_filter_message),
            actionLabel = stringResource(R.string.f3_show_all),
            onAction = { onFilter(FixtureFilter.ALL) },
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
            items(section.items, key = { "m-" + it.matchId }) { f ->
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

@Composable
private fun GroupHeader(group: FixtureGroup) {
    val res = when (group) {
        FixtureGroup.LIVE -> R.string.f3_group_live
        FixtureGroup.UPCOMING -> R.string.f3_group_upcoming
        FixtureGroup.COMPLETED -> R.string.f3_group_completed
        FixtureGroup.OTHER -> R.string.f3_group_other
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(res),
            style = MaterialTheme.typography.titleMedium,
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

    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClickLabel = openLabel, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = cd },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    f.homeName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (showScore) "${f.homeScore} – ${f.awayScore}" else vs,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.md),
                )
                Text(
                    f.awayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                FixtureStatusChip(kind)
                if (pending) {
                    StatusChip(
                        text = stringResource(R.string.f3_pending_changes),
                        icon = Icons.Default.HourglassEmpty,
                    )
                }
            }
            Text(time, style = MaterialTheme.typography.bodyLarge)
            if (!f.venueName.isNullOrBlank()) {
                Text(
                    f.venueName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
