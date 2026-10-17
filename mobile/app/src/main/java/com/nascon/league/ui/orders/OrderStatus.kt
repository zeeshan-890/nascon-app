package com.nascon.league.ui.orders

import androidx.annotation.StringRes
import com.nascon.league.R
import com.nascon.league.ui.components.ChipTone
import java.util.Locale

/** Maps a server order status to plain words; unknown or missing -> "Status unavailable". */
@StringRes
fun orderStatusRes(status: String?): Int = when (status?.trim()?.lowercase(Locale.ROOT)) {
    "paid" -> R.string.ord_status_paid
    "packed" -> R.string.ord_status_packed
    "shipped" -> R.string.ord_status_shipped
    "delivered" -> R.string.ord_status_delivered
    "refunded" -> R.string.ord_status_refunded
    "cancelled", "canceled" -> R.string.ord_status_cancelled
    else -> R.string.ord_status_unknown
}

fun orderStatusTone(status: String?): ChipTone = when (status?.trim()?.lowercase(Locale.ROOT)) {
    "paid", "delivered" -> ChipTone.Success
    "packed", "shipped" -> ChipTone.Brand
    "refunded", "cancelled", "canceled" -> ChipTone.Warning
    else -> ChipTone.Neutral
}
