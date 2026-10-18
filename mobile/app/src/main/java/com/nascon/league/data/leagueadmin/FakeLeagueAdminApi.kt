package com.nascon.league.data.leagueadmin

import com.nascon.league.di.AppContainer
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import kotlin.math.abs

/** Demo implementation of [LeagueAdminApi] with in-memory state. */
class FakeLeagueAdminApi(
    private val hasNetwork: () -> Boolean = { true },
    private val pauseMs: (String) -> Long = { 300L + (abs(it.hashCode()) % 400).toLong() },
) : LeagueAdminApi {

    private val leagues = linkedMapOf(
        "league-alpha-1" to AdminLeagueDto(
            leagueId = "league-alpha-1", name = "Alpha Premier League", sportId = "football", season = "2026",
            format = "round_robin", status = "active", teamCount = 8, playerCount = 192, matchCount = 28,
            divisionCount = 2, transferWindowOpens = "2026-10-01", transferWindowCloses = "2026-10-31", maxSquadSize = 25,
        ),
        "league-alpha-2" to AdminLeagueDto(
            leagueId = "league-alpha-2", name = "Alpha Cup", sportId = "football", season = "2026",
            format = "hybrid", status = "draft", teamCount = 16, playerCount = 0, matchCount = 0,
            divisionCount = 0, maxSquadSize = 25,
        ),
    )
    private val seasons = mutableListOf(
        SeasonDto("season-2026", "Season 2026", "2026-08-01", "2027-06-30", "current", 2, 2, false),
        SeasonDto("season-2025", "Season 2025", "2025-08-01", "2026-06-30", "finished", 2, 2, true),
    )
    private val divisions = mutableMapOf(
        "league-alpha-1" to mutableListOf(
            DivisionDto("div-a", "Division A", 1, 8, listOf(DivisionTeamDto("Lions FC", 19), DivisionTeamDto("Tigers", 17))),
            DivisionDto("div-b", "Division B", 2, 8, listOf(DivisionTeamDto("Bears", 15))),
        ),
    )
    private val promotion = mutableMapOf<String, PromotionRulesDto>()
    private val standings = mutableMapOf<String, StandingsRulesDto>()
    private var nextId = 100

    private suspend fun pause(key: String) {
        if (!hasNetwork()) throw java.net.UnknownHostException("offline")
        delay(pauseMs(key))
    }

    private fun fail(status: Int, code: String, message: String): Nothing {
        val body = """{"error":{"code":"$code","message":"$message"}}""".toResponseBody("application/json".toMediaType())
        throw HttpException(Response.error<Any>(status, body))
    }

    private fun league(id: String) = leagues[id] ?: fail(404, "LEAGUE_NOT_FOUND", "League not found.")

    override suspend fun sports(): List<SportOptionDto> {
        pause("sports")
        return listOf(
            SportOptionDto("football", "Football"),
            SportOptionDto("cricket", "Cricket"),
            SportOptionDto("basketball", "Basketball"),
        )
    }

    override suspend fun adminLeagues(): List<AdminLeagueDto> {
        pause("adminLeagues")
        return leagues.values.toList()
    }

    override suspend fun adminLeague(id: String): AdminLeagueDto {
        pause("adminLeague$id")
        return league(id)
    }

    override suspend fun createLeague(body: AdminLeagueRequest): AdminLeagueDto {
        pause("createLeague")
        if (body.name.isBlank()) fail(422, "VALIDATION_ERROR", "Name is required.")
        val id = "league-alpha-${nextId++}"
        val dto = AdminLeagueDto(
            id, body.name.trim(), body.sportId, "2026", body.format, "pending", 0, 0, 0, 0,
            body.transferWindowOpens, body.transferWindowCloses, body.maxSquadSize,
        )
        leagues[id] = dto
        return dto
    }

    override suspend fun approveLeague(id: String): AdminLeagueDto = review(id, "active")

    override suspend fun rejectLeague(id: String): AdminLeagueDto = review(id, "rejected")

    private suspend fun review(id: String, status: String): AdminLeagueDto {
        pause("review$id")
        val cur = league(id)
        if (cur.status != "pending") fail(409, "ILLEGAL_TRANSITION", "Only a pending league can be reviewed.")
        return cur.copy(status = status).also { leagues[id] = it }
    }

    override suspend fun updateLeague(id: String, body: AdminLeagueRequest): AdminLeagueDto {
        pause("updateLeague$id")
        val dto = league(id).copy(
            name = body.name.trim(), sportId = body.sportId, format = body.format,
            transferWindowOpens = body.transferWindowOpens, transferWindowCloses = body.transferWindowCloses,
            maxSquadSize = body.maxSquadSize,
        )
        leagues[id] = dto
        return dto
    }

    private val teams = mutableMapOf<String, MutableList<TeamDto>>()

    override suspend fun teams(leagueId: String): List<TeamDto> {
        pause("teams$leagueId")
        league(leagueId)
        return teams[leagueId].orEmpty().toList()
    }

    override suspend fun addTeam(leagueId: String, body: TeamRequest): TeamDto {
        pause("addTeam$leagueId")
        league(leagueId)
        val name = body.name.trim()
        if (name.isEmpty()) fail(400, "VALIDATION_ERROR", "Team name is required.")
        val list = teams.getOrPut(leagueId) { mutableListOf() }
        if (list.any { it.name.equals(name, ignoreCase = true) }) fail(409, "DUPLICATE_TEAM", "That team is already in the league.")
        return TeamDto("team-${leagueId}-${list.size + 1}", name).also { list += it }
    }

    override suspend fun removeTeam(leagueId: String, teamId: String) {
        pause("removeTeam$leagueId")
        league(leagueId)
        if (teams[leagueId]?.removeAll { it.teamId == teamId } != true) fail(404, "NOT_FOUND", "Team not found.")
    }

    override suspend fun seasons(leagueId: String): List<SeasonDto> {
        pause("seasons$leagueId")
        league(leagueId)
        return seasons.toList()
    }

    override suspend fun createSeason(leagueId: String, body: SeasonRequest): SeasonDto {
        pause("createSeason$leagueId")
        league(leagueId)
        if (body.endDate <= body.startDate) fail(422, "VALIDATION_ERROR", "End date must be after the start date.")
        val copied = seasons.firstOrNull { it.seasonId == body.copyFromSeasonId }
        val dto = SeasonDto(
            "season-${nextId++}", body.name.trim(), body.startDate, body.endDate, "upcoming",
            copied?.leagueCount ?: 0, copied?.divisionCount ?: 0, false,
        )
        seasons.add(0, dto)
        return dto
    }

    override suspend fun divisions(leagueId: String): List<DivisionDto> {
        pause("divisions$leagueId")
        league(leagueId)
        return divisions[leagueId].orEmpty().toList()
    }

    override suspend fun createDivision(leagueId: String, body: DivisionRequest): DivisionDto {
        pause("createDivision$leagueId")
        league(leagueId)
        if (body.name.isBlank()) fail(422, "VALIDATION_ERROR", "Name is required.")
        val list = divisions.getOrPut(leagueId) { mutableListOf() }
        val dto = DivisionDto("div-${nextId++}", body.name.trim(), list.size + 1, 0, emptyList())
        list.add(dto)
        leagues[leagueId] = league(leagueId).copy(divisionCount = list.size)
        return dto
    }

    override suspend fun promotionRules(leagueId: String): PromotionRulesDto {
        pause("promo$leagueId")
        league(leagueId)
        return promotion[leagueId] ?: PromotionRulesDto(2, 2)
    }

    override suspend fun savePromotionRules(leagueId: String, body: PromotionRulesDto): PromotionRulesDto {
        pause("savePromo$leagueId")
        league(leagueId)
        if (body.spotsUp < 0 || body.spotsDown < 0) fail(422, "VALIDATION_ERROR", "Spots cannot be negative.")
        promotion[leagueId] = body
        return body
    }

    override suspend fun standingsRules(leagueId: String): StandingsRulesDto {
        pause("rules$leagueId")
        league(leagueId)
        return standings[leagueId] ?: StandingsRulesDto()
    }

    override suspend fun saveStandingsRules(leagueId: String, body: StandingsRulesDto): StandingsRulesDto {
        pause("saveRules$leagueId")
        league(leagueId)
        if (body.pointsWin < body.pointsDraw || body.pointsDraw < body.pointsLoss) {
            fail(422, "VALIDATION_ERROR", "A win must be worth at least a draw.")
        }
        standings[leagueId] = body
        return body
    }
}

object LeagueAdminFakeHolder {
    private var inst: FakeLeagueAdminApi? = null
    fun get(hasNetwork: () -> Boolean): FakeLeagueAdminApi = inst ?: FakeLeagueAdminApi(hasNetwork).also { inst = it }
}

fun AppContainer.leagueAdminApi(): LeagueAdminApi =
    service(LeagueAdminApi::class.java) { hn -> LeagueAdminFakeHolder.get(hn) }
