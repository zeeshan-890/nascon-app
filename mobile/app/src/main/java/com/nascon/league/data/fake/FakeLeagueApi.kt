package com.nascon.league.data.fake

import com.nascon.league.data.api.LeagueApi
import com.nascon.league.data.api.dto.CheckoutRequest
import com.nascon.league.data.api.dto.CheckoutResponse
import com.nascon.league.data.api.dto.ClubDto
import com.nascon.league.data.api.dto.ClubStatDto
import com.nascon.league.data.api.dto.ClubSummaryDto
import com.nascon.league.data.api.dto.FixtureDto
import com.nascon.league.data.api.dto.LeagueDto
import com.nascon.league.data.api.dto.LoginRequest
import com.nascon.league.data.api.dto.LoginResponse
import com.nascon.league.data.api.dto.LogoutRequest
import com.nascon.league.data.api.dto.MatchDetailDto
import com.nascon.league.data.api.dto.MatchEventDto
import com.nascon.league.data.api.dto.MatchEventRequest
import com.nascon.league.data.api.dto.MatchEventResponse
import com.nascon.league.data.api.dto.PingResponse
import com.nascon.league.data.api.dto.PinnedProductDto
import com.nascon.league.data.api.dto.ProductDto
import com.nascon.league.data.api.dto.ReceiptDto
import com.nascon.league.data.api.dto.RefreshRequest
import com.nascon.league.data.api.dto.RefreshResponse
import com.nascon.league.data.api.dto.RosterPlayerDto
import com.nascon.league.data.api.dto.RosterTransferResponse
import com.nascon.league.data.api.dto.SportConfigDto
import com.nascon.league.data.api.dto.SquadDto
import com.nascon.league.data.api.dto.StaffDto
import com.nascon.league.data.api.dto.StandingRowDto
import com.nascon.league.data.api.dto.StandingsDto
import com.nascon.league.data.api.dto.SyncRequest
import com.nascon.league.data.api.dto.SyncResponse
import com.nascon.league.data.api.dto.SyncResultDto
import com.nascon.league.data.api.dto.SyncSummaryDto
import com.nascon.league.data.api.dto.TeamScoreDto
import com.nascon.league.data.api.dto.TenantDto
import com.nascon.league.data.api.dto.UserDto
import com.nascon.league.data.api.dto.VariantDto
import com.nascon.league.data.api.dto.VenueDto
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.util.UUID
import kotlin.math.abs

class FakeLeagueApi(private val hasNetwork: () -> Boolean = { true }) : LeagueApi {

    private data class Team(val id: String, val name: String)
    private data class Game(
        val matchId: String,
        val home: Team,
        val away: Team,
        val status: String,
        val scheduledAt: String?,
        val homeScore: Int,
        val awayScore: Int,
    )

    private val lions = Team("team-alpha-lions", "Alpha Lions")
    private val hawks = Team("team-beta-hawks", "Beta Hawks")
    private val wolves = Team("team-gamma-wolves", "Gamma Wolves")
    private val sharks = Team("team-delta-sharks", "Delta Sharks")
    private val teams = listOf(lions, hawks, wolves, sharks)

    private val lock = Any()
    private val games: MutableList<Game> = mutableListOf(
        Game("demo-m-1", lions, hawks, "completed", "2026-10-04T14:00:00Z", 2, 1),
        Game("demo-m-2", wolves, sharks, "completed", "2026-10-04T16:00:00Z", 0, 0),
        Game("demo-m-3", sharks, hawks, "completed", "2026-10-11T14:00:00Z", 1, 2),
        Game("demo-m-live", lions, sharks, "in_progress", "2026-10-17T14:00:00Z", 1, 0),
        Game("demo-m-5", hawks, wolves, "scheduled", "2026-10-18T14:00:00Z", 0, 0),
        Game("demo-m-6", wolves, lions, "scheduled", "2026-10-25T14:00:00Z", 0, 0),
    )

    // Fake sport config data table: the scoring event keys live here only.
    private val footballScoringKeys = listOf("goal")
    private val scoringKey get() = footballScoringKeys.first()

    // Events of the live demo match (mutable, in memory).
    private val liveEvents = ArrayList<MatchEventDto>()
    private var liveToggle = false

    private val postedEvents = HashMap<String, Int>()
    private var syncOrder = 0

    init {
        liveEvents += scoreEvent(LIVE_ID, 1, lions.id, 10)
    }

    private val variants = mapOf(
        "var-jersey-s" to Triple("prod-jersey", "S", 4999L),
        "var-jersey-m" to Triple("prod-jersey", "M", 4999L),
        "var-jersey-l" to Triple("prod-jersey", "L", 5499L),
        "var-scarf-red" to Triple("prod-scarf", null, 1999L),
        "var-scarf-blue" to Triple("prod-scarf", null, 1999L),
        "var-cap-black" to Triple("prod-cap", null, 1499L),
        "var-cap-white" to Triple("prod-cap", null, 1499L),
    )
    private val productNames = mapOf(
        "prod-jersey" to "Alpha Lions Home Jersey",
        "prod-scarf" to "Alpha Lions Scarf",
        "prod-cap" to "Alpha Lions Cap",
    )

    private companion object {
        const val LIVE_ID = "demo-m-live"
    }

    private suspend fun pause(key: String) {
        if (!hasNetwork()) throw java.net.UnknownHostException("offline")
        delay(300L + (abs(key.hashCode()) % 400).toLong())
    }

    private fun fail(status: Int, code: String, message: String): Nothing {
        val body = """{"error":{"code":"$code","message":"$message"}}"""
            .toResponseBody("application/json".toMediaType())
        throw HttpException(Response.error<Any>(status, body))
    }

    private fun tenant() = TenantDto("tenant-alpha", "Alpha Sports", null, "#0B5FFF", "#FFB800")

    // ---- auth ----
    override suspend fun login(body: LoginRequest): LoginResponse {
        pause("login")
        val email = body.email.trim().lowercase()
        if (email == "locked@nascon.test") fail(429, "RATE_LIMITED", "Too many attempts. Try again later.")
        if (body.password != DemoAccounts.PASSWORD) fail(401, "UNAUTHENTICATED", "Invalid email or password.")
        val (user, withTenant) = when (email) {
            "admin@nascon.test" -> UserDto("u-admin", email, "super_admin") to false
            "organizer@nascon.test" -> UserDto(
                "u-organizer", email, "league_organizer", "tenant-alpha", listOf("league-alpha-1"),
            ) to true
            "manager@nascon.test" -> UserDto(
                "u-manager", email, "club_manager", "tenant-alpha", clubId = "club-alpha-1",
            ) to true
            "player@nascon.test" -> UserDto(
                "u-player", email, "player", "tenant-alpha", clubId = "club-alpha-1", playerId = "p1",
            ) to true
            "viewer@nascon.test" -> UserDto("u-viewer", email, "viewer", "tenant-alpha") to true
            else -> fail(401, "UNAUTHENTICATED", "Invalid email or password.")
        }
        return LoginResponse(
            accessToken = "fake-access-${UUID.randomUUID()}",
            refreshToken = "fake-refresh-${UUID.randomUUID()}",
            expiresIn = 3600,
            user = user,
            tenant = if (withTenant) tenant() else null,
        )
    }

    override suspend fun refresh(body: RefreshRequest): RefreshResponse {
        pause("refresh")
        if (body.refreshToken.isBlank()) fail(401, "UNAUTHENTICATED", "Invalid refresh token.")
        return RefreshResponse("fake-access-${UUID.randomUUID()}", "fake-refresh-${UUID.randomUUID()}", 3600)
    }

    override suspend fun logout(body: LogoutRequest): JsonObject {
        pause("logout")
        return JsonObject(emptyMap())
    }

    override suspend fun ping(): PingResponse {
        pause("ping")
        return PingResponse(true)
    }

    // ---- leagues ----
    private fun requireLeague(id: String) {
        if (id != "league-alpha-1") fail(404, "LEAGUE_NOT_FOUND", "League not found.")
    }

    override suspend fun leagues(): List<LeagueDto> {
        pause("leagues")
        return listOf(LeagueDto("league-alpha-1", "Alpha Premier League", "football", "2026"))
    }

    override suspend fun fixtures(leagueId: String): List<FixtureDto> {
        pause("fixtures$leagueId")
        requireLeague(leagueId)
        return synchronized(lock) { games.toList() }.map {
            FixtureDto(
                matchId = it.matchId,
                homeTeamId = it.home.id,
                homeName = it.home.name,
                awayTeamId = it.away.id,
                awayName = it.away.name,
                status = it.status,
                scheduledAt = it.scheduledAt,
                venueName = "Alpha Stadium",
                homeScore = it.homeScore,
                awayScore = it.awayScore,
                version = if (it.status == "scheduled") 0 else (it.homeScore + it.awayScore + 1).toLong(),
            )
        }
    }

    private fun outcomeOf(g: Game) = when (g.status) {
        "scheduled" -> "scheduled"
        "in_progress" -> "in_progress"
        else -> when {
            g.homeScore > g.awayScore -> "home_win"
            g.awayScore > g.homeScore -> "away_win"
            else -> "draw"
        }
    }

    override suspend fun match(id: String): MatchDetailDto {
        pause("match$id")
        val g = synchronized(lock) { games.firstOrNull { it.matchId == id } }
            ?: fail(404, "MATCH_NOT_FOUND", "Match not found.")
        val goals = ArrayList<Pair<Int, String>>() // minute, team
        for (i in 0 until g.homeScore) goals += (10 + i * 20) to g.home.id
        for (i in 0 until g.awayScore) goals += (25 + i * 20) to g.away.id
        goals.sortBy { it.first }
        val events = if (g.matchId == LIVE_ID) {
            synchronized(lock) { liveEvents.toList() }
        } else goals.mapIndexed { idx, (minute, team) ->
            scoreEvent(g.matchId, idx + 1, team, minute)
        }
        val winner = when (outcomeOf(g)) {
            "home_win" -> g.home.id
            "away_win" -> g.away.id
            else -> null
        }
        return MatchDetailDto(
            matchId = g.matchId,
            sportId = "football",
            leagueId = "league-alpha-1",
            status = g.status,
            outcome = outcomeOf(g),
            winnerTeamId = winner,
            home = TeamScoreDto(g.home.id, g.homeScore),
            away = TeamScoreDto(g.away.id, g.awayScore),
            version = (events.size + (postedEvents[id] ?: 0)).toLong(),
            events = events,
            pinnedProducts = listOf(PinnedProductDto("prod-jersey", "Alpha Lions Home Jersey", 4999)),
        )
    }

    private fun scoreEvent(matchId: String, seq: Int, teamId: String, minute: Int) = MatchEventDto(
        sequence = seq,
        eventId = "$matchId-e$seq",
        type = "score",
        payload = buildJsonObject {
            put("team_id", teamId)
            put("event_type", scoringKey)
            put("minute", minute)
        },
        signature = "demo-signature",
    )

    /** Appends one scoring event to the live demo match, alternating home/away. */
    fun simulateLiveEvent() {
        synchronized(lock) {
            val idx = games.indexOfFirst { it.matchId == LIVE_ID }
            if (idx < 0) return
            val g = games[idx]
            liveToggle = !liveToggle
            val homeScores = liveToggle
            games[idx] = if (homeScores) g.copy(homeScore = g.homeScore + 1) else g.copy(awayScore = g.awayScore + 1)
            val team = if (homeScores) g.home.id else g.away.id
            liveEvents += scoreEvent(LIVE_ID, liveEvents.size + 1, team, 10 + liveEvents.size * 5)
        }
    }

    override suspend fun postEvent(matchId: String, body: MatchEventRequest): MatchEventResponse {
        pause("event$matchId")
        val current = match(matchId)
        val extra = (postedEvents[matchId] ?: 0) + 1
        postedEvents[matchId] = extra
        return MatchEventResponse(
            eventId = body.eventId,
            matchId = matchId,
            type = body.type,
            sequence = current.events.size + extra,
            matchVersion = current.version + 1,
            signature = "demo-signature",
        )
    }

    override suspend fun sport(id: String): SportConfigDto {
        pause("sport$id")
        if (id != "football") fail(404, "SPORT_NOT_FOUND", "Sport not found.")
        return SportConfigDto(
            sportId = "football",
            scoringModel = "goals",
            scoringEvents = buildJsonObject {
                footballScoringKeys.forEach { key -> put(key, buildJsonObject { put("points", 1) }) }
            },
        )
    }

    override suspend fun standings(leagueId: String): StandingsDto {
        pause("standings$leagueId")
        requireLeague(leagueId)
        class Row(val team: Team) {
            var w = 0; var d = 0; var l = 0; var gf = 0; var ga = 0
            val played get() = w + d + l
            val pts get() = w * 3 + d
            val diff get() = gf - ga
        }
        val rows = teams.associate { it.id to Row(it) }
        for (g in synchronized(lock) { games.toList() }.filter { it.status == "completed" }) {
            val h = rows.getValue(g.home.id)
            val a = rows.getValue(g.away.id)
            h.gf += g.homeScore; h.ga += g.awayScore
            a.gf += g.awayScore; a.ga += g.homeScore
            when {
                g.homeScore > g.awayScore -> { h.w++; a.l++ }
                g.homeScore < g.awayScore -> { a.w++; h.l++ }
                else -> { h.d++; a.d++ }
            }
        }
        val spots = 2
        val sorted = rows.values.sortedWith(
            compareByDescending<Row> { it.pts }.thenByDescending { it.diff }.thenByDescending { it.gf },
        )
        val table = sorted.mapIndexed { i, r ->
            StandingRowDto(
                rank = i + 1,
                teamId = r.team.id,
                played = r.played,
                won = r.w,
                drawn = r.d,
                lost = r.l,
                scored = r.gf,
                conceded = r.ga,
                diff = r.diff.toDouble(),
                points = r.pts,
                fairPlay = 0,
                status = if (i + 1 <= spots) "qualified" else "active",
            )
        }
        return StandingsDto(
            leagueId = leagueId,
            sportId = "football",
            qualificationSpots = spots,
            table = table,
            h2h = JsonObject(emptyMap()),
            teamNames = teams.associate { it.id to it.name },
        )
    }

    // ---- clubs ----
    override suspend fun clubs(): List<ClubSummaryDto> {
        pause("clubs")
        return listOf(
            ClubSummaryDto("club-alpha-1", "Alpha Lions FC", null),
            ClubSummaryDto("club-alpha-2", "Beta Hawks United", null),
            ClubSummaryDto("club-alpha-3", "Gamma Wolves Athletic", null),
        )
    }

    override suspend fun club(id: String): ClubDto {
        pause("club$id")
        if (id != "club-alpha-1") fail(404, "CLUB_NOT_FOUND", "Club not found.")
        val names = listOf(
            Triple("Marco Reyes", "GK", "sq-first"),
            Triple("Liam Carter", "DEF", "sq-first"),
            Triple("Noah Okafor", "DEF", "sq-first"),
            Triple("Ethan Silva", "MID", "sq-first"),
            Triple("Omar Haddad", "MID", "sq-first"),
            Triple("Jack Turner", "MID", "sq-youth"),
            Triple("Diego Alvarez", "FWD", "sq-first"),
            Triple("Yusuf Khan", "FWD", "sq-youth"),
        )
        return ClubDto(
            clubId = "club-alpha-1",
            name = "Alpha Lions FC",
            tenantId = "tenant-alpha",
            venue = VenueDto("Alpha Stadium", 31.5204, 74.3587),
            socials = buildJsonObject {
                put("website", "https://alphalions.example.com")
                put("twitter", "@alphalions")
                put("instagram", "@alphalionsfc")
            },
            staff = listOf(
                StaffDto("Sofia Marin", "Head Coach"),
                StaffDto("Daniel Park", "Assistant Coach"),
                StaffDto("Amina Rao", "Physio"),
            ),
            stats = listOf(ClubStatDto("2026", 3, 2, 0, 1, 7, 5)),
            roster = names.mapIndexed { i, (n, pos, sq) ->
                RosterPlayerDto(
                    playerId = "p${i + 1}",
                    name = n,
                    position = pos,
                    role = "player",
                    squadId = sq,
                    leagueId = "league-alpha-1",
                    onLoan = false,
                    auditVersion = 1,
                )
            },
            squads = listOf(
                SquadDto("sq-first", "First Team", "club-alpha-1"),
                SquadDto("sq-youth", "Youth Squad", "club-alpha-1"),
            ),
        )
    }

    override suspend fun rosterTransfer(clubId: String, body: JsonObject): RosterTransferResponse {
        pause("transfer$clubId")
        if (clubId != "club-alpha-1") fail(404, "CLUB_NOT_FOUND", "Club not found.")
        val reqId = (body["request_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: UUID.randomUUID().toString()
        return RosterTransferResponse(
            requestId = reqId,
            status = "approved",
            state = "completed",
            registration = buildJsonObject { put("status", "registered") },
            auditEntry = buildJsonObject { put("action", "roster_transfer") },
        )
    }

    // ---- commerce ----
    private val fakeStore = FakeStore(AndroidReceiptPdfGenerator())

    private fun requireNetwork() {
        if (!hasNetwork()) throw java.net.UnknownHostException("offline")
    }

    override suspend fun products(): List<ProductDto> {
        requireNetwork()
        return fakeStore.products()
    }

    override suspend fun checkout(body: CheckoutRequest): CheckoutResponse {
        requireNetwork()
        return fakeStore.checkout(body)
    }

    override suspend fun receiptPdf(orderId: String): ResponseBody {
        requireNetwork()
        delay(300L)
        val bytes = fakeStore.receiptPdf(orderId) ?: fail(404, "ORDER_NOT_FOUND", "Order not found.")
        return bytes.toResponseBody("application/pdf".toMediaType())
    }

    // ---- sync ----
    override suspend fun sync(body: SyncRequest): SyncResponse {
        pause("sync${body.deviceId}")
        val results = body.actions.map {
            syncOrder++
            SyncResultDto(
                actionId = it.actionId,
                status = "applied",
                resolution = "applied",
                processedOrder = syncOrder,
                serverVersion = it.baseVersion + 1,
                signature = "demo-signature",
            )
        }
        return SyncResponse(
            deviceId = body.deviceId,
            results = results,
            summary = SyncSummaryDto(applied = results.size, rejected = 0, duplicate = 0),
        )
    }
}
