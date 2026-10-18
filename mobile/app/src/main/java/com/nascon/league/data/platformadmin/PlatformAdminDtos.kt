package com.nascon.league.data.platformadmin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** `POST /api/v1/admin/clubs` (contract addition). */
@Serializable
data class AdminClubCreateRequest(
    @SerialName("name") val name: String,
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("venue_name") val venueName: String,
    @SerialName("city") val city: String? = null,
)

@Serializable
data class AdminClubDto(
    @SerialName("club_id") val clubId: String,
    @SerialName("name") val name: String,
    @SerialName("tenant_id") val tenantId: String = "",
    @SerialName("venue_name") val venueName: String = "",
    @SerialName("city") val city: String? = null,
)

@Serializable
data class SportRosterDto(
    @SerialName("min_squad_size") val minSquadSize: Int = 0,
    @SerialName("max_squad_size") val maxSquadSize: Int = 0,
    @SerialName("required_positions") val requiredPositions: JsonObject = JsonObject(emptyMap()),
)

/**
 * Full sport definition. Same shape as the server seed (backend/seed/sports.json) and as the real
 * `GET /api/v1/sports/{id}` response. Which keys inside [periods] / [scoringEvents] exist depends on the sport.
 */
@Serializable
data class SportDefinitionDto(
    @SerialName("sport_id") val sportId: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("scoring_model") val scoringModel: String = "",
    @SerialName("periods") val periods: JsonObject = JsonObject(emptyMap()),
    @SerialName("scoring_events") val scoringEvents: JsonObject = JsonObject(emptyMap()),
    @SerialName("draw_allowed") val drawAllowed: Boolean = false,
    @SerialName("overtime") val overtime: JsonObject = JsonObject(emptyMap()),
    @SerialName("tie_break_order") val tieBreakOrder: List<String> = emptyList(),
    @SerialName("roster") val roster: SportRosterDto = SportRosterDto(),
)

@Serializable
data class AccessPolicyDto(
    @SerialName("role") val role: String,
    @SerialName("action") val action: String,
    @SerialName("scope") val scope: String,
)

@Serializable
data class AccessMatrixDto(
    @SerialName("policies") val policies: List<AccessPolicyDto> = emptyList(),
)

@Serializable
data class AuditEntryDto(
    @SerialName("id") val id: String,
    /** ISO-8601 instant. */
    @SerialName("at") val at: String = "",
    @SerialName("actor_id") val actorId: String = "",
    @SerialName("actor_name") val actorName: String = "",
    @SerialName("actor_role") val actorRole: String = "",
    @SerialName("action") val action: String = "",
    @SerialName("target") val target: String = "",
    @SerialName("summary") val summary: String = "",
    @SerialName("request_id") val requestId: String = "",
    @SerialName("reason") val reason: String? = null,
    @SerialName("before") val before: JsonElement? = null,
    @SerialName("after") val after: JsonElement? = null,
)

@Serializable
data class AuditPageDto(
    @SerialName("items") val items: List<AuditEntryDto> = emptyList(),
    @SerialName("total") val total: Int = 0,
)

/** Public `GET /health` -> `{ "status": "ok" }` (503 with an error body while the server starts). */
@Serializable
data class HealthDto(@SerialName("status") val status: String = "")

/** `GET /api/v1/ping` -> `{ "pong": true }`. */
@Serializable
data class PingDto(@SerialName("pong") val pong: Boolean = false)
