package com.nascon.league.ui.match

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.ConnState
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.data.api.dto.MatchDetailDto
import com.nascon.league.data.realtime.RealtimeState
import com.nascon.league.data.realtime.throttleFirst
import com.nascon.league.data.repo.Cached
import com.nascon.league.di.AppContainer
import com.nascon.league.domain.model.Fixture
import com.nascon.league.domain.model.Permissions
import com.nascon.league.ui.fixtures.StatusKind
import com.nascon.league.ui.fixtures.statusKind
import com.nascon.league.ui.scoring.dedupePendingByEventId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

data class MatchUiState(
    val cacheChecked: Boolean = false,
    val match: MatchDetailDto? = null,
    val updatedAt: Instant? = null,
    val homeName: String? = null,
    val awayName: String? = null,
    val timeline: List<TimelineItem> = emptyList(),
    val refreshing: Boolean = false,
    val refreshError: ApiError? = null,
    val offline: Boolean = false,
    val realtimeConnected: Boolean = false,
    val canScore: Boolean = false,
    /** From the cached fixture row; used by the upcoming header. */
    val scheduledAt: String? = null,
    val venueName: String? = null,
) {
    val isLive: Boolean get() = match?.let { statusKind(it.status) == StatusKind.LIVE } == true
    val notFound: Boolean get() = refreshError.isNotFound()
    val forbidden: Boolean get() = refreshError.isForbidden()
}

fun ApiError?.isNotFound(): Boolean =
    this is ApiError.Http && (status == 404 || code.endsWith("_NOT_FOUND"))

fun ApiError?.isForbidden(): Boolean =
    this is ApiError.Http && (status == 403 || code == "FORBIDDEN")

fun ApiError?.isConnectivity(): Boolean =
    this is ApiError.NoConnection || this is ApiError.ServerUnreachable || this is ApiError.Timeout

private data class RefreshState(val refreshing: Boolean = false, val error: ApiError? = null)

private data class Base(
    val cached: Cached<MatchDetailDto>?,
    val fixture: Fixture?,
    val pending: List<PendingInput>,
    val refresh: RefreshState,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MatchDetailViewModel(
    private val container: AppContainer,
    handle: SavedStateHandle,
) : ViewModel() {

    val matchId: String = handle.get<String>("matchId").orEmpty()

    private val active = MutableStateFlow(false)
    private val refreshState = MutableStateFlow(RefreshState())
    private val errors = Channel<ApiError>(Channel.BUFFERED)

    /** One-shot refresh failures that happened while cached data is on screen (for a snackbar). */
    val refreshFailures: Flow<ApiError> = errors.receiveAsFlow()

    private val cache: Flow<Cached<MatchDetailDto>?> = container.matchRepository.observe(matchId)

    private val leagueId: Flow<String?> = cache.map { it?.data?.leagueId }.distinctUntilChanged()

    private val fixtureForNames: Flow<Fixture?> = leagueId.flatMapLatest { id ->
        if (id == null) {
            flowOf(null)
        } else {
            container.fixturesRepository.observe(id).map { c -> c?.data?.firstOrNull { it.matchId == matchId } }
        }
    }

    private val base: Flow<Base> = combine(
        cache,
        fixtureForNames,
        container.pendingRepository.observePendingScoreEvents(matchId),
        refreshState,
    ) { c, f, p, r ->
        Base(
            c,
            f,
            p.map {
                PendingInput(
                    it.actionId, it.type, it.status.toString(), it.ownerUserId,
                    it.entityKey, it.payloadJson, it.createdAt,
                )
            },
            r,
        )
    }

    val state: StateFlow<MatchUiState> = combine(
        base,
        container.realtime.state,
        container.connectivity.state,
        container.sessionRepository.session,
    ) { b, rt, conn, session ->
        val m = b.cached?.data
        val homeName = b.fixture?.homeName?.takeIf { it.isNotBlank() }
        val awayName = b.fixture?.awayName?.takeIf { it.isNotBlank() }
        val teamName: (String?) -> String? = { id ->
            when {
                id == null || m == null -> null
                id == m.home.teamId -> homeName
                id == m.away.teamId -> awayName
                else -> null
            }
        }
        // A queued event whose event_id already arrived from the server (realtime) is shown once.
        val pendingEvents = pendingEventsFor(
            matchId,
            session?.user?.userId,
            dedupePendingByEventId(b.pending, m?.events.orEmpty().map { it.eventId }.toSet()),
            teamName,
        )
        MatchUiState(
            cacheChecked = true,
            match = m,
            updatedAt = b.cached?.updatedAt,
            homeName = homeName,
            awayName = awayName,
            scheduledAt = b.fixture?.scheduledAt,
            venueName = b.fixture?.venueName?.takeIf { it.isNotBlank() },
            timeline = if (m == null) emptyList() else buildTimeline(m.events, pendingEvents, teamName),
            refreshing = b.refresh.refreshing,
            refreshError = b.refresh.error,
            offline = conn == ConnState.OFFLINE,
            realtimeConnected = rt == RealtimeState.CONNECTED,
            canScore = m != null && m.leagueId != null &&
                Permissions(session).canScore(m.leagueId) &&
                statusKind(m.status) == StatusKind.LIVE,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MatchUiState())

    init {
        refreshNow(silent = false)
        // refresh when connectivity returns
        viewModelScope.launch { container.refreshSignal.collect { refreshNow(silent = true) } }
        // realtime: refetch (throttled) on any message for this match while the screen is active
        viewModelScope.launch {
            active.flatMapLatest { on ->
                if (on) container.realtime.subscribe("match:$matchId") else emptyFlow()
            }.throttleFirst(1000).collect { refreshNow(silent = true) }
        }
        // fallback poll while the socket is down but the device is online
        viewModelScope.launch {
            combine(active, container.realtime.state, container.connectivity.state) { a, rt, c ->
                a && rt != RealtimeState.CONNECTED && (c == ConnState.ONLINE || c == ConnState.SYNCING)
            }.distinctUntilChanged().collectLatest { poll ->
                if (poll) {
                    while (true) {
                        delay(10_000)
                        refreshNow(silent = true)
                    }
                }
            }
        }
        // make sure team names exist even if the fixtures list was never opened
        viewModelScope.launch {
            val id = leagueId.filterNotNull().first()
            val known = container.fixturesRepository.observe(id).first()?.data?.any { it.matchId == matchId }
            if (known != true && container.connectivity.state.value != ConnState.OFFLINE) {
                container.fixturesRepository.refresh(id)
            }
        }
    }

    fun setActive(on: Boolean) {
        active.value = on
    }

    fun retry() = refreshNow(silent = false)

    private fun refreshNow(silent: Boolean) {
        if (refreshState.value.refreshing) return
        if (container.connectivity.state.value == ConnState.OFFLINE) {
            refreshState.value = RefreshState(false, ApiError.NoConnection)
            return
        }
        refreshState.value = RefreshState(true, refreshState.value.error)
        viewModelScope.launch {
            val r = container.matchRepository.refresh(matchId)
            val err = (r as? NetworkResult.Failure)?.error
            refreshState.value = RefreshState(false, err)
            val hadCache = state.value.match != null
            if (err != null && !silent && hadCache && !err.isNotFound() && err !is ApiError.NoConnection) {
                errors.trySend(err)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { MatchDetailViewModel(container, createSavedStateHandle()) }
        }
    }
}
