package com.nascon.league.ui.receipt

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** True when the saved body is an error document rather than a PDF. */
fun isErrorPayload(leading: String): Boolean {
    val t = leading.trimStart().trimStart('\uFEFF')
    return t.startsWith("{") || t.startsWith("[")
}

const val RECEIPT_NOT_OPENED = "The receipt could not be opened."

/** Pure formatting helpers for the receipt screens. */
object ReceiptFormat {
    /** Short upper-case 8-character reference taken from the order id (dashes ignored). */
    fun orderReference(orderId: String): String =
        orderId.filter { it.isLetterOrDigit() }.take(8).uppercase(Locale.ROOT)

    /** Formats a UTC ISO timestamp in [zone]; returns null when it cannot be parsed. */
    fun paidDate(
        paidAtIso: String,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String? = try {
        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale)
            .withZone(zone)
            .format(Instant.parse(paidAtIso))
    } catch (_: Exception) {
        null
    }
}
