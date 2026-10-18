package com.nascon.league.data.cart

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeGuestCartDao : GuestCartDao {
    val state = MutableStateFlow<Map<Pair<String, String>, GuestCartItemEntity>>(emptyMap())

    override fun observe(token: String): Flow<List<GuestCartItemEntity>> =
        state.map { m -> m.values.filter { it.token == token }.sortedBy { it.name } }

    override suspend fun items(token: String) = state.value.values.filter { it.token == token }
    override suspend fun find(token: String, variantId: String) = state.value[token to variantId]
    override suspend fun upsert(item: GuestCartItemEntity) {
        state.value = state.value + ((item.token to item.variantId) to item)
    }

    override suspend fun delete(token: String, variantId: String) {
        state.value = state.value - (token to variantId)
    }

    override suspend fun clear(token: String) {
        state.value = state.value.filterKeys { it.first != token }
    }
}

class RoomGuestCartTest {
    private val dao = FakeGuestCartDao()
    private var token: String? = "tok1"
    private val cart = RoomGuestCart(dao, { token })

    @Test fun addTwiceAccumulates() = runBlocking {
        cart.add("v1", "Shirt", 1500)
        cart.add("v1", "Shirt", 1500, 2)
        assertEquals(3, cart.snapshot().single().quantity)
    }

    @Test fun capsAtTenPerVariant() = runBlocking {
        cart.add("v1", "A", 100, 8)
        cart.add("v1", "A", 100, 8)
        assertEquals(10, cart.snapshot().single().quantity)
    }

    @Test fun firstAddOverCapIsCapped() = runBlocking {
        cart.add("v1", "A", 100, 50)
        assertEquals(10, cart.snapshot().single().quantity)
    }

    @Test fun zeroQuantityAddCoercedToOne() = runBlocking {
        cart.add("v1", "A", 100, 0)
        assertEquals(1, cart.snapshot().single().quantity)
    }

    @Test fun eleventhDistinctVariantIgnored() = runBlocking {
        for (i in 1..11) cart.add("v$i", "N$i", 100)
        val lines = cart.snapshot()
        assertEquals(10, lines.size)
        assertTrue(lines.none { it.variantId == "v11" })
    }

    @Test fun existingVariantStillUpdatableWhenFull() = runBlocking {
        for (i in 1..10) cart.add("v$i", "N$i", 100)
        cart.add("v1", "N1", 100)
        assertEquals(2, cart.snapshot().first { it.variantId == "v1" }.quantity)
    }

    @Test fun setQuantityZeroRemoves() = runBlocking {
        cart.add("v1", "A", 100)
        cart.setQuantity("v1", 0)
        assertTrue(cart.snapshot().isEmpty())
    }

    @Test fun setQuantityCapsAtTen() = runBlocking {
        cart.add("v1", "A", 100)
        cart.setQuantity("v1", 99)
        assertEquals(10, cart.snapshot().single().quantity)
    }

    @Test fun setQuantityUpdates() = runBlocking {
        cart.add("v1", "A", 100)
        cart.setQuantity("v1", 4)
        assertEquals(4, cart.snapshot().single().quantity)
    }

    @Test fun removeDeletesOnlyThatLine() = runBlocking {
        cart.add("v1", "A", 100)
        cart.add("v2", "B", 100)
        cart.remove("v1")
        assertEquals(listOf("v2"), cart.snapshot().map { it.variantId })
    }

    @Test fun clearEmptiesCart() = runBlocking {
        cart.add("v1", "A", 100)
        cart.add("v2", "B", 100)
        cart.clear()
        assertTrue(cart.snapshot().isEmpty())
        assertEquals(0, cart.count.first())
    }

    @Test fun countSumsQuantities() = runBlocking {
        cart.add("v1", "A", 100, 3)
        cart.add("v2", "B", 100, 4)
        assertEquals(7, cart.count.first())
        assertEquals(2, cart.lines.first().size)
    }

    @Test fun perTokenIsolation() = runBlocking {
        cart.add("v1", "A", 100, 2)
        token = "tok2"
        assertTrue(cart.snapshot().isEmpty())
        assertEquals(0, cart.count.first())
        cart.add("v1", "A", 100, 5)
        token = "tok1"
        assertEquals(2, cart.snapshot().single().quantity)
    }

    @Test fun nullAndBlankTokenUseFallbackKey() = runBlocking {
        token = null
        cart.add("v1", "A", 100)
        token = "  "
        cart.add("v1", "A", 100)
        assertEquals(2, dao.items(RoomGuestCart.FALLBACK_KEY).single().quantity)
        assertNull(dao.find("tok1", "v1"))
    }

    @Test fun concurrentAddsEndAtCap() = runBlocking {
        (1..20).map { async(kotlinx.coroutines.Dispatchers.Default) { cart.add("v1", "A", 100) } }.awaitAll()
        assertEquals(10, cart.snapshot().single().quantity)
    }

    @Test fun concurrentAddsAreNotLost() = runBlocking {
        (1..8).map { async(kotlinx.coroutines.Dispatchers.Default) { cart.add("v1", "A", 100) } }.awaitAll()
        assertEquals(8, cart.snapshot().single().quantity)
    }

    @Test fun persistsAcrossInstances() = runBlocking {
        cart.add("v1", "A", 100, 3)
        val other = RoomGuestCart(dao, { token })
        assertEquals(3, other.snapshot().single().quantity)
        assertEquals(3, other.count.first())
    }

    @Test fun pricesStoredAsMinorUnits() = runBlocking {
        cart.add("v1", "A", 123_456_789_012L)
        assertEquals(123_456_789_012L, dao.find("tok1", "v1")!!.priceMinor)
        assertEquals(123_456_789_012L, cart.lines.first().single().priceMinor)
    }
}
