package com.nascon.league.data.realtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Drops items arriving within [windowMs] of the last emitted one. */
fun <T> Flow<T>.throttleFirst(windowMs: Long, now: () -> Long = System::currentTimeMillis): Flow<T> {
    val source = this
    return flow {
        var last = Long.MIN_VALUE
        source.collect { v ->
            val t = now()
            if (last == Long.MIN_VALUE || t - last >= windowMs) {
                last = t
                emit(v)
            }
        }
    }
}
