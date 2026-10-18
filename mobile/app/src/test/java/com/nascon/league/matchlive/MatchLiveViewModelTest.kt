package com.nascon.league.matchlive

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.data.realtime.LiveMessage
import com.nascon.league.data.realtime.LiveState
import com.nascon.league.deeplink.MatchScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MatchLiveViewModelTest {

    private class FakeRepo : MatchLiveRepository {
        var snapshotCalls = 0
        var snapshotResult: () -> NetworkResult<MatchSnapshot> = { NetworkResult.Success(snap("in_progress")) }
        var history: NetworkResult<List<ChatMessage>> = NetworkResult.Success(emptyList())
        var sendResult: (String) -> NetworkResult<ChatMessage> = { NetworkResult.Failure(ApiError.NoConnection) }
        val sent = mutableListOf<String>()

        override suspend fun snapshot(matchId: String): NetworkResult<MatchSnapshot> {
            snapshotCalls++
            return snapshotResult()
        }

        override suspend fun chatHistory(roomId: String, afterSequence: Long?) = history

        override suspend fun sendChat(roomId: String, text: String): NetworkResult<ChatMessage> {
            sent += text
            return sendResult(text)
        }
    }

    private class FakeSource : MatchEventSource {
        val flow = MutableSharedFlow<LiveMessage>(extraBufferCapacity = 64)
        val st = MutableStateFlow(LiveState.LIVE)
        override val state: StateFlow<LiveState> get() = st
        override fun events(matchId: String): Flow<LiveMessage> = flow
    }

    private class FakeCart : GuestCart {
        val count0 = MutableStateFlow(0)
        val adds = mutableListOf<String>()
        override val lines: Flow<List<CartLine>> = MutableStateFlow(emptyList())
        override val count: Flow<Int> = count0
        override suspend fun add(variantId: String, name: String, priceMinor: Long, quantity: Int) {
            adds += variantId
            count0.value += quantity
        }
        override suspend fun setQuantity(variantId: String, quantity: Int) = Unit
        override suspend fun remove(variantId: String) = Unit
        override suspend fun clear() = Unit
    }

    private val repo = FakeRepo()
    private val source = FakeSource()
    private val cart = FakeCart()
    private var guest = true
    private var now = 0L
    private var lid = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(startSeconds: Int? = null) = MatchLiveViewModel(
        "m1", startSeconds, repo, source, cart, { guest }, { "me" }, { now }, { "local-${++lid}" },
    )

    private fun TestScope.loaded(): MatchLiveViewModel {
        val v = vm()
        advanceUntilIdle()
        return v
    }

    private fun msg(type: String, data: JsonObject?) = LiveMessage(null, null, null, type, "m1", data)

    private fun chatMsg(id: String, seq: Long, text: String, room: String = "chat-m1") = msg(
        "chat.message",
        buildJsonObject {
            put("message_id", id)
            put("room_id", room)
            put("sequence", seq)
            put("sender", "bob")
            put("text", text)
            put("flagged", false)
        },
    )

    private fun cm(id: String, seq: Long, text: String = "t", sender: String = "bob") =
        ChatMessage("chat-m1", id, seq, sender, text)

    private fun http(status: Int, code: String) = NetworkResult.Failure(ApiError.Http(status, code, "raw {json}"))

    // ---- loading ----

    @Test
    fun initial_state_is_loading_with_room_and_start() = runTest {
        val v = vm(startSeconds = 90)
        val s = v.uiState.value
        assertEquals(LoadPhase.LOADING, s.phase)
        assertEquals("chat-m1", s.chatRoomId)
        assertEquals(90, s.startSeconds)
        assertTrue(s.isGuest)
    }

    @Test
    fun loads_live_content() = runTest {
        val v = loaded()
        assertEquals(LoadPhase.CONTENT, v.uiState.value.phase)
        assertEquals(MatchScreen.LIVE, v.uiState.value.screen)
    }

    @Test
    fun upcoming_maps_to_preview() = runTest {
        repo.snapshotResult = { NetworkResult.Success(snap("upcoming")) }
        assertEquals(MatchScreen.PREVIEW, loaded().uiState.value.screen)
    }

    @Test
    fun completed_maps_to_vod() = runTest {
        repo.snapshotResult = { NetworkResult.Success(snap("completed")) }
        assertEquals(MatchScreen.VOD, loaded().uiState.value.screen)
    }

    @Test
    fun cancelled_maps_to_notice() = runTest {
        repo.snapshotResult = { NetworkResult.Success(snap("cancelled")) }
        assertEquals(MatchScreen.NOTICE, loaded().uiState.value.screen)
    }

    @Test
    fun unknown_status_maps_to_live_shell() = runTest {
        repo.snapshotResult = { NetworkResult.Success(snap("weird")) }
        assertEquals(MatchScreen.LIVE_SHELL, loaded().uiState.value.screen)
    }

    @Test
    fun not_found_sets_phase() = runTest {
        repo.snapshotResult = { http(404, "MATCH_NOT_FOUND") }
        assertEquals(LoadPhase.NOT_FOUND, loaded().uiState.value.phase)
    }

    @Test
    fun failure_without_snapshot_gives_plain_error() = runTest {
        repo.snapshotResult = { http(500, "INTERNAL") }
        val s = loaded().uiState.value
        assertEquals(LoadPhase.ERROR, s.phase)
        assertNotNull(s.errorMessage)
        assertFalse(s.errorMessage!!.contains("{"))
    }

    @Test
    fun retry_failure_keeps_stale_snapshot() = runTest {
        val v = loaded()
        repo.snapshotResult = { http(500, "INTERNAL") }
        v.retry()
        advanceUntilIdle()
        val s = v.uiState.value
        assertEquals(LoadPhase.ERROR, s.phase)
        assertNotNull(s.errorMessage)
        assertEquals("in_progress", s.snapshot?.status)
        assertEquals(MatchScreen.LIVE, s.screen)
    }

    // ---- events ----

    @Test
    fun live_to_completed_keeps_score_and_shows_vod() = runTest {
        repo.snapshotResult = { NetworkResult.Success(snap("in_progress", 2, 1)) }
        val v = loaded()
        source.flow.emit(msg("match.status_changed", buildJsonObject { put("status", "completed") }))
        advanceUntilIdle()
        val s = v.uiState.value
        assertEquals(MatchScreen.VOD, s.screen)
        assertEquals(2, s.snapshot?.homeScore)
        assertEquals(1, s.snapshot?.awayScore)
        assertEquals(LoadPhase.CONTENT, s.phase)
    }

    @Test
    fun postponed_event_shows_notice_without_error() = runTest {
        val v = loaded()
        source.flow.emit(msg("match.status_changed", buildJsonObject { put("status", "postponed") }))
        advanceUntilIdle()
        assertEquals(MatchScreen.NOTICE, v.uiState.value.screen)
        assertEquals(LoadPhase.CONTENT, v.uiState.value.phase)
    }

    @Test
    fun score_snapshot_updates_immediately() = runTest {
        val v = loaded()
        source.flow.emit(scoreSnapshot(3, 2, 5))
        runCurrent()
        assertEquals(3, v.uiState.value.snapshot?.homeScore)
        assertEquals(2, v.uiState.value.snapshot?.awayScore)
        assertEquals(1, repo.snapshotCalls)
    }

    @Test
    fun out_of_order_snapshot_is_ignored() = runTest {
        val v = loaded()
        source.flow.emit(scoreSnapshot(3, 2, 5))
        source.flow.emit(scoreSnapshot(1, 0, 2))
        runCurrent()
        assertEquals(3, v.uiState.value.snapshot?.homeScore)
    }

    @Test
    fun rapid_event_appended_triggers_one_refetch() = runTest {
        loaded()
        repeat(3) { source.flow.emit(msg("match.event_appended", buildJsonObject { put("version", it + 1) })) }
        advanceUntilIdle()
        assertEquals(2, repo.snapshotCalls)
    }

    private fun scoreSnapshot(h: Int, a: Int, version: Long) = msg(
        "match.snapshot",
        buildJsonObject {
            put("version", version)
            put("home", buildJsonObject { put("score", h) })
            put("away", buildJsonObject { put("score", a) })
        },
    )

    // ---- chat ----

    @Test
    fun chat_history_is_loaded() = runTest {
        repo.history = NetworkResult.Success(listOf(cm("a", 1), cm("b", 2)))
        assertEquals(listOf("a", "b"), loaded().uiState.value.chat.map { it.messageId })
    }

    @Test
    fun chat_history_failure_is_ignored() = runTest {
        repo.history = NetworkResult.Failure(ApiError.NoConnection)
        val s = loaded().uiState.value
        assertTrue(s.chat.isEmpty())
        assertEquals(LoadPhase.CONTENT, s.phase)
    }

    @Test
    fun chat_message_from_source_is_sorted_by_sequence() = runTest {
        repo.history = NetworkResult.Success(listOf(cm("a", 1), cm("c", 3)))
        val v = loaded()
        source.flow.emit(chatMsg("b", 2, "mid"))
        runCurrent()
        assertEquals(listOf("a", "b", "c"), v.uiState.value.chat.map { it.messageId })
    }

    @Test
    fun chat_for_other_room_is_ignored() = runTest {
        val v = loaded()
        source.flow.emit(chatMsg("x", 1, "hi", room = "chat-other"))
        runCurrent()
        assertTrue(v.uiState.value.chat.isEmpty())
    }

    @Test
    fun masked_text_passes_through() = runTest {
        val v = loaded()
        source.flow.emit(chatMsg("x", 1, "***"))
        runCurrent()
        assertEquals("***", v.uiState.value.chat.single().text)
    }

    @Test
    fun guest_send_403_opens_sign_in_and_appends_nothing() = runTest {
        repo.sendResult = { http(403, "GUEST_WRITE_FORBIDDEN") }
        val v = loaded()
        v.sendChat("hello")
        advanceUntilIdle()
        assertEquals(listOf("hello"), repo.sent)
        assertEquals(MatchLiveEvent.OpenSignInSheet, v.events.first())
        assertTrue(v.uiState.value.chat.isEmpty())
    }

    @Test
    fun guest_send_accepted_by_server_merges_server_message() = runTest {
        repo.sendResult = { NetworkResult.Success(cm("s1", 7, it, "guest")) }
        val v = loaded()
        v.sendChat("hello")
        advanceUntilIdle()
        val chat = v.uiState.value.chat
        assertEquals(1, chat.size)
        assertFalse(chat.single().pending)
    }

    @Test
    fun member_send_shows_pending_then_confirms() = runTest {
        guest = false
        repo.sendResult = { NetworkResult.Success(cm("s1", 7, it, "me")) }
        val v = loaded()
        v.sendChat("  hello  ")
        assertTrue(v.uiState.value.chatSending)
        assertTrue(v.uiState.value.chat.single().pending)
        assertEquals("hello", v.uiState.value.chat.single().text)
        advanceUntilIdle()
        val chat = v.uiState.value.chat
        assertEquals(1, chat.size)
        assertFalse(chat.single().pending)
        assertEquals("s1", chat.single().messageId)
        assertFalse(v.uiState.value.chatSending)
    }

    @Test
    fun member_send_failure_removes_pending_and_shows_message() = runTest {
        guest = false
        repo.sendResult = { NetworkResult.Failure(ApiError.NoConnection) }
        val v = loaded()
        v.sendChat("hello")
        advanceUntilIdle()
        assertTrue(v.uiState.value.chat.isEmpty())
        val e = v.events.first()
        assertTrue(e is MatchLiveEvent.ShowMessage)
        assertFalse((e as MatchLiveEvent.ShowMessage).message.contains("{"))
    }

    @Test
    fun blank_send_is_ignored() = runTest {
        guest = false
        val v = loaded()
        v.sendChat("   ")
        advanceUntilIdle()
        assertTrue(repo.sent.isEmpty())
        assertTrue(v.uiState.value.chat.isEmpty())
    }

    // ---- cart ----

    private val item = MerchItem("p1", "v1", "Jersey", 450000)

    @Test
    fun add_to_cart_double_tap_ignored_then_accepted_after_400ms() = runTest {
        val v = loaded()
        now = 1000
        v.addToCart(item)
        now = 1200
        v.addToCart(item)
        advanceUntilIdle()
        assertEquals(1, cart.adds.size)
        now = 1400
        v.addToCart(item)
        advanceUntilIdle()
        assertEquals(2, cart.adds.size)
    }

    @Test
    fun cart_count_mirrors_cart() = runTest {
        val v = loaded()
        assertEquals(0, v.uiState.value.cartCount)
        cart.count0.value = 4
        advanceUntilIdle()
        assertEquals(4, v.uiState.value.cartCount)
    }

    // ---- countdown ----

    @Test
    fun countdown_ticks_and_refetches_at_zero() = runTest {
        repo.snapshotResult = { NetworkResult.Success(snap("upcoming", scheduled = "1970-01-01T00:00:03Z")) }
        now = 0
        val v = vm()
        runCurrent()
        v.setActive(true)
        runCurrent()
        assertEquals(3, v.uiState.value.countdown?.seconds)
        now = 1000
        advanceTimeBy(1001)
        assertEquals(2, v.uiState.value.countdown?.seconds)
        assertEquals(1, repo.snapshotCalls)
        now = 3000
        advanceTimeBy(2100)
        runCurrent()
        assertTrue(v.uiState.value.countdown?.finished == true)
        assertEquals(2, repo.snapshotCalls)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(2, repo.snapshotCalls)
        v.setActive(false)
    }

    // ---- connection ----

    @Test
    fun reconnect_refetches_once() = runTest {
        val v = loaded()
        assertEquals(1, repo.snapshotCalls)
        source.st.value = LiveState.RECONNECTING
        runCurrent()
        source.st.value = LiveState.LIVE
        advanceUntilIdle()
        assertEquals(2, repo.snapshotCalls)
        assertEquals(LiveState.LIVE, v.uiState.value.connection)
    }

    @Test
    fun offline_flag_from_connection_and_network() = runTest {
        val v = loaded()
        source.st.value = LiveState.OFFLINE
        runCurrent()
        assertTrue(v.uiState.value.offline)
        source.st.value = LiveState.LIVE
        advanceUntilIdle()
        assertFalse(v.uiState.value.offline)
        v.onNetworkOffline(true)
        assertTrue(v.uiState.value.offline)
    }

    @Test
    fun going_back_online_refetches() = runTest {
        val v = loaded()
        v.onNetworkOffline(true)
        advanceUntilIdle()
        assertEquals(1, repo.snapshotCalls)
        v.onNetworkOffline(false)
        advanceUntilIdle()
        assertEquals(2, repo.snapshotCalls)
        assertFalse(v.uiState.value.offline)
    }

    @Test
    fun set_active_false_stops_processing() = runTest {
        val v = loaded()
        v.setActive(false)
        runCurrent()
        source.flow.emit(scoreSnapshot(9, 9, 50))
        runCurrent()
        assertNull(v.uiState.value.snapshot?.homeScore.takeIf { it == 9 })
        v.setActive(true)
        runCurrent()
        source.flow.emit(scoreSnapshot(4, 4, 60))
        runCurrent()
        assertEquals(4, v.uiState.value.snapshot?.homeScore)
    }

    companion object {
        fun snap(status: String, home: Int? = 0, away: Int? = 0, scheduled: String? = null) = MatchSnapshot(
            matchId = "m1",
            status = status,
            homeName = "Home",
            awayName = "Away",
            homeScore = home,
            awayScore = away,
            scheduledAtIso = scheduled,
        )
    }
}
