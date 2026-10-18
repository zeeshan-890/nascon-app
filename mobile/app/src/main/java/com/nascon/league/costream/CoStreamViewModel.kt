package com.nascon.league.costream

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.error.Part2Errors
import com.nascon.league.core.error.UiErrorAction
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.di.AppContainer
import com.nascon.league.matchlive.ChatTimeline
import com.nascon.league.matchlive.LoadPhase
import com.nascon.league.matchlive.MatchLiveEvent
import com.nascon.league.matchlive.MatchLiveRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CoStreamViewModel(
    private val sessionId: String,
    private val repo: CoStreamRepository,
    private val chatRepo: MatchLiveRepository,
    private val isGuest: () -> Boolean,
    private val currentUserName: () -> String = { "me" },
    private val pollMillis: Long = 2000,
    private val localId: () -> String = { java.util.UUID.randomUUID().toString() },
) : ViewModel() {

    private var roomId = "chat-$sessionId"
    private val timeline = ChatTimeline()

    private val _ui = MutableStateFlow(
        CoStreamUiState(sessionId = sessionId, chatRoomId = roomId, isGuest = isGuest()),
    )
    val uiState: StateFlow<CoStreamUiState> = _ui

    private val oneShots = Channel<MatchLiveEvent>(Channel.BUFFERED)
    val events: Flow<MatchLiveEvent> = oneShots.receiveAsFlow()

    private var active = true
    private var networkOffline = false
    private var pollFailed = false
    private var sending = 0
    private var loadJob: Job? = null
    private var pollJob: Job? = null

    init {
        startLoad()
    }

    fun retry() {
        loadJob?.cancel()
        pollJob?.cancel()
        pollJob = null
        startLoad()
    }

    fun setActive(active: Boolean) {
        this.active = active
        if (!active) {
            pollJob?.cancel()
            pollJob = null
        } else if (loadJob?.isActive != true) {
            startPolling(immediate = true)
        }
    }

    fun onNetworkOffline(offline: Boolean) {
        networkOffline = offline
        pushOffline()
    }

    fun sendChat(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val room = roomId
        if (isGuest()) {
            viewModelScope.launch {
                when (val r = chatRepo.sendChat(room, body)) {
                    is NetworkResult.Success -> {
                        timeline.mergeServer(listOf(r.data))
                        pushChat()
                    }
                    is NetworkResult.Failure -> reportSendFailure(r.error)
                }
            }
            return
        }
        val lid = localId()
        timeline.addPending(lid, room, currentUserName(), body)
        sending++
        pushChat()
        viewModelScope.launch {
            when (val r = chatRepo.sendChat(room, body)) {
                is NetworkResult.Success -> timeline.confirm(lid, r.data)
                is NetworkResult.Failure -> {
                    timeline.fail(lid)
                    reportSendFailure(r.error)
                }
            }
            sending--
            pushChat()
        }
    }

    // ---- loading and polling ----

    private fun startLoad() {
        if (_ui.value.session == null) _ui.update { it.copy(phase = LoadPhase.LOADING, errorMessage = null) }
        loadJob = viewModelScope.launch {
            fetchSession(foreground = true)
            if (_ui.value.phase != LoadPhase.NOT_FOUND) fetchChat()
            if (active) startPolling(immediate = false)
        }
    }

    private fun startPolling(immediate: Boolean) {
        val s = _ui.value
        if (s.ended || s.phase == LoadPhase.NOT_FOUND) return
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            var first = immediate
            while (true) {
                if (!first) delay(pollMillis)
                first = false
                fetchSession(foreground = false)
                if (_ui.value.ended || _ui.value.phase == LoadPhase.NOT_FOUND) break
                fetchChat()
            }
        }
    }

    private suspend fun fetchSession(foreground: Boolean) {
        when (val r = repo.session(sessionId)) {
            is NetworkResult.Success -> {
                val next = r.data
                pollFailed = false
                roomId = next.chatRoomId
                val prevSession = _ui.value.session
                val banner = CoStreamLogic.handoffBanner(prevSession, next)
                val ended = CoStreamLogic.isEnded(next)
                _ui.update {
                    it.copy(
                        phase = LoadPhase.CONTENT,
                        session = next,
                        chatRoomId = next.chatRoomId,
                        tiles = CoStreamLogic.tilesFor(next.layout, next.participants),
                        banner = banner,
                        ended = ended,
                        errorMessage = null,
                        offline = networkOffline,
                    )
                }
            }
            is NetworkResult.Failure -> {
                val stale = _ui.value.session != null
                if (stale) {
                    pollFailed = true
                    pushOffline()
                    if (foreground) _ui.update { it.copy(phase = LoadPhase.ERROR, errorMessage = messageFor(Part2Errors.map(r.error))) }
                    return
                }
                val action = Part2Errors.map(r.error)
                if (action is UiErrorAction.NotFound) {
                    _ui.update { it.copy(phase = LoadPhase.NOT_FOUND, errorMessage = null) }
                } else {
                    _ui.update { it.copy(phase = LoadPhase.ERROR, errorMessage = messageFor(action)) }
                }
            }
        }
    }

    private suspend fun fetchChat() {
        val last = timeline.messages().filter { !it.pending }.maxOfOrNull { it.sequence }
        val r = chatRepo.chatHistory(roomId, last)
        if (r is NetworkResult.Success) {
            timeline.mergeServer(r.data)
            pushChat()
        }
    }

    private fun pushChat() {
        val msgs = timeline.messages()
        _ui.update { it.copy(chat = msgs, chatSending = sending > 0) }
    }

    private fun pushOffline() {
        _ui.update { it.copy(offline = networkOffline || (pollFailed && it.session != null)) }
    }

    private suspend fun reportSendFailure(error: ApiError) {
        when (val action = Part2Errors.map(error)) {
            UiErrorAction.OpenSignInSheet -> oneShots.send(MatchLiveEvent.OpenSignInSheet)
            else -> oneShots.send(MatchLiveEvent.ShowMessage(messageFor(action)))
        }
    }

    private fun messageFor(action: UiErrorAction): String = when (action) {
        is UiErrorAction.Inline -> action.message
        is UiErrorAction.Generic -> action.message
        is UiErrorAction.Backoff -> action.message
        is UiErrorAction.FieldErrors -> action.message
        UiErrorAction.OpenSignInSheet, UiErrorAction.GoGuestAndOfferSignIn -> Part2Errors.GUEST_LOCK_COPY
        UiErrorAction.NotFound -> "We could not find that."
        is UiErrorAction.OutOfStock -> "That item is out of stock."
    }

    companion object {
        fun factory(container: AppContainer, sessionId: String) = viewModelFactory {
            initializer {
                CoStreamViewModel(
                    sessionId,
                    container.coStreamRepository,
                    container.matchLiveRepository,
                    { container.sessionRepository.isGuest },
                    { container.sessionRepository.session.value?.user?.email?.ifBlank { null } ?: "me" },
                )
            }
        }
    }
}
