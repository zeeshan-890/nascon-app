package com.nascon.league.ui.fixtures

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Server timestamps are UTC (yyyy-MM-ddTHH:mm:ssZ). Never throws. */
object FixtureTime {
    const val TBC = "Time to be confirmed"

    fun parse(raw: String?): Instant? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return try {
            Instant.parse(s)
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(s).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Formats like "Sat 17 Oct, 3:30 PM" in [zone]/[locale]; [fallback] when null or invalid. */
    fun format(
        raw: String?,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        fallback: String = TBC,
    ): String {
        val instant = parse(raw) ?: return fallback
        return try {
            DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", locale)
                .withZone(zone)
                .format(instant)
                .replace(' ', ' ')
        } catch (_: Exception) {
            fallback
        }
    }
}
