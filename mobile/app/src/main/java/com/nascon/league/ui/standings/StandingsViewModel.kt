package com.nascon.league.ui.standings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.ConnState
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.data.api.dto.StandingsDto
import com.nascon.league.data.realtime.throttleFirst
import com.nascon.league.data.repo.Cached
import com.nascon.league.di.AppContainer
import com.nascon.league.ui.failure.RateLimitRetry
import com.nascon.league.domain.model.League
import com.nascon.league.ui.fixtures.FixturesPrefsStore
import com.nascon.league.ui.fixtures.RefreshInfo
import com.nascon.league.ui.fixtures.pickLeague
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class StandingsUiState(
    val leagues: List<League> = emptyList(),
    val leaguesRefresh: RefreshInfo = RefreshInfo(),
    val selectedLeagueId: String? = null,
    /** null = nothing cached for the selected league yet. */
    val standings: StandingsDto? = null,
    val updatedAt: Instant? = null,
    val refresh: RefreshInfo = RefreshInfo(),
    val offline: Boolean = false,
    val sheetTeamId: String? = null,
) {
    val selectedLeague: League? get() = leagues.firstOrNull { it.leagueId == selectedLeagueId }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StandingsViewModel(
    private val container: AppContainer,
    private val handle: SavedStateHandle,
) : ViewModel() {

    private val userId: String = container.sessionRepository.session.value?.user?.userId.orEmpty()
    private val sessionLeagueIds: List<String> =
        container.sessionRepository.session.value?.user?.leagueIds.orEmpty()
    private val prefs = FixturesPrefsStore(container.appContext)

    private val chosenLeague = MutableStateFlow<String?>(handle.get<String>(KEY_LEAGUE))
    private val sheetTeam = MutableStateFlow<String?>(handle.get<String>(KEY_SHEET))
    private val refreshByLeague = MutableStateFlow<Map<String, RefreshInfo>>(emptyMap())
    private val leaguesInfo = MutableStateFlow(RefreshInfo(refreshing = true))
    private val active = MutableStateFlow(false)
    private val rateLimit = RateLimitRetry(viewModelScope)
    private var leaguesStarted = false

    private val leagueCache = container.leaguesRepository.observe()
    private val persisted: Flow<String?> = prefs.observeLeague(userId)

    private val selectedId: Flow<String?> =
        combine(leagueCache, chosenLeague, persisted) { cached, chosen, saved ->
            pickLeague(cached?.data.orEmpty().map { it.leagueId }, chosen, saved, sessionLeagueIds)
        }.distinctUntilChanged()

    /** Pairs the data with the league id it belongs to so a stale emission never shows under another league. */
    private val standingsCache: Flow<Pair<String?, Cached<StandingsDto>?>> = selectedId.flatMapLatest { id ->
        if (id == null) flowOf<Pair<String?, Cached<StandingsDto>?>>(null to null)
        else container.standingsRepository.observe(id).map { id to it }
    }

    val state: StateFlow<StandingsUiState> = combine(
        leagueCache, standingsCache, refreshByLeague, leaguesInfo, sheetTeam,
    ) { leagues, (leagueId, cached), refreshMap, lInfo, sheet ->
        StandingsUiState(
            leagues = leagues?.data.orEmpty(),
            leaguesRefresh = lInfo,
            selectedLeagueId = leagueId,
            standings = cached?.data,
            updatedAt = cached?.updatedAt,
            refresh = leagueId?.let { refreshMap[it] } ?: RefreshInfo(),
            sheetTeamId = sheet,
        )
    }.combine(container.connectivity.state) { s, conn ->
        s.copy(offline = conn == ConnState.OFFLINE)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StandingsUiState())

    init {
        refreshLeagues()
        viewModelScope.launch {
            selectedId.collect { id -> if (id != null) refreshStandings(id) }
        }
        viewModelScope.launch {
            container.refreshSignal.collect {
                refreshLeagues()
                state.value.selectedLeagueId?.let { refreshStandings(it) }
            }
        }
        // realtime: league:{id} while the screen is visible; refetch at most once per second
        viewModelScope.launch {
            combine(active, selectedId) { on, id -> if (on) id else null }
                .distinctUntilChanged()
                .flatMapLatest { id ->
                    if (id == null) {
                        emptyFlow()
                    } else {
                        container.realtime.subscribe("league:$id")
                            .filter { it.type == "standings.updated" }
                            .throttleFirst(1000)
                            .map { id }
                    }
                }
                .collect { id -> refreshStandings(id, force = true) }
        }
    }

    fun setActive(on: Boolean) {
        active.value = on
    }

    fun selectLeague(id: String) {
        chosenLeague.value = id
        handle[KEY_LEAGUE] = id
        // a sheet for a team of the previous league makes no sense
        openSheet(null)
        viewModelScope.launch { prefs.saveLeague(userId, id) }
    }

    fun openSheet(teamId: String?) {
        sheetTeam.value = teamId
        handle[KEY_SHEET] = teamId
    }

    fun refresh() {
        refreshLeagues()
        state.value.selectedLeagueId?.let { refreshStandings(it) }
    }

    private fun isOffline() = container.connectivity.state.value == ConnState.OFFLINE

    private fun refreshLeagues() {
        if (leaguesStarted && leaguesInfo.value.refreshing) return
        if (isOffline()) {
            leaguesInfo.value = RefreshInfo(false, ApiError.NoConnection)
            return
        }
        leaguesStarted = true
        leaguesInfo.value = RefreshInfo(true, null)
        viewModelScope.launch {
            val r = container.leaguesRepository.refresh()
            val err = (r as? NetworkResult.Failure)?.error
            leaguesInfo.value = RefreshInfo(false, err)
            rateLimit.consider("leagues", err) { refreshLeagues() }
        }
    }

    private fun refreshStandings(leagueId: String, force: Boolean = false) {
        if (refreshByLeague.value[leagueId]?.refreshing == true) return
        if (isOffline()) {
            if (!force) refreshByLeague.update { it + (leagueId to RefreshInfo(false, ApiError.NoConnection)) }
            return
        }
        refreshByLeague.update { it + (leagueId to RefreshInfo(true, null)) }
        viewModelScope.launch {
            val r = container.standingsRepository.refresh(leagueId)
            val err = (r as? NetworkResult.Failure)?.error
            refreshByLeague.update { it + (leagueId to RefreshInfo(false, err)) }
            rateLimit.consider("standings:$leagueId", err) { refreshStandings(leagueId) }
        }
    }

    companion object {
        private const val KEY_LEAGUE = "league"
        private const val KEY_SHEET = "sheet_team"

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { StandingsViewModel(container, createSavedStateHandle()) }
        }
    }
}
