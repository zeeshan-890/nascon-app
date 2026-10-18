package com.nascon.league.ui.roster

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.ConnState
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.di.AppContainer
import com.nascon.league.ui.club.ClubProfileState
import com.nascon.league.ui.club.ClubRefresh
import com.nascon.league.ui.failure.RateLimitRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------- pure helpers

const val ROSTER_REASON_MAX = 200

enum class RosterStep { NONE, ACTIONS, FORM, CONFIRM }

/** Editable form values; mirrors RosterFormState but is a plain holder so reducers stay trivial. */
data class RosterDraft(
    val type: MoveType = MoveType.TRANSFER,
    val toSquadId: String? = null,
    val effectiveDate: LocalDate = RosterLogic.defaultEffectiveDate(),
    val loanEndDate: LocalDate? = null,
    val reason: String = "",
) {
    fun toForm(): RosterFormState = RosterFormState(
        type = type,
        toSquadId = if (type == MoveType.RELEASE) null else toSquadId,
        effectiveDate = effectiveDate,
        loanEndDate = if (type == MoveType.LOAN) loanEndDate else null,
        reason = reason,
    )
}

/** Pure reducers for the form (no Android types, JVM-testable). */
object RosterDraftReducer {
    fun start(type: MoveType, today: LocalDate = RosterLogic.defaultEffectiveDate()): RosterDraft =
        RosterDraft(type = type, effectiveDate = today)

    fun withType(d: RosterDraft, type: MoveType): RosterDraft = d.copy(
        type = type,
        toSquadId = if (type == MoveType.RELEASE) null else d.toSquadId,
        loanEndDate = if (type == MoveType.LOAN) d.loanEndDate else null,
    )

    fun withToSquad(d: RosterDraft, squadId: String?): RosterDraft = d.copy(toSquadId = squadId)

    fun withEffectiveDate(d: RosterDraft, date: LocalDate): RosterDraft = d.copy(effectiveDate = date)

    fun withLoanEnd(d: RosterDraft, date: LocalDate?): RosterDraft = d.copy(loanEndDate = date)

    fun withReason(d: RosterDraft, text: String): RosterDraft = d.copy(reason = text.take(ROSTER_REASON_MAX))
}

enum class SavedKind { TRANSFER, LOAN, RELEASE, OFFLINE }

/** Which snackbar to show after a move is queued. */
fun savedKind(type: MoveType, online: Boolean): SavedKind = when {
    !online -> SavedKind.OFFLINE
    type == MoveType.TRANSFER -> SavedKind.TRANSFER
    type == MoveType.LOAN -> SavedKind.LOAN
    else -> SavedKind.RELEASE
}

/** "20 Oct 2026". */
fun formatRosterDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(date)

/** Material3 DatePicker reports UTC midnight millis for the picked day. */
fun pickerMillisToDate(millis: Long, zone: ZoneId = ZoneOffset.UTC): LocalDate =
    java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

fun dateToPickerMillis(date: LocalDate, zone: ZoneId = ZoneOffset.UTC): Long =
    date.atStartOfDay(zone).toInstant().toEpochMilli()

/** The id recorded as requested_by: user id, falling back to email. */
fun requestedByFor(userId: String?, email: String?): String =
    userId?.takeIf { it.isNotBlank() } ?: email.orEmpty()

// ---------------------------------------------------------------- view model

data class RosterFlowState(
    val playerId: String? = null,
    val step: RosterStep = RosterStep.NONE,
    val draft: RosterDraft? = null,
    val submitting: Boolean = false,
    /** A rule rejection to explain in the form (E11-E14); cleared when the form is edited. */
    val rejection: RosterRejection? = null,
)

data class RosterState(
    val data: ClubProfileState = ClubProfileState(),
    val flow: RosterFlowState = RosterFlowState(),
)

sealed interface RosterEvent {
    data class Saved(val kind: SavedKind) : RosterEvent
    data object Failed : RosterEvent
}

class RosterViewModel(
    private val container: AppContainer,
    val clubId: String,
) : ViewModel() {
    private val refreshInfo = MutableStateFlow(ClubRefresh(refreshing = true))
    private var inFlight = false
    private val rateLimit = RateLimitRetry(viewModelScope)
    private val cacheChecked = MutableStateFlow(false)
    private val flowState = MutableStateFlow(RosterFlowState())
    private val eventChannel = Channel<RosterEvent>(Channel.BUFFERED)
    val events: Flow<RosterEvent> = eventChannel.receiveAsFlow()

    private val data: Flow<ClubProfileState> = combine(
        container.clubRepository.observe(clubId).map { it.also { cacheChecked.value = true } },
        refreshInfo,
        container.connectivity.state,
        cacheChecked,
    ) { cached, info, conn, checked ->
        ClubProfileState(
            cacheChecked = checked,
            club = cached?.data,
            updatedAt = cached?.updatedAt,
            refresh = info,
            offline = conn == ConnState.OFFLINE,
        )
    }

    val state: StateFlow<RosterState> = combine(data, flowState) { d, f -> RosterState(d, f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RosterState())

    init {
        refresh()
        viewModelScope.launch { container.refreshSignal.collect { refresh() } }
    }

    fun refresh() {
        if (inFlight) return
        if (container.connectivity.state.value == ConnState.OFFLINE) {
            refreshInfo.value = ClubRefresh(false, ApiError.NoConnection)
            return
        }
        inFlight = true
        refreshInfo.value = ClubRefresh(true, null)
        viewModelScope.launch {
            val r = container.clubRepository.refresh(clubId)
            inFlight = false
            val err = (r as? NetworkResult.Failure)?.error
            refreshInfo.value = ClubRefresh(false, err)
            rateLimit.consider("roster", err) { refresh() }
        }
    }

    fun openPlayer(playerId: String) {
        flowState.value = RosterFlowState(playerId = playerId, step = RosterStep.ACTIONS)
    }

    fun chooseType(type: MoveType) = flowState.update {
        if (it.playerId == null) it else it.copy(step = RosterStep.FORM, draft = RosterDraftReducer.start(type))
    }

    fun edit(transform: (RosterDraft) -> RosterDraft) = flowState.update {
        val d = it.draft
        if (d == null || it.submitting) it else it.copy(draft = transform(d), rejection = null)
    }

    /** Shows the rejection card in the open form (returns to the form step). Ignored for codes without a card. */
    fun showRejection(code: String, details: kotlinx.serialization.json.JsonObject? = null) = flowState.update {
        if (it.playerId == null || it.draft == null || !hasRosterRejectionCard(code)) it
        else it.copy(step = RosterStep.FORM, submitting = false, rejection = RosterRejection(code, details))
    }

    fun dismiss() {
        if (flowState.value.submitting) return
        flowState.value = RosterFlowState()
    }

    fun backToActions() = flowState.update {
        if (it.submitting) it else it.copy(step = RosterStep.ACTIONS, draft = null)
    }

    fun requestConfirm() {
        val st = state.value
        val player = st.data.club?.roster?.firstOrNull { it.playerId == st.flow.playerId } ?: return
        val draft = st.flow.draft ?: return
        if (!RosterLogic.isValid(draft.toForm(), player.squadId)) return
        flowState.update { it.copy(step = RosterStep.CONFIRM) }
    }

    fun cancelConfirm() = flowState.update {
        if (it.submitting) it else it.copy(step = RosterStep.FORM)
    }

    fun confirm() {
        val st = state.value
        val club = st.data.club ?: return
        val flow = st.flow
        if (flow.submitting || flow.step != RosterStep.CONFIRM) return
        val player = club.roster.firstOrNull { it.playerId == flow.playerId } ?: return
        val draft = flow.draft ?: return
        val form = draft.toForm()
        if (!RosterLogic.isValid(form, player.squadId)) {
            flowState.update { it.copy(step = RosterStep.FORM) }
            return
        }
        val online = container.connectivity.state.value != ConnState.OFFLINE
        val user = container.sessionRepository.session.value?.user
        val requestedBy = requestedByFor(user?.userId, user?.email)
        val leagueId = player.leagueId ?: user?.leagueIds?.firstOrNull().orEmpty()
        flowState.update { it.copy(submitting = true) }
        viewModelScope.launch {
            try {
                val toName = club.squads.firstOrNull { it.squadId == form.toSquadId }?.name
                val fields = RosterLogic.buildFields(leagueId, player.playerId, player.squadId.orEmpty(), form, requestedBy)
                val label = RosterLogic.queueLabel(form.type, player.name, toName)
                container.syncRepository.enqueueRosterTransfer(
                    player.playerId, fields, label, player.auditVersion.toInt(),
                )
                flowState.value = RosterFlowState()
                eventChannel.send(RosterEvent.Saved(savedKind(form.type, online)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flowState.update { it.copy(submitting = false, step = RosterStep.FORM) }
                eventChannel.send(RosterEvent.Failed)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, clubId: String) = viewModelFactory {
            initializer { RosterViewModel(container, clubId) }
        }
    }
}
