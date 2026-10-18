package com.nascon.league.data.platformadmin

import com.nascon.league.core.network.AppJson
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Roles in the order the access matrix shows them. */
val ACCESS_ROLES = listOf("super_admin", "league_organizer", "club_manager", "player", "viewer")

/** Mirrors backend/src/domain/rbac/defaultPolicies.js (DEFAULT_POLICIES). */
fun defaultAccessPolicies(): List<AccessPolicyDto> {
    val readActions = listOf("league.read", "match.read", "standings.read", "sport.read", "product.read")
    val allSameTenant = listOf("league_organizer", "club_manager", "player", "viewer")
    val rows = mutableListOf<Triple<String, String, String>>()
    fun add(role: String, action: String, scope: String) { rows += Triple(role, action, scope) }
    listOf("tenant.create", "league.fixtures.write", "club.roster.write", "club.products.write", "player.update", "club.read", "platform.config.read")
        .forEach { add("super_admin", it, "any") }
    add("league_organizer", "league.fixtures.write", "own_league")
    add("league_organizer", "club.read", "same_tenant")
    add("club_manager", "club.roster.write", "own_club")
    add("club_manager", "club.products.write", "own_club")
    add("club_manager", "player.update", "own_club_player")
    add("club_manager", "club.read", "same_tenant")
    add("player", "player.update", "self")
    add("player", "club.read", "same_tenant")
    add("viewer", "club.read", "same_tenant")
    ACCESS_ROLES.forEach { role -> readActions.forEach { add(role, it, if (role == "super_admin") "any" else "same_tenant") } }
    add("super_admin", "match.score.write", "any")
    add("league_organizer", "match.score.write", "own_league")
    add("super_admin", "match.manage", "any")
    add("league_organizer", "match.manage", "own_league")
    add("super_admin", "season.write", "any")
    add("super_admin", "league.create", "any")
    add("super_admin", "club.write", "any")
    add("club_manager", "club.write", "own_club")
    ACCESS_ROLES.forEach { add(it, "order.create", "any") }
    add("super_admin", "order.read", "any")
    allSameTenant.forEach { add(it, "order.read", "self") }
    add("super_admin", "order.manage", "any")
    add("club_manager", "order.manage", "own_club")
    add("super_admin", "player.history.read", "any")
    add("club_manager", "player.history.read", "own_club_player")
    add("player", "player.history.read", "self")
    return rows.map { AccessPolicyDto(it.first, it.second, it.third) }
}

private const val SPORTS_SEED = """
[
 {"sport_id":"football","name":"Football","scoring_model":"event","periods":{"count":2,"duration_minutes":45},
  "scoring_events":{"goal":{"points":1}},"draw_allowed":true,"overtime":{"policy":"none"},
  "tie_break_order":["h2h_points","h2h_diff","overall_diff","scored","fair_play","draw_lots"],
  "roster":{"min_squad_size":11,"max_squad_size":25,"required_positions":{"gk":1}}},
 {"sport_id":"basketball","name":"Basketball","scoring_model":"event","periods":{"count":4,"duration_minutes":12},
  "scoring_events":{"free_throw":{"points":1},"two_pointer":{"points":2},"three_pointer":{"points":3}},"draw_allowed":false,
  "overtime":{"policy":"extra_time","duration_minutes":5},
  "tie_break_order":["h2h_points","h2h_diff","overall_diff","scored","fair_play","draw_lots"],
  "roster":{"min_squad_size":5,"max_squad_size":15,"required_positions":{}}},
 {"sport_id":"cricket","name":"Cricket","scoring_model":"innings",
  "periods":{"innings_per_team":1,"overs_per_innings":20,"balls_per_over":6,"wickets_per_innings":10},
  "scoring_events":{"dot_ball":{"runs":0,"legal_ball":true,"wicket":false},"single":{"runs":1,"legal_ball":true,"wicket":false},
   "two":{"runs":2,"legal_ball":true,"wicket":false},"four":{"runs":4,"legal_ball":true,"wicket":false},
   "six":{"runs":6,"legal_ball":true,"wicket":false},"wide":{"runs":1,"legal_ball":false,"wicket":false},
   "no_ball":{"runs":1,"legal_ball":false,"wicket":false},"wicket":{"runs":0,"legal_ball":true,"wicket":true}},
  "draw_allowed":true,"overtime":{"policy":"super_over"},
  "tie_break_order":["h2h_points","h2h_diff","overall_diff","scored","fair_play","draw_lots"],
  "roster":{"min_squad_size":11,"max_squad_size":18,"required_positions":{"wicketkeeper":1}}},
 {"sport_id":"esports","name":"Esports","scoring_model":"round","periods":{"best_of":3},
  "scoring_events":{"round_win":{"rounds":1}},"draw_allowed":false,"overtime":{"policy":"none"},
  "tie_break_order":["h2h_points","h2h_diff","overall_diff","scored","fair_play","draw_lots"],
  "roster":{"min_squad_size":5,"max_squad_size":8,"required_positions":{}}}
]
"""

fun seededSports(): List<SportDefinitionDto> =
    AppJson.decodeFromString(ListSerializer(SportDefinitionDto.serializer()), SPORTS_SEED)

/** Fixed point in time the seeded audit entries count back from, so the demo is deterministic. */
val AUDIT_SEED_NEWEST: Instant = Instant.parse("2026-10-17T10:42:00Z")

private data class AuditActor(val id: String, val name: String, val role: String)

private val seedActors = listOf(
    AuditActor("user-admin", "Admin", "super_admin"),
    AuditActor("user-organizer", "Sara Malik", "league_organizer"),
    AuditActor("user-manager", "Ayesha Khan", "club_manager"),
)

private fun obj(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

private class AuditSeed(
    val actor: Int,
    val action: String,
    val summary: String,
    val target: String,
    val reason: String?,
    val before: JsonElement?,
    val after: JsonElement?,
)

private val auditSeeds = listOf(
    AuditSeed(2, "roster.transfer_approved", "Transfer approved", "Alpha Club 1", null,
        obj("club" to "Alpha Club 2"), obj("club" to "Alpha Club 1")),
    AuditSeed(1, "score.corrected", "Score corrected", "Alpha Club 1 v Alpha Club 2", "Entered for the wrong team",
        obj("home" to "1", "away" to "2"), obj("home" to "0", "away" to "2")),
    AuditSeed(0, "user.role_changed", "Role changed to manager", "Ayesha Khan", null,
        obj("role" to "viewer"), obj("role" to "club_manager")),
    AuditSeed(1, "fixture.rescheduled", "Fixture rescheduled", "Alpha Club 2 v Alpha Club 1", "Pitch unavailable",
        obj("kickoff" to "2026-10-18 15:00"), obj("kickoff" to "2026-10-19 17:30")),
    AuditSeed(2, "order.refunded", "Refund issued", "Order 1042", "Wrong size",
        obj("status" to "paid"), obj("status" to "refunded")),
    AuditSeed(0, "platform.club_created", "Club created", "Alpha Club 2", null,
        null, obj("name" to "Alpha Club 2", "venue" to "Alpha Ground")),
    AuditSeed(1, "score.entered", "Score entered", "Alpha Club 1 v Alpha Club 2", null,
        null, obj("home" to "1", "away" to "0")),
    AuditSeed(0, "platform.access_updated", "Access rules changed", "Club manager", null,
        obj("order.manage" to "none"), obj("order.manage" to "own_club")),
    AuditSeed(2, "roster.player_added", "Player added to squad", "Alpha Club 1", null,
        null, obj("squad_size" to "23")),
    AuditSeed(0, "user.deactivated", "Account deactivated", "Former viewer", "Left the organisation",
        obj("active" to "yes"), obj("active" to "no")),
    AuditSeed(1, "fixture.generated", "Fixtures generated", "Alpha Premier League", null,
        null, obj("rounds" to "9")),
    AuditSeed(0, "platform.sport_updated", "Sport settings changed", "Sport", null,
        obj("draw_allowed" to "no"), obj("draw_allowed" to "yes")),
)

fun seededAudit(): List<AuditEntryDto> = (0 until 36).map { i ->
    val seed = auditSeeds[i % auditSeeds.size]
    val actor = seedActors[seed.actor]
    AuditEntryDto(
        id = "aud-%03d".format(36 - i),
        at = AUDIT_SEED_NEWEST.minusSeconds(i * 53L * 60L).toString(),
        actorId = actor.id,
        actorName = actor.name,
        actorRole = actor.role,
        action = seed.action,
        target = seed.target,
        summary = seed.summary,
        requestId = "%04x-%04x".format(0xa91f + i * 37, 0x30c2 + i * 11),
        reason = seed.reason,
        before = seed.before,
        after = seed.after,
    )
}

/** Scoring event codes: lower-case words joined by single underscores, starting with a letter. */
fun isValidEventKey(key: String): Boolean = key.length in 1..40 && EVENT_KEY.matches(key)
private val EVENT_KEY = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")

/**
 * In-memory platform admin backend for demo mode. Matches the real server for `sport` and applies the
 * contract-addition rules (validation, 409/422 style failures) so the UI error paths can be exercised.
 */
class FakePlatformAdminApi(
    private val hasNetwork: () -> Boolean,
    private val pause: suspend (Int) -> Unit = { delay(it.toLong()) },
    private val now: () -> Instant = Instant::now,
) : PlatformAdminApi {
    private var calls = 0
    private val sports = LinkedHashMap<String, SportDefinitionDto>().also { m -> seededSports().forEach { m[it.sportId] = it } }
    private var policies: List<AccessPolicyDto> = defaultAccessPolicies()
    private val audit = seededAudit().toMutableList()
    private val clubIds = mutableSetOf("club-alpha-1", "club-alpha-2")
    private val clubNames = mutableSetOf("alpha club 1", "alpha club 2")
    private val knownTenants = setOf("tenant-alpha")

    private suspend fun gate() {
        if (!hasNetwork()) throw java.net.UnknownHostException("offline")
        pause(300 + (calls++ * 97) % 401)
    }

    private fun httpError(status: Int, code: String, message: String): HttpException {
        val body = """{"error":{"code":"$code","message":"$message","details":{}}}"""
        return HttpException(Response.error<Any>(status, body.toResponseBody("application/json".toMediaType())))
    }

    private fun record(action: String, summary: String, target: String, before: JsonElement? = null, after: JsonElement? = null) {
        val actor = seedActors[0]
        audit.add(0, AuditEntryDto(
            id = "aud-%03d".format(audit.size + 1), at = now().toString(), actorId = actor.id, actorName = actor.name,
            actorRole = actor.role, action = action, target = target, summary = summary,
            requestId = "%08x".format(audit.size + 1), before = before, after = after,
        ))
    }

    override suspend fun createClub(body: AdminClubCreateRequest): AdminClubDto {
        gate()
        if (body.name.isBlank() || body.venueName.isBlank()) throw httpError(422, "VALIDATION_ERROR", "Invalid club")
        if (body.tenantId !in knownTenants) throw httpError(404, "TENANT_NOT_FOUND", "Tenant not found")
        if (body.name.trim().lowercase() in clubNames) throw httpError(409, "CLUB_EXISTS", "Club already exists")
        val slug = body.name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        var id = "club-$slug"
        var n = 2
        while (id in clubIds) id = "club-$slug-${n++}"
        clubIds += id
        clubNames += body.name.trim().lowercase()
        record("platform.club_created", "Club created", body.name.trim(), null,
            obj("name" to body.name.trim(), "venue" to body.venueName.trim()))
        return AdminClubDto(id, body.name.trim(), body.tenantId, body.venueName.trim(), body.city?.trim()?.ifBlank { null })
    }

    override suspend fun sports(): List<SportDefinitionDto> {
        gate()
        return sports.values.toList()
    }

    override suspend fun sport(id: String): SportDefinitionDto {
        gate()
        return sports[id] ?: throw httpError(404, "SPORT_NOT_FOUND", "Sport not found")
    }

    override suspend fun saveSport(id: String, body: SportDefinitionDto): SportDefinitionDto {
        gate()
        val old = sports[id] ?: throw httpError(404, "SPORT_NOT_FOUND", "Sport not found")
        if (body.roster.minSquadSize < 0 || body.roster.maxSquadSize < body.roster.minSquadSize) {
            throw httpError(422, "VALIDATION_ERROR", "Invalid roster limits")
        }
        if (body.scoringEvents.keys.any { !isValidEventKey(it) }) throw httpError(422, "VALIDATION_ERROR", "Invalid event code")
        val saved = body.copy(sportId = id, name = body.name.ifBlank { old.name })
        sports[id] = saved
        record("platform.sport_updated", "Sport settings changed", saved.name,
            obj("draw_allowed" to old.drawAllowed.toString(), "events" to old.scoringEvents.size.toString()),
            obj("draw_allowed" to saved.drawAllowed.toString(), "events" to saved.scoringEvents.size.toString()))
        return saved
    }

    override suspend fun access(): AccessMatrixDto {
        gate()
        return AccessMatrixDto(policies)
    }

    override suspend fun saveAccess(body: AccessMatrixDto): AccessMatrixDto {
        gate()
        val bad = body.policies.any { it.role !in ACCESS_ROLES }
        // Lock-out guard: the super admin must keep every action it has today.
        val lost = policies.filter { it.role == "super_admin" }.any { p ->
            body.policies.none { it.role == "super_admin" && it.action == p.action && it.scope == "any" }
        }
        if (bad || lost) throw httpError(422, "VALIDATION_ERROR", "Invalid access rules")
        val changes = (body.policies.toSet() - policies.toSet()).size + (policies.toSet() - body.policies.toSet()).size
        policies = body.policies
        record("platform.access_updated", "Access rules changed", "$changes rule changes")
        return AccessMatrixDto(policies)
    }

    override suspend fun audit(actor: String?, action: String?, from: String?, to: String?, limit: Int?, offset: Int?): AuditPageDto {
        gate()
        val filter = AuditFilter(
            actor = actor?.ifBlank { null },
            action = action?.ifBlank { null },
            from = from?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            to = to?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
        val all = audit.filter { it.matches(filter, ZoneId.of("UTC")) }.sortedByDescending { it.at }
        val start = (offset ?: 0).coerceAtLeast(0)
        val page = all.drop(start).take((limit ?: 50).coerceIn(1, 200))
        return AuditPageDto(page, all.size)
    }

    override suspend fun auditEntry(id: String): AuditEntryDto {
        gate()
        return audit.firstOrNull { it.id == id } ?: throw httpError(404, "NOT_FOUND", "Entry not found")
    }
}

object PlatformAdminFakeHolder {
    private var inst: FakePlatformAdminApi? = null
    fun get(hasNetwork: () -> Boolean): FakePlatformAdminApi = inst ?: FakePlatformAdminApi(hasNetwork).also { inst = it }
}

/** Demo-mode health: always `{status: ok}`, still failing like a real call when the device is offline. */
class FakeHealthApi(private val hasNetwork: () -> Boolean) : HealthApi {
    override suspend fun health(): HealthDto {
        if (!hasNetwork()) throw java.net.UnknownHostException("offline")
        delay(300)
        return HealthDto("ok")
    }

    override suspend fun ping(): PingDto {
        if (!hasNetwork()) throw java.net.UnknownHostException("offline")
        delay(300)
        return PingDto(true)
    }
}

object HealthFakeHolder {
    private var inst: FakeHealthApi? = null
    fun get(hasNetwork: () -> Boolean): FakeHealthApi = inst ?: FakeHealthApi(hasNetwork).also { inst = it }
}
