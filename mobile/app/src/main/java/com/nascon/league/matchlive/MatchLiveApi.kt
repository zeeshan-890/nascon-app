package com.nascon.league.matchlive

import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Raw match and chat routes. Responses are [JsonObject] so [MatchJsonMapper] stays pure and tolerant.
 *
 * Route status:
 * - `audit/v1/score/{id}` EXISTS on the backend, needs no auth, and is the route guests use.
 * - `api/v1/matches/{id}` EXISTS, needs a member JWT.
 * - `api/v1/chat/{room}/messages` is ASSUMED. It does NOT exist on the backend yet.
 * - `api/v1/chat` (POST) is ASSUMED. It does NOT exist on the backend yet; guests are expected to get 403 GUEST_WRITE_FORBIDDEN.
 */
interface MatchLiveApi {
    @GET("audit/v1/score/{id}")
    suspend fun score(@Path("id") matchId: String): JsonObject

    @GET("api/v1/matches/{id}")
    suspend fun match(@Path("id") matchId: String): JsonObject

    /** ASSUMED route. Response is `{messages:[...]}` (a bare array is tolerated by the mapper too). */
    @GET("api/v1/chat/{room}/messages")
    suspend fun chatMessages(@Path("room") roomId: String, @Query("after") afterSequence: Long? = null): JsonObject

    /** ASSUMED route. Body `{room_id,text}`; returns the stored message. */
    @POST("api/v1/chat")
    suspend fun sendChat(@Body body: JsonObject): JsonObject
}
