package com.nascon.league.ui.scoring

import com.nascon.league.core.db.PendingActionEntity
import com.nascon.league.data.api.dto.MatchEventDto
import com.nascon.league.data.api.dto.SportConfigDto
import com.nascon.league.domain.model.Permissions
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

object ScoringLogic {
    private const val MAX_REASON = 200
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val undoable = setOf("score", "discipline", "tiebreak_winner")

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull

    private fun prettify(key: String): String {
        val words = key.split('_', '-', ' ').filter { it.isNotEmpty() }.joinToString(" ")
        return words.replaceFirstChar { it.uppercase() }
    }

    fun scoreButtons(config: SportConfigDto?): List<ScoreButton> =
        config?.scoringEvents?.map { (key, v) ->
            ScoreButton(key, prettify(key), (v as? JsonObject)?.get("points").int())
        } ?: emptyList()

    private fun disciplineElement(config: SportConfigDto?): JsonElement? =
        config?.discipline?.takeIf { it !is JsonNull }

    fun supportsDiscipline(config: SportConfigDto?): Boolean = disciplineElement(config) != null

    fun disciplineOptions(config: SportConfigDto?): List<Int> {
        val d = disciplineElement(config) ?: return emptyList()
        fun ints(e: JsonElement?): List<Int> = when (e) {
            is JsonArray -> e.mapNotNull { it.int() }
            is JsonPrimitive -> listOfNotNull(e.intOrNull)
            else -> emptyList()
        }
        return when (d) {
            is JsonArray -> ints(d)
            is JsonObject -> listOf("options", "values", "points", "allowed").firstNotNullOfOrNull {
                ints(d[it]).takeIf { l -> l.isNotEmpty() }
            } ?: emptyList()
            else -> emptyList()
        }.distinct()
    }

    fun tiebreakAvailable(config: SportConfigDto?, homeScore: Int, awayScore: Int): Boolean =
        config?.drawAllowed == false && homeScore == awayScore

    /** True when a non-compensated tie-break winner exists on the server or in the pending queue. */
    fun tiebreakRecorded(events: List<MatchEventDto>, pendingActions: List<PendingActionEntity>): Boolean {
        if (events.any { it.type == "tiebreak_winner" && canUndo(it, events) }) return true
        return pendingActions.any { a ->
            a.type == "score_event" && parse(a.payloadJson)?.get("type").str() == "tiebreak_winner"
        }
    }

    /** The match is level, draws are not allowed and nobody has been named tie-break winner yet. */
    fun tiebreakRequired(config: SportConfigDto?, homeScore: Int, awayScore: Int, recorded: Boolean): Boolean =
        tiebreakAvailable(config, homeScore, awayScore) && !recorded

    fun canEndMatch(config: SportConfigDto?, homeScore: Int, awayScore: Int, tiebreakRecorded: Boolean = false): Boolean =
        !tiebreakRequired(config, homeScore, awayScore, tiebreakRecorded)

    /** Rejected sync result that means "ended level but draws are not allowed". */
    fun isTieNotAllowed(reasonCode: String?): Boolean = reasonCode == "TIE_NOT_ALLOWED"

    /** Steps the discipline points: through [options] when the sport lists them, otherwise 1..99. */
    fun stepDiscipline(options: List<Int>, current: Int, delta: Int): Int {
        if (options.isEmpty()) return (current + delta).coerceIn(1, 99)
        val sorted = options.distinct().sorted()
        val idx = sorted.indexOf(current).let { if (it < 0) 0 else it }
        return sorted[(idx + delta).coerceIn(0, sorted.lastIndex)]
    }

    fun initialDiscipline(options: List<Int>): Int = options.minOrNull() ?: 1

    fun controlsMode(status: String?): ControlsMode = when (status) {
        "upcoming", "scheduled" -> ControlsMode.START
        "in_progress" -> ControlsMode.SCORE
        else -> ControlsMode.READ_ONLY
    }

    fun canShowPanel(session: Session?, leagueId: String?): Boolean {
        if (session == null) return false
        if (leagueId == null) return session.user.role == Role.SUPER_ADMIN
        return Permissions(session).canScore(leagueId)
    }

    fun scorePayload(teamId: String, eventKey: String): JsonObject =
        buildJsonObject { put("team_id", teamId); put("event_type", eventKey) }

    fun disciplinePayload(teamId: String, points: Int): JsonObject =
        buildJsonObject { put("team_id", teamId); put("points", points) }

    fun tiebreakPayload(teamId: String): JsonObject = buildJsonObject { put("team_id", teamId) }

    fun emptyPayload(): JsonObject = JsonObject(emptyMap())

    fun compensationPayload(eventId: String, reason: String): JsonObject =
        buildJsonObject { put("compensates", eventId); put("reason", reason.trim()) }

    fun validateReason(raw: String): ReasonCheck {
        val t = raw.trim()
        return when {
            t.isEmpty() -> ReasonCheck.Empty
            t.length > MAX_REASON -> ReasonCheck.TooLong
            else -> ReasonCheck.Ok(t)
        }
    }

    fun canUndo(event: MatchEventDto, allEvents: List<MatchEventDto>): Boolean {
        if (event.type !in undoable || event.compensated) return false
        return allEvents.none { it.type == "compensation" && it.payload["compensates"].str() == event.eventId }
    }

    fun label(type: String, teamName: String?, eventKey: String?): String = when (type) {
        "score" -> if (teamName.isNullOrBlank()) "Add score" else "Add score for $teamName"
        "discipline" -> if (teamName.isNullOrBlank()) "Add discipline" else "Add discipline for $teamName"
        "tiebreak_winner" -> if (teamName.isNullOrBlank()) "Set tie-break winner" else "Set tie-break winner for $teamName"
        "match_start" -> "Start match"
        "match_end" -> "End match"
        "compensation" -> if (eventKey.isNullOrBlank()) "Undo event" else "Undo event #$eventKey"
        else -> "Update match"
    }

    private fun parse(s: String): JsonObject? = try {
        json.parseToJsonElement(s) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private fun points(config: SportConfigDto?, key: String?): Int {
        if (key == null) return 0
        return ((config?.scoringEvents?.get(key) as? JsonObject)?.get("points")).int() ?: 0
    }

    fun optimisticScores(
        homeTeamId: String,
        awayTeamId: String,
        serverHome: Int,
        serverAway: Int,
        pendingScoreActions: List<PendingActionEntity>,
        config: SportConfigDto?,
        events: List<MatchEventDto> = emptyList(),
    ): OptimisticScore {
        var home = serverHome
        var away = serverAway
        var count = 0
        fun add(team: String?, delta: Int) {
            if (team == homeTeamId) home += delta else if (team == awayTeamId) away += delta
        }
        for (a in pendingScoreActions) {
            if (a.type != "score_event") continue
            val obj = parse(a.payloadJson) ?: continue
            val inner = obj["payload"] as? JsonObject ?: JsonObject(emptyMap())
            when (obj["type"].str()) {
                "score" -> {
                    count++
                    add(inner["team_id"].str(), points(config, inner["event_type"].str()))
                }
                "compensation" -> {
                    count++
                    val target = events.firstOrNull { it.eventId == inner["compensates"].str() }
                    if (target != null && target.type == "score") {
                        add(target.payload["team_id"].str(), -points(config, target.payload["event_type"].str()))
                    }
                }
            }
        }
        return OptimisticScore(maxOf(home, 0), maxOf(away, 0), count, count > 0)
    }
}
