package com.nascon.league.clubstandings

import com.nascon.league.data.api.dto.ClubStatDto
import com.nascon.league.data.api.dto.StandingRowDto
import com.nascon.league.ui.club.ClubTab
import com.nascon.league.ui.club.clubTabOf
import com.nascon.league.ui.club.latestSeasonStat
import com.nascon.league.ui.club.manageActions
import com.nascon.league.ui.standings.TieStep
import com.nascon.league.ui.standings.tieExplanation
import com.nascon.league.ui.standings.visibleTieSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClubStandingsLogicTest {
    private fun row(id: String, rank: Int, pts: Int, by: String? = null) =
        StandingRowDto(rank = rank, teamId = id, points = pts, resolvedBy = by)

    @Test fun tieAboveNeighbour() {
        val t = listOf(row("a", 1, 19), row("b", 2, 17, "h2h_points"), row("c", 3, 17), row("d", 4, 5))
        val ex = tieExplanation(t, "b")!!
        assertEquals("c", ex.otherId)
        assertTrue(ex.above)
        assertEquals(17, ex.points)
        assertEquals(TieStep.HEAD_TO_HEAD, ex.decidedBy)
    }

    @Test fun tieBelowWhenNoTeamUnder() {
        val t = listOf(row("a", 1, 17), row("b", 2, 17, "scored"))
        val ex = tieExplanation(t, "b")!!
        assertEquals("a", ex.otherId)
        assertFalse(ex.above)
        assertEquals(TieStep.SCORED, ex.decidedBy)
    }

    @Test fun noExplanationWithoutTieBreakOrNeighbour() {
        assertNull(tieExplanation(listOf(row("a", 1, 9), row("b", 2, 7)), "a"))
        assertNull(tieExplanation(listOf(row("a", 1, 9, "scored"), row("b", 2, 7)), "a"))
        assertNull(tieExplanation(emptyList(), "zz"))
    }

    @Test fun stepsAlwaysAtLeastThree() {
        assertEquals(3, visibleTieSteps(TieStep.HEAD_TO_HEAD).size)
        assertEquals(5, visibleTieSteps(TieStep.LOTS).size)
    }

    @Test fun tabRestoreFallsBack() {
        assertEquals(ClubTab.STATS, clubTabOf("STATS"))
        assertEquals(ClubTab.SQUAD, clubTabOf("nope"))
        assertEquals(ClubTab.SQUAD, clubTabOf(null))
    }

    @Test fun latestSeasonPicksHighestLabel() {
        val s = listOf(ClubStatDto(season = "2024", played = 1), ClubStatDto(season = "2025", played = 2))
        assertEquals(2, latestSeasonStat(s)!!.played)
        assertNull(latestSeasonStat(emptyList()))
    }

    @Test fun manageRowGatedByPermissionAndWiring() {
        assertFalse(manageActions(false, true, true, true, true).any)
        assertFalse(manageActions(true, false, false, false, false).any)
        val m = manageActions(true, true, false, true, false)
        assertTrue(m.editClub); assertFalse(m.editStaff); assertTrue(m.products); assertFalse(m.orders)
    }
}
