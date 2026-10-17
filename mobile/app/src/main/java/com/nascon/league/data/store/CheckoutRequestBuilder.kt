package com.nascon.league.data.store

import com.nascon.league.data.api.dto.CheckoutItemDto
import com.nascon.league.data.api.dto.CheckoutRequest
import com.nascon.league.data.api.dto.PaymentRequestDto

object CheckoutRequestBuilder {
    fun build(
        orderId: String,
        customerId: String,
        lines: List<CartLine>,
        discountCode: String?,
        token: String,
    ): CheckoutRequest {
        val merged = LinkedHashMap<String, Int>()
        lines.forEach { merged[it.variantId] = (merged[it.variantId] ?: 0) + it.quantity }
        return CheckoutRequest(
            orderId = orderId,
            customerId = customerId,
            items = merged.map { (id, qty) -> CheckoutItemDto(id, qty) },
            discountCode = discountCode?.trim()?.takeIf { it.isNotEmpty() },
            payment = PaymentRequestDto(token),
        )
    }

    /** Variant id / quantity pairs in request order, for [CheckoutAttempts.hash]. */
    fun itemsOf(request: CheckoutRequest): List<Pair<String, Int>> =
        request.items.map { it.variantId to it.quantity }
}
