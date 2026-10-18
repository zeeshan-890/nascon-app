package com.nascon.league.ui.scoring

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.network.ConnState
import com.nascon.league.data.api.dto.MatchDetailDto
import com.nascon.league.data.api.dto.SportConfigDto
import com.nascon.league.di.AppContainer
import com.nascon.league.ui.match.PendingInput
import com.nascon.league.ui.match.TimelineItem
import com.nascon.league.ui.match.buildTimeline
import com.nascon.league.ui.match.pendingEventsFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ---------------------------------------------------------------- pure helpers (JVM-testable)

/** What the scoring surface should render. */
enum class ScoringSurface { NONE, START, SCORE, NOTICE }

/**
 * Maps permission + match status mode to the visible surface.
 * Embedded (match detail): nothing for read-only. Full screen: read-only notice instead.
 */
fun surfaceFor(showPanel: Boolean, mode: ControlsMode, embedded: Boolean): ScoringSurface = when {
    !showPanel -> if (embedded) ScoringSurface.NONE else ScoringSurface.NOTICE
    mode == ControlsMode.START -> ScoringSurface.START
    mode == ControlsMode.SCORE -> ScoringSurface.SCORE
    else -> if (embedded) ScoringSurface.NONE else ScoringSurface.NOTICE
}

enum class ActionKind { SCORE, STATE, UNDO }

enum class ScoringMessage { SCORE_ADDED, SCORE_SAVED_OFFLINE, CHANGE_SAVED, CHANGE_SAVED_OFFLINE, FAILED }

/** Snackbar selection: ordinary score taps use the score wording; other actions the generic one. */
fun messageFor(kind: ActionKind, offline: Boolean): ScoringMessage = when (kind) {
    ActionKind.SCORE -> if (offline) ScoringMessage.SCORE_SAVED_OFFLINE else ScoringMessage.SCORE_ADDED
    ActionKind.STATE, ActionKind.UNDO ->
        if (offline) ScoringMessage.CHANGE_SAVED_OFFLINE else ScoringMessage.CHANGE_SAVED
}

enum class ReasonError { EMPTY, TOO_LONG }

fun reasonErrorOf(check: ReasonCheck): ReasonError? = when (check) {
    is ReasonCheck.Ok -> null
    is ReasonCheck.Empty -> ReasonError.EMPTY
    is ReasonCheck.TooLong -> ReasonError.TOO_LONG
}

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun eventIdOf(payloadJson: String): String? = try {
    ((lenientJson.parseToJsonElement(payloadJson) as? JsonObject)?.get("event_id") as? JsonPrimitive)?.contentOrNull
} catch (_: Exception) {
    null
}

/** Drops queued actions whose event_id already arrived from the server (realtime/refresh before ack). */
fun dedupePendingByEventId(pending: List<PendingInput>, serverEventIds: Set<String>): List<PendingInput> =
    if (serverEventIds.isEmpty()) pending else pending.filter { p ->
        val id = eventIdOf(p.payloadJson)
        id == null || id !in serverEventIds
    }

// ---------------------------------------------------------------- state

data class ScoringUiState(
    val loaded: Boolean = false,
    val match: MatchDetailDto? = null,
    val homeName: String? = null,
    val awayName: String? = null,
    val showPanel: Boolean = false,
    val mode: ControlsMode = ControlsMode.READ_ONLY,
    val offline: Boolean = false,
    val homeScore: Int = 0,
    val awayScore: Int = 0,
    val pendingCount: Int = 0,
    val buttons: List<ScoreButton> = emptyList(),
    val disciplineOptions: List<Int> = emptyList(),
    val tiebreakAvailable: Boolean = false,
    val supportsDiscipline: Boolean = false,
    /** Level score, no draws allowed, no tie-break winner yet: ending the match must be blocked. */
    val tiebreakRequired: Boolean = false,
    val showTiebreak: Boolean = false,
    val showDiscipline: Boolean = false,
    val showTieBlocked: Boolean = false,
    val timeline: List<TimelineItem> = emptyList(),
    /** Server event ids that may be undone right now. */
    val undoableEventIds: Set<String> = emptySet(),
    val showEndConfirm: Boolean = false,
    val undoEventId: String? = null,
    val reasonError: ReasonError? = null,
) {
    val hasPending: Boolean get() = pendingCount > 0
}

private const val K_END = "f8_end_confirm"
private const val K_UNDO = "f8_undo_id"
private const val K_ERROR = "f8_reason_error"
private const val K_TIEBREAK = "sp_tiebreak_sheet"
private const val K_DISCIPLINE = "sp_discipline_sheet"
private const val K_TIE_BLOCKED = "sp_tie_blocked"

private data class Core(
    val match: MatchDetailDto?,
    val homeName: String?,
    val awayName: String?,
    val config: SportConfigDto?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ScoringViewModel(
    private val container: AppContainer,
    val matchId: String,
    private val handle: SavedStateHandle,
) : ViewModel() {

    private val debouncer = TapDebouncer()
    private val messages = Channel<ScoringMessage>(Channel.BUFFERED)
    val messageFlow: Flow<ScoringMessage> = messages.receiveAsFlow()

    private val cache = container.matchRepository.observe(matchId)

    private val core: Flow<Core> = cache.flatMapLatest { c ->
        val m = c?.data
        val league = m?.leagueId
        val fixtures = if (league == null) flowOf(null) else container.fixturesRepository.observe(league)
        val sport = if (m == null || m.sportId.isBlank()) flowOf(null) else container.sportRepository.observe(m.sportId)
        combine(fixtures, sport) { f, s ->
            val fx = f?.data?.firstOrNull { it.matchId == matchId }
            Core(
                m,
                fx?.homeName?.takeIf { it.isNotBlank() },
                fx?.awayName?.takeIf { it.isNotBlank() },
                s?.data,
            )
        }
    }

    private val dialogs: Flow<Dialogs> = combine(
        handle.getStateFlow(K_END, false),
        handle.getStateFlow<String?>(K_UNDO, null),
        handle.getStateFlow<String?>(K_ERROR, null),
    ) { end, id, err -> Dialogs(end, id, err?.let { runCatching { ReasonError.valueOf(it) }.getOrNull() }) }

    private data class Dialogs(val end: Boolean, val undoId: String?, val error: ReasonError?)

    private val sheets: Flow<Sheets> = combine(
        handle.getStateFlow(K_TIEBREAK, false),
        handle.getStateFlow(K_DISCIPLINE, false),
        handle.getStateFlow(K_TIE_BLOCKED, false),
    ) { tb, di, blocked -> Sheets(tb, di, blocked) }

    private data class Sheets(val tiebreak: Boolean, val discipline: Boolean, val tieBlocked: Boolean)

    val state: StateFlow<ScoringUiState> = combine(
        core,
        container.pendingRepository.observePendingScoreEvents(matchId),
        container.sessionRepository.session,
        container.connectivity.state,
        dialogs,
        sheets,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val c = values[0] as Core
        @Suppress("UNCHECKED_CAST")
        val pendingEntities = values[1] as List<com.nascon.league.core.db.PendingActionEntity>
        val session = values[2] as com.nascon.league.domain.model.Session?
        val conn = values[3] as ConnState
        val d = values[4] as Dialogs
        val sh = values[5] as Sheets
        val m = c.match
            ?: return@combine ScoringUiState(loaded = true, offline = conn == ConnState.OFFLINE)
        val showPanel = ScoringLogic.canShowPanel(session, m.leagueId)
        val mode = ScoringLogic.controlsMode(m.status)
        val opt = ScoringLogic.optimisticScores(
            m.home.teamId, m.away.teamId, m.home.score, m.away.score, pendingEntities, c.config, m.events,
        )
        val teamName: (String?) -> String? = { id ->
            when (id) {
                null -> null
                m.home.teamId -> c.homeName
                m.away.teamId -> c.awayName
                else -> null
            }
        }
        val inputs = pendingEntities.map {
            PendingInput(it.actionId, it.type, it.status, it.ownerUserId, it.entityKey, it.payloadJson, it.createdAt)
        }
        val deduped = dedupePendingByEventId(inputs, m.events.map { it.eventId }.toSet())
        val pendingEvents = pendingEventsFor(matchId, session?.user?.userId, deduped, teamName)
        val canAct = showPanel && mode != ControlsMode.READ_ONLY
        ScoringUiState(
            loaded = true,
            match = m,
            homeName = c.homeName,
            awayName = c.awayName,
            showPanel = showPanel,
            mode = mode,
            offline = conn == ConnState.OFFLINE,
            homeScore = opt.home,
            awayScore = opt.away,
            pendingCount = opt.pendingCount,
            buttons = ScoringLogic.scoreButtons(c.config),
            disciplineOptions = if (ScoringLogic.supportsDiscipline(c.config)) ScoringLogic.disciplineOptions(c.config) else emptyList(),
            tiebreakAvailable = ScoringLogic.tiebreakAvailable(c.config, opt.home, opt.away),
            supportsDiscipline = ScoringLogic.supportsDiscipline(c.config),
            tiebreakRequired = ScoringLogic.tiebreakRequired(
                c.config, opt.home, opt.away, ScoringLogic.tiebreakRecorded(m.events, pendingEntities),
            ),
            showTiebreak = sh.tiebreak && canAct,
            showDiscipline = sh.discipline && canAct,
            showTieBlocked = sh.tieBlocked && canAct,
            timeline = buildTimeline(m.events, pendingEvents, teamName),
            undoableEventIds = if (!canAct) emptySet()
            else m.events.filter { ScoringLogic.canUndo(it, m.events) }.map { it.eventId }.toSet(),
            showEndConfirm = d.end,
            undoEventId = d.undoId,
            reasonError = d.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScoringUiState())

    init {
        // A queued match end the server refused because the score was level and draws are not allowed.
        viewModelScope.launch {
            var seen: Set<String>? = null
            container.syncRepository.observeResults().collect { list ->
                val prior = seen
                seen = list.map { it.actionId }.toSet()
                if (prior == null) return@collect
                val hit = list.any {
                    it.actionId !in prior && it.status == com.nascon.league.core.db.SyncResultStatus.REJECTED &&
                        ScoringLogic.isTieNotAllowed(it.reasonCode)
                }
                if (hit && state.value.mode == ControlsMode.SCORE) {
                    handle[K_TIE_BLOCKED] = true
                }
            }
        }
        // Make sure the sport config exists locally even if the user never opened another screen.
        viewModelScope.launch {
            val sportId = cache.map { it?.data?.sportId.orEmpty() }.filter { it.isNotBlank() }.first()
            if (container.sportRepository.observe(sportId).first() == null &&
                container.connectivity.state.value != ConnState.OFFLINE
            ) {
                container.sportRepository.refresh(sportId)
            }
        }
    }

    // ---- actions

    fun onScore(teamId: String, teamName: String?, button: ScoreButton) {
        if (!canAct()) return
        if (!debouncer.allow("score:$teamId:${button.eventKey}")) return
        enqueue(
            "score", ScoringLogic.scorePayload(teamId, button.eventKey),
            ScoringLogic.label("score", teamName, button.eventKey), ActionKind.SCORE,
        )
    }

    fun onDiscipline(teamId: String, teamName: String?, points: Int) {
        if (!canAct()) return
        if (!debouncer.allow("discipline:$teamId:$points")) return
        enqueue(
            "discipline", ScoringLogic.disciplinePayload(teamId, points),
            ScoringLogic.label("discipline", teamName, null), ActionKind.SCORE,
        )
    }

    fun onTiebreak(teamId: String, teamName: String?) {
        if (!canAct()) return
        if (!debouncer.allow("tiebreak:$teamId")) return
        enqueue(
            "tiebreak_winner", ScoringLogic.tiebreakPayload(teamId),
            ScoringLogic.label("tiebreak_winner", teamName, null), ActionKind.SCORE,
        )
    }

    fun onStart() {
        if (!canAct() || !debouncer.allow("state:start")) return
        enqueue("match_start", ScoringLogic.emptyPayload(), ScoringLogic.label("match_start", null, null), ActionKind.STATE)
    }

    fun requestEnd() {
        if (!canAct()) return
        if (state.value.tiebreakRequired) handle[K_TIE_BLOCKED] = true else handle[K_END] = true
    }

    fun dismissTieBlocked() {
        handle[K_TIE_BLOCKED] = false
    }

    /** Primary action of the tie-not-allowed screen: close it and open the tie-break sheet. */
    fun openTiebreakFromBlocked() {
        handle[K_TIE_BLOCKED] = false
        handle[K_TIEBREAK] = true
    }

    fun openTiebreak() {
        if (canAct()) handle[K_TIEBREAK] = true
    }

    fun dismissTiebreak() {
        handle[K_TIEBREAK] = false
    }

    fun openDiscipline() {
        if (canAct()) handle[K_DISCIPLINE] = true
    }

    fun dismissDiscipline() {
        handle[K_DISCIPLINE] = false
    }

    fun confirmTiebreak(teamId: String, teamName: String?) {
        handle[K_TIEBREAK] = false
        onTiebreak(teamId, teamName)
    }

    fun confirmDiscipline(teamId: String, teamName: String?, points: Int) {
        handle[K_DISCIPLINE] = false
        onDiscipline(teamId, teamName, points)
    }

    fun dismissEnd() {
        handle[K_END] = false
    }

    fun confirmEnd() {
        handle[K_END] = false
        if (!canAct() || !debouncer.allow("state:end")) return
        if (state.value.tiebreakRequired) {
            handle[K_TIE_BLOCKED] = true
            return
        }
        enqueue("match_end", ScoringLogic.emptyPayload(), ScoringLogic.label("match_end", null, null), ActionKind.STATE)
    }

    fun requestUndo(eventId: String) {
        if (eventId !in state.value.undoableEventIds) return
        handle[K_UNDO] = eventId
        handle[K_ERROR] = null
    }

    fun onReasonChange(text: String) {
        handle[K_ERROR] = reasonErrorOf(ScoringLogic.validateReason(text))
            ?.takeIf { it == ReasonError.TOO_LONG }?.name
    }

    fun dismissUndo() {
        handle[K_UNDO] = null
        handle[K_ERROR] = null
    }

    fun confirmUndo(reason: String) {
        val id = handle.get<String?>(K_UNDO) ?: return
        when (val check = ScoringLogic.validateReason(reason)) {
            is ReasonCheck.Ok -> {
                dismissUndo()
                if (!canAct() || !debouncer.allow("undo:$id")) return
                enqueue(
                    "compensation", ScoringLogic.compensationPayload(id, check.reason),
                    ScoringLogic.label("compensation", null, null), ActionKind.UNDO,
                )
            }
            else -> handle[K_ERROR] = reasonErrorOf(check)?.name
        }
    }

    private fun canAct(): Boolean {
        val s = state.value
        return s.showPanel && s.mode != ControlsMode.READ_ONLY
    }

    private fun enqueue(type: String, payload: JsonObject, label: String, kind: ActionKind) {
        val offline = container.connectivity.state.value == ConnState.OFFLINE
        viewModelScope.launch {
            try {
                container.syncRepository.enqueueScoreEvent(matchId, type, payload, label)
                messages.trySend(messageFor(kind, offline))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                messages.trySend(ScoringMessage.FAILED)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, matchId: String) = viewModelFactory {
            initializer { ScoringViewModel(container, matchId, createSavedStateHandle()) }
        }
    }
}
