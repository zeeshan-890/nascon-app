package com.nascon.league.ui.platformadmin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.data.api.dto.ClubSummaryDto
import com.nascon.league.data.platformadmin.AccessMatrixDto
import com.nascon.league.data.platformadmin.AdminClubCreateRequest
import com.nascon.league.data.platformadmin.AuditEntryDto
import com.nascon.league.data.platformadmin.AuditFilter
import com.nascon.league.data.platformadmin.HealthApi
import com.nascon.league.data.platformadmin.PlatformAdminApi
import com.nascon.league.data.platformadmin.SportDefinitionDto
import com.nascon.league.data.platformadmin.healthApi
import com.nascon.league.data.platformadmin.platformAdminApi
import com.nascon.league.data.repo.Resource
import com.nascon.league.di.AppContainer
import com.nascon.league.ui.components.isFeatureUnavailable
import com.nascon.league.ui.failure.userMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** A failed call, ready for the UI: [unavailable] = the server has no such endpoint yet. */
data class Failure(val unavailable: Boolean, val message: String)

fun ApiError.toFailure() = Failure(isFeatureUnavailable(), userMessage(this))

// ---------- Shared in-process state ----------

/** Clubs created in this session, so the list shows them even when the clubs list is cached or served by the demo data. */
object CreatedClubsStore {
    private val _clubs = MutableStateFlow<List<ClubSummaryDto>>(emptyList())
    val clubs: StateFlow<List<ClubSummaryDto>> = _clubs.asStateFlow()
    fun add(club: ClubSummaryDto) = _clubs.update { list -> if (list.any { it.clubId == club.clubId }) list else list + club }
}

/** Sport definitions seen so far; lets the sport and scoring-event screens share one source of truth. */
object AdminSportsCache {
    private val _sports = MutableStateFlow<Map<String, SportDefinitionDto>>(emptyMap())
    val sports: StateFlow<Map<String, SportDefinitionDto>> = _sports.asStateFlow()
    fun put(sport: SportDefinitionDto) = _sports.update { it + (sport.sportId to sport) }
    fun putAll(list: List<SportDefinitionDto>) = _sports.update { it + list.associateBy { s -> s.sportId } }
}

/** The audit filter shared by the log (G13) and the filter screen (G15). */
object AuditFilterHolder {
    private val _filter = MutableStateFlow(AuditFilter())
    val filter: StateFlow<AuditFilter> = _filter.asStateFlow()
    private val _actors = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    /** (actor id, actor name) seen in the log so far. */
    val actors: StateFlow<List<Pair<String, String>>> = _actors.asStateFlow()
    private val _entries = MutableStateFlow<Map<String, AuditEntryDto>>(emptyMap())
    val entries: StateFlow<Map<String, AuditEntryDto>> = _entries.asStateFlow()

    fun set(f: AuditFilter) { _filter.value = f }
    fun reset() { _filter.value = AuditFilter() }
    fun remember(items: List<AuditEntryDto>) {
        _actors.update { cur -> (cur + items.map { it.actorId to it.actorName }).distinctBy { it.first }.sortedBy { it.second } }
        _entries.update { it + items.associateBy { e -> e.id } }
    }
}

private fun <T : ViewModel> factoryOf(create: () -> T, clazz: Class<T>) =
    viewModelFactory { addInitializer(clazz.kotlin) { create() } }

// ---------- G07 clubs ----------

data class ClubsUi(
    val clubs: List<ClubSummaryDto> = emptyList(),
    val loading: Boolean = true,
    val error: ApiError? = null,
    val updatedAt: Instant? = null,
    val stale: Boolean = false,
)

class AdminClubsViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ClubsUi())
    val state: StateFlow<ClubsUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(container.clubsListRepository.observeResource(), CreatedClubsStore.clubs) { res, created ->
                val server: List<ClubSummaryDto>?
                val ui = when (res) {
                    is Resource.Loading -> { server = res.cached; ClubsUi(loading = true) }
                    is Resource.Success -> { server = res.data; ClubsUi(loading = false, updatedAt = res.lastUpdatedAt, stale = res.isStale) }
                    is Resource.Error -> { server = res.cached; ClubsUi(loading = false, error = res.error) }
                }
                val base = server.orEmpty()
                ui.copy(clubs = base + created.filter { c -> base.none { it.clubId == c.clubId } })
            }.collect { _state.value = it }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { container.clubsListRepository.refresh() }
    }

    companion object {
        fun factory(container: AppContainer) = factoryOf({ AdminClubsViewModel(container) }, AdminClubsViewModel::class.java)
    }
}

// ---------- G08 new club ----------

data class NewClubUi(
    val name: String = "",
    val tenant: String = "",
    val venue: String = "",
    val city: String = "",
    val nameProblem: FieldProblem? = null,
    val tenantProblem: FieldProblem? = null,
    val venueProblem: FieldProblem? = null,
    val submitting: Boolean = false,
    val failure: Failure? = null,
)

class NewClubViewModel(private val container: AppContainer, defaultTenant: String) : ViewModel() {
    private val _state = MutableStateFlow(NewClubUi(tenant = defaultTenant))
    val state: StateFlow<NewClubUi> = _state.asStateFlow()
    private val _created = Channel<String>(Channel.BUFFERED)
    val created = _created.receiveAsFlow()

    fun onName(v: String) = _state.update { it.copy(name = v, nameProblem = null, failure = null) }
    fun onTenant(v: String) = _state.update { it.copy(tenant = v, tenantProblem = null, failure = null) }
    fun onVenue(v: String) = _state.update { it.copy(venue = v, venueProblem = null, failure = null) }
    fun onCity(v: String) = _state.update { it.copy(city = v, failure = null) }

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        val n = validateClubName(s.name)
        val t = validateTenantId(s.tenant)
        val v = validateVenueName(s.venue)
        if (n != null || t != null || v != null) {
            _state.update { it.copy(nameProblem = n, tenantProblem = t, venueProblem = v) }
            return
        }
        _state.update { it.copy(submitting = true, failure = null) }
        viewModelScope.launch {
            val api = container.platformAdminApi()
            val body = AdminClubCreateRequest(s.name.trim(), s.tenant.trim(), s.venue.trim(), s.city.trim().ifBlank { null })
            when (val r = safeCall { api.createClub(body) }) {
                is NetworkResult.Success -> {
                    CreatedClubsStore.add(ClubSummaryDto(clubId = r.data.clubId, name = r.data.name))
                    _state.update { it.copy(submitting = false) }
                    _created.send(r.data.clubId)
                }
                is NetworkResult.Failure -> {
                    val e = r.error
                    if (e is ApiError.Http && (e.status == 409 || e.code == "CLUB_EXISTS")) {
                        _state.update { it.copy(submitting = false, nameProblem = FieldProblem.DUPLICATE) }
                    } else {
                        _state.update { it.copy(submitting = false, failure = e.toFailure()) }
                    }
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, defaultTenant: String) =
            factoryOf({ NewClubViewModel(container, defaultTenant) }, NewClubViewModel::class.java)
    }
}

// ---------- G09 sports ----------

data class SportsUi(
    val sports: List<SportDefinitionDto> = emptyList(),
    val loading: Boolean = true,
    val failure: Failure? = null,
    /** True when the list was rebuilt from the leagues because the server has no sports list. */
    val readOnlyFallback: Boolean = false,
)

class AdminSportsViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SportsUi())
    val state: StateFlow<SportsUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            AdminSportsCache.sports.collect { cache ->
                _state.update { cur -> cur.copy(sports = cur.sports.map { cache[it.sportId] ?: it }) }
            }
        }
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            val api = container.platformAdminApi()
            when (val r = safeCall { api.sports() }) {
                is NetworkResult.Success -> {
                    AdminSportsCache.putAll(r.data)
                    _state.value = SportsUi(sports = r.data, loading = false)
                }
                is NetworkResult.Failure -> {
                    val f = r.error.toFailure()
                    if (f.unavailable && !container.isDemoMode) {
                        val fallback = fromLeagues(api)
                        if (fallback.isNotEmpty()) {
                            AdminSportsCache.putAll(fallback)
                            _state.value = SportsUi(sports = fallback, loading = false, readOnlyFallback = true)
                            return@launch
                        }
                    }
                    _state.value = SportsUi(loading = false, failure = f)
                }
            }
        }
    }

    /** The server has no sports list: use the sports its leagues play, each read with the real sport endpoint. */
    private suspend fun fromLeagues(api: PlatformAdminApi): List<SportDefinitionDto> {
        val leagues = (safeCall { container.apiProvider().leagues(100, 0) } as? NetworkResult.Success)?.data?.body().orEmpty()
        return leagues.map { it.sportId }.filter { it.isNotBlank() }.distinct().mapNotNull { id ->
            (safeCall { api.sport(id) } as? NetworkResult.Success)?.data?.let { if (it.sportId.isBlank()) it.copy(sportId = id) else it }
        }
    }

    companion object {
        fun factory(container: AppContainer) = factoryOf({ AdminSportsViewModel(container) }, AdminSportsViewModel::class.java)
    }
}

// ---------- G10 sport config ----------

data class SportConfigUi(
    val base: SportDefinitionDto? = null,
    val draw: Boolean? = null,
    val minSquad: Int? = null,
    val maxSquad: Int? = null,
    val loading: Boolean = true,
    val loadFailure: Failure? = null,
    val saving: Boolean = false,
    val saveFailure: Failure? = null,
    val saved: Boolean = false,
) {
    val shown: SportDefinitionDto?
        get() = base?.copy(
            drawAllowed = draw ?: base.drawAllowed,
            roster = base.roster.copy(
                minSquadSize = minSquad ?: base.roster.minSquadSize,
                maxSquadSize = maxSquad ?: base.roster.maxSquadSize,
            ),
        )
    val dirty: Boolean get() = base != null && shown != base
}

class SportConfigViewModel(private val container: AppContainer, private val sportId: String) : ViewModel() {
    private val _state = MutableStateFlow(SportConfigUi(base = AdminSportsCache.sports.value[sportId], loading = AdminSportsCache.sports.value[sportId] == null))
    val state: StateFlow<SportConfigUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            AdminSportsCache.sports.collect { cache ->
                cache[sportId]?.let { s -> _state.update { it.copy(base = s, loading = false) } }
            }
        }
        load()
    }

    fun load() {
        if (_state.value.base == null) _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val api = container.platformAdminApi()
            when (val r = safeCall { api.sport(sportId) }) {
                is NetworkResult.Success -> {
                    AdminSportsCache.put(r.data.let { if (it.sportId.isBlank()) it.copy(sportId = sportId) else it })
                    _state.update { it.copy(loading = false, loadFailure = null) }
                }
                is NetworkResult.Failure -> _state.update { it.copy(loading = false, loadFailure = r.error.toFailure()) }
            }
        }
    }

    fun setDraw(v: Boolean) = _state.update { it.copy(draw = v, saved = false, saveFailure = null) }

    fun setMinSquad(v: Int) = _state.update { s ->
        val max = s.shown?.roster?.maxSquadSize ?: v
        s.copy(minSquad = v.coerceIn(0, MAX_SQUAD), maxSquad = if (max < v) v.coerceAtMost(MAX_SQUAD) else s.maxSquad, saved = false, saveFailure = null)
    }

    fun setMaxSquad(v: Int) = _state.update { s ->
        val min = s.shown?.roster?.minSquadSize ?: 0
        s.copy(maxSquad = v.coerceIn(min, MAX_SQUAD), saved = false, saveFailure = null)
    }

    fun save() {
        val s = _state.value
        val def = s.shown ?: return
        if (s.saving || !s.dirty) return
        _state.update { it.copy(saving = true, saveFailure = null, saved = false) }
        viewModelScope.launch {
            val api = container.platformAdminApi()
            when (val r = safeCall { api.saveSport(sportId, def) }) {
                is NetworkResult.Success -> {
                    AdminSportsCache.put(r.data.let { if (it.sportId.isBlank()) it.copy(sportId = sportId) else it })
                    _state.update { it.copy(saving = false, draw = null, minSquad = null, maxSquad = null, saved = true) }
                }
                is NetworkResult.Failure -> _state.update { it.copy(saving = false, saveFailure = r.error.toFailure()) }
            }
        }
    }

    companion object {
        const val MAX_SQUAD = 200
        fun factory(container: AppContainer, sportId: String) =
            factoryOf({ SportConfigViewModel(container, sportId) }, SportConfigViewModel::class.java)
    }
}

// ---------- G11 scoring event ----------

data class ScoringEventUi(
    val sport: SportDefinitionDto? = null,
    val loading: Boolean = true,
    val loadFailure: Failure? = null,
    val label: String = "",
    val code: String = "",
    val points: Int = 1,
    val needsPlayer: Boolean = false,
    val labelProblem: FieldProblem? = null,
    val codeProblem: FieldProblem? = null,
    val saving: Boolean = false,
    val saveFailure: Failure? = null,
    val confirmRemove: Boolean = false,
)

class ScoringEventViewModel(
    private val container: AppContainer,
    private val sportId: String,
    private val key: String?,
) : ViewModel() {
    private val editing = key != null
    private var codeTouched = editing
    private var populated = false
    private var existingEvent: JsonObject? = null

    private val _state = MutableStateFlow(ScoringEventUi())
    val state: StateFlow<ScoringEventUi> = _state.asStateFlow()
    private val _done = Channel<Unit>(Channel.BUFFERED)
    val done = _done.receiveAsFlow()

    val isEditing: Boolean get() = editing

    init {
        viewModelScope.launch {
            AdminSportsCache.sports.collect { cache -> cache[sportId]?.let(::onSport) }
        }
        load()
    }

    private fun onSport(s: SportDefinitionDto) {
        _state.update { it.copy(sport = s, loading = false) }
        if (!populated) {
            populated = true
            if (key != null) {
                val ev = s.scoringEvents[key] as? JsonObject
                existingEvent = ev
                _state.update {
                    it.copy(label = eventLabel(key, ev), code = key, points = eventPoints(ev).coerceIn(0, MAX_EVENT_POINTS), needsPlayer = eventNeedsPlayer(ev))
                }
            }
        }
    }

    fun load() {
        if (_state.value.sport == null) _state.update { it.copy(loading = true, loadFailure = null) }
        viewModelScope.launch {
            val api = container.platformAdminApi()
            when (val r = safeCall { api.sport(sportId) }) {
                is NetworkResult.Success -> AdminSportsCache.put(r.data.let { if (it.sportId.isBlank()) it.copy(sportId = sportId) else it })
                is NetworkResult.Failure -> _state.update { it.copy(loading = false, loadFailure = r.error.toFailure()) }
            }
        }
    }

    fun onLabel(v: String) = _state.update {
        it.copy(label = v, labelProblem = null, saveFailure = null, code = if (codeTouched) it.code else suggestEventKey(v), codeProblem = null)
    }

    fun onCode(v: String) {
        codeTouched = true
        _state.update { it.copy(code = v.lowercase(), codeProblem = null, saveFailure = null) }
    }

    fun onPoints(v: Int) = _state.update { it.copy(points = v.coerceIn(0, MAX_EVENT_POINTS)) }
    fun onNeedsPlayer(v: Boolean) = _state.update { it.copy(needsPlayer = v) }
    fun askRemove(show: Boolean) = _state.update { it.copy(confirmRemove = show) }

    fun save() {
        val s = _state.value
        val sport = s.sport ?: return
        if (s.saving) return
        val lp = validateEventLabel(s.label)
        val cp = validateEventKey(s.code, sport.scoringEvents.keys, editing)
        if (lp != null || cp != null) {
            _state.update { it.copy(labelProblem = lp, codeProblem = cp) }
            return
        }
        val pointsKey = existingEvent?.let(::eventPointsKey) ?: sportPointsKey(sport)
        val event = buildEvent(existingEvent, pointsKey, s.points, s.label, s.needsPlayer)
        push(sport.withEvent(s.code, event))
    }

    fun remove() {
        val sport = _state.value.sport ?: return
        val k = key ?: return
        _state.update { it.copy(confirmRemove = false) }
        push(sport.withoutEvent(k))
    }

    private fun push(def: SportDefinitionDto) {
        _state.update { it.copy(saving = true, saveFailure = null) }
        viewModelScope.launch {
            val api = container.platformAdminApi()
            when (val r = safeCall { api.saveSport(sportId, def) }) {
                is NetworkResult.Success -> {
                    AdminSportsCache.put(r.data.let { if (it.sportId.isBlank()) it.copy(sportId = sportId) else it })
                    _state.update { it.copy(saving = false) }
                    _done.send(Unit)
                }
                is NetworkResult.Failure -> _state.update { it.copy(saving = false, saveFailure = r.error.toFailure()) }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, sportId: String, key: String?) =
            factoryOf({ ScoringEventViewModel(container, sportId, key) }, ScoringEventViewModel::class.java)
    }
}

// ---------- G12 access ----------

data class AccessUi(
    val original: List<AccessRow> = emptyList(),
    val rows: List<AccessRow> = emptyList(),
    val loading: Boolean = true,
    val loadFailure: Failure? = null,
    val editing: Boolean = false,
    val confirmSave: Boolean = false,
    val saving: Boolean = false,
    val saveFailure: Failure? = null,
    val savedOnce: Boolean = false,
) {
    val changes: Int get() = accessChangeCount(original, rows)
}

class AccessViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AccessUi())
    val state: StateFlow<AccessUi> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, loadFailure = null) }
        viewModelScope.launch {
            val api = container.platformAdminApi()
            when (val r = safeCall { api.access() }) {
                is NetworkResult.Success -> {
                    val rows = buildAccessRows(r.data.policies)
                    _state.update { it.copy(original = rows, rows = rows, loading = false, editing = false) }
                }
                is NetworkResult.Failure -> _state.update { it.copy(loading = false, loadFailure = r.error.toFailure()) }
            }
        }
    }

    fun startEdit() = _state.update { it.copy(editing = true, saveFailure = null, savedOnce = false) }
    fun discard() = _state.update { it.copy(editing = false, rows = it.original, saveFailure = null, confirmSave = false) }
    fun setScope(action: String, role: String, scope: String) {
        if (!canEditAccessCell(role)) return
        _state.update { it.copy(rows = it.rows.withScope(action, role, scope), saveFailure = null) }
    }
    fun askSave(show: Boolean) = _state.update { it.copy(confirmSave = show && it.changes > 0) }

    fun save() {
        val s = _state.value
        if (s.saving) return
        _state.update { it.copy(confirmSave = false, saving = true, saveFailure = null) }
        viewModelScope.launch {
            val api = container.platformAdminApi()
            when (val r = safeCall { api.saveAccess(AccessMatrixDto(accessRowsToPolicies(s.rows))) }) {
                is NetworkResult.Success -> {
                    val rows = buildAccessRows(r.data.policies)
                    _state.update { it.copy(original = rows, rows = rows, saving = false, editing = false, savedOnce = true) }
                }
                is NetworkResult.Failure -> _state.update { it.copy(saving = false, saveFailure = r.error.toFailure()) }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = factoryOf({ AccessViewModel(container) }, AccessViewModel::class.java)
    }
}

// ---------- G13 audit list ----------

data class AuditListUi(
    val filter: AuditFilter = AuditFilter(),
    val items: List<AuditEntryDto> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val failure: Failure? = null,
) {
    val hasMore: Boolean get() = items.size < total
}

class AuditListViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AuditListUi(filter = AuditFilterHolder.filter.value))
    val state: StateFlow<AuditListUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            AuditFilterHolder.filter.collect { f ->
                _state.update { it.copy(filter = f) }
                reload()
            }
        }
    }

    fun selectCategory(category: String?) = AuditFilterHolder.set(AuditFilterHolder.filter.value.copy(action = category))

    fun reload() {
        _state.update { it.copy(loading = true, failure = null) }
        fetch(0)
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || !s.hasMore) return
        _state.update { it.copy(loadingMore = true) }
        fetch(s.items.size)
    }

    private var job: kotlinx.coroutines.Job? = null

    private fun fetch(offset: Int) {
        job?.cancel()
        val f = _state.value.filter
        job = viewModelScope.launch {
            val api = container.platformAdminApi()
            val r = safeCall {
                api.audit(
                    actor = f.actor, action = f.action, from = f.from?.toString(), to = f.to?.toString(),
                    limit = PAGE, offset = offset,
                )
            }
            when (r) {
                is NetworkResult.Success -> {
                    AuditFilterHolder.remember(r.data.items)
                    _state.update {
                        it.copy(
                            items = if (offset == 0) r.data.items else it.items + r.data.items,
                            total = r.data.total, loading = false, loadingMore = false, failure = null,
                        )
                    }
                }
                is NetworkResult.Failure -> _state.update { it.copy(loading = false, loadingMore = false, failure = r.error.toFailure()) }
            }
        }
    }

    companion object {
        const val PAGE = 30
        fun factory(container: AppContainer) = factoryOf({ AuditListViewModel(container) }, AuditListViewModel::class.java)
    }
}

// ---------- G14 audit detail ----------

data class AuditDetailUi(
    val entry: AuditEntryDto? = null,
    val loading: Boolean = true,
    val failure: Failure? = null,
)

class AuditDetailViewModel(private val container: AppContainer, private val id: String) : ViewModel() {
    private val _state = MutableStateFlow(AuditDetailUi(entry = AuditFilterHolder.entries.value[id], loading = AuditFilterHolder.entries.value[id] == null))
    val state: StateFlow<AuditDetailUi> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = it.entry == null, failure = null) }
        viewModelScope.launch {
            val api = container.platformAdminApi()
            when (val r = safeCall { api.auditEntry(id) }) {
                is NetworkResult.Success -> _state.value = AuditDetailUi(entry = r.data, loading = false)
                is NetworkResult.Failure -> _state.update { it.copy(loading = false, failure = if (it.entry == null) r.error.toFailure() else null) }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, id: String) = factoryOf({ AuditDetailViewModel(container, id) }, AuditDetailViewModel::class.java)
    }
}

// ---------- G15 audit filter ----------

data class AuditFilterUi(
    val action: String? = null,
    val actor: String? = null,
    val from: String = "",
    val to: String = "",
    val problem: DateRangeProblem? = null,
)

class AuditFilterViewModel : ViewModel() {
    private val _state = MutableStateFlow(AuditFilterHolder.filter.value.let {
        AuditFilterUi(action = it.action, actor = it.actor, from = it.from?.toString().orEmpty(), to = it.to?.toString().orEmpty())
    })
    val state: StateFlow<AuditFilterUi> = _state.asStateFlow()

    fun onAction(v: String?) = _state.update { it.copy(action = v) }
    fun onActor(v: String?) = _state.update { it.copy(actor = v) }
    fun onFrom(v: String) = _state.update { it.copy(from = v, problem = null) }
    fun onTo(v: String) = _state.update { it.copy(to = v, problem = null) }
    fun reset() {
        AuditFilterHolder.reset()
        _state.value = AuditFilterUi()
    }

    /** True when the filter was valid and applied. */
    fun apply(): Boolean {
        val s = _state.value
        val p = validateDateRange(s.from, s.to)
        if (p != null) {
            _state.update { it.copy(problem = p) }
            return false
        }
        AuditFilterHolder.set(
            AuditFilter(
                actor = s.actor, action = s.action,
                from = s.from.takeIf { it.isNotBlank() }?.let(::parseDateInput),
                to = s.to.takeIf { it.isNotBlank() }?.let(::parseDateInput),
            ),
        )
        return true
    }

    companion object {
        fun factory() = factoryOf({ AuditFilterViewModel() }, AuditFilterViewModel::class.java)
    }
}

// ---------- G16 status ----------

data class CheckRow(val state: CheckState, val millis: Long? = null, val message: String? = null)

data class StatusUi(
    val checking: Boolean = true,
    val server: CheckRow? = null,
    val database: CheckRow? = null,
    val address: String = "",
    val demo: Boolean = false,
    val checkedAt: Instant? = null,
)

class SystemStatusViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(StatusUi())
    val state: StateFlow<StatusUi> = _state.asStateFlow()

    private var running = false

    init { check() }

    fun check() {
        if (running) return
        running = true
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            val cfg = container.configStore.snapshot()
            val api: HealthApi = container.healthApi()
            val server = timed { api.ping() }.let { (res, ms) ->
                when (res) {
                    is NetworkResult.Success -> CheckRow(if (res.data.pong) classifyLatency(ms) else CheckState.DOWN, ms)
                    is NetworkResult.Failure -> CheckRow(CheckState.DOWN, null, userMessage(res.error))
                }
            }
            val db = timed { api.health() }.let { (res, ms) ->
                when (res) {
                    is NetworkResult.Success -> CheckRow(classifyHealth(res.data.status, ms), ms)
                    is NetworkResult.Failure -> {
                        val e = res.error
                        if (e is ApiError.Http && e.status == 503) CheckRow(CheckState.STARTING, ms)
                        else CheckRow(CheckState.DOWN, null, userMessage(e))
                    }
                }
            }
            _state.value = StatusUi(
                checking = false, server = server, database = db, address = cfg.baseUrl,
                demo = cfg.useDemoData, checkedAt = Instant.now(),
            )
            running = false
        }
    }

    private suspend fun <T> timed(block: suspend () -> T): Pair<NetworkResult<T>, Long> {
        val t0 = System.nanoTime()
        val r = safeCall(block)
        return r to (System.nanoTime() - t0) / 1_000_000
    }

    companion object {
        fun factory(container: AppContainer) = factoryOf({ SystemStatusViewModel(container) }, SystemStatusViewModel::class.java)
    }
}
