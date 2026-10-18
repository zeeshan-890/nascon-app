package com.nascon.league.data.realtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ResilientMatchStreamTest {

    // ---- fakes -------------------------------------------------------------------------

    enum class End { COMPLETE, FAIL, HANG }

    class Step(val events: List<TransportEvent>, val end: End)

    private fun ok(vararg msgs: LiveMessage, end: End = End.HANG) =
        Step(listOf(TransportEvent.Opened) + msgs.map { TransportEvent.Message(it) }, end)

    private fun fail(vararg msgs: LiveMessage) =
        Step(msgs.map { TransportEvent.Message(it) }, End.FAIL)

    class FakeTransport(
        override val kind: TransportKind,
        private val steps: List<Step>,
        private val log: MutableList<TransportKind>,
    ) : MatchTransport {
        var attempts = 0
        var closed = 0

        override fun connect(matchId: String): Flow<TransportEvent> = flow {
            val step = steps.getOrElse(attempts) { steps.last() }
            attempts++
            log.add(kind)
            try {
                step.events.forEach { emit(it) }
                when (step.end) {
                    End.COMPLETE -> Unit
                    End.FAIL -> throw IOException("drop")
                    End.HANG -> awaitCancellation()
                }
            } finally {
                closed++
            }
        }
    }

    private fun msg(
        id: String? = null,
        seq: Long? = null,
        ts: Long? = null,
        type: String = "t",
    ) = LiveMessage(id, seq, ts, type, "m1", null)

    private class Harness(
        val log: MutableList<TransportKind> = mutableListOf(),
        val delays: MutableList<Long> = mutableListOf(),
        val statesAtDelay: MutableList<LiveState> = mutableListOf(),
        val resyncStates: MutableList<LiveState> = mutableListOf(),
        val emitted: MutableList<LiveMessage> = mutableListOf(),
    )

    private fun TestScope.build(
        h: Harness,
        transports: (MutableList<TransportKind>) -> List<FakeTransport>,
        offlineAfter: Int = 5,
        jitter: (Long) -> Long = { it },
        resync: suspend (LiveState) -> Unit = {},
    ): Pair<ResilientMatchStream, List<FakeTransport>> {
        val ts = transports(h.log)
        var holder: ResilientMatchStream? = null
        val s = ResilientMatchStream(
            transports = ts,
            resync = {
                h.resyncStates.add(holder!!.state.value)
                resync(holder!!.state.value)
            },
            offlineAfterFailures = offlineAfter,
            jitter = jitter,
            onDelay = {
                h.delays.add(it)
                h.statesAtDelay.add(holder!!.state.value)
                delay(it)
            },
        )
        holder = s
        return s to ts
    }

    private fun TestScope.collect(s: ResilientMatchStream, h: Harness): Job =
        backgroundScope.launch { s.stream("m1").collect { h.emitted.add(it) } }

    private fun single(
        steps: List<Step>,
        kind: TransportKind = TransportKind.WEBSOCKET,
    ): (MutableList<TransportKind>) -> List<FakeTransport> =
        { log -> listOf(FakeTransport(kind, steps, log)) }


    /**
     * The stream runs in backgroundScope, and advanceUntilIdle() ignores background work, so drive virtual time explicitly:
     * run what is due, move past every backoff step (250 ms doubling to 2 s), then run again.
     */
    private fun TestScope.settle(ms: Long = 30_000) {
        runCurrent()
        advanceTimeBy(ms)
        runCurrent()
    }

    // ---- tests -------------------------------------------------------------------------

    @Test
    fun normalFlow_connectingThenLive_emitsThreeInOrder() = runTest {
        val h = Harness()
        val (s, _) = build(h, single(listOf(ok(msg("a", 1), msg("b", 2), msg("c", 3)))))
        assertEquals(LiveState.CONNECTING, s.state.value)
        collect(s, h)
        runCurrent()
        assertEquals(LiveState.LIVE, s.state.value)
        assertEquals(listOf(1L, 2L, 3L), h.emitted.map { it.sequence })
        assertTrue(h.resyncStates.isEmpty())
    }

    @Test
    fun dropAndRecover_resyncOnceBeforeLive_thenContinues() = runTest {
        val h = Harness()
        val (s, _) = build(
            h,
            single(listOf(ok(msg("a", 1), end = End.FAIL), ok(msg("b", 2)))),
        )
        collect(s, h)
        runCurrent()
        assertEquals(LiveState.RECONNECTING, s.state.value)
        assertEquals(0, h.resyncStates.size)
        settle()
        assertEquals(listOf(250L), h.delays)
        assertEquals(1, h.resyncStates.size)
        assertEquals(LiveState.RECONNECTING, h.resyncStates[0])
        assertEquals(LiveState.LIVE, s.state.value)
        assertEquals(listOf(1L, 2L), h.emitted.map { it.sequence })
    }

    @Test
    fun normalCompletion_alsoTriggersReconnect() = runTest {
        val h = Harness()
        val (s, ts) = build(h, single(listOf(ok(msg("a", 1), end = End.COMPLETE), ok())))
        collect(s, h)
        settle()
        assertEquals(2, ts[0].attempts)
        assertEquals(1, h.resyncStates.size)
        assertEquals(LiveState.LIVE, s.state.value)
    }

    @Test
    fun duplicateId_isDropped() = runTest {
        val h = Harness()
        val (s, _) = build(h, single(listOf(ok(msg("a", 1), msg("a", 2), msg("b", 3)))))
        collect(s, h)
        runCurrent()
        assertEquals(listOf("a", "b"), h.emitted.map { it.id })
    }

    @Test
    fun olderSequence_isDroppedAsStale() = runTest {
        val h = Harness()
        val (s, _) = build(h, single(listOf(ok(msg("a", 1), msg("b", 2), msg("c", 1), msg("d", 2), msg("e", 3)))))
        collect(s, h)
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), h.emitted.map { it.sequence })
    }

    @Test
    fun gap_triggersExactlyOneResync_andStillEmits() = runTest {
        val h = Harness()
        val (s, _) = build(h, single(listOf(ok(msg("a", 1), msg("b", 2), msg("c", 5), msg("d", 6)))))
        collect(s, h)
        runCurrent()
        assertEquals(listOf(1L, 2L, 5L, 6L), h.emitted.map { it.sequence })
        assertEquals(1, h.resyncStates.size)
    }

    @Test
    fun gapBurst_whileResyncRunning_callsResyncOnce() = runTest {
        val h = Harness()
        val (s, _) = build(
            h,
            single(listOf(ok(msg("a", 1), msg("b", 5), msg("c", 9)))),
            resync = { delay(100) },
        )
        collect(s, h)
        runCurrent()
        assertEquals(3, h.emitted.size)
        assertEquals(1, h.resyncStates.size)
    }

    @Test
    fun firstMessageWithAnySequence_isEmittedWithoutGapResync() = runTest {
        val h = Harness()
        val (s, _) = build(h, single(listOf(ok(msg("a", 42), msg("b", 43)))))
        collect(s, h)
        runCurrent()
        assertEquals(2, h.emitted.size)
        assertTrue(h.resyncStates.isEmpty())
    }

    @Test
    fun staleTimestampWithoutSequence_isDropped() = runTest {
        val h = Harness()
        val (s, _) = build(
            h,
            single(listOf(ok(msg("a", ts = 1000), msg("b", ts = 900), msg("c", ts = 1000), msg("d", ts = 1100)))),
        )
        collect(s, h)
        runCurrent()
        assertEquals(listOf("a", "c", "d"), h.emitted.map { it.id })
    }

    @Test
    fun fallbackOrder_wsThenSseThenPolling() = runTest {
        val h = Harness()
        val (s, _) = build(h, { log ->
            listOf(
                FakeTransport(TransportKind.WEBSOCKET, listOf(fail()), log),
                FakeTransport(TransportKind.SSE, listOf(fail()), log),
                FakeTransport(TransportKind.POLLING, listOf(ok(msg("a", 1))), log),
            )
        })
        collect(s, h)
        settle()
        assertEquals(
            listOf(TransportKind.WEBSOCKET, TransportKind.SSE, TransportKind.POLLING),
            h.log,
        )
        assertEquals(LiveState.LIVE, s.state.value)
        assertEquals(1, h.emitted.size)
    }

    @Test
    fun wrapAround_afterAllTransportsFail() = runTest {
        val h = Harness()
        val (s, _) = build(h, { log ->
            listOf(
                FakeTransport(TransportKind.WEBSOCKET, listOf(fail()), log),
                FakeTransport(TransportKind.SSE, listOf(fail()), log),
                FakeTransport(TransportKind.POLLING, listOf(fail()), log),
            )
        })
        collect(s, h)
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(
            listOf(
                TransportKind.WEBSOCKET, TransportKind.SSE, TransportKind.POLLING, TransportKind.WEBSOCKET,
            ),
            h.log.take(4),
        )
    }

    @Test
    fun offlineAfterConsecutiveFailures_thenLiveAfterSuccess() = runTest {
        val h = Harness()
        val steps = List(5) { fail() } + ok(msg("a", 1))
        val (s, _) = build(h, single(steps, TransportKind.POLLING))
        collect(s, h)
        settle()
        assertEquals(
            listOf(
                LiveState.CONNECTING, LiveState.CONNECTING, LiveState.CONNECTING,
                LiveState.CONNECTING, LiveState.OFFLINE,
            ),
            h.statesAtDelay,
        )
        assertEquals(1, h.resyncStates.size)
        assertEquals(LiveState.OFFLINE, h.resyncStates[0])
        assertEquals(LiveState.LIVE, s.state.value)
    }

    @Test
    fun backoffSequence_doublesAndCaps() = runTest {
        val h = Harness()
        val (s, _) = build(h, single(listOf(fail())))
        collect(s, h)
        advanceTimeBy(8000)
        runCurrent()
        assertEquals(listOf(250L, 500L, 1000L, 2000L, 2000L), h.delays.take(5))
    }

    @Test
    fun backoff_neverBelowMinimum_evenWithNegativeJitter() = runTest {
        val h = Harness()
        val (s, _) = build(h, single(listOf(fail())), jitter = { it - 200 })
        collect(s, h)
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(h.delays.isNotEmpty())
        assertTrue(h.delays.all { it >= 250L })
    }

    @Test
    fun noReconnectStorm_allTransportsFailInstantly() = runTest {
        val h = Harness()
        val (s, ts) = build(h, { log ->
            listOf(
                FakeTransport(TransportKind.WEBSOCKET, listOf(fail()), log),
                FakeTransport(TransportKind.SSE, listOf(fail()), log),
                FakeTransport(TransportKind.POLLING, listOf(fail()), log),
            )
        })
        collect(s, h)
        advanceTimeBy(10_000)
        runCurrent()
        val total = ts.sumOf { it.attempts }
        assertTrue("attempts=$total", total <= 45)
        assertTrue(total >= 5)
        assertEquals(LiveState.OFFLINE, s.state.value)
    }

    @Test
    fun backoff_resetsAfterMessageDelivered() = runTest {
        val h = Harness()
        val steps = listOf(fail(), fail(), ok(msg("a", 1), end = End.FAIL), fail())
        val (s, _) = build(h, single(steps))
        collect(s, h)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(250L, 500L, 250L, 500L), h.delays.take(4))
    }

    @Test
    fun cancel_closesActiveTransport() = runTest {
        val h = Harness()
        val (s, ts) = build(h, single(listOf(ok(msg("a", 1)))))
        val job = collect(s, h)
        runCurrent()
        assertEquals(0, ts[0].closed)
        job.cancel()
        runCurrent()
        assertEquals(1, ts[0].closed)
        advanceTimeBy(10_000)
        assertEquals(1, ts[0].attempts)
    }

    @Test
    fun cancelDuringBackoff_stopsLoop() = runTest {
        val h = Harness()
        val (s, ts) = build(h, single(listOf(fail())))
        val job = collect(s, h)
        runCurrent()
        job.cancel()
        advanceTimeBy(10_000)
        assertEquals(1, ts[0].attempts)
    }

    @Test
    fun multipleDistinctIds_interleaved() = runTest {
        val h = Harness()
        val (s, _) = build(
            h,
            single(listOf(ok(msg("a", 1), msg("b", 2), msg("a", 3), msg("c", 3), msg("b", 4), msg("d", 4)))),
        )
        collect(s, h)
        runCurrent()
        assertEquals(listOf("a", "b", "c", "d"), h.emitted.map { it.id })
    }

    @Test
    fun nullIds_areNotDeduped() = runTest {
        val h = Harness()
        val (s, _) = build(h, single(listOf(ok(msg(null, ts = 1), msg(null, ts = 2), msg(null, ts = 3)))))
        collect(s, h)
        runCurrent()
        assertEquals(3, h.emitted.size)
    }

    @Test
    fun resyncFailure_doesNotCrashLoop() = runTest {
        val h = Harness()
        val (s, _) = build(
            h,
            single(listOf(ok(msg("a", 1), end = End.FAIL), ok(msg("b", 2)))),
            resync = { throw IOException("snapshot failed") },
        )
        collect(s, h)
        settle()
        assertEquals(LiveState.LIVE, s.state.value)
        assertEquals(listOf(1L, 2L), h.emitted.map { it.sequence })
    }

    @Test
    fun gapResyncFailure_doesNotCrashLoop() = runTest {
        val h = Harness()
        val (s, _) = build(
            h,
            single(listOf(ok(msg("a", 1), msg("b", 5), msg("c", 6)))),
            resync = { throw IOException("boom") },
        )
        collect(s, h)
        runCurrent()
        assertEquals(3, h.emitted.size)
        assertEquals(LiveState.LIVE, s.state.value)
    }

    @Test
    fun lastSequenceKept_acrossReconnect() = runTest {
        val h = Harness()
        val steps = listOf(
            ok(msg("a", 1), msg("b", 2), end = End.FAIL),
            ok(msg("c", 2), msg("d", 3)),
        )
        val (s, _) = build(h, single(steps))
        collect(s, h)
        settle()
        assertEquals(listOf(1L, 2L, 3L), h.emitted.map { it.sequence })
        assertEquals(1, h.resyncStates.size)
    }

    @Test
    fun state_neverGoesFromLiveBackToConnecting() = runTest {
        val h = Harness()
        val steps = listOf(
            ok(msg("a", 1), end = End.FAIL), fail(), fail(), ok(msg("b", 2), end = End.FAIL),
            fail(), ok(msg("c", 3)),
        )
        val (s, _) = build(h, single(steps))
        val seen = mutableListOf<LiveState>()
        backgroundScope.launch { s.state.collect { seen.add(it) } }
        collect(s, h)
        settle()
        assertTrue(seen.contains(LiveState.LIVE))
        seen.zipWithNext().forEach { (a, b) ->
            assertFalse("LIVE -> CONNECTING", a == LiveState.LIVE && b == LiveState.CONNECTING)
        }
        assertFalse(h.statesAtDelay.contains(LiveState.CONNECTING))
        assertEquals(LiveState.LIVE, s.state.value)
    }
}
