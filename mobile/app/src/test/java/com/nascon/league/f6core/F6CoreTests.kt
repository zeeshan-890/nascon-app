package com.nascon.league.f6core

import com.nascon.league.core.network.AppJson
import com.nascon.league.data.api.dto.CheckoutItemDto
import com.nascon.league.data.api.dto.CheckoutRequest
import com.nascon.league.data.api.dto.ErrorEnvelope
import com.nascon.league.data.api.dto.PaymentRequestDto
import com.nascon.league.data.api.dto.ProductDto
import com.nascon.league.data.api.dto.VariantDto
import com.nascon.league.data.fake.FakeStore
import com.nascon.league.data.fake.ReceiptPdfGenerator
import com.nascon.league.data.store.Cart
import com.nascon.league.data.store.CartLine
import com.nascon.league.data.store.CartProblem
import com.nascon.league.data.store.CartStore
import com.nascon.league.data.store.CheckoutAttempt
import com.nascon.league.data.store.CheckoutAttemptStore
import com.nascon.league.data.store.CheckoutAttempts
import com.nascon.league.data.store.CheckoutOutcome
import com.nascon.league.data.store.CheckoutRequestBuilder
import com.nascon.league.data.store.PaymentMethod
import com.nascon.league.data.store.StockText
import com.nascon.league.data.store.StoreLogic
import com.nascon.league.data.store.formatPkr
import com.nascon.league.data.store.reconcile
import com.nascon.league.data.store.shouldClearAttempt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

private fun line(
    id: String = "v1",
    price: Long = 1000,
    qty: Int = 2,
    problem: CartProblem? = null,
) = CartLine(id, "p1", "Jersey", "M · Red", price, qty, null, problem)

private fun product(vararg v: VariantDto, category: String = "apparel") =
    ProductDto("p1", "Jersey", category, null, v.toList())

class MoneyTest {
    @Test
    fun formats() {
        assertEquals("PKR 0.00", formatPkr(0))
        assertEquals("PKR 0.05", formatPkr(5))
        assertEquals("PKR 4,500.00", formatPkr(450000))
        assertEquals("PKR 12,345,678.90", formatPkr(1234567890))
        assertEquals("-PKR 1.50", formatPkr(-150))
        assertEquals("PKR 999.99", formatPkr(99999))
    }
}

class ReconcileTest {
    private fun variant(price: Long = 1000, stock: Int = 10) = VariantDto("v1", "M", "Red", price, stock)

    @Test
    fun unchangedHasNoProblem() {
        val r = reconcile(listOf(line(problem = CartProblem.PriceUpdated)), listOf(product(variant())))
        assertNull(r.single().problem)
    }

    @Test
    fun priceChange() {
        val r = reconcile(listOf(line()), listOf(product(variant(price = 1200)))).single()
        assertEquals(1200L, r.unitPriceMinor)
        assertEquals(CartProblem.PriceUpdated, r.problem)
    }

    @Test
    fun clampsToStock() {
        val r = reconcile(listOf(line(qty = 5)), listOf(product(variant(stock = 3)))).single()
        assertEquals(3, r.quantity)
        assertEquals(CartProblem.OnlyLeft(3), r.problem)
    }

    @Test
    fun missingVariant() {
        val r = reconcile(listOf(line("gone")), listOf(product(variant()))).single()
        assertEquals(CartProblem.NoLongerAvailable, r.problem)
        assertTrue(Cart(listOf(r)).hasBlockingProblem)
    }

    @Test
    fun zeroStockIsSoldOut() {
        val r = reconcile(listOf(line()), listOf(product(variant(stock = 0)))).single()
        assertEquals(CartProblem.SoldOut, r.problem)
        assertTrue(Cart(listOf(r)).hasBlockingProblem)
    }

    @Test
    fun cartTotals() {
        val c = Cart(listOf(line(price = 500, qty = 2), line("v2", price = 100, qty = 3)))
        assertEquals(1300L, c.subtotalMinor)
        assertEquals(5, c.itemCount)
        assertFalse(c.hasBlockingProblem)
    }
}

class StoreLogicTest {
    @Test
    fun categories() {
        val ps = listOf(
            product(category = "apparel"), product(category = "accessories"),
            product(category = "apparel"), product(category = ""),
        )
        assertEquals(listOf("apparel", "accessories"), StoreLogic.deriveCategories(ps))
        assertEquals("Apparel", StoreLogic.capitalizeLabel("apparel"))
    }

    @Test
    fun stock() {
        assertEquals(StockText.SoldOut, StoreLogic.stockText(0))
        assertEquals(StockText.OnlyLeft(1), StoreLogic.stockText(1))
        assertEquals(StockText.OnlyLeft(5), StoreLogic.stockText(5))
        assertEquals(StockText.InStock, StoreLogic.stockText(6))
        assertEquals(3, StoreLogic.maxQuantityFor(3))
        assertEquals(10, StoreLogic.maxQuantityFor(50))
        assertEquals(0, StoreLogic.maxQuantityFor(-2))
    }

    @Test
    fun labels() {
        assertEquals("M · Red", StoreLogic.variantLabel("M", "Red"))
        assertEquals("M", StoreLogic.variantLabel("M", null))
        assertEquals("Red", StoreLogic.variantLabel(" ", "Red"))
        assertEquals("Standard", StoreLogic.variantLabel(null, null))
    }

    @Test
    fun priceAndSoldOut() {
        val p = product(VariantDto("a", null, null, 500, 0), VariantDto("b", null, null, 300, 0))
        assertEquals(300L, StoreLogic.minPrice(p))
        assertTrue(StoreLogic.isSoldOut(p))
        assertFalse(StoreLogic.isSoldOut(product(VariantDto("a", null, null, 500, 1))))
        assertNull(StoreLogic.minPrice(product()))
    }
}

class CheckoutAttemptTest {
    private val items = listOf("v1" to 2, "v2" to 1)

    @Test
    fun sameHashReusesId() {
        val h = CheckoutAttempts.hash(items, "CODE", "tok_success")
        val first = CheckoutAttempts.resolve(null, h) { "id-1" }
        val again = CheckoutAttempts.resolve(first, h) { "id-2" }
        assertEquals("id-1", again.orderId)
    }

    @Test
    fun changedInputsGiveNewId() {
        val base = CheckoutAttempts.hash(items, "CODE", "tok_success")
        val stored = CheckoutAttempt("id-1", base)
        val variants = listOf(
            CheckoutAttempts.hash(listOf("v1" to 3, "v2" to 1), "CODE", "tok_success"),
            CheckoutAttempts.hash(items, "code", "tok_success"),
            CheckoutAttempts.hash(items, "CODE", "tok_declined"),
            CheckoutAttempts.hash(items.reversed(), "CODE", "tok_success"),
        )
        variants.forEach {
            assertNotEquals(base, it)
            assertEquals("new", CheckoutAttempts.resolve(stored, it) { "new" }.orderId)
        }
    }

    @Test
    fun blankCodeEqualsNoCode() {
        assertEquals(
            CheckoutAttempts.hash(items, null, "t"),
            CheckoutAttempts.hash(items, "  ", "t"),
        )
    }

    @Test
    fun clearing() {
        assertFalse(shouldClearAttempt(CheckoutOutcome.Unknown))
        CheckoutOutcome.entries.filter { it != CheckoutOutcome.Unknown }
            .forEach { assertTrue(shouldClearAttempt(it)) }
    }

    @Test
    fun persistenceShapeAndKeys() {
        val a = CheckoutAttempt("id", "hash")
        val json = AppJson.encodeToString(CheckoutAttempt.serializer(), a)
        assertEquals(a, AppJson.decodeFromString(CheckoutAttempt.serializer(), json))
        assertNotEquals(CheckoutAttemptStore.keyName("u1"), CheckoutAttemptStore.keyName("u2"))
    }
}

class RequestBuilderTest {
    @Test
    fun mergesDuplicatesKeepingOrder() {
        val req = CheckoutRequestBuilder.build(
            "o1", "c1",
            listOf(line("b", qty = 1), line("a", qty = 2), line("b", qty = 3)),
            "  Sale10 ", PaymentMethod.DECLINED.token,
        )
        assertEquals(listOf(CheckoutItemDto("b", 4), CheckoutItemDto("a", 2)), req.items)
        assertEquals("Sale10", req.discountCode)
        assertEquals("tok_declined", req.payment.token)
        assertEquals("o1", req.orderId)
        assertEquals("c1", req.customerId)
    }

    @Test
    fun blankCodeIsNull() {
        val req = CheckoutRequestBuilder.build("o", "c", listOf(line()), "   ", "tok_success")
        assertNull(req.discountCode)
    }
}

class CartJsonTest {
    @Test
    fun roundTrip() {
        val lines = listOf(
            line("a", problem = CartProblem.OnlyLeft(2)),
            line("b", problem = CartProblem.PriceUpdated),
            line("c", problem = CartProblem.SoldOut),
            line("d", problem = CartProblem.NoLongerAvailable),
            line("e"),
        )
        assertEquals(lines, CartStore.decodeCart(CartStore.encodeCart(lines)))
    }

    @Test
    fun badJsonIsEmpty() {
        assertTrue(CartStore.decodeCart("not json").isEmpty())
        assertTrue(CartStore.decodeCart(null).isEmpty())
    }

    @Test
    fun jsonUsesSnakeCaseKeys() {
        val json = CartStore.encodeCart(listOf(line()))
        assertTrue(json.contains("\"variant_id\""))
        assertTrue(json.contains("\"unit_price_minor\""))
    }

    @Test
    fun perUserKeys() {
        assertNotEquals(CartStore.keyName("u1"), CartStore.keyName("u2"))
        assertTrue(CartStore.keyName("u1").contains("u1"))
    }

    @Test
    fun mergeAndClamp() {
        val merged = CartStore.addLine(listOf(line("a", qty = 7)), line("a", qty = 6))
        assertEquals(1, merged.size)
        assertEquals(10, merged.single().quantity)
        assertEquals(2, CartStore.addLine(merged, line("b")).size)
        assertEquals(1, CartStore.setLineQuantity(merged, "a", 0).single().quantity)
        assertEquals(4, CartStore.setLineQuantity(merged, "a", 4).single().quantity)
    }
}

class FakeStoreTest {
    private val pdf = ReceiptPdfGenerator { id, total -> "pdf:$id:$total".toByteArray() }
    private fun store() = FakeStore(pdf)

    private fun req(
        orderId: String = "o1",
        items: List<CheckoutItemDto> = listOf(CheckoutItemDto("var-jersey-s", 2)),
        code: String? = null,
        token: String = "tok_success",
    ) = CheckoutRequest(orderId, "c1", items, code, PaymentRequestDto(token))

    private suspend fun expectError(status: Int, code: String, block: suspend () -> Unit): ErrorEnvelope {
        try {
            block()
        } catch (e: HttpException) {
            assertEquals(status, e.code())
            val env = AppJson.decodeFromString<ErrorEnvelope>(e.response()!!.errorBody()!!.string())
            assertEquals(code, env.error.code)
            return env
        }
        throw AssertionError("expected HttpException $status $code")
    }

    private suspend fun FakeStore.stockOf(id: String) =
        products().flatMap { it.variants }.first { it.variantId == id }.stock

    @Test
    fun successProducesReceipt() = runTest {
        val s = store()
        val r = s.checkout(req())
        assertEquals(9998L, r.totalMinor)
        assertEquals("demo-signature", r.receipt!!.signature)
        assertTrue(r.receipt!!.pdfBase64.isNotEmpty())
        assertEquals("pdf:o1:9998", String(s.receiptPdf("o1")!!))
        assertEquals(18, s.stockOf("var-jersey-s"))
    }

    @Test
    fun declinedRestoresStock() = runTest {
        val s = store()
        expectError(402, "PAYMENT_DECLINED") { s.checkout(req(token = "tok_declined")) }
        assertEquals(20, s.stockOf("var-jersey-s"))
    }

    @Test
    fun captureFailure() = runTest {
        val s = store()
        expectError(402, "PAYMENT_CAPTURE_FAILED") { s.checkout(req(token = "tok_capture_fail")) }
        assertEquals(20, s.stockOf("var-jersey-s"))
    }

    @Test
    fun replayIsIdempotent() = runTest {
        val s = store()
        val first = s.checkout(req())
        val second = s.checkout(req())
        assertEquals(first, second)
        assertEquals(18, s.stockOf("var-jersey-s"))
        expectError(402, "PAYMENT_DECLINED") { s.checkout(req("d", token = "tok_declined")) }
        expectError(402, "PAYMENT_DECLINED") { s.checkout(req("d", token = "tok_declined")) }
    }

    @Test
    fun differentBodyIsDuplicate() = runTest {
        val s = store()
        s.checkout(req())
        expectError(409, "DUPLICATE_ORDER_ID") {
            s.checkout(req(items = listOf(CheckoutItemDto("var-jersey-s", 3))))
        }
    }

    @Test
    fun discountFloors() = runTest {
        val s = store()
        // 3 scarves = 5997; 10% = 599.7 -> 599
        val r = s.checkout(req(items = listOf(CheckoutItemDto("var-scarf-red", 3)), code = "WELCOME10"))
        assertEquals(5997L, r.subtotalMinor)
        assertEquals(599L, r.discountMinor)
        assertEquals(5398L, r.totalMinor)
    }

    @Test
    fun badDiscountCode() = runTest {
        expectError(400, "INVALID_DISCOUNT_CODE") { store().checkout(req(code = "NOPE")) }
    }

    @Test
    fun outOfStockCarriesVariant() = runTest {
        val s = store()
        val env = expectError(409, "OUT_OF_STOCK") {
            s.checkout(req(items = listOf(CheckoutItemDto("var-scarf-blue", 2))))
        }
        assertEquals("var-scarf-blue", (env.error.details!!["variant_id"] as kotlinx.serialization.json.JsonPrimitive).content)
        expectError(409, "OUT_OF_STOCK") {
            s.checkout(req("o2", items = listOf(CheckoutItemDto("var-cap-white", 1))))
        }
        assertEquals(1, s.stockOf("var-scarf-blue"))
    }

    @Test
    fun validation() = runTest {
        val s = store()
        expectError(400, "VALIDATION_ERROR") { s.checkout(req(items = emptyList())) }
        expectError(400, "VALIDATION_ERROR") { s.checkout(req(items = listOf(CheckoutItemDto("var-jersey-s", 0)))) }
        expectError(400, "VALIDATION_ERROR") { s.checkout(req(items = listOf(CheckoutItemDto("var-jersey-s", 11)))) }
        expectError(400, "VALIDATION_ERROR") {
            s.checkout(req(items = List(11) { CheckoutItemDto("var-jersey-s", 1) }))
        }
    }

    @Test
    fun receiptUnknownOrder() = runTest {
        expectError(404, "ORDER_NOT_FOUND") { store().receiptPdf("missing") }
    }
}
