package com.nascon.league.ui.scoring

data class ScoreButton(val eventKey: String, val label: String, val points: Int?)

enum class ControlsMode { START, SCORE, READ_ONLY }

data class OptimisticScore(val home: Int, val away: Int, val pendingCount: Int, val hasPending: Boolean)

sealed interface ReasonCheck {
    data class Ok(val reason: String) : ReasonCheck
    data object Empty : ReasonCheck
    data object TooLong : ReasonCheck
}

/** Blocks the same key within [windowMs]; different keys are independent. */
class TapDebouncer(
    private val windowMs: Long = 400,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val last = HashMap<String, Long>()

    @Synchronized
    fun allow(key: String): Boolean {
        val t = now()
        val prev = last[key]
        if (prev != null && t - prev < windowMs) return false
        last[key] = t
        return true
    }
}
