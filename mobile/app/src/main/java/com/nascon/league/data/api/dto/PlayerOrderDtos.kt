package com.nascon.league.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlayerHistoryDto(
    @SerialName("player_id") val playerId: String,
    @SerialName("league_id") val leagueId: String? = null,
    @SerialName("current_squad_id") val currentSquadId: String? = null,
    @SerialName("parent_squad_id") val parentSquadId: String? = null,
    @SerialName("history") val history: List<HistoryEntryDto> = emptyList(),
)

@Serializable
data class HistoryEntryDto(
    @SerialName("audit_id") val auditId: String? = null,
    @SerialName("action") val action: String = "",
    @SerialName("from_squad_id") val fromSquadId: String? = null,
    @SerialName("to_squad_id") val toSquadId: String? = null,
    @SerialName("effective_date") val effectiveDate: String? = null,
    @SerialName("state") val state: String? = null,
    @SerialName("who") val who: String? = null,
    @SerialName("why") val why: String? = null,
    @SerialName("when") val whenAt: String? = null,
    @SerialName("loan_end_date") val loanEndDate: String? = null,
)

@Serializable
data class OrderDetailDto(
    @SerialName("order_id") val orderId: String,
    @SerialName("status") val status: String = "",
    @SerialName("currency") val currency: String = "PKR",
    @SerialName("items") val items: List<OrderItemDto> = emptyList(),
    @SerialName("subtotal_minor") val subtotalMinor: Long = 0,
    @SerialName("discount_minor") val discountMinor: Long = 0,
    @SerialName("total_minor") val totalMinor: Long = 0,
    @SerialName("paid_at") val paidAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("status_history") val statusHistory: List<OrderStatusEntryDto> = emptyList(),
)

@Serializable
data class OrderItemDto(
    @SerialName("variant_id") val variantId: String = "",
    @SerialName("name") val name: String? = null,
    @SerialName("quantity") val quantity: Int = 0,
    @SerialName("unit_price_minor") val unitPriceMinor: Long = 0,
)

@Serializable
data class OrderStatusEntryDto(
    @SerialName("from_status") val fromStatus: String? = null,
    @SerialName("to_status") val toStatus: String = "",
    @SerialName("at") val at: String? = null,
)
