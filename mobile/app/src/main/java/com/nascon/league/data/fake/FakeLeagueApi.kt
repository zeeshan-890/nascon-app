package com.nascon.league.data.fake

import com.nascon.league.data.api.LeagueApi
import com.nascon.league.data.api.dto.CheckoutRequest
import com.nascon.league.data.api.dto.CheckoutResponse
import com.nascon.league.data.api.dto.ClubDto
import com.nascon.league.data.api.dto.ClubStatDto
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

class FakeLeagueApi : LeagueApi {

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

    private val games = listOf(
        Game("demo-m-1", lions, hawks, "completed", "2026-10-04T14:00:00Z", 2, 1),
        Game("demo-m-2", wolves, sharks, "completed", "2026-10-04T16:00:00Z", 0, 0),
        Game("demo-m-3", sharks, hawks, "completed", "2026-10-11T14:00:00Z", 1, 2),
        Game("demo-m-live", lions, sharks, "in_progress", "2026-10-17T14:00:00Z", 1, 0),
        Game("demo-m-5", hawks, wolves, "scheduled", "2026-10-18T14:00:00Z", 0, 0),
        Game("demo-m-6", wolves, lions, "scheduled", "2026-10-25T14:00:00Z", 0, 0),
    )

    private val postedEvents = HashMap<String, Int>()
    private var syncOrder = 0

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

    private suspend fun pause(key: String) {
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
        return games.map {
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
        val g = games.firstOrNull { it.matchId == id } ?: fail(404, "MATCH_NOT_FOUND", "Match not found.")
        val goals = ArrayList<Pair<Int, String>>() // minute, team
        for (i in 0 until g.homeScore) goals += (10 + i * 20) to g.home.id
        for (i in 0 until g.awayScore) goals += (25 + i * 20) to g.away.id
        goals.sortBy { it.first }
        val events = goals.mapIndexed { idx, (minute, team) ->
            MatchEventDto(
                sequence = idx + 1,
                eventId = "${g.matchId}-e${idx + 1}",
                type = "goal",
                payload = buildJsonObject {
                    put("team_id", team)
                    put("minute", minute)
                },
                signature = "demo-signature",
            )
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
                put("goal", buildJsonObject { put("points", 1) })
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
        for (g in games.filter { it.status == "completed" }) {
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
    override suspend fun products(): List<ProductDto> {
        pause("products")
        return listOf(
            ProductDto(
                "prod-jersey", productNames.getValue("prod-jersey"), "apparel", null,
                listOf(
                    VariantDto("var-jersey-s", "S", "Blue", 4999, 20),
                    VariantDto("var-jersey-m", "M", "Blue", 4999, 15),
                    VariantDto("var-jersey-l", "L", "Blue", 5499, 8),
                ),
            ),
            ProductDto(
                "prod-scarf", productNames.getValue("prod-scarf"), "accessories", null,
                listOf(
                    VariantDto("var-scarf-red", null, "Red", 1999, 30),
                    VariantDto("var-scarf-blue", null, "Blue", 1999, 25),
                ),
            ),
            ProductDto(
                "prod-cap", productNames.getValue("prod-cap"), "accessories", null,
                listOf(
                    VariantDto("var-cap-black", null, "Black", 1499, 40),
                    VariantDto("var-cap-white", null, "White", 1499, 0),
                ),
            ),
        )
    }

    override suspend fun checkout(body: CheckoutRequest): CheckoutResponse {
        pause("checkout${body.orderId}")
        if (body.items.isEmpty()) fail(422, "EMPTY_CART", "Cart is empty.")
        var subtotal = 0L
        val items = body.items.map {
            val v = variants[it.variantId] ?: fail(404, "VARIANT_NOT_FOUND", "Variant not found.")
            val line = v.third * it.quantity
            subtotal += line
            buildJsonObject {
                put("variant_id", it.variantId)
                put("product_id", v.first)
                put("name", productNames.getValue(v.first))
                put("quantity", it.quantity)
                put("unit_price_minor", v.third)
                put("line_total_minor", line)
            }
        }
        val code = body.discountCode?.trim().orEmpty()
        val discount = when {
            code.isEmpty() -> 0L
            code.equals("WELCOME10", ignoreCase = true) -> subtotal / 10
            else -> fail(422, "INVALID_DISCOUNT_CODE", "Discount code is not valid.")
        }
        val total = subtotal - discount
        return CheckoutResponse(
            orderId = body.orderId,
            status = "paid",
            currency = "USD",
            items = items,
            subtotalMinor = subtotal,
            discountMinor = discount,
            totalMinor = total,
            payment = buildJsonObject {
                put("status", "captured")
                put("amount_minor", total)
            },
            receipt = ReceiptDto(
                payload = buildJsonObject {
                    put("order_id", body.orderId)
                    put("total_minor", total)
                    put("currency", "USD")
                },
                signature = "demo-signature",
                pdfBase64 = "",
            ),
        )
    }

    override suspend fun receiptPdf(orderId: String): ResponseBody {
        pause("receipt$orderId")
        val pdf = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
            "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
            "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n" +
            "trailer<</Root 1 0 R>>\n%%EOF\n"
        return pdf.toByteArray().toResponseBody("application/pdf".toMediaType())
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
