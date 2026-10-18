package com.nascon.league.ui.leagueadmin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.data.api.dto.LeagueDto
import com.nascon.league.data.leagueadmin.AdminLeagueDto
import com.nascon.league.data.leagueadmin.AdminLeagueRequest
import com.nascon.league.data.leagueadmin.DivisionDto
import com.nascon.league.data.leagueadmin.DivisionRequest
import com.nascon.league.data.leagueadmin.PromotionRulesDto
import com.nascon.league.data.leagueadmin.SeasonDto
import com.nascon.league.data.leagueadmin.SeasonRequest
import com.nascon.league.data.leagueadmin.SportOptionDto
import com.nascon.league.data.leagueadmin.StandingsRulesDto
import com.nascon.league.data.leagueadmin.TeamDto
import com.nascon.league.data.leagueadmin.TeamRequest
import com.nascon.league.data.leagueadmin.leagueAdminApi
import com.nascon.league.data.repo.toPage
import com.nascon.league.di.AppContainer
import com.nascon.league.ui.components.isFeatureUnavailable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Data<T>(val value: T) : Load<T>
    data class Failed(val error: ApiError) : Load<Nothing>
}

/** State of a save or create action. [unavailable] means the server has no such endpoint yet. */
data class SaveUi(val saving: Boolean = false, val error: String? = null, val unavailable: Boolean = false)

/**
 * Real leagues merged with the contract-addition data. A missing contract endpoint never hides the real leagues;
 * a failed real call falls back to the contract-addition list when that one worked.
 */
fun combineLeagueResults(
    real: NetworkResult<List<LeagueDto>>,
    admin: NetworkResult<List<AdminLeagueDto>>,
): Load<List<LeagueSummary>> {
    val adminList = (admin as? NetworkResult.Success)?.data.orEmpty()
    return when (real) {
        is NetworkResult.Success -> Load.Data(LeagueMerge.merge(real.data, adminList))
        is NetworkResult.Failure ->
            if (admin is NetworkResult.Success) Load.Data(LeagueMerge.merge(emptyList(), adminList)) else Load.Failed(real.error)
    }
}

suspend fun loadLeagueSummaries(container: AppContainer): Load<List<LeagueSummary>> {
    val real = safeCall { container.apiProvider().leagues(100, 0).toPage().items }
    val admin = safeCall { container.leagueAdminApi().adminLeagues() }
    return combineLeagueResults(real, admin)
}

private fun <T> NetworkResult<T>.toLoad(): Load<T> = when (this) {
    is NetworkResult.Success -> Load.Data(data)
    is NetworkResult.Failure -> Load.Failed(error)
}

/** Shared base: runs [loader], exposes [state], supports retry. */
abstract class LoadViewModel<T>(private val loader: suspend () -> Load<T>) : ViewModel() {
    private val _state = MutableStateFlow<Load<T>>(Load.Loading)
    val state: StateFlow<Load<T>> = _state.asStateFlow()

    protected fun start() = reload()

    fun reload() {
        _state.value = Load.Loading
        viewModelScope.launch { _state.value = loader() }
    }

    /** Reloads without showing the spinner; a failed silent refresh keeps what is on screen. */
    fun refresh() {
        if (_state.value !is Load.Data) return
        viewModelScope.launch {
            val r = loader()
            if (r is Load.Data) _state.value = r
        }
    }

    protected fun setData(v: T) {
        _state.value = Load.Data(v)
    }
}

/** Runs a save and reports through [save] and [saved]. */
abstract class SaveViewModel<T>(loader: suspend () -> Load<T>) : LoadViewModel<T>(loader) {
    private val _save = MutableStateFlow(SaveUi())
    val save: StateFlow<SaveUi> = _save.asStateFlow()
    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved: Flow<Unit> = _saved.receiveAsFlow()

    protected fun <R> runSave(call: suspend () -> R, onOk: (R) -> Unit = {}) {
        if (_save.value.saving) return
        _save.value = SaveUi(saving = true)
        viewModelScope.launch {
            when (val r = safeCall { call() }) {
                is NetworkResult.Success -> {
                    onOk(r.data)
                    _save.value = SaveUi()
                    _saved.send(Unit)
                }
                is NetworkResult.Failure -> _save.value =
                    if (r.error.isFeatureUnavailable()) SaveUi(unavailable = true) else SaveUi(error = ErrorMapper.message(r.error))
            }
        }
    }

    fun dismissSaveError() {
        _save.update { it.copy(error = null, unavailable = false) }
    }
}

// ---------------- list ----------------

class LeagueListViewModel(private val container: AppContainer) : LoadViewModel<List<LeagueSummary>>({
    when (val l = loadLeagueSummaries(container)) {
        is Load.Data -> Load.Data(
            // Leagues awaiting approval come first so an admin sees what needs a decision.
            LeagueAdminAccess.visible(container.sessionRepository.session.value, l.value) { it.leagueId }
                .sortedBy { if (it.status == "pending") 0 else 1 },
        )
        else -> l
    }
}) {
    init { start() }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory { initializer { LeagueListViewModel(container) } }
    }
}

// ---------------- detail ----------------

class LeagueDetailViewModel(private val container: AppContainer, private val leagueId: String) :
    LoadViewModel<LeagueSummary?>({
        when (val l = loadLeagueSummaries(container)) {
            is Load.Data -> Load.Data(l.value.firstOrNull { it.leagueId == leagueId })
            is Load.Failed -> l
            Load.Loading -> Load.Loading
        }
    }) {
    private val _reviewing = MutableStateFlow(false)
    val reviewing: StateFlow<Boolean> = _reviewing.asStateFlow()

    init { start() }

    /** Super admin decision on a pending league. [onDone] gets true when the server accepted it. */
    fun review(approve: Boolean, onDone: (Boolean) -> Unit) {
        if (_reviewing.value) return
        _reviewing.value = true
        viewModelScope.launch {
            val api = container.leagueAdminApi()
            val r = safeCall { if (approve) api.approveLeague(leagueId) else api.rejectLeague(leagueId) }
            _reviewing.value = false
            val ok = r is NetworkResult.Success
            if (ok) reload()
            onDone(ok)
        }
    }

    companion object {
        fun factory(container: AppContainer, leagueId: String) =
            viewModelFactory { initializer { LeagueDetailViewModel(container, leagueId) } }
    }
}

// ---------------- league form ----------------

data class LeagueFormUi(
    val input: LeagueFormInput = LeagueFormInput(),
    val showErrors: Boolean = false,
    val sports: List<SportOptionDto> = emptyList(),
    val prefilled: Boolean = false,
) {
    val errors: Set<LeagueFormError> get() = LeagueFormValidator.validate(input)
}

class LeagueFormViewModel(private val container: AppContainer, private val leagueId: String?) :
    SaveViewModel<LeagueFormUi>({ Load.Loading }) {

    private val api = container.leagueAdminApi()
    private val form = MutableStateFlow(LeagueFormUi())
    val ui: StateFlow<LeagueFormUi> = form.asStateFlow()
    private val _loadState = MutableStateFlow<Load<Unit>>(Load.Loading)
    val loadState: StateFlow<Load<Unit>> = _loadState.asStateFlow()

    init { load() }

    fun load() {
        _loadState.value = Load.Loading
        viewModelScope.launch {
            val sports = safeCall { api.sports() }.let { (it as? NetworkResult.Success)?.data.orEmpty() }
            if (leagueId == null) {
                form.update { it.copy(sports = sports) }
                _loadState.value = Load.Data(Unit)
                return@launch
            }
            when (val l = loadLeagueSummaries(container)) {
                is Load.Failed -> _loadState.value = l
                else -> {
                    val s = (l as? Load.Data)?.value?.firstOrNull { it.leagueId == leagueId }
                    if (s != null && !form.value.prefilled) {
                        val x = s.extras
                        form.update {
                            it.copy(
                                sports = sports,
                                prefilled = true,
                                input = LeagueFormInput(
                                    name = s.name,
                                    sportId = s.sportId.ifBlank { null },
                                    format = s.format ?: LeagueFormat.ROUND_ROBIN,
                                    windowOpens = parseIsoDate(x?.transferWindowOpens),
                                    windowCloses = parseIsoDate(x?.transferWindowCloses),
                                    maxSquad = (x?.maxSquadSize ?: 25).toString(),
                                ),
                            )
                        }
                    } else {
                        form.update { it.copy(sports = sports) }
                    }
                    _loadState.value = Load.Data(Unit)
                }
            }
        }
    }

    fun edit(block: (LeagueFormInput) -> LeagueFormInput) = form.update { it.copy(input = block(it.input)) }

    fun submit() {
        val cur = form.value
        if (cur.errors.isNotEmpty()) {
            form.update { it.copy(showErrors = true) }
            return
        }
        val i = cur.input
        val req = AdminLeagueRequest(
            name = i.name.trim(),
            sportId = i.sportId.orEmpty(),
            format = i.format.apiValue,
            transferWindowOpens = i.windowOpens?.toString(),
            transferWindowCloses = i.windowCloses?.toString(),
            maxSquadSize = i.maxSquad.trim().toInt(),
        )
        runSave({ if (leagueId == null) api.createLeague(req) else api.updateLeague(leagueId, req) }) { saved ->
            if (leagueId == null) container.sessionRepository.addLeague(saved.leagueId)
        }
    }

    companion object {
        fun factory(container: AppContainer, leagueId: String?) =
            viewModelFactory { initializer { LeagueFormViewModel(container, leagueId) } }
    }
}

// ---------------- seasons ----------------

class SeasonsViewModel(container: AppContainer, leagueId: String) :
    LoadViewModel<List<SeasonDto>>({ safeCall { container.leagueAdminApi().seasons(leagueId) }.toLoad() }) {
    init { start() }

    companion object {
        fun factory(container: AppContainer, leagueId: String) =
            viewModelFactory { initializer { SeasonsViewModel(container, leagueId) } }
    }
}

data class SeasonFormUi(
    val name: String = "",
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val copyFromSeasonId: String? = null,
    val applyPromotion: Boolean = true,
    val showErrors: Boolean = false,
    val seasons: List<SeasonDto> = emptyList(),
) {
    val errors: Set<SeasonFormError> get() = SeasonFormValidator.validate(name, start, end)
}

class SeasonFormViewModel(container: AppContainer, private val leagueId: String) :
    SaveViewModel<Unit>({ Load.Data(Unit) }) {
    private val api = container.leagueAdminApi()
    private val form = MutableStateFlow(SeasonFormUi())
    val ui: StateFlow<SeasonFormUi> = form.asStateFlow()

    init {
        viewModelScope.launch {
            val list = (safeCall { api.seasons(leagueId) } as? NetworkResult.Success)?.data.orEmpty()
            form.update { it.copy(seasons = list) }
        }
    }

    fun edit(block: (SeasonFormUi) -> SeasonFormUi) = form.update(block)

    fun submit() {
        val cur = form.value
        if (cur.errors.isNotEmpty()) {
            form.update { it.copy(showErrors = true) }
            return
        }
        val req = SeasonRequest(
            name = cur.name.trim(),
            startDate = cur.start.toString(),
            endDate = cur.end.toString(),
            copyFromSeasonId = cur.copyFromSeasonId,
            applyPromotion = cur.applyPromotion,
        )
        runSave({ api.createSeason(leagueId, req) })
    }

    companion object {
        fun factory(container: AppContainer, leagueId: String) =
            viewModelFactory { initializer { SeasonFormViewModel(container, leagueId) } }
    }
}

// ---------------- divisions ----------------

class DivisionsViewModel(container: AppContainer, private val leagueId: String) :
    SaveViewModel<List<DivisionDto>>({ safeCall { container.leagueAdminApi().divisions(leagueId) }.toLoad() }) {
    private val api = container.leagueAdminApi()

    init { start() }

    fun create(name: String) {
        if (name.isBlank()) return
        runSave({ api.createDivision(leagueId, DivisionRequest(name.trim())) }, onOk = { reload() })
    }

    companion object {
        fun factory(container: AppContainer, leagueId: String) =
            viewModelFactory { initializer { DivisionsViewModel(container, leagueId) } }
    }
}

// ---------------- teams ----------------

class LeagueTeamsViewModel(container: AppContainer, private val leagueId: String) :
    SaveViewModel<List<TeamDto>>({ safeCall { container.leagueAdminApi().teams(leagueId) }.toLoad() }) {
    private val api = container.leagueAdminApi()

    init { start() }

    fun add(name: String) {
        if (name.isBlank()) return
        runSave({ api.addTeam(leagueId, TeamRequest(name.trim())) }, onOk = { reload() })
    }

    fun remove(teamId: String) {
        runSave({ api.removeTeam(leagueId, teamId) }, onOk = { reload() })
    }

    companion object {
        fun factory(container: AppContainer, leagueId: String) =
            viewModelFactory { initializer { LeagueTeamsViewModel(container, leagueId) } }
    }
}

// ---------------- promotion ----------------

data class PromotionUi(val up: Int = 2, val down: Int = 2, val teamCount: Int = PromotionRules.DEFAULT_TEAMS, val divisionName: String = "")

class PromotionViewModel(container: AppContainer, private val leagueId: String) :
    SaveViewModel<PromotionUi>({
        val api = container.leagueAdminApi()
        when (val r = safeCall { api.promotionRules(leagueId) }) {
            is NetworkResult.Failure -> Load.Failed(r.error)
            is NetworkResult.Success -> {
                val top = (safeCall { api.divisions(leagueId) } as? NetworkResult.Success)?.data
                    ?.minByOrNull { it.tier }
                Load.Data(
                    PromotionUi(
                        r.data.spotsUp, r.data.spotsDown,
                        top?.teamCount?.takeIf { it > 0 } ?: PromotionRules.DEFAULT_TEAMS,
                        top?.name.orEmpty(),
                    ),
                )
            }
        }
    }) {
    private val api = container.leagueAdminApi()
    private val edits = MutableStateFlow<PromotionUi?>(null)
    val draft: StateFlow<PromotionUi?> = edits.asStateFlow()

    init { start() }

    /** Server value until the user changes something. */
    fun current(loaded: PromotionUi): PromotionUi = edits.value ?: loaded

    fun setUp(loaded: PromotionUi, v: Int) {
        val c = current(loaded)
        edits.value = c.copy(up = PromotionRules.clamp(v, c.teamCount))
    }

    fun setDown(loaded: PromotionUi, v: Int) {
        val c = current(loaded)
        edits.value = c.copy(down = PromotionRules.clamp(v, c.teamCount))
    }

    fun submit(loaded: PromotionUi) {
        val c = current(loaded)
        runSave({ api.savePromotionRules(leagueId, PromotionRulesDto(c.up, c.down)) })
    }

    companion object {
        fun factory(container: AppContainer, leagueId: String) =
            viewModelFactory { initializer { PromotionViewModel(container, leagueId) } }
    }
}

// ---------------- standings rules ----------------

class StandingsRulesViewModel(container: AppContainer, private val leagueId: String) :
    SaveViewModel<StandingsRulesDto>({ safeCall { container.leagueAdminApi().standingsRules(leagueId) }.toLoad() }) {
    private val api = container.leagueAdminApi()
    private val edits = MutableStateFlow<StandingsRulesDto?>(null)
    val draft: StateFlow<StandingsRulesDto?> = edits.asStateFlow()

    init { start() }

    fun current(loaded: StandingsRulesDto): StandingsRulesDto =
        edits.value ?: loaded.copy(tiebreaks = StandingsRulesLogic.sanitize(loaded.tiebreaks))

    fun edit(loaded: StandingsRulesDto, block: (StandingsRulesDto) -> StandingsRulesDto) {
        edits.value = block(current(loaded))
    }

    fun submit(loaded: StandingsRulesDto) {
        val c = current(loaded)
        if (StandingsRulesLogic.validatePoints(c.pointsWin, c.pointsDraw, c.pointsLoss).isNotEmpty()) return
        runSave({ api.saveStandingsRules(leagueId, c) })
    }

    companion object {
        fun factory(container: AppContainer, leagueId: String) =
            viewModelFactory { initializer { StandingsRulesViewModel(container, leagueId) } }
    }
}
