package com.nascon.league.ui.fixtures

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.ConnState
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.data.realtime.throttleFirst
import com.nascon.league.data.repo.Cached
import com.nascon.league.di.AppContainer
import com.nascon.league.ui.failure.RateLimitRetry
import com.nascon.league.domain.model.Fixture
import com.nascon.league.domain.model.League
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

data class RefreshInfo(val refreshing: Boolean = false, val error: ApiError? = null)

data class FixturesUiState(
    val leaguesLoaded: Boolean = false,
    val leagues: List<League> = emptyList(),
    val leaguesRefresh: RefreshInfo = RefreshInfo(),
    val selectedLeagueId: String? = null,
    val filter: FixtureFilter = FixtureFilter.ALL,
    /** null = nothing cached yet for the selected league. */
    val fixtures: List<Fixture>? = null,
    val updatedAt: Instant? = null,
    val refresh: RefreshInfo = RefreshInfo(),
    val offline: Boolean = false,
) {
    val selectedLeague: League? get() = leagues.firstOrNull { it.leagueId == selectedLeagueId }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FixturesViewModel(
    private val container: AppContainer,
    private val handle: SavedStateHandle,
) : ViewModel() {

    private val userId: String = container.sessionRepository.session.value?.user?.userId.orEmpty()
    private val sessionLeagueIds: List<String> =
        container.sessionRepository.session.value?.user?.leagueIds.orEmpty()
    private val prefs = FixturesPrefsStore(container.appContext)

    private val filter = MutableStateFlow(FixtureFilter.fromKey(handle.get<String>("filter")))
    private val chosenLeague = MutableStateFlow<String?>(handle.get<String>(KEY_LEAGUE))
    /** Refresh state keyed by league id so a slow result never leaks into another league. */
    private val refreshByLeague = MutableStateFlow<Map<String, RefreshInfo>>(emptyMap())
    private val leaguesInfo = MutableStateFlow(RefreshInfo(refreshing = true))
    private val active = MutableStateFlow(false)
    private val rateLimit = RateLimitRetry(viewModelScope)
    private var leaguesStarted = false

    private val persisted: Flow<String?> = prefs.observeLeague(userId)

    private val leagueCache = container.leaguesRepository.observe()

    private val selectedId: Flow<String?> =
        combine(leagueCache, chosenLeague, persisted) { cached, chosen, saved ->
            pickLeague(cached?.data.orEmpty().map { it.leagueId }, chosen, saved, sessionLeagueIds)
        }.distinctUntilChanged()

    private val fixturesCache: Flow<Pair<String?, Cached<List<Fixture>>?>> = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(null to null) else container.fixturesRepository.observe(id).map { id to it }
    }

    val state: StateFlow<FixturesUiState> = combine(
        leagueCache, fixturesCache, filter, refreshByLeague, leaguesInfo,
    ) { leagues, (leagueId, fixtures), f, refreshMap, lErr ->
        FixturesUiState(
            leaguesLoaded = leagues != null,
            leagues = leagues?.data.orEmpty(),
            leaguesRefresh = lErr,
            selectedLeagueId = leagueId,
            filter = f,
            fixtures = fixtures?.data,
            updatedAt = fixtures?.updatedAt,
            refresh = leagueId?.let { refreshMap[it] } ?: RefreshInfo(),
        )
    }.combine(container.connectivity.state) { s, conn ->
        s.copy(offline = conn == ConnState.OFFLINE)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FixturesUiState())

    init {
        refreshLeagues()
        // refresh the selected league once when it first resolves / changes
        viewModelScope.launch {
            selectedId.collect { id -> if (id != null) refreshFixtures(id) }
        }
        viewModelScope.launch {
            container.refreshSignal.collect {
                refreshLeagues()
                state.value.selectedLeagueId?.let { refreshFixtures(it) }
            }
        }
        // Live score and status: refetch this league. The list is replaced, then deduped by match id.
        viewModelScope.launch {
            combine(active, selectedId) { on, id -> if (on) id else null }
                .distinctUntilChanged()
                .flatMapLatest { id ->
                    if (id == null) {
                        emptyFlow()
                    } else {
                        container.realtime.subscribe("league:$id")
                            .filter { it.type == "match.event_appended" || it.type == "match.status_changed" }
                            .throttleFirst(1000)
                            .map { id }
                    }
                }
                .collect { id -> refreshFixtures(id) }
        }
    }

    fun setActive(on: Boolean) {
        active.value = on
    }

    fun setFilter(f: FixtureFilter) {
        filter.value = f
        handle["filter"] = f.key
    }

    fun selectLeague(id: String) {
        chosenLeague.value = id
        handle[KEY_LEAGUE] = id
        viewModelScope.launch { prefs.saveLeague(userId, id) }
    }

    fun refresh() {
        refreshLeagues()
        state.value.selectedLeagueId?.let { refreshFixtures(it) }
    }

    fun hasPending(matchId: String): Flow<Boolean> =
        container.pendingRepository.observeForEntity(matchId).map { it.isNotEmpty() }.distinctUntilChanged()

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

    private fun refreshFixtures(leagueId: String) {
        if (refreshByLeague.value[leagueId]?.refreshing == true) return
        if (isOffline()) {
            refreshByLeague.update { it + (leagueId to RefreshInfo(false, ApiError.NoConnection)) }
            return
        }
        refreshByLeague.update { it + (leagueId to RefreshInfo(true, null)) }
        viewModelScope.launch {
            val r = container.fixturesRepository.refresh(leagueId)
            val err = (r as? NetworkResult.Failure)?.error
            refreshByLeague.update { it + (leagueId to RefreshInfo(false, err)) }
            rateLimit.consider("fixtures:$leagueId", err) { refreshFixtures(leagueId) }
        }
    }

    companion object {
        private const val KEY_LEAGUE = "league"

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { FixturesViewModel(container, createSavedStateHandle()) }
        }
    }
}
