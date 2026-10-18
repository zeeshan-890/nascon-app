package com.nascon.league.costream

enum class CoPhase { ACTIVE, HANDOFF_PENDING, TERMINATED }

object CoStreamLogic {
    const val MAX_TILES = 4

    fun phaseOf(status: String?): CoPhase = when (status?.trim()?.lowercase()) {
        "handoff_pending" -> CoPhase.HANDOFF_PENDING
        "terminated" -> CoPhase.TERMINATED
        else -> CoPhase.ACTIVE
    }

    /** Null when the string is not a known layout. */
    fun parseLayout(s: String?): CoLayout? = when (s?.trim()?.lowercase()) {
        "solo" -> CoLayout.SOLO
        "grid_2" -> CoLayout.GRID_2
        "grid_4" -> CoLayout.GRID_4
        "spotlight" -> CoLayout.SPOTLIGHT
        else -> null
    }

    /** Unknown strings map to GRID_4 (shows everyone up to four); [tilesFor] derives from the count instead. */
    fun layoutOf(s: String?): CoLayout = parseLayout(s) ?: CoLayout.GRID_4

    fun tiles(layout: CoLayout, participants: List<CoParticipant>): List<TileUi> {
        val visible = participants.filter { !it.kicked }
        val ordered = visible.filter { it.isHost } + visible.filter { !it.isHost }
        val cap = when (layout) {
            CoLayout.SOLO -> 1
            CoLayout.GRID_2 -> 2
            CoLayout.GRID_4, CoLayout.SPOTLIGHT -> MAX_TILES
        }
        return ordered.take(cap.coerceAtMost(MAX_TILES)).mapIndexed { i, p ->
            TileUi(p.userId, p.displayName, p.isHost, p.muted, large = layout == CoLayout.SPOTLIGHT && i == 0)
        }
    }

    fun tilesFor(layout: String?, participants: List<CoParticipant>): List<TileUi> {
        val parsed = parseLayout(layout)
        if (parsed != null) return tiles(parsed, participants)
        val n = participants.count { !it.kicked }
        val derived = when {
            n <= 1 -> CoLayout.SOLO
            n == 2 -> CoLayout.GRID_2
            else -> CoLayout.GRID_4
        }
        return tiles(derived, participants)
    }

    fun handoffBanner(prev: CoSession?, next: CoSession): String? {
        val a = prev?.activeHost
        val b = next.activeHost
        if (a != null && b != null && a != b) {
            val name = next.participants.firstOrNull { it.userId == b }?.displayName ?: b
            return "Host changed to $name"
        }
        if (phaseOf(next.status) == CoPhase.HANDOFF_PENDING) return "Waiting for a new host"
        return null
    }

    fun isEnded(s: CoSession): Boolean =
        phaseOf(s.status) == CoPhase.TERMINATED ||
            (s.participants.none { !it.kicked } && phaseOf(s.status) != CoPhase.ACTIVE)
}
