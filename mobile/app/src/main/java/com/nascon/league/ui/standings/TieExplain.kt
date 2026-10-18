package com.nascon.league.ui.standings

import com.nascon.league.data.api.dto.StandingRowDto

/** Tie-break steps in the order the rules check them (mockup B13). */
enum class TieStep { HEAD_TO_HEAD, GOAL_DIFF, SCORED, FAIR_PLAY, LOTS }

fun tieStepOf(tb: TieBreak): TieStep = when (tb) {
    TieBreak.H2H_POINTS, TieBreak.H2H_DIFF -> TieStep.HEAD_TO_HEAD
    TieBreak.OVERALL_DIFF -> TieStep.GOAL_DIFF
    TieBreak.SCORED -> TieStep.SCORED
    TieBreak.FAIR_PLAY -> TieStep.FAIR_PLAY
    TieBreak.DRAW_LOTS -> TieStep.LOTS
}

/** Why [teamId] sits above ([above] = true) or below its level-on-points neighbour [otherId]. */
data class TieExplanation(
    val teamId: String,
    val otherId: String,
    val above: Boolean,
    val points: Int,
    val decidedBy: TieStep,
)

/**
 * The tied neighbour is the next team down on equal points (then the next team up). Null when the
 * team was not separated by a tie-break or has no tied neighbour.
 */
fun tieExplanation(table: List<StandingRowDto>, teamId: String): TieExplanation? {
    val sorted = dedupeStandingRows(table).sortedBy { it.rank }
    val idx = sorted.indexOfFirst { it.teamId == teamId }
    if (idx < 0) return null
    val me = sorted[idx]
    val tb = tieBreakOf(me.resolvedBy) ?: return null
    val below = sorted.drop(idx + 1).firstOrNull { it.points == me.points }
    if (below != null) return TieExplanation(teamId, below.teamId, true, me.points, tieStepOf(tb))
    val above = sorted.take(idx).lastOrNull { it.points == me.points }
    if (above != null) return TieExplanation(teamId, above.teamId, false, me.points, tieStepOf(tb))
    return null
}

/** Steps listed in the sheet: everything up to the deciding step, and at least three rows. */
fun visibleTieSteps(decidedBy: TieStep): List<TieStep> =
    TieStep.values().toList().take(maxOf(decidedBy.ordinal + 1, 3))
