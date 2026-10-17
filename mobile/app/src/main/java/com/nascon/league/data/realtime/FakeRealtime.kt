package com.nascon.league.data.realtime

import com.nascon.league.data.fake.FakeLeagueApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FakeRealtime(
    private val scope: CoroutineScope,
    private val fake: FakeLeagueApi,
    private val intervalMs: Long = 8000,
) : RealtimeClient {

    private val _state = MutableStateFlow(RealtimeState.DISCONNECTED)
    override val state: StateFlow<RealtimeState> = _state.asStateFlow()

    private val registry = SubscriptionRegistry()
    private val live = MutableSharedFlow<RealtimeMessage>(extraBufferCapacity = 16)
    private var ticker: Job? = null
    private val lock = Any()

    override fun subscribe(topic: String): Flow<RealtimeMessage> = flow {
        add(topic)
        try {
            if (topic == LIVE_TOPIC) emitAll(live) else awaitCancellation()
        } finally {
            remove(topic)
        }
    }

    private fun add(topic: String) = synchronized(lock) {
        registry.add(topic)
        _state.value = RealtimeState.CONNECTED
        if (topic == LIVE_TOPIC && ticker?.isActive != true) {
            ticker = scope.launch {
                while (isActive) {
                    delay(intervalMs)
                    fake.simulateLiveEvent()
                    live.tryEmit(RealtimeMessage("match.event_appended", LIVE_MATCH, null))
                }
            }
        }
    }

    private fun remove(topic: String) = synchronized(lock) {
        registry.remove(topic)
        if (!registry.has(LIVE_TOPIC)) {
            ticker?.cancel()
            ticker = null
        }
        if (registry.isEmpty()) _state.value = RealtimeState.DISCONNECTED
    }

    private companion object {
        const val LIVE_MATCH = "demo-m-live"
        const val LIVE_TOPIC = "match:$LIVE_MATCH"
    }
}
