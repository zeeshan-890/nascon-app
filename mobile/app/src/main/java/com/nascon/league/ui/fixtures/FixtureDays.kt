package com.nascon.league.ui.fixtures

import com.nascon.league.domain.model.Fixture
import java.time.LocalDate
import java.time.ZoneId

/** A run of fixtures on one local calendar day; [day] is null for fixtures without a valid time. */
data class DayRun(val day: LocalDate?, val items: List<Fixture>)

/** Splits an already-sorted list into consecutive same-day runs (order preserved). Never throws. */
fun splitByDay(items: List<Fixture>, zone: ZoneId = ZoneId.systemDefault()): List<DayRun> {
    val out = ArrayList<DayRun>()
    for (f in items) {
        val day = FixtureTime.parse(f.scheduledAt)?.atZone(zone)?.toLocalDate()
        val last = out.lastOrNull()
        if (last != null && last.day == day) {
            out[out.lastIndex] = last.copy(items = last.items + f)
        } else {
            out += DayRun(day, listOf(f))
        }
    }
    return out
}
