package com.nascon.league.data.realtime

object Backoff {
    private val steps = longArrayOf(1000, 2000, 4000, 8000, 15000)

    /** attempt is 0-based. jitter in [0,1] scales up to 20% of the base delay. */
    fun delayMs(attempt: Int, jitter: Double = 0.0): Long {
        val base = steps[attempt.coerceIn(0, steps.size - 1)]
        val j = jitter.coerceIn(0.0, 1.0)
        return base + (base * 0.2 * j).toLong()
    }
}
