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
import com.nascon.league.data.api.dto.MeResponse
import com.nascon.league.data.api.dto.MatchEventDto
import com.nascon.league.data.api.dto.MatchEventRequest
import com.nascon.league.data.api.dto.MatchEventResponse
import com.nascon.league.data.api.dto.PingResponse
import com.nascon.league.data.api.dto.PinnedProductDto
import com.nascon.league.data.api.dto.ProductDto
import com.nascon.league.data.api.dto.ReceiptDto
import com.nascon.league.data.api.dto.RefreshRequest
import com.nascon.league.data.api.dto.RefreshResponse
import com.nascon.league.data.api.dto.OrderDetailDto
import com.nascon.league.data.api.dto.PlayerHistoryDto
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
import okhttp3.Headers
import retrofit2.HttpException
import retrofit2.Response
import java.util.UUID
import kotlin.math.abs

class FakeLeagueApi(private val hasNetwork: () -> Boolean = { true }) : LeagueApi {

    private var signedIn: LoginResponse? = null
    private val matches = FakeMatches()
    private val rosterState = FakeRoster()
    private val syncState = FakeSync(matches, rosterState)
    private val teams = matches.teams

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
        val response = LoginResponse(
            accessToken = "fake-access-${UUID.randomUUID()}",
            refreshToken = "fake-refresh-${UUID.randomUUID()}",
            expiresIn = 3600,
            user = user,
            tenant = if (withTenant) tenant() else null,
        )
        signedIn = response
        return response
    }

    override suspend fun me(): MeResponse {
        pause("me")
        val session = signedIn ?: fail(401, "UNAUTHENTICATED", "Authentication required.")
        return MeResponse(session.user, session.tenant)
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

    override suspend fun leagues(limit: Int?, offset: Int?): Response<List<LeagueDto>> {
        pause("leagues")
        return paged(listOf(LeagueDto("league-alpha-1", "Alpha Premier League", "football", "2026")), limit, offset)
    }

    override suspend fun fixtures(leagueId: String, limit: Int?, offset: Int?): Response<List<FixtureDto>> {
        pause("fixtures$leagueId")
        requireLeague(leagueId)
        return paged(matches.fixtures(), limit, offset)
    }

    override suspend fun match(id: String): MatchDetailDto {
        pause("match$id")
        return matches.detail(id) ?: fail(404, "MATCH_NOT_FOUND", "Match not found.")
    }

    /** Appends one scoring event to the live demo match, alternating home/away. */
    fun simulateLiveEvent() = matches.simulateLiveEvent()

    override suspend fun postEvent(matchId: String, body: MatchEventRequest): MatchEventResponse {
        pause("event$matchId")
        return when (val o = matches.applyEvent(body.copy(matchId = matchId), null)) {
            is MatchOutcome.Applied -> o.response
            is MatchOutcome.Duplicate -> o.response
            is MatchOutcome.Rejected -> when (o.code) {
                "MATCH_NOT_FOUND" -> fail(404, o.code, "Match not found.")
                "VALIDATION_ERROR" -> fail(400, o.code, "Invalid event.")
                else -> fail(409, o.code, "Event rejected.")
            }
        }
    }

    override suspend fun sport(id: String): SportConfigDto {
        pause("sport$id")
        if (id != "football") fail(404, "SPORT_NOT_FOUND", "Sport not found.")
        return SportConfigDto(
            sportId = "football",
            scoringModel = "goals",
            scoringEvents = buildJsonObject {
                matches.scoringPoints.forEach { (key, pts) -> put(key, buildJsonObject { put("points", pts) }) }
            },
        )
    }

    override suspend fun standings(leagueId: String): StandingsDto {
        pause("standings$leagueId")
        requireLeague(leagueId)
        class Row(val team: FakeTeam) {
            var w = 0; var d = 0; var l = 0; var gf = 0; var ga = 0
            val played get() = w + d + l
            val pts get() = w * 3 + d
            val diff get() = gf - ga
        }
        val rows = teams.associate { it.id to Row(it) }
        for (g in matches.games().filter { it.status == "completed" }) {
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
    override suspend fun clubs(limit: Int?, offset: Int?): Response<List<ClubSummaryDto>> {
        pause("clubs")
        return paged(listOf(
            ClubSummaryDto("club-alpha-1", "Alpha Lions FC", null),
            ClubSummaryDto("club-alpha-2", "Beta Hawks United", null),
            ClubSummaryDto("club-alpha-3", "Gamma Wolves Athletic", null),
        ), limit, offset)
    }

    override suspend fun club(id: String): ClubDto {
        pause("club$id")
        if (id != "club-alpha-1") fail(404, "CLUB_NOT_FOUND", "Club not found.")
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
            roster = rosterState.playerDtos(),
            squads = rosterState.squadDtos(),
        )
    }

    override suspend fun rosterTransfer(clubId: String, body: JsonObject): RosterTransferResponse {
        pause("transfer$clubId")
        if (clubId != "club-alpha-1") fail(404, "CLUB_NOT_FOUND", "Club not found.")
        val withId = if (body["request_id"] is JsonPrimitive) body
        else JsonObject(body + ("request_id" to JsonPrimitive(UUID.randomUUID().toString())))
        return when (val o = rosterState.transfer(withId, null)) {
            is RosterOutcome.Applied -> o.response
            is RosterOutcome.Duplicate -> o.response
            is RosterOutcome.Rejected -> when {
                o.code == "VALIDATION_ERROR" -> fail(400, o.code, "Invalid transfer.")
                o.code.endsWith("_NOT_FOUND") -> fail(404, o.code, "Not found.")
                else -> fail(409, o.code, "Transfer rejected.")
            }
        }
    }

    // ---- commerce ----
    private val fakeStore = FakeStore(AndroidReceiptPdfGenerator())

    /** Shared demo store, so the club orders fake sees (and changes) the same orders. */
    fun orderStore(): FakeStore = fakeStore

    private fun requireNetwork() {
        if (!hasNetwork()) throw java.net.UnknownHostException("offline")
    }

    override suspend fun products(limit: Int?, offset: Int?): Response<List<ProductDto>> {
        requireNetwork()
        return paged(fakeStore.products(), limit, offset)
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

    // ---- player history / order detail ----
    override suspend fun playerHistory(playerId: String, asOf: String?): PlayerHistoryDto {
        pause("history$playerId")
        return rosterState.history(playerId, asOf) ?: fail(404, "PLAYER_NOT_FOUND", "Player not found.")
    }

    override suspend fun order(orderId: String): OrderDetailDto {
        requireNetwork()
        return fakeStore.order(orderId)
    }

    // ---- sync ----
    override suspend fun sync(body: SyncRequest): SyncResponse {
        pause("sync${body.deviceId}")
        return syncState.sync(body)
    }

    private fun <T> paged(all: List<T>, limit: Int?, offset: Int?): Response<List<T>> {
        val start = (offset ?: 0).coerceAtLeast(0)
        val end = if (limit == null || limit < 0) all.size else minOf(all.size, start + limit.coerceAtLeast(0))
        val slice = if (start >= all.size) emptyList() else all.subList(start, end.coerceAtLeast(start))
        return Response.success(slice, Headers.headersOf("X-Total-Count", all.size.toString()))
    }
}
