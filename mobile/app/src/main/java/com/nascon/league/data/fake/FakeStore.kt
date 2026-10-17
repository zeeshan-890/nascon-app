package com.nascon.league.data.fake

import com.nascon.league.data.api.dto.CheckoutRequest
import com.nascon.league.data.api.dto.CheckoutResponse
import com.nascon.league.data.api.dto.ProductDto
import com.nascon.league.data.api.dto.ReceiptDto
import com.nascon.league.data.api.dto.VariantDto
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.util.Base64
import kotlin.math.abs

/** Produces the receipt PDF bytes. The real one uses android.graphics.pdf; tests use a stub. */
fun interface ReceiptPdfGenerator {
    fun generate(orderId: String, totalMinor: Long): ByteArray
}

/** In-memory demo store: catalogue with stock, discount codes and idempotent checkout. */
class FakeStore(
    private val pdf: ReceiptPdfGenerator,
    private val discountCodes: Map<String, Int> = mapOf("WELCOME10" to 10),
) {
    private class Stored(
        val request: CheckoutRequest,
        val response: CheckoutResponse?,
        val failureStatus: Int,
        val failureJson: String?,
        val pdfBytes: ByteArray?,
    )

    private val lock = Any()
    private val stock = LinkedHashMap<String, Int>()
    private val orders = HashMap<String, Stored>()

    private val catalogue: List<ProductDto> = listOf(
        ProductDto(
            "prod-jersey", "Alpha Lions Home Jersey", "apparel", null,
            listOf(
                VariantDto("var-jersey-s", "S", "Blue", 4999, 20),
                VariantDto("var-jersey-m", "M", "Blue", 4999, 15),
                VariantDto("var-jersey-l", "L", "Blue", 5499, 8),
            ),
        ),
        ProductDto(
            "prod-scarf", "Alpha Lions Scarf", "accessories", null,
            listOf(
                VariantDto("var-scarf-red", null, "Red", 1999, 30),
                VariantDto("var-scarf-blue", null, "Blue", 1999, 1),
            ),
        ),
        ProductDto(
            "prod-cap", "Alpha Lions Cap", "accessories", null,
            listOf(
                VariantDto("var-cap-black", null, "Black", 1499, 40),
                VariantDto("var-cap-white", null, "White", 1499, 0),
            ),
        ),
    )

    init {
        catalogue.forEach { p -> p.variants.forEach { stock[it.variantId] = it.stock } }
    }

    private fun variantOf(id: String): Pair<ProductDto, VariantDto>? {
        for (p in catalogue) p.variants.firstOrNull { it.variantId == id }?.let { return p to it }
        return null
    }

    private suspend fun pause(key: String) = delay(300L + (abs(key.hashCode()) % 400).toLong())

    suspend fun products(): List<ProductDto> {
        pause("products")
        return synchronized(lock) {
            catalogue.map { p ->
                p.copy(variants = p.variants.map { it.copy(stock = stock.getValue(it.variantId)) })
            }
        }
    }

    suspend fun checkout(req: CheckoutRequest): CheckoutResponse {
        pause("checkout${req.orderId}")
        return synchronized(lock) { process(req) }
    }

    fun receiptPdf(orderId: String): ByteArray? {
        val bytes = synchronized(lock) { orders[orderId]?.pdfBytes }
        return bytes ?: fail(404, "ORDER_NOT_FOUND", "Order not found.")
    }

    private fun process(req: CheckoutRequest): CheckoutResponse {
        orders[req.orderId]?.let { stored ->
            if (stored.request != req) {
                fail(409, "DUPLICATE_ORDER_ID", "This order id was already used with different contents.")
            }
            stored.response?.let { return it }
            throw httpError(stored.failureStatus, stored.failureJson.orEmpty())
        }

        if (req.items.isEmpty() || req.items.size > 10 || req.items.any { it.quantity !in 1..10 }) {
            fail(400, "VALIDATION_ERROR", "Items must be 1 to 10 lines with a quantity of 1 to 10.")
        }
        val token = req.payment.token
        if (token !in KNOWN_TOKENS) fail(400, "VALIDATION_ERROR", "Unknown payment method.")

        val wanted = LinkedHashMap<String, Int>()
        req.items.forEach { wanted[it.variantId] = (wanted[it.variantId] ?: 0) + it.quantity }
        wanted.keys.forEach {
            if (variantOf(it) == null) fail(400, "VALIDATION_ERROR", "Unknown item.")
        }

        val code = req.discountCode?.trim().orEmpty()
        val percent = if (code.isEmpty()) 0 else {
            discountCodes[code] ?: discountCodes.entries.firstOrNull { it.key.equals(code, ignoreCase = true) }?.value
                ?: fail(400, "INVALID_DISCOUNT_CODE", "Discount code is not valid.")
        }

        wanted.forEach { (id, qty) ->
            if (stock.getValue(id) < qty) {
                fail(409, "OUT_OF_STOCK", "Item is out of stock.", buildJsonObject { put("variant_id", id) })
            }
        }
        wanted.forEach { (id, qty) -> stock[id] = stock.getValue(id) - qty }

        if (token == "tok_declined" || token == "tok_capture_fail") {
            wanted.forEach { (id, qty) -> stock[id] = stock.getValue(id) + qty }
            val declined = token == "tok_declined"
            val status = 402
            val json = errorJson(
                if (declined) "PAYMENT_DECLINED" else "PAYMENT_CAPTURE_FAILED",
                if (declined) "Payment was declined." else "Payment could not be completed.",
                null,
            )
            orders[req.orderId] = Stored(req, null, status, json, null)
            throw httpError(status, json)
        }

        var subtotal = 0L
        val items = wanted.map { (id, qty) ->
            val (product, variant) = variantOf(id)!!
            val line = variant.priceMinor * qty
            subtotal += line
            buildJsonObject {
                put("variant_id", id)
                put("product_id", product.productId)
                put("name", product.name)
                put("quantity", qty)
                put("unit_price_minor", variant.priceMinor)
                put("line_total_minor", line)
            }
        }
        val discount = subtotal * percent / 100
        val total = subtotal - discount
        val bytes = pdf.generate(req.orderId, total)
        val response = CheckoutResponse(
            orderId = req.orderId,
            status = "paid",
            currency = "PKR",
            items = items,
            subtotalMinor = subtotal,
            discountMinor = discount,
            totalMinor = total,
            payment = buildJsonObject {
                put("status", "captured")
                put("amount_minor", total)
            },
            receipt = ReceiptDto(
                payload = buildJsonObject {
                    put("order_id", req.orderId)
                    put("total_minor", total)
                    put("currency", "PKR")
                    put("paid_at", java.time.Instant.now().toString())
                },
                signature = "demo-signature",
                pdfBase64 = Base64.getEncoder().encodeToString(bytes),
            ),
        )
        orders[req.orderId] = Stored(req, response, 201, null, bytes)
        return response
    }

    private fun errorJson(code: String, message: String, details: JsonObject?): String {
        val error = buildJsonObject {
            put("code", code)
            put("message", message)
            if (details != null) put("details", details)
        }
        return buildJsonObject { put("error", error) }.toString()
    }

    private fun httpError(status: Int, json: String): HttpException =
        HttpException(Response.error<Any>(status, json.toResponseBody("application/json".toMediaType())))

    private fun fail(status: Int, code: String, message: String, details: JsonObject? = null): Nothing =
        throw httpError(status, errorJson(code, message, details))

    private companion object {
        val KNOWN_TOKENS = setOf("tok_success", "tok_declined", "tok_capture_fail")
    }
}
