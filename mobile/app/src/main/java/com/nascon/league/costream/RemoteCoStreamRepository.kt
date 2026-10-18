package com.nascon.league.costream

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import retrofit2.http.GET
import retrofit2.http.Path

/** ASSUMED route: it does NOT exist on the backend yet. */
interface CoStreamApi {
    @GET("api/v1/costream/{id}")
    suspend fun session(@Path("id") id: String): JsonObject
}

class RemoteCoStreamRepository(private val api: () -> CoStreamApi) : CoStreamRepository {
    override suspend fun session(sessionId: String): NetworkResult<CoSession> {
        return when (val r = safeCall { api().session(sessionId) }) {
            is NetworkResult.Failure -> r
            is NetworkResult.Success -> {
                val s = try {
                    CoStreamJson.session(r.data, sessionId)
                } catch (e: Exception) {
                    null
                }
                if (s == null) NetworkResult.Failure(ApiError.Unexpected(null)) else NetworkResult.Success(s)
            }
        }
    }
}

object CoStreamJson {
    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val p = this[k] as? JsonPrimitive ?: continue
            val c = p.content
            if (p.isString || p !is kotlinx.serialization.json.JsonNull) {
                if (c.isNotBlank() && c != "null") return c
            }
        }
        return null
    }

    private fun JsonObject.bool(vararg keys: String): Boolean? {
        for (k in keys) {
            val b = (this[k] as? JsonPrimitive)?.booleanOrNull
            if (b != null) return b
        }
        return null
    }

    fun session(json: JsonObject, fallbackId: String): CoSession? {
        if (json.isEmpty()) return null
        val id = json.str("session_id") ?: fallbackId
        val activeHost = json.str("active_host")
        val arr = json["participants"] as? JsonArray
        val participants = arr?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val uid = o.str("user_id", "player_id", "id") ?: return@mapNotNull null
            CoParticipant(
                userId = uid,
                displayName = o.str("display_name", "name") ?: uid,
                isHost = (o.bool("is_host", "host") ?: false) || (activeHost != null && uid == activeHost),
                muted = o.bool("muted") ?: false,
                kicked = o.bool("kicked") ?: false,
            )
        } ?: emptyList()
        return CoSession(
            sessionId = id,
            title = json.str("title"),
            status = json.str("status") ?: "active",
            activeHost = activeHost,
            layout = json.str("layout") ?: "",
            participants = participants,
            playbackUrl = json.str("composite_playback_url", "playback_url", "hls_url"),
            posterUrl = json.str("poster_url"),
            chatRoomId = json.str("chat_room_id") ?: ("chat-$id"),
            vodUrl = json.str("vod_url"),
        )
    }
}
