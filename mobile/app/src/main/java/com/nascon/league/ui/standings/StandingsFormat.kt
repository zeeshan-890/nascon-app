package com.nascon.league.ui.standings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

private const val MINUS = "−"

/**
 * Goal/point difference for display. Whole numbers have no decimals, others up to 3 decimals
 * with an explicit sign (+1.234, -0.5 using a real minus sign). Zero (including -0.0 and
 * values that round to zero) is plain "0".
 */
fun formatDiff(diff: Double): String {
    if (diff.isNaN() || diff.isInfinite()) return "0"
    val thousandths = (abs(diff) * 1000.0).roundToLong()
    if (thousandths == 0L) return "0"
    val sign = if (diff < 0) MINUS else "+"
    val whole = thousandths / 1000
    val frac = thousandths % 1000
    if (frac == 0L) return "$sign$whole"
    val fracText = String.format(Locale.ROOT, "%03d", frac).trimEnd('0')
    return "$sign$whole.$fracText"
}

/** Diff for TalkBack: "plus 5", "minus 0.5", "0" (words supplied by the caller for translation). */
fun spokenDiff(diff: Double, plusWord: String, minusWord: String): String {
    val shown = formatDiff(diff)
    return when {
        shown.startsWith("+") -> "$plusWord ${shown.substring(1)}"
        shown.startsWith(MINUS) -> "$minusWord ${shown.substring(1)}"
        else -> shown
    }
}

enum class QualStatus { QUALIFIED, OUT, IN_CONTENTION, UNKNOWN }

/** Maps the server status defensively; anything unrecognised becomes UNKNOWN (never throws). */
fun qualStatus(raw: String?): QualStatus = when (raw?.trim()?.lowercase(Locale.ROOT)) {
    "clinched", "qualified" -> QualStatus.QUALIFIED
    "eliminated" -> QualStatus.OUT
    "still_possible", "active", "in_contention" -> QualStatus.IN_CONTENTION
    else -> QualStatus.UNKNOWN
}

enum class TieBreak { H2H_POINTS, H2H_DIFF, OVERALL_DIFF, SCORED, FAIR_PLAY, DRAW_LOTS }

/** null for no tie-break or an unknown value (no caption is shown). */
fun tieBreakOf(raw: String?): TieBreak? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
    "h2h_points" -> TieBreak.H2H_POINTS
    "h2h_diff" -> TieBreak.H2H_DIFF
    "overall_diff" -> TieBreak.OVERALL_DIFF
    "scored" -> TieBreak.SCORED
    "fair_play" -> TieBreak.FAIR_PLAY
    "draw_lots" -> TieBreak.DRAW_LOTS
    else -> null
}

/**
 * Number of rows shown above the "Qualification line" divider, or null when no divider
 * should be drawn (no spots, or every team qualifies / spots exceed team count).
 */
fun qualificationLineAfter(spots: Int, teamCount: Int): Int? =
    if (spots <= 0 || teamCount <= 0 || spots >= teamCount) null else spots

data class H2hEntry(
    val otherTeamId: String,
    val played: Int,
    val points: Int,
    val scored: Int,
    val conceded: Int,
) {
    val neverMet: Boolean get() = played <= 0
}

private fun JsonElement?.intOrZero(): Int =
    (this as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

/** h2h[team][other] against every team in [otherIds]; a missing or malformed pair counts as never met. */
fun parseH2h(h2h: JsonObject, teamId: String, otherIds: List<String>): List<H2hEntry> {
    val mine = h2h[teamId].obj()
    return otherIds.filter { it != teamId }.map { other ->
        val pair = mine?.get(other).obj()
        if (pair == null) {
            H2hEntry(other, 0, 0, 0, 0)
        } else {
            H2hEntry(
                otherTeamId = other,
                played = pair["played"].intOrZero(),
                points = pair["points"].intOrZero(),
                scored = (pair["scored"] ?: pair["for"] ?: pair["goals_for"]).intOrZero(),
                conceded = (pair["conceded"] ?: pair["against"] ?: pair["goals_against"]).intOrZero(),
            )
        }
    }
}

/** Plain-language row description for TalkBack. [template] has nine positional args (see strings_f4). */
fun rowDescription(
    template: String,
    rank: Int,
    name: String,
    played: Int,
    won: Int,
    drawn: Int,
    lost: Int,
    spokenDiff: String,
    points: Int,
    statusText: String,
): String = String.format(Locale.ROOT, template, rank, name, played, won, drawn, lost, spokenDiff, points, statusText)

fun teamDisplayName(teamNames: Map<String, String>, teamId: String): String =
    teamNames[teamId]?.takeIf { it.isNotBlank() } ?: teamId
