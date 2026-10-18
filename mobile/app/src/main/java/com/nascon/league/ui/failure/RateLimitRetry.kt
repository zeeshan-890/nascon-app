package com.nascon.league.ui.failure

import com.nascon.league.core.network.ApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One delayed refresh per key, taken from the error JSON's retry-after fields.
 * This never reads or clears the pending-action queue.
 */
class RateLimitRetry(private val scope: CoroutineScope) {
    private val jobs = HashMap<String, Job>()

    fun consider(key: String, error: ApiError?, refresh: () -> Unit) {
        val seconds = retryAfterSeconds(error)
        if (seconds == null) {
            jobs.remove(key)?.cancel()
            return
        }
        if (jobs[key]?.isActive == true) return
        val wait = seconds.coerceAtMost(MAX_WAIT_SECONDS)
        jobs[key] = scope.launch {
            delay(wait * 1000L)
            refresh()
        }
    }

    private companion object {
        const val MAX_WAIT_SECONDS = 300
    }
}
