package com.nascon.league.newdata

import com.nascon.league.core.network.AppJson
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.data.FakeApi
import com.nascon.league.data.FakeCacheDao
import com.nascon.league.data.FakeFixtureDao
import com.nascon.league.data.api.LeagueApi
import com.nascon.league.data.api.dto.CheckoutItemDto
import com.nascon.league.data.api.dto.CheckoutRequest
import com.nascon.league.data.api.dto.ErrorEnvelope
import com.nascon.league.data.api.dto.HistoryEntryDto
import com.nascon.league.data.api.dto.OrderDetailDto
import com.nascon.league.data.api.dto.PaymentRequestDto
import com.nascon.league.data.api.dto.PlayerHistoryDto
import com.nascon.league.data.fake.FakeRoster
import com.nascon.league.data.fake.FakeStore
import com.nascon.league.data.fake.ReceiptPdfGenerator
import com.nascon.league.data.repo.CacheManager
import com.nascon.league.data.repo.OrderRepository
import com.nascon.league.data.repo.PlayerHistoryRepository
import com.nascon.league.data.repo.RefreshDeduper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import java.io.IOException

private class DocApi(
    var history: suspend (String) -> PlayerHistoryDto = { PlayerHistoryDto(it) },
    var orderImpl: suspend (String) -> OrderDetailDto = { OrderDetailDto(it, "paid") },
) : LeagueApi by FakeApi() {
    override suspend fun playerHistory(playerId: String, asOf: String?): PlayerHistoryDto = history(playerId)
    override suspend fun order(orderId: String): OrderDetailDto = orderImpl(orderId)
}

class PlayerOrderRepositoryTests {
    @Test
    fun historyNullBeforeRefreshThenCachedThenKeptOnFailure() = runTest {
        val dao = FakeCacheDao(FakeFixtureDao())
        val api = DocApi(history = { PlayerHistoryDto(it, currentSquadId = "sq-first", history = listOf(HistoryEntryDto(action = "registration"))) })
        val repo = PlayerHistoryRepository(dao, { api }, RefreshDeduper(backgroundScope), { true }, { 5_000L })
        assertNull(repo.observe("p1").first())
        assertTrue(repo.refresh("p1") is NetworkResult.Success)
        val cached = repo.observe("p1").first()!!
        assertEquals("registration", cached.data.history.single().action)
        assertEquals(5_000L, cached.updatedAt.toEpochMilli())
        assertNotNull(dao.meta["player_history:p1"])
        assertNotNull(dao.meta["updated:player_history:p1"])

        api.history = { throw IOException("boom") }
        assertTrue(repo.refresh("p1") is NetworkResult.Failure)
        assertEquals("sq-first", repo.observe("p1").first()!!.data.currentSquadId)
    }

    @Test
    fun orderCachedAndKeyedPerId() = runTest {
        val dao = FakeCacheDao(FakeFixtureDao())
        val api = DocApi()
        val repo = OrderRepository(dao, { api }, RefreshDeduper(backgroundScope), { true }, { 1L })
        assertTrue(repo.refresh("o1") is NetworkResult.Success)
        assertEquals("paid", repo.observe("o1").first()!!.data.status)
        assertNull(repo.observe("o2").first())
        assertNotNull(dao.meta["order:o1"])
    }

    @Test
    fun offlineRefreshFailsAndCacheClearRemovesDocs() = runTest {
        val dao = FakeCacheDao(FakeFixtureDao())
        val api = DocApi()
        val repo = OrderRepository(dao, { api }, RefreshDeduper(backgroundScope), { false }, { 1L })
        assertTrue(repo.refresh("o1") is NetworkResult.Failure)
        val online = OrderRepository(dao, { api }, RefreshDeduper(backgroundScope), { true }, { 1L })
        online.refresh("o1")
        CacheManager(dao).onLogout()
        assertNull(dao.meta["order:o1"])
        assertNull(online.observe("o1").first())
    }
}

class FakePlayerOrderDataTests {
    private fun transfer(player: String, type: String, from: String, to: String?, date: String, loanEnd: String? = null) =
        buildJsonObject {
            put("request_id", "req-$player-$type"); put("league_id", "league-alpha-1"); put("player_id", player)
            put("type", type); put("from_squad_id", from)
            if (to != null) put("to_squad_id", to)
            put("effective_date", date)
            if (loanEnd != null) put("loan_end_date", loanEnd)
            put("reason", "tactical"); put("requested_by", "u-manager")
        }

    @Test
    fun seededHistoryAndLoanEntry() {
        val roster = FakeRoster()
        val p2 = roster.history("p2", null)!!
        assertEquals("registration", p2.history.single().action)
        val p8 = roster.history("p8", null)!!
        assertEquals(listOf("registration", "loan"), p8.history.map { it.action })
        assertEquals("2027-06-30", p8.history.last().loanEndDate)
        assertEquals("sq-first", p8.parentSquadId)
        assertNull(roster.history("nope", null))
    }

    @Test
    fun transferAppendsHistoryEntryAndAsOfFilters() {
        val roster = FakeRoster()
        roster.transfer(transfer("p2", "transfer", "sq-first", "sq-youth", "2026-10-20"), null)
        val h = roster.history("p2", null)!!
        assertEquals("sq-youth", h.currentSquadId)
        val last = h.history.last()
        assertEquals("transfer", last.action)
        assertEquals("sq-youth", last.toSquadId)
        assertEquals("u-manager", last.who)
        assertEquals("tactical", last.why)
        assertEquals("2026-10-20", last.effectiveDate)
        assertNotNull(last.whenAt)
        assertEquals(1, roster.history("p2", "2026-09-01")!!.history.size)
    }

    private val pdf = ReceiptPdfGenerator { id, total -> "pdf:$id:$total".toByteArray() }

    @Test
    fun orderAfterCheckoutAndUnknown404() = runTest {
        val s = FakeStore(pdf)
        s.checkout(CheckoutRequest("o9", "c1", listOf(CheckoutItemDto("var-jersey-s", 2)), null, PaymentRequestDto("tok_success")))
        val o = s.order("o9")
        assertEquals("paid", o.status)
        assertEquals(9998L, o.totalMinor)
        assertEquals("Alpha Lions Home Jersey", o.items.single().name)
        assertEquals(listOf("pending", "paid"), o.statusHistory.map { it.toStatus })
        assertNotNull(o.paidAt)
        try {
            s.order("missing")
            throw AssertionError("expected 404")
        } catch (e: HttpException) {
            assertEquals(404, e.code())
            assertEquals("ORDER_NOT_FOUND", AppJson.decodeFromString<ErrorEnvelope>(e.response()!!.errorBody()!!.string()).error.code)
        }
    }
}
