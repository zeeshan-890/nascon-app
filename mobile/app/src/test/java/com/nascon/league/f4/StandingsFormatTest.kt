package com.nascon.league.f4

import com.nascon.league.ui.fixtures.pickLeague
import com.nascon.league.ui.standings.QualStatus
import com.nascon.league.ui.standings.TieBreak
import com.nascon.league.ui.standings.formatDiff
import com.nascon.league.ui.standings.parseH2h
import com.nascon.league.ui.standings.qualStatus
import com.nascon.league.ui.standings.qualificationLineAfter
import com.nascon.league.ui.standings.rowDescription
import com.nascon.league.ui.standings.spokenDiff
import com.nascon.league.ui.standings.teamDisplayName
import com.nascon.league.ui.standings.tieBreakOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandingsFormatTest {
    private val minus = "−"

    @Test fun diffIntegers() {
        assertEquals("+5", formatDiff(5.0))
        assertEquals("${minus}3", formatDiff(-3.0))
        assertEquals("0", formatDiff(0.0))
    }

    @Test fun diffDecimals() {
        assertEquals("+1.234", formatDiff(1.234))
        assertEquals("${minus}0.5", formatDiff(-0.5))
        assertEquals("+2.5", formatDiff(2.5))
        assertEquals("+1.235", formatDiff(1.23456))
    }

    @Test fun diffNegativeZeroAndTiny() {
        assertEquals("0", formatDiff(-0.0))
        assertEquals("0", formatDiff(0.0001))
        assertEquals("0", formatDiff(-0.0001))
        assertEquals("+0.001", formatDiff(0.001))
        assertEquals("0", formatDiff(Double.NaN))
    }

    @Test fun spokenDiffWords() {
        assertEquals("plus 5", spokenDiff(5.0, "plus", "minus"))
        assertEquals("minus 0.5", spokenDiff(-0.5, "plus", "minus"))
        assertEquals("0", spokenDiff(-0.0, "plus", "minus"))
    }

    @Test fun qualificationLinePlacement() {
        assertEquals(1, qualificationLineAfter(1, 8))
        assertEquals(4, qualificationLineAfter(4, 8))
        assertNull(qualificationLineAfter(8, 8))
        assertNull(qualificationLineAfter(12, 8))
        assertNull(qualificationLineAfter(0, 8))
        assertNull(qualificationLineAfter(2, 0))
    }

    @Test fun statusMapping() {
        assertEquals(QualStatus.QUALIFIED, qualStatus("clinched"))
        assertEquals(QualStatus.OUT, qualStatus("eliminated"))
        assertEquals(QualStatus.IN_CONTENTION, qualStatus("still_possible"))
        assertEquals(QualStatus.UNKNOWN, qualStatus("something_new"))
        assertEquals(QualStatus.UNKNOWN, qualStatus(null))
    }

    @Test fun resolvedByMapping() {
        assertEquals(TieBreak.H2H_POINTS, tieBreakOf("h2h_points"))
        assertEquals(TieBreak.H2H_DIFF, tieBreakOf("h2h_diff"))
        assertEquals(TieBreak.OVERALL_DIFF, tieBreakOf("overall_diff"))
        assertEquals(TieBreak.SCORED, tieBreakOf("scored"))
        assertEquals(TieBreak.FAIR_PLAY, tieBreakOf("fair_play"))
        assertEquals(TieBreak.DRAW_LOTS, tieBreakOf("draw_lots"))
        assertNull(tieBreakOf("coin_flip"))
        assertNull(tieBreakOf(null))
    }

    private val h2h = Json.parseToJsonElement(
        """{"a":{"b":{"played":2,"points":4,"scored":5,"conceded":1},"c":{"played":0,"points":0,"scored":0,"conceded":0}}}""",
    ).jsonObject

    @Test fun h2hParsing() {
        val r = parseH2h(h2h, "a", listOf("a", "b", "c", "d"))
        assertEquals(listOf("b", "c", "d"), r.map { it.otherTeamId })
        assertEquals(2, r[0].played)
        assertEquals(4, r[0].points)
        assertEquals(5, r[0].scored)
        assertEquals(1, r[0].conceded)
        assertFalse(r[0].neverMet)
    }

    @Test fun h2hZeroAndMissingPairsAreNeverMet() {
        val r = parseH2h(h2h, "a", listOf("b", "c", "d"))
        assertTrue(r[1].neverMet) // zero pair
        assertTrue(r[2].neverMet) // missing pair
        val unknownTeam = parseH2h(h2h, "zzz", listOf("a"))
        assertTrue(unknownTeam.single().neverMet)
    }

    @Test fun rowDescriptionBuilder() {
        val template =
            "Rank %1\$d, %2\$s, played %3\$d, won %4\$d, drawn %5\$d, lost %6\$d, difference %7\$s, %8\$d points, %9\$s"
        val d = rowDescription(template, 1, "Lions", 3, 2, 1, 0, spokenDiff(5.0, "plus", "minus"), 7, "qualified")
        assertEquals(
            "Rank 1, Lions, played 3, won 2, drawn 1, lost 0, difference plus 5, 7 points, qualified",
            d,
        )
    }

    @Test fun teamNameFallsBackToId() {
        assertEquals("Lions", teamDisplayName(mapOf("t1" to "Lions"), "t1"))
        assertEquals("t2", teamDisplayName(mapOf("t1" to "Lions"), "t2"))
        assertEquals("t3", teamDisplayName(mapOf("t3" to " "), "t3"))
    }

    @Test fun leagueSelectionPersistence() {
        val ids = listOf("l1", "l2", "l3")
        // remembered league wins when nothing chosen this session
        assertEquals("l2", pickLeague(ids, null, "l2", listOf("l1")))
        // explicit choice beats remembered
        assertEquals("l3", pickLeague(ids, "l3", "l2", listOf("l1")))
        // remembered league that no longer exists falls back to the user's league
        assertEquals("l1", pickLeague(ids, null, "gone", listOf("l1")))
        assertNull(pickLeague(emptyList(), null, "l2", emptyList()))
    }
}
