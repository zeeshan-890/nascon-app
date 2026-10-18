package com.nascon.league.ui.matchops

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
import com.nascon.league.data.api.dto.PinnedProductDto
import com.nascon.league.data.api.dto.ProductDto
import com.nascon.league.data.matchops.ConflictView
import com.nascon.league.data.matchops.MatchOpsRepository
import com.nascon.league.data.matchops.MatchOverride
import com.nascon.league.data.matchops.MatchStatusMachine
import com.nascon.league.data.matchops.ScheduleResult
import com.nascon.league.data.matchops.ScheduleTime
import com.nascon.league.data.matchops.canManageMatch
import com.nascon.league.data.matchops.describeConflicts
import com.nascon.league.data.matchops.matchOpsRepository
import com.nascon.league.di.AppContainer
import com.nascon.league.domain.model.Fixture
import com.nascon.league.ui.components.isFeatureUnavailable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

sealed interface MatchOpsEvent {
    data class StatusChanged(val status: String) : MatchOpsEvent
    data object ScheduleSaved : MatchOpsEvent
    data object Pinned : MatchOpsEvent
    data object Unpinned : MatchOpsEvent
    data class Failed(val error: ApiError) : MatchOpsEvent
}

/** One selectable row of the pin screen. */
data class PinRow(val productId: String, val name: String, val fromPriceMinor: Long?, val pinned: Boolean)

data class MatchOpsUi(
    val cacheChecked: Boolean = false,
    val match: MatchDetailDto? = null,
    val homeName: String? = null,
    val awayName: String? = null,
    val leagueId: String? = null,
    /** Backend status vocabulary ("upcoming" ...), with this device's confirmed changes applied. */
    val status: String? = null,
    val scheduledAt: String? = null,
    val venueName: String? = null,
    val canManage: Boolean = false,
    val offline: Boolean = false,
    val refreshError: ApiError? = null,
    // actions
    val busy: Boolean = false,
    val confirmTo: String? = null,
    val unavailable: Boolean = false,
    // reschedule
    val dateEpochDay: Long? = null,
    val minuteOfDay: Int? = null,
    val conflicts: List<ConflictView>? = null,
    // pins
    val pinRows: List<PinRow> = emptyList(),
    val productsLoaded: Boolean = false,
    val selectedProduct: String? = null,
) {
    val title: String
        get() = if (homeName != null && awayName != null) "$homeName vs $awayName" else ""
}

private data class Local(
    val busy: Boolean = false,
    val confirmTo: String? = null,
    val unavailable: Boolean = false,
    val dateEpochDay: Long? = null,
    val minuteOfDay: Int? = null,
    val conflicts: List<ConflictView>? = null,
    val selectedProduct: String? = null,
    val refreshError: ApiError? = null,
    val touched: Boolean = false,
)

private data class Base(
    val match: MatchDetailDto?,
    val fixtures: List<Fixture>,
    val override: MatchOverride?,
    val canManage: Boolean,
    val offline: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MatchOpsViewModel(
    private val container: AppContainer,
    handle: SavedStateHandle,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    val matchId: String = handle.get<String>("matchId").orEmpty()
    private val repo = container.matchOpsRepository()
    private val local = MutableStateFlow(Local())
    private val events = Channel<MatchOpsEvent>(Channel.BUFFERED)
    val eventFlow: Flow<MatchOpsEvent> = events.receiveAsFlow()

    private val cache = container.matchRepository.observe(matchId)
    private val leagueId: Flow<String?> = cache.map { it?.data?.leagueId }.distinctUntilChanged()
    private val fixtures: Flow<List<Fixture>> = leagueId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else container.fixturesRepository.observe(id).map { it?.data.orEmpty() }
    }

    private val base: Flow<Base> = combine(
        cache,
        fixtures,
        repo.overrides,
        container.sessionRepository.session,
        container.connectivity.state,
    ) { c, f, o, session, conn ->
        Base(
            match = c?.data,
            fixtures = f,
            override = o[matchId],
            canManage = canManageMatch(session, c?.data?.leagueId),
            offline = conn == ConnState.OFFLINE,
        )
    }

    private val products: Flow<List<ProductDto>?> = container.productRepository.observe().map { it?.data }

    val state: StateFlow<MatchOpsUi> = combine(base, products, local, cacheChecked()) { b, prods, l, checked ->
        val fx = b.fixtures.firstOrNull { it.matchId == matchId }
        val m = b.match
        val status = b.override?.status ?: MatchStatusMachine.normalize(m?.status)
        val cachedPins = m?.pinnedProducts.orEmpty()
        val pinnedIds = MatchOpsRepository.effectivePinned(cachedPins.map { it.productId }.toSet(), b.override)
        MatchOpsUi(
            cacheChecked = checked,
            match = m,
            homeName = fx?.homeName?.takeIf { it.isNotBlank() },
            awayName = fx?.awayName?.takeIf { it.isNotBlank() },
            leagueId = m?.leagueId,
            status = status,
            scheduledAt = b.override?.scheduledAt ?: fx?.scheduledAt,
            venueName = fx?.venueName?.takeIf { it.isNotBlank() },
            canManage = b.canManage,
            offline = b.offline,
            refreshError = l.refreshError,
            busy = l.busy,
            confirmTo = l.confirmTo,
            unavailable = l.unavailable,
            dateEpochDay = l.dateEpochDay,
            minuteOfDay = l.minuteOfDay,
            conflicts = l.conflicts,
            pinRows = pinRows(prods, cachedPins, pinnedIds),
            productsLoaded = prods != null,
            selectedProduct = l.selectedProduct?.takeIf { it !in pinnedIds },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MatchOpsUi())

    private fun cacheChecked(): Flow<Boolean> = cache.map { true }

    init {
        viewModelScope.launch {
            if (container.hasNetwork()) {
                val r = container.matchRepository.refresh(matchId)
                if (r is NetworkResult.Failure) local.value = local.value.copy(refreshError = r.error)
            }
        }
        viewModelScope.launch {
            if (container.hasNetwork()) container.productRepository.refresh()
        }
        viewModelScope.launch {
            leagueId.collect { id ->
                if (id != null && container.hasNetwork()) container.fixturesRepository.refresh(id)
            }
        }
    }

    fun retry() {
        viewModelScope.launch {
            local.value = local.value.copy(refreshError = null)
            val r = container.matchRepository.refresh(matchId)
            if (r is NetworkResult.Failure) local.value = local.value.copy(refreshError = r.error)
        }
    }

    // ---- status ----
    fun askStatus(to: String) {
        val from = state.value.status ?: return
        if (MatchStatusMachine.canTransition(from, to)) local.value = local.value.copy(confirmTo = to)
    }

    fun dismissConfirm() {
        if (!local.value.busy) local.value = local.value.copy(confirmTo = null)
    }

    fun confirmStatus() {
        val to = local.value.confirmTo ?: return
        if (local.value.busy) return
        local.value = local.value.copy(busy = true)
        viewModelScope.launch {
            val r = repo.changeStatus(matchId, state.value.leagueId, to)
            local.value = local.value.copy(busy = false, confirmTo = null)
            when (r) {
                is NetworkResult.Success -> events.send(MatchOpsEvent.StatusChanged(to))
                is NetworkResult.Failure -> {
                    handleFailure(r.error)
                    // The match moved on elsewhere: pick up the real status so the buttons are right.
                    if (r.error is ApiError.Http && (r.error as ApiError.Http).code == "ILLEGAL_TRANSITION") {
                        container.matchRepository.refresh(matchId)
                    }
                }
            }
        }
    }

    // ---- reschedule ----
    /** Seeds the pickers from the match's current time (or tomorrow 18:00) once. */
    fun ensureScheduleDefaults() {
        val l = local.value
        if (l.touched) return
        val current = ScheduleTime.parse(state.value.scheduledAt, zone)
        val date = current?.toLocalDate() ?: LocalDate.now(zone).plusDays(1)
        val time = current?.toLocalTime() ?: LocalTime.of(18, 0)
        local.value = l.copy(dateEpochDay = date.toEpochDay(), minuteOfDay = time.hour * 60 + time.minute, touched = false)
    }

    fun setDate(date: LocalDate) {
        local.value = local.value.copy(dateEpochDay = date.toEpochDay(), touched = true)
    }

    fun setTime(hour: Int, minute: Int) {
        local.value = local.value.copy(minuteOfDay = hour * 60 + minute, touched = true)
    }

    fun backToForm() {
        local.value = local.value.copy(conflicts = null)
    }

    fun submitReschedule() {
        val l = local.value
        val day = l.dateEpochDay ?: return
        val minute = l.minuteOfDay ?: return
        if (l.busy) return
        val iso = ScheduleTime.toIsoUtc(LocalDate.ofEpochDay(day), LocalTime.of(minute / 60, minute % 60), zone)
        local.value = l.copy(busy = true)
        viewModelScope.launch {
            val r = repo.reschedule(matchId, state.value.leagueId, iso)
            when (r) {
                is ScheduleResult.Saved -> {
                    local.value = local.value.copy(busy = false)
                    events.send(MatchOpsEvent.ScheduleSaved)
                }
                is ScheduleResult.Conflicts -> {
                    val list = fixtures.first()
                    val teams = HashMap<String, String>()
                    val matchesByName = HashMap<String, String>()
                    list.forEach {
                        teams[it.homeTeamId] = it.homeName
                        teams[it.awayTeamId] = it.awayName
                        matchesByName[it.matchId] = "${it.homeName} vs ${it.awayName}"
                    }
                    local.value = local.value.copy(
                        busy = false,
                        conflicts = describeConflicts(
                            r.conflicts,
                            teamName = { teams[it]?.takeIf(String::isNotBlank) },
                            matchName = { matchesByName[it] },
                        ),
                    )
                }
                is ScheduleResult.Failed -> {
                    local.value = local.value.copy(busy = false)
                    handleFailure(r.error)
                }
            }
        }
    }

    // ---- pins ----
    fun select(productId: String) {
        local.value = local.value.copy(selectedProduct = productId)
    }

    fun pinSelected() {
        val id = state.value.selectedProduct ?: return
        if (local.value.busy) return
        local.value = local.value.copy(busy = true)
        viewModelScope.launch {
            val r = repo.pin(matchId, state.value.leagueId, id)
            local.value = local.value.copy(busy = false, selectedProduct = null)
            when (r) {
                is NetworkResult.Success -> events.send(MatchOpsEvent.Pinned)
                is NetworkResult.Failure -> handleFailure(r.error)
            }
        }
    }

    fun unpin(productId: String) {
        if (local.value.busy) return
        local.value = local.value.copy(busy = true)
        viewModelScope.launch {
            val r = repo.unpin(matchId, state.value.leagueId, productId)
            local.value = local.value.copy(busy = false)
            when (r) {
                is NetworkResult.Success -> events.send(MatchOpsEvent.Unpinned)
                is NetworkResult.Failure -> handleFailure(r.error)
            }
        }
    }

    private suspend fun handleFailure(error: ApiError) {
        if (error.isFeatureUnavailable()) local.value = local.value.copy(unavailable = true)
        else events.send(MatchOpsEvent.Failed(error))
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { MatchOpsViewModel(container, createSavedStateHandle()) }
        }

        /** Products with their lowest variant price; pinned ones (even if not in the catalogue) come first. */
        fun pinRows(
            products: List<ProductDto>?,
            cachedPins: List<PinnedProductDto>,
            pinnedIds: Set<String>,
        ): List<PinRow> {
            val known = products.orEmpty()
            val rows = known.map { p ->
                PinRow(p.productId, p.name, p.variants.minOfOrNull { it.priceMinor }, p.productId in pinnedIds)
            }
            val missing = pinnedIds.filter { id -> known.none { it.productId == id } }.map { id ->
                val pp = cachedPins.firstOrNull { it.productId == id }
                PinRow(id, pp?.name.orEmpty(), pp?.priceMinor, true)
            }
            return (missing + rows).sortedByDescending { it.pinned }
        }
    }
}
