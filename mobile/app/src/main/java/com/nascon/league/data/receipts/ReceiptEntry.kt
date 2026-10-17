package com.nascon.league.data.receipts

import kotlinx.serialization.Serializable

/** One row of the per-user receipt index. [paidAt] is UTC ISO yyyy-MM-ddTHH:mm:ssZ. */
@Serializable
data class ReceiptEntry(
    val orderId: String,
    val totalMinor: Long,
    val paidAt: String,
)
