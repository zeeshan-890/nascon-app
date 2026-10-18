package com.nascon.league.fixturegen

import com.nascon.league.data.fixturegen.FixtureGenRules
import com.nascon.league.data.fixturegen.FixtureGenerator
import com.nascon.league.data.fixturegen.GenApiFormat
import com.nascon.league.data.fixturegen.GenException
import com.nascon.league.data.fixturegen.GenRequest
import com.nascon.league.data.fixturegen.GeneratedDto
import com.nascon.league.data.fixturegen.SwissHistoryMatch
import com.nascon.league.data.fixturegen.SwissHistoryRound
import com.nascon.league.data.fixturegen.Withdrawal
import com.nascon.league.data.fixturegen.countMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FixtureGeneratorTest {
    private fun teams(n: Int) = (1..n).map { "T$it" }

    private fun rr(n: Int, legs: Int = 1, w: List<Withdrawal> = emptyList(), policy: String? = null) =
        FixtureGenerator.generate(
            GenRequest(GenApiFormat.ROUND_ROBIN, teams(n), legs = legs, withdrawals = w, withdrawalPolicy = policy),
        )

    // ---------------------------------------------------------------- round robin

    @Test fun roundRobinFourTeamsMatchesBackend() {
        val dto = FixtureGenerator.generate(GenRequest(GenApiFormat.ROUND_ROBIN, listOf("A", "B", "C", "D")))
        assertEquals(3, dto.roundCount)
        assertEquals(6, dto.summary.total)
        val pairs = dto.rounds.map { r -> r.matches.map { it.home to it.away } }
        assertEquals(listOf("D" to "A", "B" to "C"), pairs[0])
        assertEquals(listOf("A" to "B", "C" to "D"), pairs[1])
        assertEquals(listOf("C" to "A", "D" to "B"), pairs[2])
        assertEquals("RR-L1-R1-M1", dto.rounds[0].matches[0].matchId)
    }

    @Test fun roundRobinFiveTeamsTwoLegsHasByes() {
        val dto = FixtureGenerator.generate(GenRequest(GenApiFormat.ROUND_ROBIN, listOf("A", "B", "C", "D", "E"), legs = 2))
        assertEquals(10, dto.roundCount)
        assertEquals(listOf("A"), dto.rounds[0].byes)
        assertEquals(2, dto.rounds[0].matches.size)
        assertEquals("E", dto.rounds[0].matches[0].home)
        assertEquals("B", dto.rounds[0].matches[0].away)
        assertEquals(20, dto.summary.total)
    }

    @Test fun roundRobinEveryPairOnceAndNobodyTwiceInARound() {
        for (n in 4..16) {
            val dto = rr(n)
            val seen = HashSet<Set<String>>()
            for (r in dto.rounds) {
                val inRound = HashSet<String>()
                for (m in r.matches) {
                    assertTrue(inRound.add(m.home!!))
                    assertTrue(inRound.add(m.away!!))
                    assertTrue("pair repeated for $n", seen.add(setOf(m.home!!, m.away!!)))
                }
            }
            assertEquals(n * (n - 1) / 2, seen.size)
        }
    }

    @Test fun roundRobinHomeAwayBalanced() {
        for (n in 4..16) {
            val dto = rr(n, legs = 1)
            val home = HashMap<String, Int>()
            val away = HashMap<String, Int>()
            dto.rounds.flatMap { it.matches }.forEach {
                home[it.home!!] = (home[it.home!!] ?: 0) + 1
                away[it.away!!] = (away[it.away!!] ?: 0) + 1
            }
            for (t in teams(n)) assertTrue("n=$n $t", Math.abs((home[t] ?: 0) - (away[t] ?: 0)) <= 1)
        }
    }

    @Test fun roundRobinSecondLegSwapsVenues() {
        val dto = rr(6, legs = 2)
        val first = dto.rounds[0].matches[0]
        val second = dto.rounds[5].matches[0]
        assertEquals(first.home, second.away)
        assertEquals(first.away, second.home)
        assertEquals(2, second.leg)
    }

    @Test fun roundRobinWithdrawalForfeitsLaterMatches() {
        val dto = rr(4, w = listOf(Withdrawal("T1", 1)))
        val t1 = dto.rounds.flatMap { r -> r.matches.map { r.round to it } }
            .filter { it.second.home == "T1" || it.second.away == "T1" }
        for ((round, m) in t1) {
            if (round <= 1) {
                assertEquals("scheduled", m.status)
            } else {
                assertEquals("forfeit", m.status)
                assertTrue(m.awardedTo != null && m.awardedTo != "T1")
            }
        }
        assertEquals(2, dto.summary.forfeit)
    }

    @Test fun roundRobinVoidAllPolicy() {
        val dto = rr(4, w = listOf(Withdrawal("T1", 3)), policy = "void_all")
        assertEquals(3, dto.summary.voided)
        assertEquals(3, dto.summary.scheduled)
    }

    @Test fun roundRobinRejectsBadInput() {
        val bad = listOf(
            GenRequest(GenApiFormat.ROUND_ROBIN, listOf("A")),
            GenRequest(GenApiFormat.ROUND_ROBIN, listOf("A", "A")),
            GenRequest(GenApiFormat.ROUND_ROBIN, teams(4), legs = 3),
            GenRequest(GenApiFormat.ROUND_ROBIN, teams(4), withdrawals = listOf(Withdrawal("nope", 1))),
            GenRequest(GenApiFormat.ROUND_ROBIN, teams(4), withdrawals = listOf(Withdrawal("T1", 99))),
        )
        for (r in bad) {
            try {
                FixtureGenerator.generate(r)
                fail("expected validation error")
            } catch (e: GenException) {
                assertEquals("VALIDATION_ERROR", e.code)
            }
        }
    }

    // ---------------------------------------------------------------- swiss

    @Test fun swissRoundOneSplitsTopAndBottomHalf() {
        val dto = FixtureGenerator.generate(GenRequest(GenApiFormat.SWISS, listOf("A", "B", "C", "D")))
        assertEquals(listOf("A" to "C", "B" to "D"), dto.pairings.map { it.home to it.away })
        assertEquals(2, dto.totalRounds)
        assertNull(dto.bye)
    }

    @Test fun swissOddTeamsGetsBye() {
        val dto = FixtureGenerator.generate(GenRequest(GenApiFormat.SWISS, teams(9)))
        assertEquals(4, dto.pairings.size)
        assertEquals("T9", dto.bye)
        assertEquals(4, dto.totalRounds)
    }

    @Test fun swissLaterRoundsNeverRepeatPairingsOrByes() {
        for (n in listOf(4, 6, 8, 9, 10)) {
            val ts = teams(n)
            val rounds = ArrayList<GeneratedDto>()
            val results = HashMap<String, String>()
            val met = HashSet<Set<String>>()
            val byes = HashSet<String>()
            val play = FixtureGenRules.defaultSwissRounds(n)
            for (round in 1..play) {
                val history = rounds.map { r ->
                    SwissHistoryRound(
                        r.round, r.bye,
                        r.pairings.map { SwissHistoryMatch(it.home, it.away, results.getValue(it.matchId)) },
                    )
                }
                val dto = FixtureGenerator.generate(
                    GenRequest(GenApiFormat.SWISS, ts, totalRounds = n - 1, swissRound = round, history = history),
                )
                for (p in dto.pairings) {
                    assertTrue("repeat in n=$n round=$round", met.add(setOf(p.home, p.away)))
                    results[p.matchId] = listOf("home", "draw", "away")[(p.matchId.hashCode() and 0x7fffffff) % 3]
                }
                dto.bye?.let { assertTrue("second bye in n=$n", byes.add(it)) }
                assertEquals(n, dto.pairings.size * 2 + (if (dto.bye != null) 1 else 0))
                rounds += dto
            }
        }
    }

    @Test fun swissScoresAndBuchholzInStandings() {
        val history = listOf(
            SwissHistoryRound(1, null, listOf(SwissHistoryMatch("A", "C", "home"), SwissHistoryMatch("B", "D", "draw"))),
        )
        val dto = FixtureGenerator.generate(
            GenRequest(GenApiFormat.SWISS, listOf("A", "B", "C", "D"), swissRound = 2, history = history),
        )
        assertEquals("A", dto.standings[0].teamId)
        assertEquals(1.0, dto.standings[0].score, 0.0)
        assertEquals(0.5, dto.standings[1].score, 0.0)
        assertEquals("C", dto.standings[3].teamId)
        assertEquals(1.0, dto.standings[3].buchholz, 0.0)
        assertEquals(2, dto.pairings.size)
        // A and C met already, B and D met already
        val met = setOf(setOf("A", "C"), setOf("B", "D"))
        assertTrue(dto.pairings.none { setOf(it.home, it.away) in met })
    }

    @Test fun swissRejectsBadHistoryAndRounds() {
        val badHistory = GenRequest(GenApiFormat.SWISS, teams(4), swissRound = 2, history = emptyList())
        val badTotal = GenRequest(GenApiFormat.SWISS, teams(4), totalRounds = 4)
        for (r in listOf(badHistory, badTotal)) {
            try {
                FixtureGenerator.generate(r)
                fail()
            } catch (e: GenException) {
                assertEquals("VALIDATION_ERROR", e.code)
            }
        }
    }

    // ---------------------------------------------------------------- single elimination

    @Test fun singleElimSixTeamsPadsToEightWithByes() {
        val dto = FixtureGenerator.generate(GenRequest(GenApiFormat.SINGLE_ELIM, teams(6)))
        assertEquals(8, dto.bracketSize)
        assertEquals(3, dto.rounds.size)
        val r1 = dto.rounds[0].matches
        assertEquals(listOf(true, false, true, false), r1.map { it.bye })
        assertEquals("T1", r1[0].winner)
        assertEquals("T1", dto.rounds[1].matches[0].home)
        assertEquals("T2", dto.rounds[1].matches[1].home)
        assertEquals(4, r1[1].homeSeed)
        assertEquals(5, r1[1].awaySeed)
        assertEquals("R2M1", r1[1].winnerTo!!.matchId)
        assertEquals("away", r1[1].winnerTo!!.slot)
    }

    @Test fun singleElimMatchCountIsTeamsMinusOne() {
        for (n in 2..16) {
            val dto = FixtureGenerator.generate(GenRequest(GenApiFormat.SINGLE_ELIM, teams(n)))
            assertEquals("n=$n", n - 1, countMatches(dto))
        }
    }

    @Test fun singleElimThirdPlaceAddsOneMatchFedBySemis() {
        val dto = FixtureGenerator.generate(GenRequest(GenApiFormat.SINGLE_ELIM, teams(8), thirdPlace = true))
        assertEquals(8, countMatches(dto))
        val tp = dto.rounds.last().matches.first { it.bracket == "third_place" }
        assertEquals("TP", tp.matchId)
        assertTrue(dto.rounds[1].matches.all { it.loserTo?.matchId == "TP" })
    }

    @Test fun singleElimResultsAdvanceWinners() {
        val dto = FixtureGenerator.generate(
            GenRequest(GenApiFormat.SINGLE_ELIM, teams(4), results = mapOf("R1M1" to "T4", "R1M2" to "T2", "R2M1" to "T2")),
        )
        val final = dto.rounds[1].matches[0]
        assertEquals("T4", final.home)
        assertEquals("T2", final.away)
        assertEquals("T2", final.winner)
    }

    @Test fun singleElimBadResultsRejected() {
        val bad = listOf(mapOf("NOPE" to "T1"), mapOf("R1M1" to "T3"), mapOf("R2M1" to "T1"))
        for (r in bad) {
            try {
                FixtureGenerator.generate(GenRequest(GenApiFormat.SINGLE_ELIM, teams(4), results = r))
                fail()
            } catch (e: GenException) {
                assertTrue(e.fields.contains("params.results"))
            }
        }
    }

    // ---------------------------------------------------------------- double elimination

    @Test fun doubleElimEightTeamsStructure() {
        val dto = FixtureGenerator.generate(GenRequest(GenApiFormat.DOUBLE_ELIM, teams(8)))
        assertEquals(listOf(4, 2, 1), dto.winners.map { it.matches.size })
        assertEquals(listOf(2, 2, 1, 1), dto.losers.map { it.matches.size })
        assertEquals(1, dto.grandFinal.size)
        assertEquals("LR1M1", dto.winners[0].matches[0].loserTo!!.matchId)
        assertEquals("GF1", dto.winners[2].matches[0].winnerTo!!.matchId)
        assertEquals(14, countMatches(dto))
    }

    @Test fun doubleElimResetAddsConditionalFinal() {
        val dto = FixtureGenerator.generate(GenRequest(GenApiFormat.DOUBLE_ELIM, teams(4), bracketReset = true))
        assertEquals(2, dto.grandFinal.size)
        val gf2 = dto.grandFinal[1]
        assertTrue(gf2.conditional)
        assertNull(gf2.required)
        assertEquals("GF2", dto.grandFinal[0].winnerTo!!.matchId)
        assertEquals(6, countMatches(dto))
    }

    @Test fun doubleElimLosersAreFedFromWinnersBracket() {
        val dto = FixtureGenerator.generate(
            GenRequest(GenApiFormat.DOUBLE_ELIM, teams(4), results = mapOf("WR1M1" to "T4", "WR1M2" to "T2")),
        )
        val l1 = dto.losers[0].matches[0]
        assertEquals("T1", l1.home)
        assertEquals("T3", l1.away)
        assertEquals("T4", dto.winners[1].matches[0].home)
    }

    @Test fun doubleElimReferencesAlwaysResolve() {
        for (n in 3..16) {
            val dto = FixtureGenerator.generate(GenRequest(GenApiFormat.DOUBLE_ELIM, teams(n)))
            val all = (dto.winners + dto.losers).flatMap { it.matches } + dto.grandFinal
            val ids = all.map { it.matchId }
            assertEquals(ids.size, ids.toSet().size)
            for (m in all) {
                m.winnerTo?.let { assertTrue("n=$n ${m.matchId}", it.matchId in ids) }
                m.loserTo?.let { assertTrue("n=$n ${m.matchId}", it.matchId in ids) }
            }
        }
    }

    @Test fun threeTeamDoubleElimAllowedTwoIsNot() {
        assertNotNull(FixtureGenerator.generate(GenRequest(GenApiFormat.DOUBLE_ELIM, teams(3))))
        try {
            FixtureGenerator.generate(GenRequest(GenApiFormat.DOUBLE_ELIM, teams(2)))
            fail()
        } catch (e: GenException) {
            assertTrue(e.fields.contains("teams"))
        }
    }
}
