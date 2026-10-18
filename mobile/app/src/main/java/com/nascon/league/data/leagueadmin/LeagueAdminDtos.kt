package com.nascon.league.data.leagueadmin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Extra league facts that the real `GET /api/v1/leagues` does not carry. Contract addition. */
@Serializable
data class AdminLeagueDto(
    @SerialName("league_id") val leagueId: String,
    @SerialName("name") val name: String = "",
    @SerialName("sport_id") val sportId: String = "",
    @SerialName("season") val season: String? = null,
    /** round_robin | swiss | single_elimination | double_elimination | hybrid */
    @SerialName("format") val format: String = "round_robin",
    /** pending (awaiting admin approval) | active | rejected | draft | finished */
    @SerialName("status") val status: String = "active",
    @SerialName("team_count") val teamCount: Int = 0,
    @SerialName("player_count") val playerCount: Int = 0,
    @SerialName("match_count") val matchCount: Int = 0,
    @SerialName("division_count") val divisionCount: Int = 0,
    /** ISO dates, yyyy-MM-dd. */
    @SerialName("transfer_window_opens") val transferWindowOpens: String? = null,
    @SerialName("transfer_window_closes") val transferWindowCloses: String? = null,
    @SerialName("max_squad_size") val maxSquadSize: Int = 25,
)

@Serializable
data class AdminLeagueRequest(
    @SerialName("name") val name: String,
    @SerialName("sport_id") val sportId: String,
    @SerialName("format") val format: String,
    @SerialName("transfer_window_opens") val transferWindowOpens: String? = null,
    @SerialName("transfer_window_closes") val transferWindowCloses: String? = null,
    @SerialName("max_squad_size") val maxSquadSize: Int = 25,
)

@Serializable
data class SportOptionDto(
    @SerialName("sport_id") val sportId: String,
    @SerialName("name") val name: String = "",
)

@Serializable
data class SeasonDto(
    @SerialName("season_id") val seasonId: String,
    @SerialName("name") val name: String = "",
    @SerialName("start_date") val startDate: String = "",
    @SerialName("end_date") val endDate: String = "",
    /** upcoming | current | finished */
    @SerialName("status") val status: String = "upcoming",
    @SerialName("league_count") val leagueCount: Int = 0,
    @SerialName("division_count") val divisionCount: Int = 0,
    @SerialName("promotion_applied") val promotionApplied: Boolean = false,
)

@Serializable
data class SeasonRequest(
    @SerialName("name") val name: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    @SerialName("copy_from_season_id") val copyFromSeasonId: String? = null,
    @SerialName("apply_promotion") val applyPromotion: Boolean = true,
)

@Serializable
data class DivisionTeamDto(
    @SerialName("name") val name: String = "",
    @SerialName("points") val points: Int = 0,
)

@Serializable
data class DivisionDto(
    @SerialName("division_id") val divisionId: String,
    @SerialName("name") val name: String = "",
    /** 1 = top tier. */
    @SerialName("tier") val tier: Int = 1,
    @SerialName("team_count") val teamCount: Int = 0,
    @SerialName("top_teams") val topTeams: List<DivisionTeamDto> = emptyList(),
)

@Serializable
data class DivisionRequest(
    @SerialName("name") val name: String,
)

@Serializable
data class PromotionRulesDto(
    @SerialName("spots_up") val spotsUp: Int = 2,
    @SerialName("spots_down") val spotsDown: Int = 2,
)

@Serializable
data class StandingsRulesDto(
    @SerialName("points_win") val pointsWin: Int = 3,
    @SerialName("points_draw") val pointsDraw: Int = 1,
    @SerialName("points_loss") val pointsLoss: Int = 0,
    @SerialName("qualification_spots") val qualificationSpots: Int = 2,
    /** Ordered ids: head_to_head, goal_difference, goals_for, wins, fair_play. */
    @SerialName("tiebreaks") val tiebreaks: List<String> = listOf("head_to_head", "goal_difference", "goals_for"),
)

@Serializable
data class TeamDto(
    @SerialName("team_id") val teamId: String,
    @SerialName("name") val name: String = "",
)

@Serializable
data class TeamRequest(@SerialName("name") val name: String)
