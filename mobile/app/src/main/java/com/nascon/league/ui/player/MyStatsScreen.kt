package com.nascon.league.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.R
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.data.playerstats.PlayerStatsDto
import com.nascon.league.data.playerstats.playerStatsApi
import com.nascon.league.di.AppContainer
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.components.ArenaCard
import com.nascon.league.ui.components.EmptyState
import com.nascon.league.ui.components.FeatureUnavailableState
import com.nascon.league.ui.components.GenericErrorState
import com.nascon.league.ui.components.isFeatureUnavailable
import com.nascon.league.ui.theme.Arena
import com.nascon.league.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One tile of the stats grid. [labelRes] is used for fixed tiles, [label] for names that come from data. */
data class StatTile(val label: String?, val labelRes: Int?, val value: Int)

/** Pure mapping of the response to tiles: matches, one tile per event (names from data), then discipline. */
fun statTiles(s: PlayerStatsDto): List<StatTile> = buildList {
    add(StatTile(null, R.string.rp_stats_matches, s.matchesPlayed))
    s.events.filter { it.eventType.isNotBlank() }.forEach {
        add(StatTile(it.eventType.replace('_', ' ').trim().replaceFirstChar { c -> c.uppercase() }, null, it.count))
    }
    add(StatTile(null, R.string.rp_stats_discipline, s.disciplinePoints))
}

sealed interface MyStatsState {
    data object Loading : MyStatsState
    data class Loaded(val stats: PlayerStatsDto) : MyStatsState
    data object Unavailable : MyStatsState
    data class Failed(val message: String) : MyStatsState
}

class MyStatsViewModel(private val container: AppContainer, private val playerId: String) : ViewModel() {
    private val _state = MutableStateFlow<MyStatsState>(MyStatsState.Loading)
    val state: StateFlow<MyStatsState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = MyStatsState.Loading
        viewModelScope.launch {
            _state.value = when (val r = safeCall { container.playerStatsApi().stats(playerId) }) {
                is NetworkResult.Success -> MyStatsState.Loaded(r.data)
                is NetworkResult.Failure ->
                    if (r.error.isFeatureUnavailable()) MyStatsState.Unavailable
                    else MyStatsState.Failed(ErrorMapper.message(r.error))
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, playerId: String) = viewModelFactory {
            initializer { MyStatsViewModel(container, playerId) }
        }
    }
}

/** D02 - the signed-in player's own numbers as a grid of tiles. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyStatsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val session by container.sessionRepository.session.collectAsStateWithLifecycle()
    val playerId = session?.user?.playerId
    if (playerId.isNullOrBlank()) {
        EmptyState(
            icon = Icons.Default.Person,
            title = stringResource(R.string.rp_stats_no_player),
            message = stringResource(R.string.rp_stats_no_player_msg),
            modifier = modifier,
        )
        return
    }
    val vm: MyStatsViewModel = viewModel(key = "mystats-$playerId", factory = MyStatsViewModel.factory(container, playerId))
    val state by vm.state.collectAsStateWithLifecycle()
    when (val st = state) {
        MyStatsState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        MyStatsState.Unavailable -> FeatureUnavailableState(modifier, onBack)
        is MyStatsState.Failed -> GenericErrorState(st.message, vm::load, modifier)
        is MyStatsState.Loaded -> {
            val tiles = statTiles(st.stats)
            Column(
                modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    maxItemsInEachRow = 2,
                ) {
                    tiles.forEach { t ->
                        val label = t.label ?: stringResource(t.labelRes ?: R.string.rp_stats_empty)
                        ArenaCard(Modifier.weight(1f)) {
                            Column(
                                Modifier
                                    .padding(Spacing.md)
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = "$label: ${t.value}"
                                    },
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                            ) {
                                Text(label, style = MaterialTheme.typography.bodyMedium, color = Arena.colors.inkSoft)
                                Text(
                                    t.value.toString(),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Arena.colors.ink,
                                    modifier = Modifier.clearAndSetSemantics {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
