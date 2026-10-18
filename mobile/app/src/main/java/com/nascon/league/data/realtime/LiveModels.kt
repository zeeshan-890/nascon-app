package com.nascon.league.data.realtime

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

enum class LiveState { CONNECTING, LIVE, RECONNECTING, OFFLINE }

enum class TransportKind { WEBSOCKET, SSE, POLLING }

data class LiveMessage(
    val id: String?,
    val sequence: Long?,
    val timestampMs: Long?,
    val type: String,
    val matchId: String?,
    val data: JsonObject?,
)

sealed interface TransportEvent {
    data object Opened : TransportEvent
    data class Message(val message: LiveMessage) : TransportEvent
}

/**
 * connect() returns a COLD flow. It emits Opened once connected, then Message events.
 * It completes normally or throws when the connection drops. Cancelling collection closes
 * the connection.
 */
interface MatchTransport {
    val kind: TransportKind
    fun connect(matchId: String): Flow<TransportEvent>
}
