package com.nascon.league.data.orders

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Club orders. The list endpoint is a CONTRACT ADDITION (the backend has none); the status and refund calls are the
 * real PATCH /orders/:id/status and POST /orders/:id/refund (order.manage), kept here because LeagueApi is not ours.
 */
interface ClubOrdersApi {
    @GET("api/v1/clubs/{club_id}/orders")
    suspend fun clubOrders(@Path("club_id") clubId: String): List<ClubOrderDto>

    @PATCH("api/v1/orders/{id}/status")
    suspend fun setStatus(@Path("id") orderId: String, @Body body: OrderStatusRequest): OrderTransitionDto

    @POST("api/v1/orders/{id}/refund")
    suspend fun refund(@Path("id") orderId: String): OrderTransitionDto
}

@Serializable
data class ClubOrderDto(
    @SerialName("order_id") val orderId: String,
    @SerialName("status") val status: String = "",
    @SerialName("total_minor") val totalMinor: Long = 0,
    @SerialName("currency") val currency: String = "PKR",
    @SerialName("paid_at") val paidAt: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("item_count") val itemCount: Int = 0,
)

@Serializable
data class OrderStatusRequest(@SerialName("status") val status: String)

/** Real response of both order.manage endpoints: `{order_id, from, to}`. */
@Serializable
data class OrderTransitionDto(
    @SerialName("order_id") val orderId: String = "",
    @SerialName("from") val from: String = "",
    @SerialName("to") val to: String = "",
)
