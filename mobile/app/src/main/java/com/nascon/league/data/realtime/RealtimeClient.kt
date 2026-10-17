package com.nascon.league.data.realtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

enum class RealtimeState { CONNECTED, CONNECTING, DISCONNECTED }

data class RealtimeMessage(val type: String, val matchId: String?, val data: JsonObject?)

interface RealtimeClient {
    val state: StateFlow<RealtimeState>
    fun subscribe(topic: String): Flow<RealtimeMessage>
}
