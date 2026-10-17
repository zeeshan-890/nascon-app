package com.nascon.league.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class VariantDto(
    @SerialName("variant_id") val variantId: String,
    @SerialName("size") val size: String? = null,
    @SerialName("color") val color: String? = null,
    @SerialName("price_minor") val priceMinor: Long = 0,
    @SerialName("stock") val stock: Int = 0,
)

@Serializable
data class ProductDto(
    @SerialName("product_id") val productId: String,
    @SerialName("name") val name: String,
    @SerialName("category") val category: String = "",
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("variants") val variants: List<VariantDto> = emptyList(),
)

@Serializable
data class CheckoutItemDto(
    @SerialName("variant_id") val variantId: String,
    @SerialName("quantity") val quantity: Int,
)

@Serializable
data class PaymentRequestDto(@SerialName("token") val token: String)

@Serializable
data class CheckoutRequest(
    @SerialName("order_id") val orderId: String,
    @SerialName("customer_id") val customerId: String,
    @SerialName("items") val items: List<CheckoutItemDto>,
    @SerialName("discount_code") val discountCode: String? = null,
    @SerialName("payment") val payment: PaymentRequestDto,
)

@Serializable
data class ReceiptDto(
    @SerialName("payload") val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("signature") val signature: String = "",
    @SerialName("pdf_base64") val pdfBase64: String = "",
)

@Serializable
data class CheckoutResponse(
    @SerialName("order_id") val orderId: String,
    @SerialName("status") val status: String = "",
    @SerialName("currency") val currency: String = "USD",
    @SerialName("items") val items: List<JsonObject> = emptyList(),
    @SerialName("subtotal_minor") val subtotalMinor: Long = 0,
    @SerialName("discount_minor") val discountMinor: Long = 0,
    @SerialName("total_minor") val totalMinor: Long = 0,
    @SerialName("payment") val payment: JsonObject = JsonObject(emptyMap()),
    @SerialName("receipt") val receipt: ReceiptDto? = null,
)
