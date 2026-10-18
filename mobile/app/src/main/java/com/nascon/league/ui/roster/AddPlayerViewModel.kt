package com.nascon.league.ui.roster

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.ConnState
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.data.rosteradd.rosterAddApi
import com.nascon.league.di.AppContainer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

sealed interface AddPlayerFailure {
    data object Offline : AddPlayerFailure
    data object AlreadyRegistered : AddPlayerFailure
    data object NoLeague : AddPlayerFailure
    data class Rule(val rejection: RosterRejection) : AddPlayerFailure
    data class Other(val message: String) : AddPlayerFailure
}

data class AddPlayerState(
    val form: AddPlayerForm = AddPlayerForm(),
    val submitted: Boolean = false,
    val submitting: Boolean = false,
    val failure: AddPlayerFailure? = null,
)

sealed interface AddPlayerEvent {
    data class Added(val name: String) : AddPlayerEvent
}

/** Maps a failed call to what the screen shows. Pure so it can be tested. */
fun addPlayerFailureFor(error: ApiError): AddPlayerFailure = when {
    error is ApiError.NoConnection || error is ApiError.ServerUnreachable -> AddPlayerFailure.Offline
    error is ApiError.Http && error.code == "PLAYER_ALREADY_REGISTERED" -> AddPlayerFailure.AlreadyRegistered
    error is ApiError.Http && hasRosterRejectionCard(error.code) ->
        AddPlayerFailure.Rule(RosterRejection(error.code, error.details))
    else -> AddPlayerFailure.Other(ErrorMapper.message(error))
}

class AddPlayerViewModel(
    private val container: AppContainer,
    private val clubId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(AddPlayerState())
    val state: StateFlow<AddPlayerState> = _state.asStateFlow()
    private val eventChannel = Channel<AddPlayerEvent>(Channel.BUFFERED)
    val events: Flow<AddPlayerEvent> = eventChannel.receiveAsFlow()

    // one request id per form attempt so a retry after a lost response stays idempotent
    private var requestId = UUID.randomUUID().toString()
    private var playerId = AddPlayerLogic.newPlayerId()

    fun edit(transform: (AddPlayerForm) -> AddPlayerForm) = _state.update {
        if (it.submitting) it else {
            requestId = UUID.randomUUID().toString()
            it.copy(form = transform(it.form), failure = null)
        }
    }

    fun clearFailure() = _state.update { it.copy(failure = null) }

    fun submit(leagueIdHint: String?, today: LocalDate = LocalDate.now()) {
        val cur = _state.value
        if (cur.submitting) return
        if (!AddPlayerLogic.isValid(cur.form)) {
            _state.update { it.copy(submitted = true) }
            return
        }
        if (container.connectivity.state.value == ConnState.OFFLINE) {
            _state.update { it.copy(submitted = true, failure = AddPlayerFailure.Offline) }
            return
        }
        val user = container.sessionRepository.session.value?.user
        val leagueId = leagueIdHint?.takeIf { it.isNotBlank() } ?: user?.leagueIds?.firstOrNull()
        if (leagueId.isNullOrBlank()) {
            _state.update { it.copy(submitted = true, failure = AddPlayerFailure.NoLeague) }
            return
        }
        val request = AddPlayerLogic.build(
            form = cur.form,
            leagueId = leagueId,
            requestedBy = requestedByFor(user?.userId, user?.email),
            reason = REASON,
            today = today,
            requestId = requestId,
            playerId = playerId,
        )
        _state.update { it.copy(submitted = true, submitting = true, failure = null) }
        viewModelScope.launch {
            when (val r = safeCall { container.rosterAddApi().addPlayer(clubId, request) }) {
                is NetworkResult.Success -> {
                    container.clubRepository.refresh(clubId)
                    _state.update { it.copy(submitting = false) }
                    eventChannel.send(AddPlayerEvent.Added(request.name))
                }
                is NetworkResult.Failure ->
                    _state.update { it.copy(submitting = false, failure = addPlayerFailureFor(r.error)) }
            }
        }
    }

    companion object {
        /** Stored as the audit reason; the form has no reason field in the design. */
        const val REASON = "Registered in the app"

        fun factory(container: AppContainer, clubId: String) = viewModelFactory {
            initializer { AddPlayerViewModel(container, clubId) }
        }
    }
}
