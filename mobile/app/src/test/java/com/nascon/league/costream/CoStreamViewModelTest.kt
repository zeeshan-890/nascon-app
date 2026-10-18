package com.nascon.league.costream

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.matchlive.ChatMessage
import com.nascon.league.matchlive.LoadPhase
import com.nascon.league.matchlive.MatchLiveEvent
import com.nascon.league.matchlive.MatchLiveRepository
import com.nascon.league.matchlive.MatchSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoStreamViewModelTest {

    private class FakeCo : CoStreamRepository {
        var script: MutableList<NetworkResult<CoSession>> = mutableListOf()
        var calls = 0
        override suspend fun session(sessionId: String): NetworkResult<CoSession> {
            val i = calls++
            return script[minOf(i, script.size - 1)]
        }
    }

    private class FakeChat : MatchLiveRepository {
        var history: NetworkResult<List<ChatMessage>> = NetworkResult.Success(emptyList())
        var sendResult: (String) -> NetworkResult<ChatMessage> = { NetworkResult.Failure(ApiError.NoConnection) }
        val sent = mutableListOf<String>()
        val afters = mutableListOf<Long?>()
        override suspend fun snapshot(matchId: String): NetworkResult<MatchSnapshot> = NetworkResult.Failure(ApiError.NoConnection)
        override suspend fun chatHistory(roomId: String, afterSequence: Long?): NetworkResult<List<ChatMessage>> {
            afters += afterSequence
            return history
        }
        override suspend fun sendChat(roomId: String, text: String): NetworkResult<ChatMessage> {
            sent += text
            return sendResult(text)
        }
    }

    private val co = FakeCo()
    private val chat = FakeChat()
    private var guest = true
    private var lid = 0

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun parts(n: Int) = (0 until n).map { CoParticipant("u$it", "User$it", it == 0) }

    private fun sess(
        status: String = "active",
        host: String? = "u0",
        n: Int = 2,
        layout: String = "grid_2",
    ) = CoSession("s1", null, status, host, layout, parts(n), "http://p", null, "chat-s1", null)

    private fun ok(s: CoSession) = NetworkResult.Success(s)

    private fun msg(id: String, seq: Long, sender: String = "x", text: String = "t$seq") =
        ChatMessage("chat-s1", id, seq, sender, text)

    private val created = mutableListOf<CoStreamViewModel>()

    /**
     * runTest finishes by draining its scheduler, which is also the Main scheduler here. A view model that is still polling
     * would keep it busy forever, so every view model a test created is told to stop polling when the test body ends.
     */
    private fun vmTest(body: suspend TestScope.() -> Unit) = runTest {
        created.clear()
        try {
            body()
        } finally {
            created.forEach { it.setActive(false) }
        }
    }

    private fun TestScope.vm(): CoStreamViewModel {
        val v = CoStreamViewModel("s1", co, chat, { guest }, { "me" }, 2000, { "L${lid++}" })
        created += v
        runCurrent()
        return v
    }

    private fun TestScope.tick(v: Long = 2000) {
        advanceTimeBy(v)
        runCurrent()
    }

    @Test fun loads_session_and_tiles() = vmTest {
        co.script = mutableListOf(ok(sess(n = 2)))
        val v = vm()
        assertEquals(LoadPhase.CONTENT, v.uiState.value.phase)
        assertEquals(2, v.uiState.value.tiles.size)
        assertEquals(1, co.calls)
    }

    @Test fun not_found_phase() = vmTest {
        co.script = mutableListOf(NetworkResult.Failure(ApiError.Http(404, "NOT_FOUND", "")))
        val v = vm()
        assertEquals(LoadPhase.NOT_FOUND, v.uiState.value.phase)
        tick(10_000)
        assertEquals(1, co.calls)
    }

    @Test fun other_failure_is_error() = vmTest {
        co.script = mutableListOf(NetworkResult.Failure(ApiError.Timeout))
        val v = vm()
        assertEquals(LoadPhase.ERROR, v.uiState.value.phase)
        assertTrue(v.uiState.value.errorMessage != null)
    }

    @Test fun polls_every_interval() = vmTest {
        co.script = mutableListOf(ok(sess()))
        vm()
        tick()
        tick()
        assertEquals(3, co.calls)
    }

    @Test fun poll_updates_layout_and_count() = vmTest {
        co.script = mutableListOf(ok(sess(n = 2, layout = "grid_2")), ok(sess(n = 4, layout = "grid_4")))
        val v = vm()
        assertEquals(2, v.uiState.value.tiles.size)
        tick()
        assertEquals(4, v.uiState.value.tiles.size)
    }

    @Test fun tiles_capped_at_four() = vmTest {
        co.script = mutableListOf(ok(sess(n = 7, layout = "grid_4")))
        assertEquals(4, vm().uiState.value.tiles.size)
    }

    @Test fun host_marker_follows_server() = vmTest {
        co.script = mutableListOf(ok(sess(host = "u0")), ok(sess(host = "u1")))
        val v = vm()
        tick()
        assertEquals("Host changed to User1", v.uiState.value.banner)
    }

    @Test fun banner_cleared_after_next_quiet_poll() = vmTest {
        co.script = mutableListOf(ok(sess(host = "u0")), ok(sess(host = "u1")), ok(sess(host = "u1")))
        val v = vm()
        tick()
        assertTrue(v.uiState.value.banner != null)
        tick()
        assertNull(v.uiState.value.banner)
    }

    @Test fun pending_handoff_banner() = vmTest {
        co.script = mutableListOf(ok(sess(status = "handoff_pending", host = null)))
        assertEquals("Waiting for a new host", vm().uiState.value.banner)
    }

    @Test fun terminated_ends_and_stops_polling() = vmTest {
        co.script = mutableListOf(ok(sess()), ok(sess(status = "terminated")))
        val v = vm()
        tick()
        assertTrue(v.uiState.value.ended)
        val c = co.calls
        tick(10_000)
        assertEquals(c, co.calls)
    }

    @Test fun terminated_on_first_load_never_polls() = vmTest {
        co.script = mutableListOf(ok(sess(status = "terminated")))
        val v = vm()
        assertTrue(v.uiState.value.ended)
        tick(10_000)
        assertEquals(1, co.calls)
    }

    @Test fun inactive_stops_polling_and_active_resumes() = vmTest {
        co.script = mutableListOf(ok(sess()))
        val v = vm()
        v.setActive(false)
        tick(10_000)
        assertEquals(1, co.calls)
        v.setActive(true)
        runCurrent()
        assertEquals(2, co.calls)
    }

    @Test fun stale_session_kept_on_poll_failure_and_offline() = vmTest {
        co.script = mutableListOf(ok(sess()), NetworkResult.Failure(ApiError.NoConnection), ok(sess()))
        val v = vm()
        tick()
        assertTrue(v.uiState.value.session != null)
        assertTrue(v.uiState.value.offline)
        assertEquals(LoadPhase.CONTENT, v.uiState.value.phase)
        tick()
        assertFalse(v.uiState.value.offline)
    }

    @Test fun retry_with_stale_session_keeps_session() = vmTest {
        co.script = mutableListOf(ok(sess()), NetworkResult.Failure(ApiError.Timeout))
        val v = vm()
        v.retry()
        runCurrent()
        assertEquals(LoadPhase.ERROR, v.uiState.value.phase)
        assertTrue(v.uiState.value.session != null)
    }

    @Test fun network_offline_flag() = vmTest {
        co.script = mutableListOf(ok(sess()))
        val v = vm()
        v.onNetworkOffline(true)
        assertTrue(v.uiState.value.offline)
        v.onNetworkOffline(false)
        assertFalse(v.uiState.value.offline)
    }

    @Test fun chat_ordered_despite_shuffled_arrival_and_deduped() = vmTest {
        co.script = mutableListOf(ok(sess()))
        chat.history = NetworkResult.Success(listOf(msg("c", 3), msg("a", 1), msg("b", 2), msg("a", 1)))
        val v = vm()
        assertEquals(listOf(1L, 2L, 3L), v.uiState.value.chat.map { it.sequence })
    }

    @Test fun chat_polled_after_last_sequence() = vmTest {
        co.script = mutableListOf(ok(sess()))
        chat.history = NetworkResult.Success(listOf(msg("a", 5)))
        vm()
        tick()
        assertEquals(5L, chat.afters.last())
    }

    @Test fun guest_send_403_opens_sign_in_and_no_local_append() = vmTest {
        co.script = mutableListOf(ok(sess()))
        chat.sendResult = { NetworkResult.Failure(ApiError.Http(403, "GUEST_WRITE_FORBIDDEN", "")) }
        guest = true
        val v = vm()
        v.sendChat("hello")
        runCurrent()
        assertEquals(1, chat.sent.size)
        assertTrue(v.uiState.value.chat.isEmpty())
        assertEquals(MatchLiveEvent.OpenSignInSheet, v.events.first())
    }

    @Test fun member_send_pending_then_confirmed() = vmTest {
        co.script = mutableListOf(ok(sess()))
        guest = false
        chat.sendResult = { NetworkResult.Success(msg("srv1", 9, "me", it)) }
        val v = vm()
        v.sendChat("hi")
        assertTrue(v.uiState.value.chat.single().pending)
        assertTrue(v.uiState.value.chatSending)
        runCurrent()
        val m = v.uiState.value.chat.single()
        assertFalse(m.pending)
        assertEquals("srv1", m.messageId)
        assertFalse(v.uiState.value.chatSending)
    }

    @Test fun member_send_failure_removes_pending() = vmTest {
        co.script = mutableListOf(ok(sess()))
        guest = false
        val v = vm()
        v.sendChat("hi")
        runCurrent()
        assertTrue(v.uiState.value.chat.isEmpty())
    }

    @Test fun blank_chat_is_ignored() = vmTest {
        co.script = mutableListOf(ok(sess()))
        val v = vm()
        v.sendChat("   ")
        runCurrent()
        assertTrue(chat.sent.isEmpty())
    }

    @Test fun mute_during_composing_does_not_crash() = vmTest {
        guest = false
        chat.sendResult = { NetworkResult.Success(msg("srv1", 9, "me", it)) }
        co.script = mutableListOf(ok(sess()), ok(sess().copy(participants = parts(2).map { it.copy(muted = true) })))
        val v = vm()
        v.sendChat("typing")
        tick()
        assertTrue(v.uiState.value.tiles.all { it.muted })
        assertEquals(1, v.uiState.value.chat.size)
    }

    @Test fun chat_kept_across_handoff() = vmTest {
        co.script = mutableListOf(ok(sess(host = "u0")), ok(sess(host = "u1")))
        chat.history = NetworkResult.Success(listOf(msg("a", 1), msg("b", 2)))
        val v = vm()
        tick()
        assertEquals("Host changed to User1", v.uiState.value.banner)
        assertEquals(2, v.uiState.value.chat.size)
    }

    @Test fun unknown_layout_derives_tiles() = vmTest {
        co.script = mutableListOf(ok(sess(n = 3, layout = "mystery")))
        assertEquals(3, vm().uiState.value.tiles.size)
    }

    @Test fun guest_flag_reflected() = vmTest {
        co.script = mutableListOf(ok(sess()))
        guest = false
        assertFalse(vm().uiState.value.isGuest)
    }
}
