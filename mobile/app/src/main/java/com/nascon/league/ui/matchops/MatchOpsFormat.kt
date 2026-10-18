package com.nascon.league.ui.matchops

import com.nascon.league.data.matchops.ScheduleTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Plain date / time text for the match admin screens (device zone). */
object MatchOpsFormat {
    fun date(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale).format(date)

    fun time(minuteOfDay: Int, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("h:mm a", locale)
            .format(java.time.LocalTime.of((minuteOfDay / 60) % 24, minuteOfDay % 60))

    /** "Sat 24 Oct 2026, 6:00 PM" from a server ISO time, or null when unknown. */
    fun whenText(iso: String?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
        val z = ScheduleTime.parse(iso, zone) ?: return null
        return date(z.toLocalDate(), locale) + ", " + time(z.hour * 60 + z.minute, locale)
    }

    /** Names as one readable list. */
    fun joinNames(names: List<String>): String = names.joinToString(", ")
}
