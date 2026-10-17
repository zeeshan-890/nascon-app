package com.nascon.league.data.fake

import com.nascon.league.data.api.dto.RosterPlayerDto
import com.nascon.league.data.api.dto.RosterTransferResponse
import com.nascon.league.data.api.dto.SquadDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

sealed interface RosterOutcome {
    data class Applied(val response: RosterTransferResponse, val resolution: String, val serverVersion: Long) : RosterOutcome
    data class Duplicate(val response: RosterTransferResponse, val serverVersion: Long) : RosterOutcome
    data class Rejected(val code: String, val serverVersion: Long) : RosterOutcome
}

/** In-memory roster of the demo backend, implementing DESIGN section 13 rules (pure Kotlin). */
class FakeRoster {

    private class Player(
        val id: String,
        val name: String,
        val position: String,
        var squadId: String?,
        var parentSquadId: String? = null,
        var onLoan: Boolean = false,
        var loanEnd: LocalDate? = null,
        var audits: Int = 0,
    )

    private data class SquadRec(val id: String, val name: String, val clubId: String, val tenantId: String)

    // ---- fake league data (window, size limit, minimum positions) ----
    val leagueId = "league-alpha-1"
    val tenantId = "tenant-alpha"
    val clubId = "club-alpha-1"
    val windowOpen: LocalDate = LocalDate.parse("2026-08-01")
    val windowClose: LocalDate = LocalDate.parse("2026-11-30")
    val maxSquadSize = 9
    private val minPositions: Map<String, Map<String, Int>> = mapOf(
        "sq-first" to mapOf("GK" to 1, "DEF" to 2, "MID" to 2, "FWD" to 1),
    )

    private val lock = Any()
    private val squads = listOf(
        SquadRec("sq-first", "First Team", clubId, tenantId),
        SquadRec("sq-youth", "Youth Squad", clubId, tenantId),
        SquadRec("sq-foreign", "Visitors XI", "club-other", "tenant-other"),
    )
    private val players = listOf(
        Player("p1", "Marco Reyes", "GK", "sq-first"),
        Player("p2", "Liam Carter", "DEF", "sq-first"),
        Player("p3", "Noah Okafor", "DEF", "sq-first"),
        Player("p4", "Ethan Silva", "MID", "sq-first"),
        Player("p5", "Omar Haddad", "MID", "sq-first"),
        Player("p6", "Jack Turner", "MID", "sq-youth"),
        Player("p7", "Diego Alvarez", "FWD", "sq-first"),
        Player("p8", "Yusuf Khan", "FWD", "sq-youth", "sq-first", true, LocalDate.parse("2027-06-30"), 1),
        Player("p9", "Sami Rahman", "DEF", "sq-first"),
        Player("p10", "Karim Nasser", "MID", "sq-first"),
        Player("p11", "Leo Fernandez", "FWD", "sq-first"),
        Player("p12", "Ravi Patel", "DEF", "sq-youth"),
    )
    private val requests = HashMap<String, Pair<JsonObject, RosterTransferResponse>>()
    private var auditSeq = 1

    fun playerDtos(): List<RosterPlayerDto> = synchronized(lock) {
        players.map {
            RosterPlayerDto(
                playerId = it.id,
                name = it.name,
                position = it.position,
                role = "player",
                squadId = it.squadId,
                leagueId = leagueId,
                onLoan = it.onLoan,
                auditVersion = it.audits.toLong(),
            )
        }
    }

    fun squadDtos(): List<SquadDto> = squads.filter { it.clubId == clubId }.map { SquadDto(it.id, it.name, it.clubId) }

    fun auditVersion(playerId: String): Long = synchronized(lock) { players.firstOrNull { it.id == playerId }?.audits?.toLong() ?: 0 }

    fun squadSize(squadId: String): Int = synchronized(lock) { players.count { it.squadId == squadId } }

    /** baseVersion null = direct POST (no base version check). */
    fun transfer(body: JsonObject, baseVersion: Long?): RosterOutcome = synchronized(lock) {
        val playerId = body.str("player_id")
        val ver = auditVersion(playerId.orEmpty())
        fun bad() = RosterOutcome.Rejected("VALIDATION_ERROR", ver)

        // 1. validation
        val requestId = body.str("request_id")?.takeIf { it.isNotBlank() } ?: return bad()
        val league = body.str("league_id")?.takeIf { it.isNotBlank() } ?: return bad()
        if (playerId.isNullOrBlank()) return bad()
        val type = body.str("type")
        if (type !in setOf("transfer", "loan", "release")) return bad()
        val from = body.str("from_squad_id")?.takeIf { it.isNotBlank() } ?: return bad()
        val to = body.str("to_squad_id")?.takeIf { it.isNotBlank() }
        val effective = parseDate(body.str("effective_date")) ?: return bad()
        if (body.str("reason").isNullOrBlank() || body.str("requested_by").isNullOrBlank()) return bad()
        if (type != "release") {
            if (to == null || to == from) return bad()
        }
        var loanEnd: LocalDate? = null
        if (type == "loan") {
            loanEnd = parseDate(body.str("loan_end_date")) ?: return bad()
            if (!loanEnd.isAfter(effective)) return bad()
        }
        // idempotency
        requests[requestId]?.let { (stored, resp) ->
            return if (stored == body) RosterOutcome.Duplicate(resp, ver)
            else RosterOutcome.Rejected("DUPLICATE_REQUEST_ID", ver)
        }
        // 2. not found
        if (league != leagueId) return RosterOutcome.Rejected("LEAGUE_NOT_FOUND", 0)
        val player = players.firstOrNull { it.id == playerId } ?: return RosterOutcome.Rejected("PLAYER_NOT_FOUND", 0)
        val fromSquad = squads.firstOrNull { it.id == from } ?: return RosterOutcome.Rejected("SQUAD_NOT_FOUND", ver)
        val toSquad = if (type == "release") null
        else squads.firstOrNull { it.id == to } ?: return RosterOutcome.Rejected("SQUAD_NOT_FOUND", ver)
        if (baseVersion != null && baseVersion > player.audits) {
            return RosterOutcome.Rejected("INVALID_BASE_VERSION", ver)
        }
        // 3. tenant
        if (fromSquad.tenantId != tenantId || (toSquad != null && toSquad.tenantId != tenantId)) {
            return RosterOutcome.Rejected("CROSS_TENANT_TRANSFER", ver)
        }
        // 4. loan return then membership
        val le = player.loanEnd
        if (player.onLoan && le != null && le.isBefore(effective)) {
            player.squadId = player.parentSquadId
            player.parentSquadId = null
            player.onLoan = false
            player.loanEnd = null
            player.audits++
        }
        fun rej(code: String) = RosterOutcome.Rejected(code, player.audits.toLong())
        if (player.squadId != from) return rej("NOT_IN_SQUAD")
        // 5. window
        if (type != "release" && (effective.isBefore(windowOpen) || effective.isAfter(windowClose))) {
            return rej("TRANSFER_WINDOW_CLOSED")
        }
        // 6. on loan
        if (player.onLoan) return rej("PLAYER_ON_LOAN")
        // 7. squad size
        if (type != "release" && players.count { it.squadId == to } + 1 > maxSquadSize) return rej("SQUAD_FULL")
        // 8. minimum position
        val min = minPositions[from]?.get(player.position)
        if (min != null && players.count { it.squadId == from && it.position == player.position } - 1 < min) {
            return rej("MIN_POSITION_VIOLATION")
        }
        // effects
        val current = player.audits.toLong()
        when (type) {
            "transfer" -> player.squadId = to
            "loan" -> {
                player.squadId = to
                player.parentSquadId = from
                player.onLoan = true
                player.loanEnd = loanEnd
            }
            else -> player.squadId = null
        }
        player.audits++
        val audit = buildJsonObject {
            put("audit_id", "au-%04d".format(auditSeq++))
            put("player_id", player.id)
            put("action", type!!)
            put("from_squad_id", from)
            if (to != null) put("to_squad_id", to)
            put("effective_date", effective.toString())
            put("who", body.str("requested_by").orEmpty())
            put("why", body.str("reason").orEmpty())
        }
        val resp = RosterTransferResponse(
            requestId = requestId,
            status = "accepted",
            state = "active",
            blockingMatchId = null,
            registration = if (type == "release") null else buildJsonObject {
                put("player_id", player.id)
                put("squad_id", to!!)
                put("type", type)
            },
            auditEntry = audit,
        )
        requests[requestId] = body to resp
        RosterOutcome.Applied(
            resp,
            if (baseVersion == null || baseVersion == current) "appended" else "rebased_append",
            player.audits.toLong(),
        )
    }

    private fun parseDate(s: String?): LocalDate? = try {
        if (s == null || !Regex("\\d{4}-\\d{2}-\\d{2}").matches(s)) null else LocalDate.parse(s)
    } catch (_: Exception) {
        null
    }
}
