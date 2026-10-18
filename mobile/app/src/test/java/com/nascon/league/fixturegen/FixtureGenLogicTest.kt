package com.nascon.league.fixturegen

import com.nascon.league.core.network.ApiError
import com.nascon.league.data.fixturegen.FixtureGenerator
import com.nascon.league.data.fixturegen.GenApiFormat
import com.nascon.league.data.fixturegen.GenMatchDto
import com.nascon.league.data.fixturegen.GenPairingDto
import com.nascon.league.data.fixturegen.GenRefDto
import com.nascon.league.data.fixturegen.GenRequest
import com.nascon.league.data.fixturegen.GeneratedDto
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import com.nascon.league.domain.model.User
import com.nascon.league.ui.fixturegen.FixtureGenAccess
import com.nascon.league.ui.fixturegen.GenSchedule
import com.nascon.league.ui.fixturegen.RoundKind
import com.nascon.league.ui.fixturegen.byeSeeds
import com.nascon.league.ui.fixturegen.checkRoundRobin
import com.nascon.league.ui.fixturegen.firstRoundOpponent
import com.nascon.league.ui.fixturegen.formatScore
import com.nascon.league.ui.fixturegen.genErrorMessage
import com.nascon.league.ui.fixturegen.playableMatches
import com.nascon.league.ui.fixturegen.roundKind
import com.nascon.league.ui.fixturegen.shortMatchId
import com.nascon.league.ui.fixturegen.slotSources
import com.nascon.league.ui.fixturegen.swissHistory
import com.nascon.league.ui.fixturegen.swissRoundComplete
import com.nascon.league.ui.fixturegen.teamLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FixtureGenLogicTest {
    private fun session(role: Role, leagues: List<String> = emptyList()) =
        Session(User(userId = "u1", email = "x@nascon.test", role = role, leagueIds = leagues))

    // ---------------------------------------------------------------- role gate

    @Test fun superAdminAlwaysAllowed() {
        assertTrue(FixtureGenAccess.canGenerate(session(Role.SUPER_ADMIN), "league-alpha-1"))
    }

    @Test fun organizerOnlyForOwnLeague() {
        val s = session(Role.LEAGUE_ORGANIZER, listOf("league-alpha-1"))
        assertTrue(FixtureGenAccess.canGenerate(s, "league-alpha-1"))
        assertFalse(FixtureGenAccess.canGenerate(s, "league-alpha-2"))
    }

    @Test fun everyoneElseDenied() {
        for (r in listOf(Role.CLUB_MANAGER, Role.PLAYER, Role.VIEWER)) {
            assertFalse(FixtureGenAccess.canGenerate(session(r, listOf("league-alpha-1")), "league-alpha-1"))
        }
        assertFalse(FixtureGenAccess.canGenerate(null, "league-alpha-1"))
    }

    // ---------------------------------------------------------------- dates

    @Test fun roundDatesFollowMatchDays() {
        val start = LocalDate.of(2026, 10, 24) // Saturday
        val dates = GenSchedule.roundDates(start, GenSchedule.saturdayAndSunday, 4)
        assertEquals(
            listOf(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1)),
            dates,
        )
    }

    @Test fun roundDatesStartOnNextMatchDay() {
        val dates = GenSchedule.roundDates(LocalDate.of(2026, 10, 21), setOf(6), 2) // Wednesday, Saturdays only
        assertEquals(listOf(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 31)), dates)
    }

    @Test fun roundDatesWithoutDaysFallBackToDaily() {
        val dates = GenSchedule.roundDates(LocalDate.of(2026, 10, 24), emptySet(), 3)
        assertEquals(LocalDate.of(2026, 10, 26), dates[2])
        assertTrue(GenSchedule.roundDates(LocalDate.of(2026, 10, 24), setOf(6), 0).isEmpty())
    }

    @Test fun parseDateIsStrict() {
        assertEquals(LocalDate.of(2026, 10, 24), GenSchedule.parseDate(" 2026-10-24 "))
        assertNull(GenSchedule.parseDate("24/10/2026"))
        assertNull(GenSchedule.parseDate(""))
        assertNull(GenSchedule.parseDate("2026-13-40"))
    }

    // ---------------------------------------------------------------- bracket helpers

    @Test fun knockoutRoundNames() {
        assertEquals(RoundKind.FINAL, roundKind(3, 3))
        assertEquals(RoundKind.SEMI, roundKind(2, 3))
        assertEquals(RoundKind.QUARTER, roundKind(1, 3))
        assertEquals(RoundKind.ROUND, roundKind(1, 4))
    }

    @Test fun firstRoundOpponentsAndByes() {
        assertEquals(8, firstRoundOpponent(1, 8))
        assertEquals(5, firstRoundOpponent(4, 8))
        assertNull(firstRoundOpponent(1, 6))
        assertNull(firstRoundOpponent(2, 6))
        assertEquals(6, firstRoundOpponent(3, 6))
        assertEquals(listOf(1, 2), byeSeeds(6))
        assertEquals(emptyList<Int>(), byeSeeds(8))
    }

    @Test fun shortIdsAreReadable() {
        assertEquals("W1-2", shortMatchId("WR1M2"))
        assertEquals("L3-1", shortMatchId("LR3M1"))
        assertEquals("R2-1", shortMatchId("R2M1"))
        assertEquals("grand final", shortMatchId("GF1"))
        assertEquals("third place", shortMatchId("TP"))
    }

    @Test fun slotSourcesDescribeEmptySlots() {
        val dto = FixtureGenerator.generate(GenRequest(GenApiFormat.DOUBLE_ELIM, (1..4).map { "T$it" }))
        val all = (dto.winners + dto.losers).flatMap { it.matches } + dto.grandFinal
        val src = slotSources(all)
        assertEquals(false to "WR1M1", src["WR2M1:home"])
        assertEquals(true to "WR1M1", src["LR1M1:home"])
        assertEquals(true to "WR1M2", src["LR1M1:away"])
        assertEquals(false to "WR2M1", src["GF1:home"])
    }

    @Test fun playableMatchesSkipByesAndConditionalFinal() {
        val dto = FixtureGenerator.generate(GenRequest(GenApiFormat.DOUBLE_ELIM, (1..5).map { "T$it" }, bracketReset = true))
        val ids = playableMatches(dto).map { it.matchId }
        assertFalse("GF2" in ids)
        assertTrue(playableMatches(dto).none { it.bye })
    }

    // ---------------------------------------------------------------- round robin checks

    @Test fun generatedRoundRobinHasNoConflicts() {
        for (n in 4..12) {
            val dto = FixtureGenerator.generate(GenRequest(GenApiFormat.ROUND_ROBIN, (1..n).map { "T$it" }, legs = 2))
            assertTrue("n=$n", checkRoundRobin(dto).clean)
        }
    }

    @Test fun checkDetectsDoubleBookingAndImbalance() {
        val round = com.nascon.league.data.fixturegen.GenRoundDto(
            1,
            listOf(
                GenMatchDto(matchId = "a", home = "A", away = "B"),
                GenMatchDto(matchId = "b", home = "A", away = "C"),
            ),
        )
        val c = checkRoundRobin(GeneratedDto(format = "round_robin", rounds = listOf(round)))
        assertEquals(1, c.doubleBooked)
        assertFalse(c.clean)
        val lopsided = (1..3).map { com.nascon.league.data.fixturegen.GenRoundDto(it, listOf(GenMatchDto(matchId = "m$it", home = "A", away = "B"))) }
        assertEquals(2, checkRoundRobin(GeneratedDto(format = "round_robin", rounds = lopsided)).unbalanced)
    }

    // ---------------------------------------------------------------- swiss helpers

    @Test fun swissRoundCompleteNeedsEveryResult() {
        val round = GeneratedDto(
            format = "swiss", round = 1,
            pairings = listOf(GenPairingDto("m1", "A", "B"), GenPairingDto("m2", "C", "D")),
        )
        assertFalse(swissRoundComplete(round, mapOf("m1" to "home")))
        assertFalse(swissRoundComplete(round, mapOf("m1" to "home", "m2" to "bogus")))
        assertTrue(swissRoundComplete(round, mapOf("m1" to "home", "m2" to "draw")))
    }

    @Test fun swissHistoryBuiltFromRoundsAndResults() {
        val r1 = GeneratedDto(
            format = "swiss", round = 1, bye = "E",
            pairings = listOf(GenPairingDto("m1", "A", "B"), GenPairingDto("m2", "C", "D")),
        )
        val h = swissHistory(listOf(r1), mapOf("m1" to "away", "m2" to "draw"))
        assertEquals(1, h.size)
        assertEquals("E", h[0].bye)
        assertEquals("away", h[0].matches[0].result)
        assertEquals("C", h[0].matches[1].home)
    }

    @Test fun scoreFormatting() {
        assertEquals("6", formatScore(6.0))
        assertEquals("2.5", formatScore(2.5))
        assertEquals("0", formatScore(0.0))
    }

    // ---------------------------------------------------------------- labels and errors

    @Test fun teamLabelFallsBackToId() {
        assertEquals("Lions FC", teamLabel("t1", mapOf("t1" to "Lions FC")))
        assertEquals("t2", teamLabel("t2", mapOf("t1" to "Lions FC")))
        assertEquals("", teamLabel(null, emptyMap()))
    }

    @Test fun errorMessagesArePlainLanguage() {
        val noPairing = ApiError.Http(409, "NO_VALID_PAIRING", "No valid pairing exists for this round")
        assertEquals("custom", genErrorMessage(noPairing, "custom"))
        val validation = ApiError.Http(400, "VALIDATION_ERROR", "Request validation failed")
        assertEquals("This change was not valid and was not saved.", genErrorMessage(validation, "custom"))
        assertTrue(genErrorMessage(ApiError.NoConnection, "custom").contains("offline"))
        val ref = GenRefDto("X", "home")
        assertEquals("X", ref.matchId)
    }
}
