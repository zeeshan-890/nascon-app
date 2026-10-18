package com.nascon.league.cartmerge

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.matchlive.CartLine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RemoteCartMergeRepositoryTest {
    private val frozen = listOf(CartLine("va", "Alpha", 1000, 2), CartLine("vb", "Beta", 500, 1))

    private class Api(val response: () -> JsonObject) : CartMergeApi {
        var last: JsonObject? = null
        override suspend fun merge(body: JsonObject): JsonObject { last = body; return response() }
    }

    private fun cart(vararg items: JsonObject) = buildJsonObject { put("active_cart", buildJsonArray { items.forEach { add(it) } }) }
    private fun item(id: String, q: Int) = buildJsonObject { put("variant_id", id); put("quantity", q) }

    @Test fun keepsFrozenNamesAndPricesWhenOmitted() = runBlocking {
        val api = Api { cart(item("va", 3), item("vb", 1)) }
        val r = RemoteCartMergeRepository { api }.merge("g", "u", frozen) as NetworkResult.Success
        assertEquals(listOf(CartLine("va", "Alpha", 1000, 3), CartLine("vb", "Beta", 500, 1)), r.data)
    }

    @Test fun usesServerPricesAndNames() = runBlocking {
        val api = Api { cart(buildJsonObject { put("variant_id", "va"); put("quantity", 1); put("name", "Srv"); put("unit_price_minor", 42L) }) }
        val r = RemoteCartMergeRepository { api }.merge("g", "u", frozen) as NetworkResult.Success
        assertEquals(CartLine("va", "Srv", 42, 1), r.data[0])
    }

    @Test fun priceMinorAlias() = runBlocking {
        val api = Api { cart(buildJsonObject { put("variant_id", "vz"); put("quantity", 2); put("price_minor", 7L) }) }
        val r = RemoteCartMergeRepository { api }.merge("g", "u", frozen) as NetworkResult.Success
        assertEquals(CartLine("vz", "", 7, 2), r.data[0])
    }

    @Test fun missingActiveCartGivesEmptyList() = runBlocking {
        val api = Api { buildJsonObject { put("cart_merged", true); put("total_items", 3) } }
        val r = RemoteCartMergeRepository { api }.merge("g", "u", frozen) as NetworkResult.Success
        assertTrue(r.data.isEmpty())
    }

    @Test fun sendsSortedBodyWithFalseBlock() = runBlocking {
        val api = Api { cart() }
        RemoteCartMergeRepository { api }.merge("g", "u", frozen.reversed())
        assertEquals(CartMath.body("g", "u", frozen), api.last)
        assertEquals("false", api.last!!["test_write_block"].toString())
    }

    @Test fun ioErrorMapsToFailure() = runBlocking {
        val api = Api { throw IOException("x") }
        val r = RemoteCartMergeRepository { api }.merge("g", "u", frozen)
        assertEquals(NetworkResult.Failure(ApiError.NoConnection), r)
    }

    @Test fun conflictPassesThroughMachine() = runBlocking {
        val err = NetworkResult.Failure(ApiError.Http(409, "OUT_OF_STOCK", "m", buildJsonObject { put("variant_id", "va") }))
        val cart = FakeCart(frozen)
        val m = CartMergeMachine(FakeRepo(mutableListOf(err)), cart, { "g" }) { cart.store.value }
        m.stage()
        assertEquals(MergeState.Conflict("va"), m.merge("u"))
    }
}
