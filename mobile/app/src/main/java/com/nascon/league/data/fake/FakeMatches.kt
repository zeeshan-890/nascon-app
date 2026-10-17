package com.nascon.league.data.fake

import com.nascon.league.data.api.dto.FixtureDto
import com.nascon.league.data.api.dto.MatchDetailDto
import com.nascon.league.data.api.dto.MatchEventDto
import com.nascon.league.data.api.dto.MatchEventRequest
import com.nascon.league.data.api.dto.MatchEventResponse
import com.nascon.league.data.api.dto.PinnedProductDto
import com.nascon.league.data.api.dto.TeamScoreDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

data class FakeTeam(val id: String, val name: String)

data class FakeGame(
    val matchId: String,
    val home: FakeTeam,
    val away: FakeTeam,
    val status: String,
    val scheduledAt: String?,
    val homeScore: Int,
    val awayScore: Int,
    val tiebreakWinnerTeamId: String? = null,
)

sealed interface MatchOutcome {
    data class Applied(val response: MatchEventResponse, val resolution: String) : MatchOutcome
    data class Duplicate(val response: MatchEventResponse) : MatchOutcome
    data class Rejected(val code: String, val serverVersion: Long) : MatchOutcome
}

/**
 * In-memory shared match state of the demo backend (pure Kotlin, JVM testable).
 * Fixtures, match detail, standings and sync all read/write this one object.
 */
class FakeMatches {

    val lions = FakeTeam("team-alpha-lions", "Alpha Lions")
    val hawks = FakeTeam("team-beta-hawks", "Beta Hawks")
    val wolves = FakeTeam("team-gamma-wolves", "Gamma Wolves")
    val sharks = FakeTeam("team-delta-sharks", "Delta Sharks")
    val teams = listOf(lions, hawks, wolves, sharks)

    /** Fake sport config data: scoring event key -> points. */
    val scoringPoints: Map<String, Int> = linkedMapOf("goal" to 1)

    private val lock = Any()
    private val games: MutableList<FakeGame> = mutableListOf(
        FakeGame("demo-m-1", lions, hawks, "completed", "2026-10-04T14:00:00Z", 2, 1),
        FakeGame("demo-m-2", wolves, sharks, "completed", "2026-10-04T16:00:00Z", 0, 0),
        FakeGame("demo-m-3", sharks, hawks, "completed", "2026-10-11T14:00:00Z", 1, 2),
        FakeGame(LIVE_ID, lions, sharks, "in_progress", "2026-10-17T14:00:00Z", 1, 0),
        FakeGame("demo-m-5", hawks, wolves, "scheduled", "2026-10-18T14:00:00Z", 0, 0),
        FakeGame("demo-m-6", wolves, lions, "scheduled", "2026-10-25T14:00:00Z", 0, 0),
    )
    private val events = HashMap<String, MutableList<MatchEventDto>>()
    private val requests = HashMap<String, MatchEventRequest>()
    private val responses = HashMap<String, MatchEventResponse>()
    private var liveToggle = false

    init {
        for (g in games) {
            val list = ArrayList<MatchEventDto>()
            var minute = 10
            repeat(g.homeScore) { list += scoreEvent(g.matchId, list.size + 1, g.home.id, minute); minute += 20 }
            minute = 25
            repeat(g.awayScore) { list += scoreEvent(g.matchId, list.size + 1, g.away.id, minute); minute += 20 }
            events[g.matchId] = list
        }
    }

    private fun scoreEvent(matchId: String, seq: Int, teamId: String, minute: Int) = MatchEventDto(
        sequence = seq,
        eventId = "$matchId-e$seq",
        type = "score",
        payload = buildJsonObject {
            put("team_id", teamId)
            put("event_type", scoringPoints.keys.first())
            put("minute", minute)
        },
        signature = "demo-signature",
    )

    fun games(): List<FakeGame> = synchronized(lock) { games.toList() }

    fun version(matchId: String): Long = synchronized(lock) { (events[matchId]?.size ?: 0).toLong() }

    fun hasMatch(matchId: String): Boolean = synchronized(lock) { games.any { it.matchId == matchId } }

    fun fixtures(): List<FixtureDto> = synchronized(lock) {
        games.map {
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
                version = (events[it.matchId]?.size ?: 0).toLong(),
            )
        }
    }

    private fun outcomeOf(g: FakeGame) = when (g.status) {
        "scheduled", "upcoming" -> "scheduled"
        "in_progress" -> "in_progress"
        else -> when {
            g.homeScore > g.awayScore -> "home_win"
            g.awayScore > g.homeScore -> "away_win"
            else -> "draw"
        }
    }

    fun detail(id: String): MatchDetailDto? = synchronized(lock) {
        val g = games.firstOrNull { it.matchId == id } ?: return null
        val evs = events[id].orEmpty().toList()
        val outcome = outcomeOf(g)
        MatchDetailDto(
            matchId = g.matchId,
            sportId = "football",
            leagueId = "league-alpha-1",
            status = g.status,
            outcome = outcome,
            winnerTeamId = when (outcome) {
                "home_win" -> g.home.id
                "away_win" -> g.away.id
                else -> null
            },
            tiebreakWinnerTeamId = g.tiebreakWinnerTeamId,
            home = TeamScoreDto(g.home.id, g.homeScore),
            away = TeamScoreDto(g.away.id, g.awayScore),
            version = evs.size.toLong(),
            events = evs,
            pinnedProducts = listOf(PinnedProductDto("prod-jersey", "Alpha Lions Home Jersey", 4999)),
        )
    }

    /** Appends one scoring event to the live demo match, alternating home/away. */
    fun simulateLiveEvent() {
        synchronized(lock) {
            val idx = games.indexOfFirst { it.matchId == LIVE_ID }
            if (idx < 0) return
            val g = games[idx]
            if (g.status != "in_progress") return
            liveToggle = !liveToggle
            games[idx] = if (liveToggle) g.copy(homeScore = g.homeScore + 1) else g.copy(awayScore = g.awayScore + 1)
            val list = events.getOrPut(LIVE_ID) { ArrayList() }
            val team = if (liveToggle) g.home.id else g.away.id
            list += scoreEvent(LIVE_ID, list.size + 1, team, 10 + list.size * 5)
        }
    }

    /**
     * Same rule set the server runs for POST /events and for sync score_event actions.
     * baseVersion null = direct POST (no base version check).
     */
    fun applyEvent(req: MatchEventRequest, baseVersion: Long?): MatchOutcome = synchronized(lock) {
        if (req.eventId.isBlank() || req.matchId.isBlank() || req.type !in KNOWN_TYPES) {
            return MatchOutcome.Rejected("VALIDATION_ERROR", version(req.matchId))
        }
        val stored = requests[req.eventId]
        if (stored != null) {
            return if (stored == req) MatchOutcome.Duplicate(responses.getValue(req.eventId))
            else MatchOutcome.Rejected("DUPLICATE_EVENT_ID", version(stored.matchId))
        }
        if (events.values.any { l -> l.any { it.eventId == req.eventId } }) {
            return MatchOutcome.Rejected("DUPLICATE_EVENT_ID", version(req.matchId))
        }
        val idx = games.indexOfFirst { it.matchId == req.matchId }
        if (idx < 0) return MatchOutcome.Rejected("MATCH_NOT_FOUND", 0)
        val g = games[idx]
        val list = events.getOrPut(g.matchId) { ArrayList() }
        val current = list.size.toLong()
        if (baseVersion != null && baseVersion > current) {
            return MatchOutcome.Rejected("INVALID_BASE_VERSION", current)
        }
        fun reject(code: String) = MatchOutcome.Rejected(code, current)
        val live = g.status == "in_progress"
        val teamId = (req.payload["team_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        var updated = g
        when (req.type) {
            "match_start" -> {
                if (g.status != "scheduled" && g.status != "upcoming") return reject("ILLEGAL_TRANSITION")
                updated = g.copy(status = "in_progress")
            }
            "match_end" -> {
                if (!live) return reject("ILLEGAL_TRANSITION")
                updated = g.copy(status = "completed")
            }
            "score" -> {
                if (!live) return reject("MATCH_NOT_IN_PROGRESS")
                val key = (req.payload["event_type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val pts = scoringPoints[key]
                if (pts == null || (teamId != g.home.id && teamId != g.away.id)) return reject("VALIDATION_ERROR")
                updated = if (teamId == g.home.id) g.copy(homeScore = g.homeScore + pts)
                else g.copy(awayScore = g.awayScore + pts)
            }
            "discipline" -> {
                if (!live) return reject("MATCH_NOT_IN_PROGRESS")
                val pts = (req.payload["points"] as? JsonPrimitive)?.intOrNull
                if (pts == null || (teamId != g.home.id && teamId != g.away.id)) return reject("VALIDATION_ERROR")
            }
            "tiebreak_winner" -> {
                if (!live) return reject("MATCH_NOT_IN_PROGRESS")
                if (teamId != g.home.id && teamId != g.away.id) return reject("VALIDATION_ERROR")
                updated = g.copy(tiebreakWinnerTeamId = teamId)
            }
            "compensation" -> {
                if (!live) return reject("MATCH_NOT_IN_PROGRESS")
                val target = (req.payload["compensates"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val reason = (req.payload["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (target == null || reason.isNullOrBlank()) return reject("VALIDATION_ERROR")
                val oi = list.indexOfFirst { it.eventId == target }
                if (oi < 0) return reject("INVALID_COMPENSATION")
                val orig = list[oi]
                if (orig.compensated || orig.type !in COMPENSABLE) return reject("INVALID_COMPENSATION")
                list[oi] = orig.copy(compensated = true, compensatedBy = req.eventId)
                if (orig.type == "score") {
                    val pts = scoringPoints[(orig.payload["event_type"] as? JsonPrimitive)?.content] ?: 1
                    val origTeam = (orig.payload["team_id"] as? JsonPrimitive)?.content
                    updated = if (origTeam == g.home.id) g.copy(homeScore = maxOf(0, g.homeScore - pts))
                    else g.copy(awayScore = maxOf(0, g.awayScore - pts))
                }
            }
        }
        val seq = list.size + 1
        list += MatchEventDto(
            sequence = seq,
            eventId = req.eventId,
            type = req.type,
            payload = req.payload,
            signature = SIGNATURE,
        )
        games[idx] = updated
        val resp = MatchEventResponse(
            eventId = req.eventId,
            matchId = g.matchId,
            type = req.type,
            sequence = seq,
            matchVersion = list.size.toLong(),
            signature = SIGNATURE,
        )
        requests[req.eventId] = req
        responses[req.eventId] = resp
        MatchOutcome.Applied(resp, if (baseVersion == null || baseVersion == current) "appended" else "rebased_append")
    }

    companion object {
        const val LIVE_ID = "demo-m-live"
        const val SIGNATURE = "demo-signature"
        private val KNOWN_TYPES = setOf(
            "score", "discipline", "tiebreak_winner", "match_start", "match_end", "compensation",
        )
        private val COMPENSABLE = setOf("score", "discipline", "tiebreak_winner")
    }
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
