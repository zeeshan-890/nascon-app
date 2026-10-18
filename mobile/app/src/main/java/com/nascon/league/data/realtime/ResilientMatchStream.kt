package com.nascon.league.data.realtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Random +-20% jitter, clamped to >= 1. */
val defaultJitter: (Long) -> Long = { v ->
    val factor = 1.0 + (Random.nextDouble() * 0.4 - 0.2)
    (v * factor).toLong().coerceAtLeast(1L)
}

private const val DEDUPE_CAPACITY = 512

class ResilientMatchStream(
    private val transports: List<MatchTransport>,
    private val resync: suspend (matchId: String) -> Unit,
    private val pollIntervalMs: Long = 1000,
    private val offlineAfterFailures: Int = 5,
    private val minBackoffMs: Long = 250,
    private val maxBackoffMs: Long = 2000,
    private val jitter: (Long) -> Long = defaultJitter,
    private val onDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private val _state = MutableStateFlow(LiveState.CONNECTING)
    val state: StateFlow<LiveState> = _state

    init {
        require(transports.isNotEmpty()) { "at least one transport is required" }
    }

    fun stream(matchId: String): Flow<LiveMessage> = channelFlow {
        val seen = object : LinkedHashMap<String, Boolean>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) =
                size > DEDUPE_CAPACITY
        }
        var lastSequence: Long? = null
        var newestTs: Long? = null
        var gapJob: Job? = null

        var idx = 0
        var failures = 0
        var backoffStep = 0
        var everLive = false
        var needResync = false

        suspend fun safeResync() {
            try {
                resync(matchId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // a failed snapshot must not kill the live loop
            }
        }

        suspend fun handle(m: LiveMessage) {
            val id = m.id
            if (id != null && seen.containsKey(id)) return
            val seq = m.sequence
            val ts = m.timestampMs
            var gap = false
            if (seq != null) {
                val last = lastSequence
                if (last != null) {
                    if (seq <= last) return
                    if (seq > last + 1) gap = true
                }
            } else if (ts != null) {
                val newest = newestTs
                if (newest != null && ts < newest) return
            }
            if (id != null) seen[id] = true
            if (seq != null) lastSequence = seq
            if (ts != null && (newestTs == null || ts > newestTs!!)) newestTs = ts
            send(m)
            if (gap && gapJob?.isActive != true) {
                gapJob = launch { safeResync() }
            }
        }

        while (isActive) {
            val transport = transports[idx]
            var opened = false
            var delivered = false
            try {
                transport.connect(matchId).collect { ev ->
                    when (ev) {
                        TransportEvent.Opened -> {
                            if (!opened) {
                                opened = true
                                failures = 0
                                if (needResync) safeResync()
                                everLive = true
                                _state.value = LiveState.LIVE
                            }
                        }
                        is TransportEvent.Message -> if (opened) {
                            if (!delivered) {
                                delivered = true
                                backoffStep = 0
                            }
                            handle(ev.message)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                currentCoroutineContext().ensureActive()
            }
            currentCoroutineContext().ensureActive()

            needResync = true
            if (!opened) {
                failures++
                idx = (idx + 1) % transports.size
            }
            _state.value = when {
                failures >= offlineAfterFailures -> LiveState.OFFLINE
                everLive -> LiveState.RECONNECTING
                else -> LiveState.CONNECTING
            }
            val base = minOf(maxBackoffMs, minBackoffMs shl backoffStep.coerceAtMost(20))
            if (backoffStep < 20) backoffStep++
            onDelay(jitter(base).coerceAtLeast(minBackoffMs))
        }
    }
}
