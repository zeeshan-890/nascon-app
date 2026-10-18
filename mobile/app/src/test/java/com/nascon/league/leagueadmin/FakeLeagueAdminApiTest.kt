package com.nascon.league.leagueadmin

import com.nascon.league.data.leagueadmin.AdminLeagueRequest
import com.nascon.league.data.leagueadmin.DivisionRequest
import com.nascon.league.data.leagueadmin.FakeLeagueAdminApi
import com.nascon.league.data.leagueadmin.PromotionRulesDto
import com.nascon.league.data.leagueadmin.SeasonRequest
import com.nascon.league.data.leagueadmin.StandingsRulesDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import java.net.UnknownHostException

class FakeLeagueAdminApiTest {
    private fun api(online: Boolean = true) = FakeLeagueAdminApi({ online }, { 0L })

    private fun expectStatus(status: Int, block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("expected HTTP $status")
        } catch (e: HttpException) {
            assertEquals(status, e.code())
        }
    }

    @Test fun seededLeaguesMatchDemoWorld() = runBlocking {
        val l = api().adminLeagues()
        assertEquals(listOf("league-alpha-1", "league-alpha-2"), l.map { it.leagueId })
        assertEquals("draft", l[1].status)
    }

    @Test fun offlineThrowsUnknownHost() {
        try {
            runBlocking { api(online = false).adminLeagues() }
            fail("expected offline")
        } catch (e: UnknownHostException) {
            assertTrue(true)
        }
    }

    @Test fun createAndUpdateLeague() = runBlocking {
        val a = api()
        val created = a.createLeague(AdminLeagueRequest("Spring Cup", "football", "hybrid", null, null, 22))
        assertEquals("pending", created.status)
        assertEquals(3, a.adminLeagues().size)
        val updated = a.updateLeague(created.leagueId, AdminLeagueRequest("Spring Cup 2", "football", "swiss"))
        assertEquals("Spring Cup 2", updated.name)
        assertEquals("swiss", a.adminLeague(created.leagueId).format)
    }

    @Test fun approveAndRejectOnlyWorkOnPendingLeagues() = runBlocking {
        val a = api()
        val first = a.createLeague(AdminLeagueRequest("Spring Cup", "football", "hybrid", null, null, 22))
        assertEquals("active", a.approveLeague(first.leagueId).status)
        expectStatus(409) { a.approveLeague(first.leagueId) }
        val second = a.createLeague(AdminLeagueRequest("Autumn Cup", "football", "swiss", null, null, 22))
        assertEquals("rejected", a.rejectLeague(second.leagueId).status)
        expectStatus(409) { a.rejectLeague(second.leagueId) }
        expectStatus(404) { a.approveLeague("nope") }
    }

    @Test fun unknownLeagueIs404() {
        expectStatus(404) { api().adminLeague("nope") }
        expectStatus(404) { api().seasons("nope") }
    }

    @Test fun seasonEndMustBeAfterStart() {
        expectStatus(422) { api().createSeason("league-alpha-1", SeasonRequest("S", "2027-08-01", "2027-08-01")) }
    }

    @Test fun createSeasonCopiesCountsAndListsFirst() = runBlocking {
        val a = api()
        val s = a.createSeason("league-alpha-1", SeasonRequest("Season 2027", "2027-08-01", "2028-06-30", "season-2026"))
        assertEquals(2, s.leagueCount)
        assertEquals("Season 2027", a.seasons("league-alpha-1").first().name)
    }

    @Test fun divisionsGrowAndCountOnLeague() = runBlocking {
        val a = api()
        a.createDivision("league-alpha-1", DivisionRequest("Division C"))
        val d = a.divisions("league-alpha-1")
        assertEquals(3, d.size)
        assertEquals(3, d.last().tier)
        assertEquals(3, a.adminLeague("league-alpha-1").divisionCount)
        expectStatus(422) { a.createDivision("league-alpha-1", DivisionRequest(" ")) }
    }

    @Test fun rulesRoundTrip() = runBlocking {
        val a = api()
        assertEquals(2, a.promotionRules("league-alpha-1").spotsUp)
        a.savePromotionRules("league-alpha-1", PromotionRulesDto(1, 3))
        assertEquals(3, a.promotionRules("league-alpha-1").spotsDown)
        a.saveStandingsRules("league-alpha-1", StandingsRulesDto(pointsWin = 2, tiebreaks = listOf("wins")))
        assertEquals(listOf("wins"), a.standingsRules("league-alpha-1").tiebreaks)
        expectStatus(422) { a.saveStandingsRules("league-alpha-1", StandingsRulesDto(pointsWin = 0, pointsDraw = 1)) }
    }
}
