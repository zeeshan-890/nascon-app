package com.nascon.league.matchlive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.error.Part2Errors
import com.nascon.league.core.error.UiErrorAction
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.data.realtime.LiveMessage
import com.nascon.league.data.realtime.LiveState
import com.nascon.league.data.realtime.ResilientMatchStream
import com.nascon.league.deeplink.DeepLinkRouter
import com.nascon.league.deeplink.MatchScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** What the view model needs from the realtime layer. Lets tests drive events with a fake. */
interface MatchEventSource {
    val state: StateFlow<LiveState>
    fun events(matchId: String): Flow<LiveMessage>
}

fun ResilientMatchStream.asMatchEventSource(): MatchEventSource {
    val stream = this
    return object : MatchEventSource {
        override val state: StateFlow<LiveState> get() = stream.state
        override fun events(matchId: String): Flow<LiveMessage> = stream.stream(matchId)
    }
}

class MatchLiveViewModel(
    private val matchId: String,
    startSeconds: Int?,
    private val repo: MatchLiveRepository,
    private val source: MatchEventSource,
    private val cart: GuestCart,
    private val isGuest: () -> Boolean,
    private val currentUserName: () -> String = { "me" },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val localId: () -> String = { java.util.UUID.randomUUID().toString() },
) : ViewModel() {

    private val roomId = MatchLiveLogic.chatRoomIdFor(matchId)
    private val timeline = ChatTimeline()

    private val _ui = MutableStateFlow(
        MatchLiveUiState(
            matchId = matchId,
            chatRoomId = roomId,
            startSeconds = startSeconds,
            isGuest = isGuest(),
        ),
    )
    val uiState: StateFlow<MatchLiveUiState> = _ui

    private val oneShots = Channel<MatchLiveEvent>(Channel.BUFFERED)
    val events: Flow<MatchLiveEvent> = oneShots.receiveAsFlow()

    /** null = automatic (collect for LIVE screens only); true/false = the screen said so. */
    private var explicitActive: Boolean? = null
    private var networkOffline = false
    private var streamDown = false
    private var chatLoaded = false
    private var sending = 0

    private var fetchJob: Job? = null
    private var collectJob: Job? = null
    private var tickJob: Job? = null
    private var tickSettledFor: Long? = null
    private val lastAddAt = HashMap<String, Long>()

    init {
        viewModelScope.launch {
            cart.count.catch { }.collect { c -> _ui.update { it.copy(cartCount = c) } }
        }
        startLoad(foreground = true)
    }

    fun retry() {
        fetchJob?.cancel()
        startLoad(foreground = true)
    }

    fun setActive(active: Boolean) {
        explicitActive = active
        syncCollection()
        syncTicker()
    }

    fun onNetworkOffline(offline: Boolean) {
        val wasOffline = networkOffline
        networkOffline = offline
        _ui.update { it.copy(offline = it.connection == LiveState.OFFLINE || offline) }
        if (wasOffline && !offline) refetch()
    }

    fun sendChat(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val guest = isGuest()
        if (guest) {
            viewModelScope.launch {
                when (val r = repo.sendChat(roomId, body)) {
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
        timeline.addPending(lid, roomId, currentUserName(), body)
        sending++
        pushChat()
        viewModelScope.launch {
            when (val r = repo.sendChat(roomId, body)) {
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

    fun addToCart(item: MerchItem) {
        val now = nowMillis()
        val last = lastAddAt[item.variantId]
        if (last != null && now - last < DOUBLE_TAP_MS) return
        lastAddAt[item.variantId] = now
        viewModelScope.launch {
            cart.add(item.variantId, item.name, item.priceMinor, 1)
        }
    }

    // ---- loading ----

    private fun startLoad(foreground: Boolean) {
        if (_ui.value.snapshot == null) _ui.update { it.copy(phase = LoadPhase.LOADING, errorMessage = null) }
        fetchJob = viewModelScope.launch {
            fetchSnapshot(foreground)
            if (_ui.value.phase != LoadPhase.NOT_FOUND && !chatLoaded) loadChatHistory()
            syncCollection()
            syncTicker()
        }
    }

    private fun refetch() {
        if (fetchJob?.isActive == true) return
        fetchJob = viewModelScope.launch { fetchSnapshot(foreground = false) }
    }

    private suspend fun fetchSnapshot(foreground: Boolean) {
        when (val r = repo.snapshot(matchId)) {
            is NetworkResult.Success -> {
                val snap = r.data
                _ui.update {
                    it.copy(
                        phase = LoadPhase.CONTENT,
                        snapshot = snap,
                        screen = DeepLinkRouter.matchScreenFor(snap.status),
                        errorMessage = null,
                    )
                }
                syncTicker()
            }
            is NetworkResult.Failure -> {
                if (!foreground) return
                val action = Part2Errors.map(r.error)
                if (action is UiErrorAction.NotFound) {
                    _ui.update { it.copy(phase = LoadPhase.NOT_FOUND, errorMessage = null) }
                } else {
                    _ui.update { it.copy(phase = LoadPhase.ERROR, errorMessage = messageFor(action)) }
                }
            }
        }
    }

    private suspend fun loadChatHistory() {
        val r = repo.chatHistory(roomId, null)
        if (r is NetworkResult.Success) {
            timeline.mergeServer(r.data)
            chatLoaded = true
            pushChat()
        }
    }

    private fun pushChat() {
        val msgs = timeline.messages()
        _ui.update { it.copy(chat = msgs, chatSending = sending > 0) }
    }

    // ---- realtime ----

    private fun syncCollection() {
        val screen = _ui.value.screen
        val want = when (explicitActive) {
            true -> true
            false -> false
            null -> _ui.value.snapshot != null &&
                (screen == MatchScreen.LIVE || screen == MatchScreen.LIVE_SHELL)
        }
        if (!want) {
            collectJob?.cancel()
            collectJob = null
            return
        }
        if (collectJob?.isActive == true) return
        collectJob = viewModelScope.launch {
            launch {
                source.state.collect { onConnection(it) }
            }
            source.events(matchId).catch { }.collect { handle(it) }
        }
    }

    private fun onConnection(c: LiveState) {
        _ui.update { it.copy(connection = c, offline = c == LiveState.OFFLINE || networkOffline) }
        when (c) {
            LiveState.RECONNECTING, LiveState.OFFLINE -> streamDown = true
            LiveState.LIVE -> if (streamDown) {
                streamDown = false
                refetch()
            }
            LiveState.CONNECTING -> Unit
        }
    }

    private fun handle(msg: LiveMessage) {
        if (msg.type == CHAT_TYPE) {
            parseChat(msg)?.let {
                if (it.roomId == roomId) {
                    timeline.mergeServer(listOf(it))
                    pushChat()
                }
            }
            return
        }
        val cur = _ui.value.snapshot ?: return
        val applied = MatchLiveLogic.applyEvent(cur, msg)
        if (applied.snapshot != cur) {
            _ui.update {
                it.copy(
                    snapshot = applied.snapshot,
                    screen = DeepLinkRouter.matchScreenFor(applied.snapshot.status),
                )
            }
            syncTicker()
        }
        if (applied.needsRefetch) refetch()
    }

    private fun parseChat(msg: LiveMessage): ChatMessage? {
        val d = msg.data ?: return null
        val id = d.str("message_id") ?: return null
        val room = d.str("room_id") ?: return null
        val seq = d.long("sequence") ?: msg.sequence ?: return null
        val text = d.str("text") ?: return null
        return ChatMessage(
            roomId = room,
            messageId = id,
            sequence = seq,
            sender = d.str("sender") ?: "",
            text = text,
            flagged = (d["flagged"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    // ---- countdown ----

    private fun syncTicker() {
        val snap = _ui.value.snapshot
        val kickoff = MatchLiveLogic.parseIsoMillis(snap?.scheduledAtIso)
        val want = explicitActive == true && _ui.value.screen == MatchScreen.PREVIEW && kickoff != null
        if (!want) {
            tickJob?.cancel()
            tickJob = null
            return
        }
        if (tickJob?.isActive == true || tickSettledFor == kickoff) return
        tickJob = viewModelScope.launch {
            while (true) {
                val cd = MatchLiveLogic.countdown(nowMillis(), kickoff!!)
                _ui.update { it.copy(countdown = cd) }
                if (cd.finished) {
                    tickSettledFor = kickoff
                    refetch()
                    break
                }
                delay(1000)
            }
        }
    }

    // ---- errors ----

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
        const val CHAT_TYPE = "chat.message"
        const val DOUBLE_TAP_MS = 400L

        fun factory(container: com.nascon.league.di.AppContainer, matchId: String, startSeconds: Int?) = viewModelFactory {
            initializer {
                MatchLiveViewModel(
                    matchId,
                    startSeconds,
                    container.matchLiveRepository,
                    container.newMatchEventSource(),
                    container.guestCart,
                    { container.sessionRepository.isGuest },
                    { container.sessionRepository.session.value?.user?.email?.ifBlank { null } ?: "me" },
                )
            }
        }
    }
}
