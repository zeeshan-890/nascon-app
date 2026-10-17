package com.nascon.league.data.repo

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant

data class Cached<T>(val data: T, val updatedAt: Instant)

sealed interface Resource<out T> {
    data class Loading<T>(val cached: T?) : Resource<T>
    data class Success<T>(val data: T, val lastUpdatedAt: Instant?, val isStale: Boolean) : Resource<T>
    data class Error<T>(val cached: T?, val error: ApiError) : Resource<T>
}

const val STALE_AFTER_MS = 5 * 60 * 1000L

sealed interface RefreshState {
    data object Running : RefreshState
    data class Failed(val error: ApiError) : RefreshState
}

/**
 * Joins concurrent refreshes of the same key into a single call and tracks per-key state so
 * observeResource() can report Loading / Error. Work runs in [scope] so a cancelled caller
 * (screen left) does not abort the shared refresh.
 */
class RefreshDeduper(private val scope: CoroutineScope) {
    private val lock = Any()
    private val inFlight = HashMap<String, Deferred<NetworkResult<Unit>>>()
    private val states = MutableStateFlow<Map<String, RefreshState>>(emptyMap())

    fun stateOf(key: String): Flow<RefreshState?> = states.map { it[key] }.distinctUntilChanged()

    private fun setState(key: String, state: RefreshState?) {
        states.value = states.value.toMutableMap().also { if (state == null) it.remove(key) else it[key] = state }
    }

    suspend fun run(
        key: String,
        isOnline: () -> Boolean,
        block: suspend () -> NetworkResult<Unit>,
    ): NetworkResult<Unit> {
        if (!isOnline()) {
            val failure = NetworkResult.Failure(ApiError.NoConnection)
            // Only record the failure if nothing is running for this key.
            synchronized(lock) { if (!inFlight.containsKey(key)) setState(key, RefreshState.Failed(ApiError.NoConnection)) }
            return failure
        }
        val deferred = synchronized(lock) {
            inFlight[key] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    val result = block()
                    synchronized(lock) {
                        setState(key, if (result is NetworkResult.Failure) RefreshState.Failed(result.error) else null)
                    }
                    result
                } finally {
                    synchronized(lock) { inFlight.remove(key) }
                }
            }.also {
                inFlight[key] = it
                setState(key, RefreshState.Running)
            }
        }
        deferred.start()
        return deferred.await()
    }
}

internal fun <T> resourceFlow(
    key: String,
    cached: Flow<Cached<T>?>,
    deduper: RefreshDeduper,
    now: () -> Long,
): Flow<Resource<T>> = combine<Cached<T>?, RefreshState?, Resource<T>>(cached, deduper.stateOf(key)) { c, state ->
    when (state) {
        RefreshState.Running -> Resource.Loading(c?.data)
        is RefreshState.Failed -> Resource.Error(c?.data, state.error)
        null -> if (c == null) {
            Resource.Loading(null)
        } else {
            Resource.Success(c.data, c.updatedAt, now() - c.updatedAt.toEpochMilli() > STALE_AFTER_MS)
        }
    }
}.distinctUntilChanged()

internal fun Long.toInstant(): Instant = Instant.ofEpochMilli(this)
