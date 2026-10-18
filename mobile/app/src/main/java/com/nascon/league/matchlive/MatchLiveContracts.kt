package com.nascon.league.matchlive

import com.nascon.league.core.network.NetworkResult
import kotlinx.coroutines.flow.Flow

/**
 * Shared contracts for the Part II match screens (H02 live, H03 preview, H04 VOD, H05 notice) and the co-stream viewer.
 * Pure data and interfaces: no Android types, so logic and view models are unit tested on the JVM.
 */

/** One pinned product with the variant the "Add to cart" button adds. Price is in minor units (450000 = PKR 4,500.00). */
data class MerchItem(
    val productId: String,
    val variantId: String,
    val name: String,
    val priceMinor: Long,
    val imageUrl: String? = null,
)

/** The match as the screens show it. [status] is the raw server status (in_progress, upcoming, completed, postponed, cancelled, abandoned). */
data class MatchSnapshot(
    val matchId: String,
    val status: String,
    val homeName: String,
    val awayName: String,
    val homeScore: Int?,
    val awayScore: Int?,
    val minuteLabel: String? = null,
    val leagueName: String? = null,
    val scheduledAtIso: String? = null,
    val version: Long = 0,
    /** HLS (.m3u8) or other playable URL; null while the stream is not available. */
    val streamUrl: String? = null,
    val posterUrl: String? = null,
    val recapText: String? = null,
    /** Why a match was postponed, cancelled or abandoned, when the server says. */
    val reason: String? = null,
    val pinned: List<MerchItem> = emptyList(),
)

/** One chat line. Rendered strictly by [sequence]; [pending] lines are local echoes until the server confirms them. */
data class ChatMessage(
    val roomId: String,
    val messageId: String,
    val sequence: Long,
    val sender: String,
    val text: String,
    val flagged: Boolean = false,
    val pending: Boolean = false,
)

/** What the data layer offers the match screens. Real implementation hits the backend; tests use fakes. */
interface MatchLiveRepository {
    /** Current snapshot. 404 MATCH_NOT_FOUND surfaces as a Failure. Guests read through the unauthenticated score route. */
    suspend fun snapshot(matchId: String): NetworkResult<MatchSnapshot>

    /** History for a chat room (`chat-{id}`), oldest first, after [afterSequence] when given. */
    suspend fun chatHistory(roomId: String, afterSequence: Long?): NetworkResult<List<ChatMessage>>

    /** Real write. A guest gets 403 GUEST_WRITE_FORBIDDEN as a Failure; nothing is appended locally. */
    suspend fun sendChat(roomId: String, text: String): NetworkResult<ChatMessage>
}

data class CartLine(val variantId: String, val name: String, val priceMinor: Long, val quantity: Int)

/** The local guest cart (Room, per guest token). The merge into a member cart is Phase 5. */
interface GuestCart {
    val lines: Flow<List<CartLine>>

    /** Integer total of quantities, for the `guest_cart_count` badge. */
    val count: Flow<Int>

    suspend fun add(variantId: String, name: String, priceMinor: Long, quantity: Int = 1)
    suspend fun setQuantity(variantId: String, quantity: Int)
    suspend fun remove(variantId: String)
    suspend fun clear()
}

/** One-shot things a screen reacts to. */
sealed interface MatchLiveEvent {
    /** A guest tried a write (chat send, checkout): open the progressive sign-in sheet. */
    data object OpenSignInSheet : MatchLiveEvent
    data class ShowMessage(val message: String) : MatchLiveEvent
}

/** Test tags used by the screens (Appium reads them as resource ids). */
object MatchLiveTags {
    const val LIVE_ROOT = "match_live_root"
    const val SCORE = "match_score"
    const val STATUS = "match_status"
    const val CHAT_LIST = "chat_list"
    const val CHAT_INPUT = "chat_input"
    const val CHAT_SEND = "chat_send"
    const val GUEST_CART_COUNT = "guest_cart_count"
    const val PREVIEW_ROOT = "match_preview_root"
    const val VOD_ROOT = "match_vod_root"
    const val NOTICE_ROOT = "match_notice_root"
    const val OFFLINE_STRIP = "offline_strip"
}

/** Time left until kickoff, for the preview screen. [finished] is true at zero, when the screen refetches the status. */
data class Countdown(val days: Long, val hours: Int, val minutes: Int, val seconds: Int, val finished: Boolean)

enum class LoadPhase { LOADING, CONTENT, NOT_FOUND, ERROR }

/** Everything the match screens render. One immutable object per ViewModel. */
data class MatchLiveUiState(
    val matchId: String,
    val chatRoomId: String,
    val phase: LoadPhase = LoadPhase.LOADING,
    val snapshot: MatchSnapshot? = null,
    /** Which screen the status selects (LIVE, PREVIEW, VOD, NOTICE, or LIVE_SHELL while the status is unknown). */
    val screen: com.nascon.league.deeplink.MatchScreen = com.nascon.league.deeplink.MatchScreen.LIVE_SHELL,
    val connection: com.nascon.league.data.realtime.LiveState = com.nascon.league.data.realtime.LiveState.CONNECTING,
    /** True when the device or the stream is offline: cached content stays visible under a pinned "Offline - showing saved data" strip. */
    val offline: Boolean = false,
    val errorMessage: String? = null,
    val chat: List<ChatMessage> = emptyList(),
    val chatSending: Boolean = false,
    val isGuest: Boolean = true,
    val cartCount: Int = 0,
    val countdown: Countdown? = null,
    /** Start offset from `?t=`; applies to the video only when a recording exists. */
    val startSeconds: Int? = null,
)
