package com.nascon.league.data.matchops

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

@Serializable
data class StatusRequest(@SerialName("status") val status: String)

@Serializable
data class StatusResponse(
    @SerialName("match_id") val matchId: String = "",
    @SerialName("status") val status: String = "",
    @SerialName("changed_by") val changedBy: String? = null,
)

/** venue_id is omitted when null, so the server keeps the match's current venue. */
@Serializable
data class ScheduleRequest(
    @SerialName("scheduled_at") val scheduledAt: String,
    @SerialName("venue_id") val venueId: String? = null,
)

@Serializable
data class ConflictDto(
    @SerialName("type") val type: String = "",
    @SerialName("entity_ids") val entityIds: List<String> = emptyList(),
    @SerialName("match_ids") val matchIds: List<String> = emptyList(),
)

@Serializable
data class ConflictsResponse(@SerialName("conflicts") val conflicts: List<ConflictDto> = emptyList())

@Serializable
data class RescheduleResponse(
    @SerialName("match_id") val matchId: String = "",
    @SerialName("scheduled_at") val scheduledAt: String = "",
    @SerialName("venue_id") val venueId: String? = null,
    @SerialName("conflicts") val conflicts: List<ConflictDto> = emptyList(),
)

@Serializable
data class PinRequest(@SerialName("product_id") val productId: String)

@Serializable
data class PinResponse(
    @SerialName("match_id") val matchId: String = "",
    @SerialName("product_id") val productId: String = "",
    @SerialName("pinned") val pinned: Boolean = false,
)

/** Real backend endpoints (backend/src/routes/api/v1/matchOps.js). Roles: super_admin, league_organizer (own league). */
interface MatchOpsApi {
    @PATCH("api/v1/matches/{id}/status")
    suspend fun changeStatus(@Path("id") matchId: String, @Body body: StatusRequest): StatusResponse

    @POST("api/v1/matches/{id}/schedule/check")
    suspend fun checkSchedule(@Path("id") matchId: String, @Body body: ScheduleRequest): ConflictsResponse

    @PATCH("api/v1/matches/{id}/schedule")
    suspend fun reschedule(@Path("id") matchId: String, @Body body: ScheduleRequest): RescheduleResponse

    @POST("api/v1/matches/{id}/pins")
    suspend fun pin(@Path("id") matchId: String, @Body body: PinRequest): PinResponse

    @DELETE("api/v1/matches/{id}/pins/{productId}")
    suspend fun unpin(@Path("id") matchId: String, @Path("productId") productId: String): PinResponse
}
