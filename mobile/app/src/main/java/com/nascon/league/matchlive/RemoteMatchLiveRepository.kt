package com.nascon.league.matchlive

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Backend-backed [MatchLiveRepository]. Guests read through the unauthenticated score route. */
class RemoteMatchLiveRepository(
    private val api: () -> MatchLiveApi,
    private val tokenProvider: () -> String?,
) : MatchLiveRepository {

    override suspend fun snapshot(matchId: String): NetworkResult<MatchSnapshot> {
        val token = tokenProvider()
        val raw: NetworkResult<JsonObject> = if (token == null || token.startsWith("guest_")) {
            safeCall { api().score(matchId) }
        } else {
            val first = safeCall { api().match(matchId) }
            val err = (first as? NetworkResult.Failure)?.error
            if (err is ApiError.Http && (err.status == 401 || err.status == 403)) {
                safeCall { api().score(matchId) }
            } else {
                first
            }
        }
        return when (raw) {
            is NetworkResult.Failure -> raw
            is NetworkResult.Success -> MatchJsonMapper.snapshot(raw.data, matchId)
                ?.let { NetworkResult.Success(it) }
                ?: NetworkResult.Failure(ApiError.Unexpected(IllegalStateException("unmappable match")))
        }
    }

    override suspend fun chatHistory(roomId: String, afterSequence: Long?): NetworkResult<List<ChatMessage>> =
        when (val r = safeCall { api().chatMessages(roomId, afterSequence) }) {
            is NetworkResult.Failure -> r
            is NetworkResult.Success -> NetworkResult.Success(MatchJsonMapper.chatMessages(r.data))
        }

    override suspend fun sendChat(roomId: String, text: String): NetworkResult<ChatMessage> {
        val body = JsonObject(mapOf("room_id" to JsonPrimitive(roomId), "text" to JsonPrimitive(text)))
        return when (val r = safeCall { api().sendChat(body) }) {
            is NetworkResult.Failure -> r
            is NetworkResult.Success -> MatchJsonMapper.chatMessage(r.data)
                ?.let { NetworkResult.Success(it) }
                ?: NetworkResult.Failure(ApiError.Unexpected(IllegalStateException("unmappable message")))
        }
    }
}
