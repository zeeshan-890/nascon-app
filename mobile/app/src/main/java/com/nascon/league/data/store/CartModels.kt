package com.nascon.league.data.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface CartProblem {
    @Serializable
    data object PriceUpdated : CartProblem

    @Serializable
    data class OnlyLeft(val n: Int) : CartProblem

    @Serializable
    data object NoLongerAvailable : CartProblem

    @Serializable
    data object SoldOut : CartProblem
}

@Serializable
data class CartLine(
    @SerialName("variant_id") val variantId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("name") val name: String,
    @SerialName("variant_label") val variantLabel: String,
    @SerialName("unit_price_minor") val unitPriceMinor: Long,
    @SerialName("quantity") val quantity: Int,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("problem") val problem: CartProblem? = null,
)

data class Cart(val lines: List<CartLine> = emptyList()) {
    val subtotalMinor: Long get() = lines.sumOf { it.unitPriceMinor * it.quantity }
    val itemCount: Int get() = lines.sumOf { it.quantity }
    val hasBlockingProblem: Boolean
        get() = lines.any { it.problem == CartProblem.NoLongerAvailable || it.problem == CartProblem.SoldOut }
}

enum class PaymentMethod(val token: String) {
    APPROVED("tok_success"),
    DECLINED("tok_declined"),
    CAPTURE_FAIL("tok_capture_fail"),
}
