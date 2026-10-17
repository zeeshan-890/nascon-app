package com.nascon.league.core.time

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface UpdatedAge {
    object JustNow : UpdatedAge
    data class MinutesAgo(val minutes: Int) : UpdatedAge
    data class HoursAgo(val hours: Int) : UpdatedAge
    data class Absolute(val formatted: String) : UpdatedAge
}

object RelativeTime {
    fun age(
        updated: Instant,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): UpdatedAge {
        val d = Duration.between(updated, now)
        return when {
            d.isNegative || d.toMinutes() < 1 -> UpdatedAge.JustNow
            d.toMinutes() < 60 -> UpdatedAge.MinutesAgo(d.toMinutes().toInt())
            d.toHours() < 24 -> UpdatedAge.HoursAgo(d.toHours().toInt())
            else -> UpdatedAge.Absolute(
                DateTimeFormatter.ofPattern("d MMM, h:mm a", locale).withZone(zone).format(updated),
            )
        }
    }
}
