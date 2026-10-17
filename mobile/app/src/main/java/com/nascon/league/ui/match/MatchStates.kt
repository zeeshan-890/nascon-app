package com.nascon.league.ui.match

import java.time.Duration
import java.time.Instant

/** How long until kick-off, in the coarsest sensible unit. */
sealed interface Countdown {
    data object Soon : Countdown
    data class Days(val n: Int) : Countdown
    data class Hours(val n: Int) : Countdown
    data class Minutes(val n: Int) : Countdown
}

/** Null when [start] is unknown. Past or imminent (under a minute) start is [Countdown.Soon]. */
fun countdownTo(now: Instant, start: Instant?): Countdown? {
    if (start == null) return null
    val mins = Duration.between(now, start).toMinutes()
    return when {
        mins < 1 -> Countdown.Soon
        mins < 60 -> Countdown.Minutes(mins.toInt())
        mins < 24 * 60 -> Countdown.Hours((mins / 60).toInt())
        else -> Countdown.Days((mins / (24 * 60)).toInt())
    }
}
