package com.nascon.league.ui.store

import com.nascon.league.ui.failure.userMessage
import com.nascon.league.core.network.ApiError
import com.nascon.league.data.api.dto.ProductDto
import com.nascon.league.data.api.dto.VariantDto
import com.nascon.league.data.store.CheckoutOutcome
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

const val MAX_LINE_QUANTITY = 10

/** What the checkout banner should say. The UI maps each kind to a string resource. */
enum class BannerKind { Declined, CaptureFailed, ConnectionLost, TryAgain, Other }

data class PayResolution(
    val outcome: CheckoutOutcome,
    val banner: BannerKind?,
    /** Only used with [BannerKind.Other]. */
    val bannerMessage: String?,
    val discountError: Boolean,
    val soldOutVariantId: String?,
    val backToCart: Boolean,
)

fun ApiError.isConnectionProblem(): Boolean =
    this is ApiError.NoConnection || this is ApiError.Timeout || this is ApiError.ServerUnreachable

/** Maps a failed checkout call to the contract outcome (never Success). */
fun outcomeFor(error: ApiError): CheckoutOutcome = when (error) {
    is ApiError.Http -> when {
        error.code == "DUPLICATE_ORDER_ID" -> CheckoutOutcome.DuplicateOrderId
        error.status == 402 || error.code == "PAYMENT_DECLINED" || error.code == "PAYMENT_CAPTURE_FAILED" ->
            CheckoutOutcome.Declined
        error.code == "OUT_OF_STOCK" -> CheckoutOutcome.OutOfStock
        error.status == 400 || error.status == 422 ||
            error.code == "VALIDATION_ERROR" || error.code == "INVALID_DISCOUNT_CODE" -> CheckoutOutcome.BadRequest
        else -> CheckoutOutcome.Unknown
    }
    else -> CheckoutOutcome.Unknown
}

fun resolveFailure(error: ApiError, hasDiscount: Boolean): PayResolution {
    val outcome = outcomeFor(error)
    val http = error as? ApiError.Http
    return when (outcome) {
        CheckoutOutcome.Declined -> PayResolution(
            outcome,
            if (http?.code == "PAYMENT_CAPTURE_FAILED") BannerKind.CaptureFailed else BannerKind.Declined,
            null, false, null, false,
        )
        CheckoutOutcome.OutOfStock -> PayResolution(
            outcome, null, null, false,
            (http?.details?.get("variant_id") as? JsonPrimitive)?.contentOrNull,
            true,
        )
        CheckoutOutcome.BadRequest ->
            if (hasDiscount) {
                PayResolution(outcome, null, null, true, null, false)
            } else {
                PayResolution(outcome, BannerKind.Other, userMessage(error), false, null, false)
            }
        CheckoutOutcome.DuplicateOrderId -> PayResolution(outcome, BannerKind.TryAgain, null, false, null, false)
        CheckoutOutcome.Success, CheckoutOutcome.Unknown ->
            if (error.isConnectionProblem()) {
                PayResolution(outcome, BannerKind.ConnectionLost, null, false, null, false)
            } else {
                PayResolution(outcome, BannerKind.Other, userMessage(error), false, null, false)
            }
    }
}

enum class PayHint { Offline, Empty, Problems }

fun payHint(online: Boolean, lineCount: Int, hasBlockingProblem: Boolean): PayHint? = when {
    lineCount <= 0 -> PayHint.Empty
    hasBlockingProblem -> PayHint.Problems
    !online -> PayHint.Offline
    else -> null
}

fun payEnabled(online: Boolean, inFlight: Boolean, lineCount: Int, hasBlockingProblem: Boolean): Boolean =
    !inFlight && payHint(online, lineCount, hasBlockingProblem) == null

fun checkoutEnabled(lineCount: Int, hasBlockingProblem: Boolean): Boolean =
    lineCount > 0 && !hasBlockingProblem

/** Trimmed; blank means "no code". The case the user typed is kept. */
fun normalizeDiscount(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

fun categoryKey(raw: String): String = raw.trim().lowercase()

/** Distinct categories in first-seen order, compared case-insensitively. Blank categories are ignored. */
fun categoryKeys(products: List<ProductDto>): List<String> =
    products.map { categoryKey(it.category) }.filter { it.isNotEmpty() }.distinct()

fun filterByCategory(products: List<ProductDto>, key: String?): List<ProductDto> =
    if (key == null) products else products.filter { categoryKey(it.category) == key }

/** The variant to show: the chosen one if it still has stock, else the first with stock, else null. */
fun chooseVariant(product: ProductDto, selectedId: String?): VariantDto? {
    val chosen = product.variants.firstOrNull { it.variantId == selectedId && it.stock > 0 }
    return chosen ?: product.variants.firstOrNull { it.stock > 0 }
}

/** Keeps quantity within 1..10 and never above stock (but never below 1). */
fun clampQuantity(quantity: Int, stock: Int): Int {
    val max = minOf(MAX_LINE_QUANTITY, stock).coerceAtLeast(1)
    return quantity.coerceIn(1, max)
}

/** Largest quantity a cart stepper may reach; unknown stock allows the global maximum. */
fun cartMaxQuantity(stock: Int?): Int =
    if (stock == null) MAX_LINE_QUANTITY else minOf(MAX_LINE_QUANTITY, stock).coerceAtLeast(1)

fun pricesDiffer(product: ProductDto): Boolean = product.variants.map { it.priceMinor }.distinct().size > 1

fun shortOrderRef(orderId: String): String = orderId.replace("-", "").take(8).uppercase()

/** Tiny in-memory handoff from checkout to the success screen. */
object OrderResultHolder {
    private val totals = ConcurrentHashMap<String, Long>()
    fun put(orderId: String, totalMinor: Long) {
        totals[orderId] = totalMinor
    }

    fun totalFor(orderId: String): Long? = totals[orderId]
}

/** One-shot notice shown on the cart screen (e.g. after a sold-out checkout). */
object CartNotice {
    @Volatile private var message: String? = null

    fun post(text: String) {
        message = text
    }

    fun take(): String? = message.also { message = null }
}
