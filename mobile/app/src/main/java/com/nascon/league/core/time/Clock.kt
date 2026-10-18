package com.nascon.league.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object Clock {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun format(instant: Instant): String = formatter.format(instant)

    fun nowUtc(): String = format(Instant.now())

    fun nowEpochSeconds(): Long = Instant.now().epochSecond

    /**
     * ISO-8601 with `Z` or a numeric offset, as a UTC instant.
     * A date-only `YYYY-MM-DD` is midnight UTC, not the device zone.
     */
    fun parseUtc(raw: String?): Instant? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        try {
            return Instant.parse(text)
        } catch (_: DateTimeParseException) {
            // offset forms such as 2026-10-17T11:00:00+05:00
        }
        try {
            return OffsetDateTime.parse(text).toInstant()
        } catch (_: DateTimeParseException) {
            // date-only
        }
        if (!DATE_ONLY.matches(text)) return null
        return try {
            LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private val DATE_ONLY = Regex("\\d{4}-\\d{2}-\\d{2}")
}
