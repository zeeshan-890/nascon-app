package com.nascon.league.f6ui

import com.nascon.league.core.network.ApiError
import com.nascon.league.data.api.dto.ProductDto
import com.nascon.league.data.api.dto.VariantDto
import com.nascon.league.data.store.CheckoutOutcome
import com.nascon.league.data.store.shouldClearAttempt
import com.nascon.league.ui.store.BannerKind
import com.nascon.league.ui.store.CartNotice
import com.nascon.league.ui.store.OrderResultHolder
import com.nascon.league.ui.store.PayHint
import com.nascon.league.ui.store.cartMaxQuantity
import com.nascon.league.ui.store.categoryKeys
import com.nascon.league.ui.store.checkoutEnabled
import com.nascon.league.ui.store.chooseVariant
import com.nascon.league.ui.store.clampQuantity
import com.nascon.league.ui.store.filterByCategory
import com.nascon.league.ui.store.normalizeDiscount
import com.nascon.league.ui.store.outcomeFor
import com.nascon.league.ui.store.payEnabled
import com.nascon.league.ui.store.payHint
import com.nascon.league.ui.store.pricesDiffer
import com.nascon.league.ui.store.resolveFailure
import com.nascon.league.ui.store.shortOrderRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreUiLogicTest {
    private fun http(status: Int, code: String, details: JsonObject? = null) =
        ApiError.Http(status, code, "", details)

    private fun v(id: String, stock: Int, price: Long = 100) = VariantDto(id, "M", null, price, stock)
    private fun p(id: String, category: String, vararg variants: VariantDto) =
        ProductDto(id, "Product $id", category, null, variants.toList())

    // ---- ApiError -> outcome

    @Test
    fun paymentFailuresAreDeclined() {
        assertEquals(CheckoutOutcome.Declined, outcomeFor(http(402, "PAYMENT_DECLINED")))
        assertEquals(CheckoutOutcome.Declined, outcomeFor(http(402, "PAYMENT_CAPTURE_FAILED")))
        assertEquals(CheckoutOutcome.Declined, outcomeFor(http(402, "HTTP_402")))
    }

    @Test
    fun conflictsAreSeparated() {
        assertEquals(CheckoutOutcome.OutOfStock, outcomeFor(http(409, "OUT_OF_STOCK")))
        assertEquals(CheckoutOutcome.DuplicateOrderId, outcomeFor(http(409, "DUPLICATE_ORDER_ID")))
        assertEquals(CheckoutOutcome.Unknown, outcomeFor(http(409, "SOMETHING_ELSE")))
    }

    @Test
    fun badRequests() {
        assertEquals(CheckoutOutcome.BadRequest, outcomeFor(http(400, "INVALID_DISCOUNT_CODE")))
        assertEquals(CheckoutOutcome.BadRequest, outcomeFor(http(400, "VALIDATION_ERROR")))
        assertEquals(CheckoutOutcome.BadRequest, outcomeFor(http(422, "VALIDATION_ERROR")))
    }

    @Test
    fun networkAndServerProblemsAreUnknownAndKeepTheAttempt() {
        val errors = listOf(
            ApiError.NoConnection, ApiError.Timeout, ApiError.ServerUnreachable,
            ApiError.Unexpected(null), http(500, "INTERNAL"), http(503, "HTTP_503"),
        )
        errors.forEach {
            assertEquals(CheckoutOutcome.Unknown, outcomeFor(it))
            assertFalse(shouldClearAttempt(outcomeFor(it)))
        }
    }

    @Test
    fun definiteOutcomesClearTheAttempt() {
        listOf(
            http(402, "PAYMENT_DECLINED"), http(409, "OUT_OF_STOCK"),
            http(400, "INVALID_DISCOUNT_CODE"), http(409, "DUPLICATE_ORDER_ID"),
        ).forEach { assertTrue(shouldClearAttempt(outcomeFor(it))) }
    }

    // ---- failure resolution (UI state)

    @Test
    fun declinedShowsBannerAndNoNavigation() {
        val r = resolveFailure(http(402, "PAYMENT_DECLINED"), hasDiscount = false)
        assertEquals(BannerKind.Declined, r.banner)
        assertFalse(r.backToCart)
        assertFalse(r.discountError)
    }

    @Test
    fun captureFailureHasItsOwnMessage() {
        assertEquals(BannerKind.CaptureFailed, resolveFailure(http(402, "PAYMENT_CAPTURE_FAILED"), false).banner)
    }

    @Test
    fun outOfStockReturnsToCartWithVariantId() {
        val details = JsonObject(mapOf("variant_id" to JsonPrimitive("var-9")))
        val r = resolveFailure(http(409, "OUT_OF_STOCK", details), hasDiscount = true)
        assertTrue(r.backToCart)
        assertEquals("var-9", r.soldOutVariantId)
        assertNull(r.banner)
    }

    @Test
    fun outOfStockWithoutDetailsStillReturnsToCart() {
        val r = resolveFailure(http(409, "OUT_OF_STOCK"), hasDiscount = false)
        assertTrue(r.backToCart)
        assertNull(r.soldOutVariantId)
    }

    @Test
    fun badDiscountMarksTheFieldNotTheBanner() {
        val r = resolveFailure(http(400, "INVALID_DISCOUNT_CODE"), hasDiscount = true)
        assertTrue(r.discountError)
        assertNull(r.banner)
    }

    @Test
    fun badRequestWithoutDiscountUsesBanner() {
        val r = resolveFailure(http(400, "VALIDATION_ERROR"), hasDiscount = false)
        assertFalse(r.discountError)
        assertEquals(BannerKind.Other, r.banner)
        assertNotNull(r.bannerMessage)
    }

    @Test
    fun connectionProblemsExplainThatTheStatusIsBeingChecked() {
        listOf(ApiError.NoConnection, ApiError.Timeout, ApiError.ServerUnreachable).forEach {
            assertEquals(BannerKind.ConnectionLost, resolveFailure(it, false).banner)
        }
    }

    @Test
    fun duplicateOrderIdAsksToTryAgain() {
        assertEquals(BannerKind.TryAgain, resolveFailure(http(409, "DUPLICATE_ORDER_ID"), false).banner)
    }

    @Test
    fun serverErrorUsesPlainMessage() {
        val r = resolveFailure(http(500, "INTERNAL"), false)
        assertEquals(BannerKind.Other, r.banner)
        assertFalse(r.bannerMessage.orEmpty().contains("500"))
    }

    // ---- button rules

    @Test
    fun payIsEnabledOnlyWhenOnlineIdleAndCartIsClean() {
        assertTrue(payEnabled(online = true, inFlight = false, lineCount = 2, hasBlockingProblem = false))
        assertFalse(payEnabled(online = false, inFlight = false, lineCount = 2, hasBlockingProblem = false))
        assertFalse(payEnabled(online = true, inFlight = true, lineCount = 2, hasBlockingProblem = false))
        assertFalse(payEnabled(online = true, inFlight = false, lineCount = 0, hasBlockingProblem = false))
        assertFalse(payEnabled(online = true, inFlight = false, lineCount = 2, hasBlockingProblem = true))
    }

    @Test
    fun payHintExplainsWhy() {
        assertEquals(PayHint.Offline, payHint(false, 1, false))
        assertEquals(PayHint.Problems, payHint(true, 1, true))
        assertEquals(PayHint.Empty, payHint(true, 0, false))
        assertNull(payHint(true, 1, false))
    }

    @Test
    fun cartCheckoutRule() {
        assertTrue(checkoutEnabled(1, false))
        assertFalse(checkoutEnabled(0, false))
        assertFalse(checkoutEnabled(1, true))
    }

    // ---- discount, categories, variants, quantities

    @Test
    fun discountIsTrimmedAndKeepsCase() {
        assertEquals("WeLcome10", normalizeDiscount("  WeLcome10 "))
        assertNull(normalizeDiscount("   "))
        assertNull(normalizeDiscount(""))
    }

    @Test
    fun categoriesAreDerivedCaseInsensitivelyInFirstSeenOrder() {
        val products = listOf(
            p("1", "Apparel", v("a", 1)), p("2", "apparel ", v("b", 1)),
            p("3", "Drinkware", v("c", 1)), p("4", "", v("d", 1)),
        )
        assertEquals(listOf("apparel", "drinkware"), categoryKeys(products))
        assertEquals(2, filterByCategory(products, "apparel").size)
        assertEquals(4, filterByCategory(products, null).size)
    }

    @Test
    fun chooseVariantPrefersSelectionThenFirstInStock() {
        val prod = p("1", "x", v("a", 0), v("b", 3), v("c", 5))
        assertEquals("c", chooseVariant(prod, "c")?.variantId)
        assertEquals("b", chooseVariant(prod, "a")?.variantId)
        assertEquals("b", chooseVariant(prod, null)?.variantId)
        assertNull(chooseVariant(p("2", "x", v("z", 0)), "z"))
    }

    @Test
    fun quantityIsClampedToStockAndTen() {
        assertEquals(3, clampQuantity(10, 3))
        assertEquals(10, clampQuantity(25, 40))
        assertEquals(1, clampQuantity(0, 5))
        assertEquals(1, clampQuantity(-4, 5))
        assertEquals(1, clampQuantity(5, 0))
        assertEquals(1, clampQuantity(3, 1))
    }

    @Test
    fun cartStepperMaximum() {
        assertEquals(10, cartMaxQuantity(null))
        assertEquals(10, cartMaxQuantity(50))
        assertEquals(4, cartMaxQuantity(4))
        assertEquals(1, cartMaxQuantity(0))
    }

    @Test
    fun pricesDifferDetection() {
        assertFalse(pricesDiffer(p("1", "x", v("a", 1, 100), v("b", 1, 100))))
        assertTrue(pricesDiffer(p("1", "x", v("a", 1, 100), v("b", 1, 150))))
    }

    // ---- handoffs

    @Test
    fun orderReferenceIsShortAndUppercase() {
        assertEquals("1A2B3C4D", shortOrderRef("1a2b3c4d-1111-2222-3333-444455556666"))
    }

    @Test
    fun orderResultHolderRoundTrip() {
        OrderResultHolder.put("o-1", 450000)
        assertEquals(450000L, OrderResultHolder.totalFor("o-1"))
        assertNull(OrderResultHolder.totalFor("missing"))
    }

    @Test
    fun cartNoticeIsDeliveredOnce() {
        CartNotice.post("hello")
        assertEquals("hello", CartNotice.take())
        assertNull(CartNotice.take())
    }
}
