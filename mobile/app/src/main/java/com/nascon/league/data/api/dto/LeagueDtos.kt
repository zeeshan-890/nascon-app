package com.nascon.league.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class LeagueDto(
    @SerialName("league_id") val leagueId: String,
    @SerialName("name") val name: String,
    @SerialName("sport_id") val sportId: String = "",
    @SerialName("season") val season: String? = null,
)

@Serializable
data class FixtureDto(
    @SerialName("match_id") val matchId: String,
    @SerialName("home_team_id") val homeTeamId: String,
    @SerialName("home_name") val homeName: String = "",
    @SerialName("away_team_id") val awayTeamId: String,
    @SerialName("away_name") val awayName: String = "",
    @SerialName("status") val status: String = "scheduled",
    @SerialName("scheduled_at") val scheduledAt: String? = null,
    @SerialName("venue_name") val venueName: String? = null,
    @SerialName("home_score") val homeScore: Int = 0,
    @SerialName("away_score") val awayScore: Int = 0,
    @SerialName("version") val version: Long = 0,
)

@Serializable
data class TeamScoreDto(
    @SerialName("team_id") val teamId: String,
    @SerialName("score") val score: Int = 0,
    @SerialName("wickets") val wickets: Int? = null,
    @SerialName("balls") val balls: Int? = null,
    @SerialName("overs") val overs: JsonElement? = null,
    @SerialName("fair_play") val fairPlay: Int = 0,
)

@Serializable
data class MatchEventDto(
    @SerialName("sequence") val sequence: Int,
    @SerialName("event_id") val eventId: String,
    @SerialName("type") val type: String,
    @SerialName("payload") val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("signature") val signature: String = "",
    @SerialName("compensated") val compensated: Boolean = false,
    @SerialName("compensated_by") val compensatedBy: String? = null,
)

@Serializable
data class PinnedProductDto(
    @SerialName("product_id") val productId: String,
    @SerialName("name") val name: String,
    @SerialName("price_minor") val priceMinor: Long = 0,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
data class MatchDetailDto(
    @SerialName("match_id") val matchId: String,
    @SerialName("sport_id") val sportId: String = "",
    @SerialName("league_id") val leagueId: String? = null,
    @SerialName("status") val status: String = "scheduled",
    @SerialName("outcome") val outcome: String = "",
    @SerialName("winner_team_id") val winnerTeamId: String? = null,
    @SerialName("tiebreak_winner_team_id") val tiebreakWinnerTeamId: String? = null,
    @SerialName("home") val home: TeamScoreDto,
    @SerialName("away") val away: TeamScoreDto,
    @SerialName("version") val version: Long = 0,
    @SerialName("events") val events: List<MatchEventDto> = emptyList(),
    @SerialName("pinned_products") val pinnedProducts: List<PinnedProductDto> = emptyList(),
)

@Serializable
data class MatchEventRequest(
    @SerialName("event_id") val eventId: String,
    @SerialName("match_id") val matchId: String,
    @SerialName("type") val type: String,
    @SerialName("payload") val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class MatchEventResponse(
    @SerialName("event_id") val eventId: String,
    @SerialName("match_id") val matchId: String,
    @SerialName("type") val type: String,
    @SerialName("sequence") val sequence: Int,
    @SerialName("match_version") val matchVersion: Long,
    @SerialName("signature") val signature: String = "",
)

@Serializable
data class SportConfigDto(
    @SerialName("sport_id") val sportId: String? = null,
    @SerialName("scoring_model") val scoringModel: String? = null,
    @SerialName("scoring_events") val scoringEvents: JsonObject? = null,
    @SerialName("draw_allowed") val drawAllowed: Boolean? = null,
    @SerialName("discipline") val discipline: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
data class StandingRowDto(
    @SerialName("rank") val rank: Int,
    @SerialName("team_id") val teamId: String,
    @SerialName("played") val played: Int = 0,
    @SerialName("won") val won: Int = 0,
    @SerialName("drawn") val drawn: Int = 0,
    @SerialName("lost") val lost: Int = 0,
    @SerialName("scored") val scored: Int = 0,
    @SerialName("conceded") val conceded: Int = 0,
    @SerialName("diff") val diff: Double = 0.0,
    @SerialName("points") val points: Int = 0,
    @SerialName("fair_play") val fairPlay: Int = 0,
    @SerialName("status") val status: String = "active",
    @SerialName("resolved_by") val resolvedBy: String? = null,
)

@Serializable
data class StandingsDto(
    @SerialName("league_id") val leagueId: String,
    @SerialName("sport_id") val sportId: String = "",
    @SerialName("qualification_spots") val qualificationSpots: Int = 0,
    @SerialName("table") val table: List<StandingRowDto> = emptyList(),
    @SerialName("h2h") val h2h: JsonObject = JsonObject(emptyMap()),
    @SerialName("team_names") val teamNames: Map<String, String> = emptyMap(),
)

@Serializable
data class VenueDto(
    /** JSON `name` may be null. Screens read [name], which is "" when the server sent null. */
    @SerialName("name") private val rawName: String? = null,
    @SerialName("lat") val lat: Double? = null,
    @SerialName("lng") val lng: Double? = null,
) {
    val name: String get() = rawName?.trim().orEmpty()
}

@Serializable
data class StaffDto(
    @SerialName("name") val name: String,
    @SerialName("role") val role: String = "",
)

@Serializable
data class ClubStatDto(
    @SerialName("season") val season: String = "",
    @SerialName("played") val played: Int = 0,
    @SerialName("won") val won: Int = 0,
    @SerialName("drawn") val drawn: Int = 0,
    @SerialName("lost") val lost: Int = 0,
    @SerialName("scored") val scored: Int = 0,
    @SerialName("conceded") val conceded: Int = 0,
)

@Serializable
data class RosterPlayerDto(
    @SerialName("player_id") val playerId: String,
    @SerialName("name") val name: String,
    @SerialName("position") val position: String = "",
    @SerialName("role") val role: String = "",
    @SerialName("squad_id") val squadId: String? = null,
    @SerialName("league_id") val leagueId: String? = null,
    @SerialName("on_loan") val onLoan: Boolean = false,
    @SerialName("audit_version") val auditVersion: Long = 0,
)

@Serializable
data class SquadDto(
    @SerialName("squad_id") val squadId: String,
    @SerialName("name") val name: String,
    @SerialName("club_id") val clubId: String? = null,
)

@Serializable
data class ClubDto(
    @SerialName("club_id") val clubId: String,
    @SerialName("name") val name: String,
    @SerialName("tenant_id") val tenantId: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("venue") val venue: VenueDto? = null,
    @SerialName("socials") val socials: JsonObject = JsonObject(emptyMap()),
    @SerialName("staff") val staff: List<StaffDto> = emptyList(),
    @SerialName("stats") val stats: List<ClubStatDto> = emptyList(),
    @SerialName("roster") val roster: List<RosterPlayerDto> = emptyList(),
    @SerialName("squads") val squads: List<SquadDto> = emptyList(),
)

@Serializable
data class ClubSummaryDto(
    @SerialName("club_id") val clubId: String,
    @SerialName("name") val name: String,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("venue") val venue: VenueDto? = null,
)

@Serializable
data class RosterTransferResponse(
    @SerialName("request_id") val requestId: String,
    @SerialName("status") val status: String = "",
    @SerialName("state") val state: String = "",
    @SerialName("blocking_match_id") val blockingMatchId: String? = null,
    @SerialName("registration") val registration: JsonObject? = null,
    @SerialName("audit_entry") val auditEntry: JsonObject? = null,
)
