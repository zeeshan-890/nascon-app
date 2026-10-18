package com.nascon.league.cartmerge

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.matchlive.CartLine
import com.nascon.league.matchlive.GuestCart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeCart(initial: List<CartLine> = emptyList()) : GuestCart {
    val store = MutableStateFlow(initial)
    override val lines: Flow<List<CartLine>> = store
    override val count: Flow<Int> = store.map { l -> l.sumOf { it.quantity } }
    override suspend fun add(variantId: String, name: String, priceMinor: Long, quantity: Int) {
        store.value = store.value + CartLine(variantId, name, priceMinor, quantity)
    }
    override suspend fun setQuantity(variantId: String, quantity: Int) {
        store.value = store.value.map { if (it.variantId == variantId) it.copy(quantity = quantity) else it }
    }
    override suspend fun remove(variantId: String) { store.value = store.value.filter { it.variantId != variantId } }
    override suspend fun clear() { store.value = emptyList() }
}

class FakeRepo(private val script: MutableList<NetworkResult<List<CartLine>>> = mutableListOf()) : CartMergeRepository {
    val bodies = mutableListOf<JsonObject>()
    var gate: (suspend () -> Unit)? = null
    override suspend fun merge(guestToken: String, userId: String, lines: List<CartLine>): NetworkResult<List<CartLine>> {
        bodies += CartMath.body(guestToken, userId, lines)
        gate?.invoke()
        return if (script.isEmpty()) NetworkResult.Success(lines) else script.removeAt(0)
    }
}

private fun http(status: Int, code: String, variant: String? = null) = NetworkResult.Failure(
    ApiError.Http(status, code, "", variant?.let { JsonObject(mapOf("variant_id" to JsonPrimitive(it))) }),
)

class CartMergeMachineTest {
    private val a = CartLine("va", "A", 1000, 2)
    private val b = CartLine("vb", "B", 500, 1)
    private val c = CartLine("vc", "C", 250, 4)

    private fun machine(cart: FakeCart, repo: CartMergeRepository, token: String? = "guest_x") =
        CartMergeMachine(repo, cart, { token }) { cart.store.value }

    private fun many(n: Int) = (1..n).map { CartLine("v%02d".format(it), "N$it", 100, 1) }

    @Test fun emptyCartStaysIdle() = runBlocking {
        val m = machine(FakeCart(), FakeRepo())
        assertFalse(m.stage()); assertEquals(MergeState.Idle, m.state.value)
    }

    @Test fun stageWithItems() = runBlocking {
        val m = machine(FakeCart(listOf(a)), FakeRepo())
        assertTrue(m.stage()); assertEquals(MergeState.Staged, m.state.value)
    }

    @Test fun successClearsCart() = runBlocking {
        val cart = FakeCart(listOf(b, a))
        val m = machine(cart, FakeRepo()); m.stage()
        assertEquals(MergeState.Merged(listOf(a, b)), m.merge("u1"))
        assertTrue(cart.store.value.isEmpty())
    }

    @Test fun successUsesServerCart() = runBlocking {
        val server = listOf(CartLine("va", "A", 1000, 5))
        val m = machine(FakeCart(listOf(a)), FakeRepo(mutableListOf(NetworkResult.Success(server)))); m.stage()
        assertEquals(MergeState.Merged(server), m.merge("u1"))
    }

    @Test fun idempotentRetrySendsIdenticalBody() = runBlocking {
        val cart = FakeCart(listOf(b, a))
        val repo = FakeRepo(mutableListOf(NetworkResult.Failure(ApiError.Timeout)))
        val m = machine(cart, repo); m.stage()
        assertEquals(MergeState.FailedRetryable, m.merge("u1"))
        assertEquals(2, cart.store.value.size)
        val total = CartMath.total(cart.store.value)
        val r = m.merge("u1") as MergeState.Merged
        assertEquals(2, repo.bodies.size)
        assertEquals(repo.bodies[0].toString(), repo.bodies[1].toString())
        assertEquals(total, CartMath.total(r.cart))
    }

    @Test fun retryUsesFrozenBodyEvenIfCartChanged() = runBlocking {
        val cart = FakeCart(listOf(a))
        val repo = FakeRepo(mutableListOf(NetworkResult.Failure(ApiError.NoConnection)))
        val m = machine(cart, repo); m.stage(); m.merge("u1")
        cart.add("vz", "Z", 1, 1)
        m.merge("u1")
        assertEquals(repo.bodies[0], repo.bodies[1])
    }

    @Test fun killRestartProducesSameBody() = runBlocking {
        val cart = FakeCart(listOf(c, a, b))
        val repo = FakeRepo(mutableListOf(NetworkResult.Failure(ApiError.ServerUnreachable)))
        val m1 = machine(cart, repo); m1.stage(); m1.merge("u1")
        val m2 = machine(cart, repo); m2.stage(); m2.merge("u1")
        assertEquals(repo.bodies[0].toString(), repo.bodies[1].toString())
    }

    @Test fun doubleTapWhileMergingSendsOne() = runBlocking {
        val repo = FakeRepo()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repo.gate = { started.complete(Unit); release.await() }
        val m = machine(FakeCart(listOf(a)), repo); m.stage()
        val first = async(Dispatchers.Default) { m.merge("u1") }
        started.await()
        assertEquals(MergeState.Merging, m.merge("u1"))
        release.complete(Unit)
        assertTrue(first.await() is MergeState.Merged)
        assertEquals(1, repo.bodies.size)
    }

    @Test fun outOfStockKeepsCart() = runBlocking {
        val cart = FakeCart(listOf(a))
        val m = machine(cart, FakeRepo(mutableListOf(http(409, "OUT_OF_STOCK", "va")))); m.stage()
        assertEquals(MergeState.Conflict("va"), m.merge("u1"))
        assertEquals(1, cart.store.value.size)
    }

    @Test fun alreadyMergedClearsCart() = runBlocking {
        val cart = FakeCart(listOf(a))
        val m = machine(cart, FakeRepo(mutableListOf(http(409, "GUEST_TOKEN_ALREADY_MERGED")))); m.stage()
        assertTrue(m.merge("u1") is MergeState.AlreadyMerged)
        assertTrue(cart.store.value.isEmpty())
    }

    @Test fun badRequestFewLinesIsRetryable() = runBlocking {
        val m = machine(FakeCart(listOf(a)), FakeRepo(mutableListOf(http(400, "VALIDATION_ERROR")))); m.stage()
        assertEquals(MergeState.FailedRetryable, m.merge("u1"))
    }

    @Test fun badRequestManyLinesIsTooMany() = runBlocking {
        val m = machine(FakeCart(many(11)), FakeRepo(mutableListOf(http(400, "VALIDATION_ERROR")))); m.stage()
        assertEquals(MergeState.TooManyItems, m.merge("u1"))
    }

    @Test fun trimKeepsTen() = runBlocking {
        val repo = FakeRepo()
        val m = machine(FakeCart(many(12)), repo); m.stage(); m.trim()
        m.merge("u1")
        assertEquals(10, (repo.bodies[0]["guest_cart"] as JsonArray).size)
    }

    @Test fun variantMissing() = runBlocking {
        val m = machine(FakeCart(listOf(a)), FakeRepo(mutableListOf(http(404, "VARIANT_NOT_FOUND", "va")))); m.stage()
        assertEquals(MergeState.VariantMissing("va"), m.merge("u1"))
    }

    @Test fun restageAfterVariantMissingDropsLine() = runBlocking {
        val cart = FakeCart(listOf(a, b))
        val repo = FakeRepo(mutableListOf(http(404, "VARIANT_NOT_FOUND", "va")))
        val m = machine(cart, repo); m.stage(); m.merge("u1")
        cart.remove("va"); m.restage()
        assertEquals(MergeState.Staged, m.state.value)
        m.merge("u1")
        assertEquals(1, (repo.bodies[1]["guest_cart"] as JsonArray).size)
    }

    @Test fun playerMissing() = runBlocking {
        val m = machine(FakeCart(listOf(a)), FakeRepo(mutableListOf(http(404, "PLAYER_NOT_FOUND")))); m.stage()
        assertEquals(MergeState.UserMissing, m.merge("u1"))
    }

    @Test fun networkFailuresAreRetryable() = runBlocking {
        val errors = listOf(ApiError.NoConnection, ApiError.Timeout, ApiError.ServerUnreachable, ApiError.Unexpected(null), ApiError.Http(503, "HTTP_503", ""))
        for (e in errors) {
            val cart = FakeCart(listOf(a))
            val m = machine(cart, FakeRepo(mutableListOf(NetworkResult.Failure(e)))); m.stage()
            assertEquals(MergeState.FailedRetryable, m.merge("u1"))
            assertEquals(1, cart.store.value.size)
        }
    }

    @Test fun differentUserStartsNewBody() = runBlocking {
        val repo = FakeRepo(mutableListOf(NetworkResult.Failure(ApiError.Timeout)))
        val m = machine(FakeCart(listOf(a)), repo); m.stage()
        m.merge("u1"); m.merge("u2")
        assertEquals(JsonPrimitive("u2"), repo.bodies[1]["registered_user_id"])
    }

    @Test fun mergeOnEmptyCartDoesNotSend() = runBlocking {
        val repo = FakeRepo()
        val m = machine(FakeCart(), repo)
        assertEquals(MergeState.Idle, m.merge("u1")); assertTrue(repo.bodies.isEmpty())
    }

    @Test fun missingTokenIsRetryableAndNotSent() = runBlocking {
        val repo = FakeRepo()
        val m = machine(FakeCart(listOf(a)), repo, token = null); m.stage()
        assertEquals(MergeState.FailedRetryable, m.merge("u1")); assertTrue(repo.bodies.isEmpty())
    }

    @Test fun bodyShapeAndSortedOrder() {
        val body = CartMath.body("guest_t", "u9", listOf(c, a, b))
        assertEquals(listOf("guest_token", "registered_user_id", "guest_cart", "test_write_block"), body.keys.toList())
        val arr = body["guest_cart"] as JsonArray
        val ids = arr.map { ((it as JsonObject)["variant_id"] as JsonPrimitive).content }
        assertEquals(listOf("va", "vb", "vc"), ids)
        assertEquals(listOf("variant_id", "quantity"), (arr[0] as JsonObject).keys.toList())
    }

    @Test fun testWriteBlockAlwaysFalse() = runBlocking {
        val repo = FakeRepo(); val m = machine(FakeCart(listOf(a)), repo); m.stage(); m.merge("u1")
        assertEquals(JsonPrimitive(false), repo.bodies[0]["test_write_block"])
    }

    @Test fun mathTotals() {
        assertEquals(2000L + 500 + 1000, CartMath.total(listOf(a, b, c)))
        assertEquals(7, CartMath.count(listOf(a, b, c)))
        assertEquals(0L, CartMath.total(emptyList()))
    }
}
