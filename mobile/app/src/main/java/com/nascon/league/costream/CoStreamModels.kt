package com.nascon.league.costream

import com.nascon.league.core.network.NetworkResult
import com.nascon.league.matchlive.ChatMessage
import com.nascon.league.matchlive.LoadPhase

enum class CoLayout { SOLO, GRID_2, GRID_4, SPOTLIGHT }

data class CoParticipant(
    val userId: String,
    val displayName: String,
    val isHost: Boolean,
    val muted: Boolean = false,
    val kicked: Boolean = false,
)

/** status: "active" | "handoff_pending" | "terminated". The server decides the host; the UI never invents one. */
data class CoSession(
    val sessionId: String,
    val title: String?,
    val status: String,
    val activeHost: String?,
    val layout: String,
    val participants: List<CoParticipant>,
    val playbackUrl: String?,
    val posterUrl: String?,
    val chatRoomId: String,
    val vodUrl: String?,
)

data class TileUi(val userId: String, val name: String, val isHost: Boolean, val muted: Boolean, val large: Boolean)

data class CoStreamUiState(
    val sessionId: String,
    val chatRoomId: String,
    val phase: LoadPhase = LoadPhase.LOADING,
    val session: CoSession? = null,
    val tiles: List<TileUi> = emptyList(),
    val banner: String? = null,
    val ended: Boolean = false,
    val offline: Boolean = false,
    val errorMessage: String? = null,
    val chat: List<ChatMessage> = emptyList(),
    val chatSending: Boolean = false,
    val isGuest: Boolean = true,
)

interface CoStreamRepository {
    suspend fun session(sessionId: String): NetworkResult<CoSession>
}

object CoStreamTags {
    const val ROOT = "costream_root"
    const val TILE_PREFIX = "costream_tile_"
    const val HOST_MARKER = "costream_host_marker"
    const val HANDOFF_BANNER = "costream_handoff_banner"
}
