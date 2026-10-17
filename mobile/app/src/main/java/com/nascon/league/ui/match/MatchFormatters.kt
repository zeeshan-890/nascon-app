package com.nascon.league.ui.match

import com.nascon.league.data.api.dto.MatchDetailDto
import com.nascon.league.data.api.dto.TeamScoreDto
import com.nascon.league.ui.fixtures.StatusKind
import com.nascon.league.ui.fixtures.statusKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** "three_pointer" -> "Three pointer"; tolerant of extra/leading/trailing underscores and empty input. */
fun snakeToTitle(s: String): String {
    val words = s.split('_').map { it.trim() }.filter { it.isNotEmpty() }
    if (words.isEmpty()) return ""
    val joined = words.joinToString(" ") { it.lowercase() }
    return joined.replaceFirstChar { it.uppercase() }
}

/** Label model; the UI maps each case to a string resource. [team] is a resolved display name or null. */
sealed interface EventLabel {
    data object MatchInit : EventLabel
    data object MatchStart : EventLabel
    data object MatchEnd : EventLabel
    data object Unknown : EventLabel
    data class TiebreakWinner(val team: String?) : EventLabel
    data class Discipline(val points: String?, val team: String?) : EventLabel
    data class Compensation(val reason: String?) : EventLabel
    data class Score(val title: String, val team: String?) : EventLabel
}

private fun JsonObject.str(key: String): String? {
    val e: JsonElement = this[key] ?: return null
    if (e is JsonNull) return null
    return (e as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

fun eventTeamId(payload: JsonObject): String? = payload.str("team_id")

/** Minute only if the server payload carries a plain numeric "minute"; never derived. */
fun eventMinute(payload: JsonObject): String? =
    payload.str("minute")?.takeIf { m -> m.isNotEmpty() && m.length <= 3 && m.all { it.isDigit() } }

fun eventLabel(type: String, payload: JsonObject, teamName: (String?) -> String?): EventLabel {
    val team = teamName(eventTeamId(payload))
    return when (type) {
        "match_init" -> EventLabel.MatchInit
        "match_start" -> EventLabel.MatchStart
        "match_end" -> EventLabel.MatchEnd
        "tiebreak_winner" -> EventLabel.TiebreakWinner(team)
        "discipline" -> EventLabel.Discipline(payload.str("points"), team)
        "compensation" -> EventLabel.Compensation(payload.str("reason"))
        "score" -> {
            val title = payload.str("event_type")?.let(::snakeToTitle)?.takeIf { it.isNotEmpty() }
            if (title == null) EventLabel.Unknown else EventLabel.Score(title, team)
        }
        else -> EventLabel.Unknown
    }
}

/** Score text for one team. [main] is "2" or "145/4"; [overs] is set only when wickets and overs/balls exist. */
data class ScoreText(val main: String, val overs: String?, val fairPlay: Int)

fun formatScore(t: TeamScoreDto): ScoreText {
    val oversText = oversOf(t)
    val wickets = t.wickets
    return if (wickets != null && oversText != null) {
        ScoreText("${t.score}/$wickets", oversText, t.fairPlay)
    } else {
        ScoreText(t.score.toString(), null, t.fairPlay)
    }
}

private fun oversOf(t: TeamScoreDto): String? {
    val o = t.overs
    if (o != null && o !is JsonNull) {
        val c = (o as? JsonPrimitive)?.contentOrNull
        if (!c.isNullOrBlank()) return c
    }
    val balls = t.balls ?: return null
    return "${balls / 6}.${balls % 6}"
}

sealed interface OutcomeText {
    data object HomeWin : OutcomeText
    data object AwayWin : OutcomeText
    data object Draw : OutcomeText
    data object ResultPending : OutcomeText
    data object NotDecided : OutcomeText
    data object None : OutcomeText
}

fun outcomeText(m: MatchDetailDto): OutcomeText {
    val kind = statusKind(m.status)
    val raw = m.outcome.trim().lowercase()
    val fromWinner = when (m.winnerTeamId) {
        null -> null
        m.home.teamId -> OutcomeText.HomeWin
        m.away.teamId -> OutcomeText.AwayWin
        else -> null
    }
    val fromOutcome: OutcomeText? = when {
        raw.isEmpty() -> null
        raw.contains("undecided") || raw.contains("not_decided") -> OutcomeText.NotDecided
        raw.contains("pending") -> OutcomeText.ResultPending
        raw.contains("draw") || raw == "tie" || raw == "tied" -> OutcomeText.Draw
        raw.contains("home") -> OutcomeText.HomeWin
        raw.contains("away") -> OutcomeText.AwayWin
        else -> null
    }
    return when (kind) {
        StatusKind.COMPLETED -> fromOutcome ?: fromWinner ?: OutcomeText.ResultPending
        StatusKind.LIVE -> if (fromOutcome == OutcomeText.NotDecided || fromOutcome == null) OutcomeText.NotDecided else fromOutcome
        else -> OutcomeText.None
    }
}

/** Team id that won on tie-break, only when the score is level. */
fun tiebreakTeamId(m: MatchDetailDto): String? {
    val id = m.tiebreakWinnerTeamId ?: return null
    return if (m.home.score == m.away.score) id else null
}

/** price_minor (e.g. paisa) -> "4,500.00". The "PKR" prefix comes from a string resource. */
fun formatPriceMinor(minor: Long): String =
    java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols(java.util.Locale.US))
        .format(java.math.BigDecimal(minor).movePointLeft(2))
