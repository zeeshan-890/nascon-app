package com.nascon.league.ui.scoring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nascon.league.R
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.match.MatchUiState
import com.nascon.league.ui.match.Scoreboard
import com.nascon.league.ui.match.TimelineRow
import com.nascon.league.ui.theme.Spacing

@Composable
fun ScoringScreen(matchId: String, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val vm: ScoringViewModel = viewModel(
        key = "scoring:$matchId",
        factory = ScoringViewModel.factory(container, matchId),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    ScoringHost(vm)

    val m = s.match
    if (m == null) {
        Box(modifier.fillMaxSize(), Alignment.Center) {
            if (!s.loaded) {
                val cd = stringResource(R.string.f8_screen_loading_cd)
                CircularProgressIndicator(Modifier.semantics { contentDescription = cd })
            } else {
                EmptyState(
                    icon = Icons.Default.EventBusy,
                    title = stringResource(R.string.f3_gone_title),
                    message = stringResource(R.string.f3_gone_message),
                )
            }
        }
        return
    }

    val surface = surfaceFor(s.showPanel, s.mode, embedded = false)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "scoreboard") {
            val shown = if (s.hasPending) {
                m.copy(home = m.home.copy(score = s.homeScore), away = m.away.copy(score = s.awayScore))
            } else {
                m
            }
            Scoreboard(
                MatchUiState(homeName = s.homeName, awayName = s.awayName),
                shown,
                Modifier.padding(horizontal = Spacing.md),
                pendingScore = s.hasPending,
            )
        }
        if (surface == ScoringSurface.NOTICE) {
            item(key = "notice") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(24.dp))
                    Text(
                        stringResource(R.string.f8_read_only_notice),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
            }
        } else {
            item(key = "panel") { ScoringPanelContent(s, vm) }
        }
        item(key = "timeline-title") {
            Text(
                stringResource(R.string.f3_timeline_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Spacing.md).semantics { heading() },
            )
        }
        if (s.timeline.isEmpty()) {
            item(key = "no-events") {
                Text(
                    stringResource(R.string.f3_no_events),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md),
                )
            }
        } else {
            items(s.timeline, key = { it.key }) { item ->
                val eventId = if (item.key.startsWith("e-")) item.key.removePrefix("e-") else null
                TimelineRow(
                    item,
                    Modifier.padding(horizontal = Spacing.md),
                    onUndo = if (eventId != null && eventId in s.undoableEventIds) {
                        { vm.requestUndo(eventId) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
