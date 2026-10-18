package com.nascon.league.fixturegen

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.AppJson
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.data.fixturegen.FakeFixtureGenApi
import com.nascon.league.data.fixturegen.FakeFixtureGenSaveApi
import com.nascon.league.data.fixturegen.FixtureGenRules
import com.nascon.league.data.fixturegen.FixtureSaveResultDto
import com.nascon.league.data.fixturegen.GenApiFormat
import com.nascon.league.data.fixturegen.GenRequest
import com.nascon.league.data.fixturegen.GeneratedDto
import com.nascon.league.data.fixturegen.SwissHistoryMatch
import com.nascon.league.data.fixturegen.SwissHistoryRound
import com.nascon.league.data.fixturegen.Withdrawal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureGenModelsTest {
    private val teams = listOf("A", "B", "C", "D")

    // ---------------------------------------------------------------- request builders

    @Test fun roundRobinBodyHasLegsAndWithdrawals() {
        val body = GenRequest(
            GenApiFormat.ROUND_ROBIN, teams, legs = 2, withdrawalPolicy = "void_all",
            withdrawals = listOf(Withdrawal("B", 2)),
        ).toJson()
        assertEquals("round_robin", body["format"]!!.jsonPrimitive.content)
        assertEquals(4, body["teams"]!!.jsonArray.size)
        val p = body["params"]!!.jsonObject
        assertEquals(2, p["legs"]!!.jsonPrimitive.int)
        assertEquals("void_all", p["withdrawal_policy"]!!.jsonPrimitive.content)
        val w = p["withdrawals"]!!.jsonArray[0].jsonObject
        assertEquals("B", w["team_id"]!!.jsonPrimitive.content)
        assertEquals(2, w["after_round"]!!.jsonPrimitive.int)
        assertFalse(body.containsKey("save"))
    }

    @Test fun swissBodyCarriesRoundAndHistory() {
        val history = listOf(SwissHistoryRound(1, null, listOf(SwissHistoryMatch("A", "C", "home"))))
        val p = GenRequest(GenApiFormat.SWISS, teams, totalRounds = 3, swissRound = 2, history = history).params()
        assertEquals(3, p["total_rounds"]!!.jsonPrimitive.int)
        assertEquals(2, p["round"]!!.jsonPrimitive.int)
        val h = p["history"]!!.jsonArray[0].jsonObject
        assertEquals(1, h["round"]!!.jsonPrimitive.int)
        assertFalse(h.containsKey("bye"))
        assertEquals("home", h["matches"]!!.jsonArray[0].jsonObject["result"]!!.jsonPrimitive.content)
    }

    @Test fun bracketBodiesCarryFlagsAndResults() {
        val single = GenRequest(GenApiFormat.SINGLE_ELIM, teams, thirdPlace = true, results = mapOf("R1M1" to "A")).params()
        assertTrue(single["third_place_match"]!!.jsonPrimitive.boolean)
        assertEquals("A", single["results"]!!.jsonArray[0].jsonObject["winner"]!!.jsonPrimitive.content)
        val dbl = GenRequest(GenApiFormat.DOUBLE_ELIM, teams, bracketReset = true).params()
        assertTrue(dbl["bracket_reset"]!!.jsonPrimitive.boolean)
        assertFalse(dbl.containsKey("results"))
    }

    @Test fun saveBodyAddsSaveFlagAndScheduleHints() {
        val body = GenRequest(GenApiFormat.SINGLE_ELIM, teams, startDate = "2026-10-24", matchDays = listOf(6, 7)).toJson(save = true)
        assertTrue(body["save"]!!.jsonPrimitive.boolean)
        assertEquals("2026-10-24", body["start_date"]!!.jsonPrimitive.content)
        assertEquals(2, body["match_days"]!!.jsonArray.size)
        // hints never go to the pure generator
        val plain = GenRequest(GenApiFormat.SINGLE_ELIM, teams, startDate = "2026-10-24").toJson()
        assertFalse(plain.containsKey("start_date"))
    }

    @Test fun fromJsonRoundTripsToJson() {
        val req = GenRequest(
            GenApiFormat.ROUND_ROBIN, teams, legs = 2, withdrawalPolicy = "forfeit_remaining",
            withdrawals = listOf(Withdrawal("C", 1)),
        )
        val back = GenRequest.fromJson(req.toJson())
        assertEquals(req.format, back.format)
        assertEquals(req.teams, back.teams)
        assertEquals(2, back.legs)
        assertEquals(listOf(Withdrawal("C", 1)), back.withdrawals)
        val sw = GenRequest(
            GenApiFormat.SWISS, teams, totalRounds = 3, swissRound = 2,
            history = listOf(SwissHistoryRound(1, "A", listOf(SwissHistoryMatch("B", "C", "draw")))),
        )
        val swBack = GenRequest.fromJson(sw.toJson())
        assertEquals(sw.history, swBack.history)
        assertEquals(2, swBack.swissRound)
    }

    // ---------------------------------------------------------------- validation mirrors

    @Test fun teamLimitsMatchServer() {
        assertEquals(2..40, FixtureGenRules.teamRange(GenApiFormat.ROUND_ROBIN))
        assertEquals(2..64, FixtureGenRules.teamRange(GenApiFormat.SWISS))
        assertEquals(2..128, FixtureGenRules.teamRange(GenApiFormat.SINGLE_ELIM))
        assertEquals(3..128, FixtureGenRules.teamRange(GenApiFormat.DOUBLE_ELIM))
        assertFalse(FixtureGenRules.teamCountOk(GenApiFormat.DOUBLE_ELIM, 2))
        assertTrue(FixtureGenRules.teamCountOk(GenApiFormat.ROUND_ROBIN, 40))
        assertFalse(FixtureGenRules.teamCountOk(GenApiFormat.ROUND_ROBIN, 41))
    }

    @Test fun idRuleAndDuplicates() {
        assertTrue(FixtureGenRules.isValidId("club-alpha_1:a.b"))
        assertFalse(FixtureGenRules.isValidId("has space"))
        assertFalse(FixtureGenRules.isValidId(""))
        assertFalse(FixtureGenRules.isValidId("x".repeat(65)))
        assertEquals(listOf("teams"), FixtureGenRules.validate(GenRequest(GenApiFormat.SWISS, listOf("A", "A"))))
        assertEquals(listOf("format"), FixtureGenRules.validate(GenRequest("league", teams)))
        assertTrue(FixtureGenRules.validate(GenRequest(GenApiFormat.SWISS, teams)).isEmpty())
    }

    @Test fun swissDefaults() {
        assertEquals(2, FixtureGenRules.defaultSwissRounds(4))
        assertEquals(4, FixtureGenRules.defaultSwissRounds(9))
        assertEquals(1, FixtureGenRules.defaultSwissRounds(2))
        assertEquals(1..8, FixtureGenRules.swissRoundsRange(9))
        assertEquals(1..1, FixtureGenRules.swissRoundsRange(2))
    }

    @Test fun roundRobinRoundCount() {
        assertEquals(7, FixtureGenRules.roundRobinRounds(8, 1))
        assertEquals(14, FixtureGenRules.roundRobinRounds(8, 2))
        assertEquals(5, FixtureGenRules.roundRobinRounds(5, 1))
        assertEquals(8, FixtureGenRules.nextPow2(6))
    }

    // ---------------------------------------------------------------- response parsing (shapes captured from the backend)

    @Test fun parsesBackendRoundRobinResponse() {
        val json = """{"format":"round_robin","legs":1,"team_count":4,"round_count":3,"rounds":[{"round":1,"matches":[
            {"match_id":"RR-L1-R1-M1","leg":1,"home":"D","away":"A","status":"scheduled","awarded_to":null},
            {"match_id":"RR-L1-R1-M2","leg":1,"home":"B","away":"C","status":"scheduled","awarded_to":null}],"byes":[]}],
            "summary":{"total":6,"scheduled":6,"forfeit":0,"voided":0}}"""
        val dto = AppJson.decodeFromString(GeneratedDto.serializer(), json)
        assertEquals("round_robin", dto.format)
        assertEquals(3, dto.roundCount)
        assertEquals("D", dto.rounds[0].matches[0].home)
        assertEquals(null, dto.rounds[0].matches[0].awardedTo)
        assertEquals(6, dto.summary.total)
    }

    @Test fun parsesBackendSwissResponse() {
        val json = """{"format":"swiss","round":2,"total_rounds":2,"pairings":[{"match_id":"SW-R2-M1","home":"A","away":"D"},
            {"match_id":"SW-R2-M2","home":"B","away":"C"}],"bye":null,
            "standings":[{"rank":1,"team_id":"A","score":1,"buchholz":0},{"rank":2,"team_id":"B","score":0.5,"buchholz":0.5}]}"""
        val dto = AppJson.decodeFromString(GeneratedDto.serializer(), json)
        assertEquals(2, dto.round)
        assertEquals(2, dto.pairings.size)
        assertEquals(null, dto.bye)
        assertEquals(0.5, dto.standings[1].score, 0.0)
    }

    @Test fun parsesBackendBracketResponseWithNulls() {
        val json = """{"format":"double_elim","bracket_size":4,"bracket_reset":true,"winners":[{"round":1,"matches":[
            {"match_id":"WR1M1","bracket":"winners","round":1,"index":1,"home":"A","away":null,"home_seed":1,"away_seed":null,
             "bye":true,"winner":"A","loser":null,"winner_to":{"match_id":"WR2M1","slot":"home"},"loser_to":{"match_id":"LR1M1","slot":"home"}}]}],
            "losers":[],"grand_final":[{"match_id":"GF2","bracket":"grand_final","round":2,"index":1,"home":null,"away":null,
            "bye":false,"winner":null,"loser":null,"winner_to":null,"loser_to":null,"conditional":true,"required":null}]}"""
        val dto = AppJson.decodeFromString(GeneratedDto.serializer(), json)
        val m = dto.winners[0].matches[0]
        assertTrue(m.bye)
        assertEquals("WR2M1", m.winnerTo!!.matchId)
        assertEquals("home", m.loserTo!!.slot)
        assertTrue(dto.grandFinal[0].conditional)
        assertEquals(null, dto.grandFinal[0].required)
    }

    // ---------------------------------------------------------------- fake API behaves like the server

    @Test fun fakeApiReturnsParseableGeneratorShape() = runBlocking {
        val api = FakeFixtureGenApi { true }
        val out = api.generate(GenRequest(GenApiFormat.ROUND_ROBIN, teams).toJson())
        val dto = AppJson.decodeFromJsonElement(GeneratedDto.serializer(), out)
        assertEquals(6, dto.summary.total)
        assertTrue(out.containsKey("rounds"))
    }

    @Test fun fakeApiMapsValidationFailureLikeServer() = runBlocking {
        val api = FakeFixtureGenApi { true }
        val r = safeCall { api.generate(GenRequest(GenApiFormat.ROUND_ROBIN, listOf("A")).toJson()) }
        val err = (r as NetworkResult.Failure).error as ApiError.Http
        assertEquals(400, err.status)
        assertEquals("VALIDATION_ERROR", err.code)
    }

    @Test fun fakeApiRejectsRoundBeyondTotalRounds() = runBlocking {
        // all three pairings of four teams were already played, and round 4 exceeds the allowed total
        val api = FakeFixtureGenApi { true }
        val history = listOf(
            SwissHistoryRound(1, null, listOf(SwissHistoryMatch("A", "B", "home"), SwissHistoryMatch("C", "D", "home"))),
            SwissHistoryRound(2, null, listOf(SwissHistoryMatch("A", "C", "home"), SwissHistoryMatch("B", "D", "home"))),
            SwissHistoryRound(3, null, listOf(SwissHistoryMatch("A", "D", "home"), SwissHistoryMatch("B", "C", "home"))),
        )
        val req = GenRequest(GenApiFormat.SWISS, teams, totalRounds = 3, swissRound = 4, history = history)
        val r = safeCall { api.generate(req.toJson()) }
        assertEquals(400, ((r as NetworkResult.Failure).error as ApiError.Http).status)
    }

    @Test fun fakeApiOfflineThrowsUnreachable() = runBlocking {
        val api = FakeFixtureGenApi { false }
        val r = safeCall { api.generate(GenRequest(GenApiFormat.ROUND_ROBIN, teams).toJson()) }
        assertEquals(ApiError.ServerUnreachable, (r as NetworkResult.Failure).error)
    }

    @Test fun fakeSaveCountsPlayableMatches() = runBlocking {
        val save = FakeFixtureGenSaveApi { true }
        val body = GenRequest(GenApiFormat.SINGLE_ELIM, listOf("A", "B", "C", "D", "E", "F")).toJson(save = true)
        val out = save.save("league-alpha-1", body)
        val dto = AppJson.decodeFromJsonElement(FixtureSaveResultDto.serializer(), out)
        assertEquals(5, dto.created)
        assertEquals("league-alpha-1", dto.leagueId)
        assertEquals(5, save.savedCount("league-alpha-1"))
    }
}
