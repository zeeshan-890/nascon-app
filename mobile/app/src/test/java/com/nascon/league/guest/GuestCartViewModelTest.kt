package com.nascon.league.guest

import com.nascon.league.matchlive.CartLine
import com.nascon.league.matchlive.GuestCart
import com.nascon.league.ui.guest.GuestCartViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeGuestCart(initial: List<CartLine> = emptyList()) : GuestCart {
    val state = MutableStateFlow(initial)
    override val lines: Flow<List<CartLine>> = state
    override val count: Flow<Int> = state.map { l -> l.sumOf { it.quantity } }

    override suspend fun add(variantId: String, name: String, priceMinor: Long, quantity: Int) {
        val cur = state.value
        val ex = cur.firstOrNull { it.variantId == variantId }
        state.value = if (ex == null) cur + CartLine(variantId, name, priceMinor, quantity)
        else cur.map { if (it.variantId == variantId) it.copy(quantity = it.quantity + quantity) else it }
    }

    override suspend fun setQuantity(variantId: String, quantity: Int) {
        state.value = if (quantity <= 0) state.value.filter { it.variantId != variantId }
        else state.value.map { if (it.variantId == variantId) it.copy(quantity = quantity) else it }
    }

    override suspend fun remove(variantId: String) {
        state.value = state.value.filter { it.variantId != variantId }
    }

    override suspend fun clear() {
        state.value = emptyList()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GuestCartViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val a = CartLine("va", "Jersey", 450000, 2)
    private val b = CartLine("vb", "Cap", 120050, 1)

    private fun vm(cart: FakeGuestCart): GuestCartViewModel {
        val v = GuestCartViewModel(cart)
        // keep the WhileSubscribed flows active
        v.lines.onEach { }.launchIn(kotlinx.coroutines.CoroutineScope(Dispatchers.Main))
        v.count.onEach { }.launchIn(kotlinx.coroutines.CoroutineScope(Dispatchers.Main))
        v.total.onEach { }.launchIn(kotlinx.coroutines.CoroutineScope(Dispatchers.Main))
        return v
    }

    @Test fun emptyCartIsZeros() {
        val v = vm(FakeGuestCart())
        assertEquals(0, v.count.value)
        assertEquals(0L, v.total.value)
        assertTrue(v.lines.value.isEmpty())
    }

    @Test fun countIsSumOfQuantities() {
        assertEquals(3, vm(FakeGuestCart(listOf(a, b))).count.value)
    }

    @Test fun totalIsInMinorUnits() {
        assertEquals(2 * 450000L + 120050L, vm(FakeGuestCart(listOf(a, b))).total.value)
    }

    @Test fun incAddsOne() {
        val v = vm(FakeGuestCart(listOf(a)))
        v.inc("va")
        assertEquals(3, v.count.value)
    }

    @Test fun doubleIncAddsTwo() {
        val v = vm(FakeGuestCart(listOf(a)))
        v.inc("va"); v.inc("va")
        assertEquals(4, v.lines.value.first().quantity)
    }

    @Test fun decReducesByOne() {
        val v = vm(FakeGuestCart(listOf(a)))
        v.dec("va")
        assertEquals(1, v.lines.value.first().quantity)
    }

    @Test fun decAtOneRemovesLine() {
        val v = vm(FakeGuestCart(listOf(b)))
        v.dec("vb")
        assertTrue(v.lines.value.isEmpty())
        assertEquals(0L, v.total.value)
    }

    @Test fun removeDeletesOnlyThatLine() {
        val v = vm(FakeGuestCart(listOf(a, b)))
        v.remove("va")
        assertEquals(listOf(b), v.lines.value)
    }

    @Test fun incIsCappedAtTen() {
        val v = vm(FakeGuestCart(listOf(a.copy(quantity = 10))))
        v.inc("va")
        assertEquals(10, v.lines.value.first().quantity)
    }

    @Test fun unknownVariantIsIgnored() {
        val v = vm(FakeGuestCart(listOf(a)))
        v.inc("zz"); v.dec("zz")
        assertEquals(listOf(a), v.lines.value)
    }

    @Test fun linesPersistInFakeAfterChanges() {
        val cart = FakeGuestCart(listOf(a))
        val v = vm(cart)
        v.inc("va")
        assertEquals(3, cart.state.value.first().quantity)
    }

    @Test fun externalAddUpdatesTotals() {
        val cart = FakeGuestCart()
        val v = vm(cart)
        kotlinx.coroutines.runBlocking { cart.add("vb", "Cap", 100, 3) }
        assertEquals(300L, v.total.value)
        assertEquals(3, v.count.value)
    }
}
