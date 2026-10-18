package com.nascon.league.ui.fixturegen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.R
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.AppJson
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.data.fixturegen.FixtureGenRules
import com.nascon.league.data.fixturegen.FixtureSaveResultDto
import com.nascon.league.data.fixturegen.GenApiFormat
import com.nascon.league.data.fixturegen.GenFormat
import com.nascon.league.data.fixturegen.GenRequest
import com.nascon.league.data.fixturegen.GeneratedDto
import com.nascon.league.data.fixturegen.Withdrawal
import com.nascon.league.data.fixturegen.fixtureGenApi
import com.nascon.league.data.fixturegen.fixtureGenSaveApi
import com.nascon.league.di.AppContainer
import com.nascon.league.ui.components.isFeatureUnavailable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

enum class GenNav { PREVIEW, BRACKET_SINGLE, BRACKET_DOUBLE, SWISS_ROUNDS }

data class GenUiState(
    val leagueId: String = "",
    val loadingTeams: Boolean = true,
    val available: List<TeamOpt> = emptyList(),
    /** Team ids in league-table order (best first); used to seed knockouts. */
    val ranked: List<String> = emptyList(),
    val selected: List<String> = emptyList(),
    val selectionInit: Boolean = false,
    val format: GenFormat? = null,
    // round robin
    val legs: Int = 1,
    val withdrawalPolicy: String = "forfeit_remaining",
    val withdrawals: Map<String, Int> = emptyMap(),
    // scheduling hints (client side)
    val startDate: String = "",
    val matchDays: Set<Int> = GenSchedule.saturdayAndSunday,
    // swiss
    val swissTotal: Int? = null,
    val swissRounds: List<GeneratedDto> = emptyList(),
    val swissResults: Map<String, String> = emptyMap(),
    val swissView: Int = 1,
    // knockouts
    val thirdPlace: Boolean = false,
    val bracketReset: Boolean = false,
    val advancing: Int = 4,
    val bracketTeams: List<String> = emptyList(),
    val results: Map<String, String> = emptyMap(),
    // output
    val busy: Boolean = false,
    val result: GeneratedDto? = null,
    val error: String? = null,
    val generatorUnavailable: Boolean = false,
    val saving: Boolean = false,
    val savedCount: Int? = null,
    val saveUnavailable: Boolean = false,
    val saveError: String? = null,
) {
    val names: Map<String, String> get() = available.associate { it.id to it.name }
    val swissTotalEffective: Int get() = swissTotal ?: FixtureGenRules.defaultSwissRounds(selected.size)
}

class FixtureGenViewModel(private val container: AppContainer, private val leagueId: String) : ViewModel() {
    private val _state = MutableStateFlow(
        GenUiState(leagueId = leagueId, startDate = defaultStartDate().toString()),
    )
    val state: StateFlow<GenUiState> = _state.asStateFlow()

    private val _events = Channel<GenNav>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val api by lazy { container.fixtureGenApi() }
    private val saveApi by lazy { container.fixtureGenSaveApi() }

    private fun str(id: Int, vararg args: Any): String = container.appContext.getString(id, *args)

    init {
        viewModelScope.launch {
            container.standingsRepository.observe(leagueId).collect { cached ->
                val dto = cached?.data
                val ids = dto?.table?.sortedBy { it.rank }?.map { it.teamId }.orEmpty()
                val names = dto?.teamNames.orEmpty()
                val points = dto?.table?.associate { it.teamId to it.points }.orEmpty()
                val all = (ids + names.keys).distinct().map { id ->
                    TeamOpt(id, names[id]?.takeIf { n -> n.isNotBlank() } ?: id, points[id])
                }
                if (all.isNotEmpty()) applyTeams(all, ids.ifEmpty { all.map { it.id } })
            }
        }
        viewModelScope.launch {
            container.standingsRepository.refresh(leagueId)
            if (_state.value.available.isEmpty()) {
                if (container.isDemoMode) {
                    val demo = GenDemoTeams.all
                    applyTeams(demo, demo.map { it.id })
                } else {
                    _state.update { it.copy(loadingTeams = false) }
                }
            }
        }
    }

    private fun applyTeams(all: List<TeamOpt>, ranked: List<String>) {
        _state.update { s ->
            val selected = if (s.selectionInit) {
                s.selected.filter { id -> all.any { it.id == id } }
            } else {
                ranked.take(MAX_DEFAULT)
            }
            s.copy(
                available = all, ranked = ranked, loadingTeams = false, selected = selected, selectionInit = true,
                advancing = s.advancing.coerceIn(2, all.size.coerceAtLeast(2)),
            )
        }
    }

    // ------------------------------------------------------------- option setters

    fun chooseFormat(f: GenFormat) = _state.update { clearOutput(it).copy(format = f) }

    fun toggleTeam(id: String) = _state.update { s ->
        val sel = if (id in s.selected) s.selected - id else s.selected + id
        s.copy(selected = sel, withdrawals = s.withdrawals.filterKeys { it in sel }, swissTotal = null)
    }

    fun selectAllTeams() = _state.update { s ->
        val ordered = s.ranked.filter { r -> s.available.any { it.id == r } } + s.available.map { it.id }.filter { it !in s.ranked }
        s.copy(selected = ordered, swissTotal = null)
    }

    fun clearTeams() = _state.update { it.copy(selected = emptyList(), withdrawals = emptyMap(), swissTotal = null) }

    /** Moves a team up (-1) or down (+1) in seed order. */
    fun moveTeam(id: String, delta: Int) = _state.update { s ->
        val i = s.selected.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in s.selected.indices) {
            s
        } else {
            s.copy(selected = s.selected.toMutableList().also { java.util.Collections.swap(it, i, j) })
        }
    }

    fun setLegs(l: Int) = _state.update { it.copy(legs = l) }
    fun setPolicy(p: String) = _state.update { it.copy(withdrawalPolicy = p) }
    fun setWithdrawal(team: String, afterRound: Int?) = _state.update { s ->
        s.copy(withdrawals = if (afterRound == null) s.withdrawals - team else s.withdrawals + (team to afterRound))
    }
    fun setStartDate(v: String) = _state.update { it.copy(startDate = v) }
    fun toggleMatchDay(d: Int) = _state.update { s ->
        s.copy(matchDays = if (d in s.matchDays) s.matchDays - d else s.matchDays + d)
    }
    fun setSwissTotal(v: Int) = _state.update { it.copy(swissTotal = v) }
    fun setThirdPlace(v: Boolean) = _state.update { it.copy(thirdPlace = v) }
    fun setBracketReset(v: Boolean) = _state.update { it.copy(bracketReset = v) }
    fun setAdvancing(v: Int) = _state.update { it.copy(advancing = v) }
    fun dismissError() = _state.update { it.copy(error = null) }
    fun dismissSaveUnavailable() = _state.update { it.copy(saveUnavailable = false) }
    fun dismissSaveError() = _state.update { it.copy(saveError = null) }
    fun dismissGeneratorUnavailable() = _state.update { it.copy(generatorUnavailable = false) }

    private fun clearOutput(s: GenUiState) = s.copy(
        result = null, error = null, results = emptyMap(), swissRounds = emptyList(), swissResults = emptyMap(),
        swissView = 1, savedCount = null, saveError = null, saveUnavailable = false, generatorUnavailable = false,
    )

    // ------------------------------------------------------------- requests

    /** The ids a knockout is built from: hybrid uses the top of the league table, others the chosen seed order. */
    private fun knockoutTeams(s: GenUiState): List<String> =
        if (s.format == GenFormat.HYBRID) s.ranked.take(s.advancing) else s.selected

    internal fun requestFor(
        s: GenUiState,
        results: Map<String, String> = s.results,
        teams: List<String> = knockoutTeams(s),
    ): GenRequest? {
        val date = s.startDate.takeIf { GenSchedule.parseDate(it) != null }
        val days = s.matchDays.sorted()
        return when (s.format) {
            GenFormat.ROUND_ROBIN -> GenRequest(
                GenApiFormat.ROUND_ROBIN, s.selected, legs = s.legs,
                withdrawalPolicy = s.withdrawalPolicy.takeIf { s.withdrawals.isNotEmpty() },
                withdrawals = s.withdrawals.map { Withdrawal(it.key, it.value) }.sortedBy { it.teamId },
                startDate = date, matchDays = days,
            )
            GenFormat.SWISS -> swissRequest(s, s.swissRounds.size + 1)
            GenFormat.SINGLE_ELIM, GenFormat.HYBRID -> GenRequest(
                GenApiFormat.SINGLE_ELIM, teams, thirdPlace = s.thirdPlace, results = results,
                startDate = date, matchDays = days,
            )
            GenFormat.DOUBLE_ELIM -> GenRequest(
                GenApiFormat.DOUBLE_ELIM, teams, bracketReset = s.bracketReset, results = results,
                startDate = date, matchDays = days,
            )
            null -> null
        }
    }

    private fun swissRequest(s: GenUiState, round: Int): GenRequest = GenRequest(
        GenApiFormat.SWISS, s.selected,
        totalRounds = s.swissTotalEffective,
        swissRound = round,
        history = swissHistory(s.swissRounds.take(round - 1), s.swissResults),
        startDate = s.startDate.takeIf { GenSchedule.parseDate(it) != null },
        matchDays = s.matchDays.sorted(),
    )

    private fun preflight(s: GenUiState, req: GenRequest): String? {
        if (GenSchedule.parseDate(s.startDate) == null) return str(R.string.fg_err_date)
        if (s.matchDays.isEmpty()) return str(R.string.fg_err_matchdays)
        if (!FixtureGenRules.teamCountOk(req.format, req.teams.size)) {
            val r = FixtureGenRules.teamRange(req.format)
            return str(R.string.fg_err_teams, r.first, r.last)
        }
        return FixtureGenRules.validate(req).takeIf { it.isNotEmpty() }?.let { str(R.string.fg_err_invalid) }
    }

    // ------------------------------------------------------------- generate

    /** Calls the generator for the current options and emits where to go next. */
    fun generate() {
        val s0 = _state.value
        if (s0.busy) return
        val fresh = s0.copy(swissRounds = emptyList(), swissResults = emptyMap(), results = emptyMap())
        val req = requestFor(fresh) ?: return
        val problem = preflight(s0, req)
        if (problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }
        _state.update {
            it.copy(
                busy = true, error = null, results = emptyMap(), swissRounds = emptyList(),
                swissResults = emptyMap(), savedCount = null,
            )
        }
        viewModelScope.launch {
            when (val r = safeCall { api.generate(req.toJson()) }) {
                is NetworkResult.Success -> {
                    val dto = AppJson.decodeFromJsonElement(GeneratedDto.serializer(), r.data)
                    val fmt = s0.format
                    _state.update {
                        it.copy(
                            busy = false,
                            result = if (fmt == GenFormat.SWISS) null else dto,
                            bracketTeams = req.teams,
                            swissView = 1,
                            swissRounds = if (fmt == GenFormat.SWISS) listOf(dto) else emptyList(),
                        )
                    }
                    _events.send(
                        when (fmt) {
                            GenFormat.SWISS -> GenNav.SWISS_ROUNDS
                            GenFormat.SINGLE_ELIM, GenFormat.HYBRID -> GenNav.BRACKET_SINGLE
                            GenFormat.DOUBLE_ELIM -> GenNav.BRACKET_DOUBLE
                            else -> GenNav.PREVIEW
                        },
                    )
                }
                is NetworkResult.Failure -> failGenerate(r.error)
            }
        }
    }

    private fun failGenerate(e: ApiError) {
        _state.update {
            if (e.isFeatureUnavailable()) {
                it.copy(busy = false, generatorUnavailable = true)
            } else {
                it.copy(busy = false, error = genErrorMessage(e, str(R.string.fg_err_no_pairing)))
            }
        }
    }

    fun setSwissResult(matchId: String, result: String) = _state.update { s ->
        // changing an earlier round's result invalidates every later round
        val owner = s.swissRounds.indexOfFirst { r -> r.pairings.any { it.matchId == matchId } }
        val keepRounds = if (owner >= 0) s.swissRounds.take(owner + 1) else s.swissRounds
        val dropped = s.swissRounds.drop(keepRounds.size).flatMap { r -> r.pairings.map { it.matchId } }.toSet()
        s.copy(
            swissResults = (s.swissResults - dropped) + (matchId to result),
            swissRounds = keepRounds,
            swissView = s.swissView.coerceAtMost(keepRounds.size).coerceAtLeast(1),
            savedCount = null,
        )
    }

    fun viewSwissRound(r: Int) = _state.update { it.copy(swissView = r) }

    fun generateNextSwissRound() {
        val s = _state.value
        if (s.busy || s.swissRounds.isEmpty()) return
        if (!swissRoundComplete(s.swissRounds.last(), s.swissResults)) return
        if (s.swissRounds.size >= s.swissTotalEffective) return
        val req = swissRequest(s, s.swissRounds.size + 1)
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = safeCall { api.generate(req.toJson()) }) {
                is NetworkResult.Success -> {
                    val dto = AppJson.decodeFromJsonElement(GeneratedDto.serializer(), r.data)
                    _state.update {
                        it.copy(busy = false, swissRounds = it.swissRounds + dto, swissView = it.swissRounds.size + 1, savedCount = null)
                    }
                }
                is NetworkResult.Failure -> failGenerate(r.error)
            }
        }
    }

    /** Tapping a team in a bracket match advances it; tapping the same team again clears the result. */
    fun pickWinner(matchId: String, team: String) {
        val s = _state.value
        if (s.busy) return
        val clearing = s.results[matchId] == team
        val next = if (clearing) s.results - matchId else s.results + (matchId to team)
        regenerateBracket(next, if (clearing) emptyMap() else mapOf(matchId to team))
    }

    fun resetBracketResults() = regenerateBracket(emptyMap(), emptyMap())

    private fun regenerateBracket(results: Map<String, String>, fallback: Map<String, String>) {
        val s = _state.value
        val teams = s.bracketTeams.ifEmpty { knockoutTeams(s) }
        val req = requestFor(s, results, teams) ?: return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            var out = safeCall { api.generate(req.toJson()) }
            var used = results
            if (out is NetworkResult.Failure && fallback != results) {
                // a changed result made later picks impossible: keep only the new pick
                val retry = safeCall { api.generate(req.copy(results = fallback).toJson()) }
                if (retry is NetworkResult.Success) {
                    out = retry
                    used = fallback
                }
            }
            when (val o = out) {
                is NetworkResult.Success -> {
                    val dto = AppJson.decodeFromJsonElement(GeneratedDto.serializer(), o.data)
                    _state.update { it.copy(busy = false, result = dto, results = used, savedCount = null) }
                }
                is NetworkResult.Failure -> failGenerate(o.error)
            }
        }
    }

    // ------------------------------------------------------------- save

    fun save() {
        val s = _state.value
        if (s.saving || s.format == null) return
        val req = if (s.format == GenFormat.SWISS) {
            swissRequest(s, s.swissView.coerceAtLeast(1))
        } else {
            requestFor(s, s.results, s.bracketTeams.ifEmpty { knockoutTeams(s) })
        } ?: return
        _state.update { it.copy(saving = true, saveError = null, saveUnavailable = false) }
        viewModelScope.launch {
            when (val r = safeCall { saveApi.save(leagueId, req.toJson(save = true)) }) {
                is NetworkResult.Success -> {
                    val dto = runCatching { AppJson.decodeFromJsonElement(FixtureSaveResultDto.serializer(), r.data) }.getOrNull()
                    _state.update { it.copy(saving = false, savedCount = dto?.created ?: 0) }
                }
                is NetworkResult.Failure -> _state.update {
                    if (r.error.isFeatureUnavailable()) {
                        it.copy(saving = false, saveUnavailable = true)
                    } else {
                        it.copy(saving = false, saveError = genErrorMessage(r.error, str(R.string.fg_err_no_pairing)))
                    }
                }
            }
        }
    }

    companion object {
        const val MAX_DEFAULT = 16

        fun defaultStartDate(today: LocalDate = LocalDate.now()): LocalDate {
            var d = today.plusDays(1)
            while (d.dayOfWeek != DayOfWeek.SATURDAY) d = d.plusDays(1)
            return d
        }

        fun factory(container: AppContainer, leagueId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { FixtureGenViewModel(container, leagueId) }
        }
    }
}
