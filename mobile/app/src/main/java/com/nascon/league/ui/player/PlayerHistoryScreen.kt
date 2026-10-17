package com.nascon.league.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascon.league.R
import com.nascon.league.data.api.dto.HistoryEntryDto
import com.nascon.league.data.api.dto.PlayerHistoryDto
import com.nascon.league.data.repo.Resource
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.club.LoadingBox
import com.nascon.league.ui.club.titleCaseWords
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.ChipTone
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.LastUpdatedLabel
import com.nascon.league.ui.components.RefreshableList
import com.nascon.league.ui.components.SavedDataBadge
import com.nascon.league.ui.components.StatusChip
import com.nascon.league.ui.navigation.HeaderSubtitle
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing
import kotlinx.coroutines.launch

/** B22: timeline of squad moves for one player. */
@Composable
fun PlayerHistoryScreen(
    playerId: String,
    modifier: Modifier = Modifier,
) {
    HeaderSubtitle(stringResource(R.string.np_history_title))
    HistoryTimeline(playerId, highlightCurrent = false, modifier = modifier)
}

/**
 * Shared timeline for B22 / D03. Squad names come from the signed-in user's cached club payload;
 * unknown squads read as a neutral "a squad".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HistoryTimeline(playerId: String, highlightCurrent: Boolean, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    val names = squadNames(rememberClubLoad(session?.user?.clubId).club)
    val resource by remember(playerId) { container.playerHistoryRepository.observeResource(playerId) }
        .collectAsState(initial = Resource.Loading<PlayerHistoryDto>(null))
    LaunchedEffect(playerId) { container.playerHistoryRepository.refresh(playerId) }
    val refresh: () -> Unit = { scope.launch { container.playerHistoryRepository.refresh(playerId) } }

    val res = resource
    val data: PlayerHistoryDto? = when (res) {
        is Resource.Loading -> res.cached
        is Resource.Success -> res.data
        is Resource.Error -> res.cached
    }
    val unknownSquad = stringResource(R.string.np_squad_unknown)
    when {
        data == null && res is Resource.Error -> Column(modifier.fillMaxSize()) {
            LoadFailure(res.error) { refresh() }
        }
        data == null -> Column(modifier.fillMaxSize()) { LoadingBox() }
        else -> RefreshableList(
            refreshing = res is Resource.Loading,
            onRefresh = refresh,
            modifier = modifier.fillMaxSize(),
        ) {
            val entries = remember(data) { sortedHistory(data.history) }
            if (entries.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.History,
                    title = stringResource(R.string.np_history_empty_title),
                    message = stringResource(R.string.np_history_empty_message),
                )
            } else {
                val currentIdx = if (highlightCurrent) currentEntryIndex(entries, data.currentSquadId) else -1
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    item(key = "status") {
                        val failed = res is Resource.Error
                        val updated = (res as? Resource.Success)?.lastUpdatedAt
                        if (failed || updated != null) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                if (failed) SavedDataBadge()
                                LastUpdatedLabel(updated)
                            }
                        }
                    }
                    itemsIndexed(entries, key = { i, e -> "h-$i-${e.auditId.orEmpty()}" }) { i, e ->
                        HistoryRow(e, names, unknownSquad, isCurrent = i == currentIdx)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryRow(
    e: HistoryEntryDto,
    names: Map<String, String>,
    unknownSquad: String,
    isCurrent: Boolean,
) {
    val kind = historyKindOf(e.action)
    val from = e.fromSquadId?.let { names[it] } ?: unknownSquad
    val to = e.toSquadId?.let { names[it] } ?: unknownSquad
    val title = when (kind) {
        HistoryKind.LOAN -> stringResource(R.string.np_hist_loan_to, to)
        HistoryKind.TRANSFER -> stringResource(R.string.np_hist_joined, to)
        HistoryKind.RELEASE -> stringResource(R.string.np_hist_released, from)
        HistoryKind.RECALL -> stringResource(R.string.np_hist_recalled, to)
        HistoryKind.OTHER -> displayable(titleCaseWords(e.action)) ?: stringResource(R.string.np_history_title)
    }
    val kindText = when (kind) {
        HistoryKind.LOAN -> stringResource(R.string.np_kind_loan)
        HistoryKind.TRANSFER -> stringResource(R.string.np_kind_transfer)
        HistoryKind.RELEASE -> stringResource(R.string.np_kind_release)
        HistoryKind.RECALL -> stringResource(R.string.np_kind_recall)
        HistoryKind.OTHER -> null
    }
    val kindIcon = when (kind) {
        HistoryKind.LOAN -> Icons.Default.SyncAlt
        HistoryKind.TRANSFER -> Icons.Default.SwapHoriz
        HistoryKind.RELEASE -> Icons.Default.ExitToApp
        HistoryKind.RECALL -> Icons.Default.Replay
        HistoryKind.OTHER -> Icons.Default.History
    }
    val state = historyStateLabel(e.state)
    val sep = stringResource(R.string.np_meta_sep)
    val meta = buildList {
        formatHistoryDate(e.effectiveDate ?: e.whenAt)?.let { add(it) }
        displayable(e.who)?.let { add(stringResource(R.string.np_meta_by, it)) }
        displayable(e.why)?.let { add(stringResource(R.string.np_meta_reason, it)) }
        formatHistoryDate(e.loanEndDate)?.let { add(stringResource(R.string.np_meta_loan_ends, it)) }
    }.joinToString(sep)

    ArenaCard(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Arena.colors.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (kindText != null) StatusChip(kindText, kindIcon)
                if (isCurrent) StatusChip(stringResource(R.string.np_current), Icons.Default.Home, tone = ChipTone.Success)
                when (state?.kind) {
                    HistoryStateKind.PENDING -> StatusChip(stringResource(R.string.np_state_pending), Icons.Default.Schedule, tone = ChipTone.Warning)
                    HistoryStateKind.REJECTED -> StatusChip(stringResource(R.string.np_state_rejected), Icons.Default.Block, tone = ChipTone.Error)
                    HistoryStateKind.OTHER -> StatusChip(state?.text.orEmpty(), Icons.Default.Schedule, tone = ChipTone.Warning)
                    null -> {}
                }
            }
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Arena.colors.inkSoft,
                )
            }
        }
    }
}
