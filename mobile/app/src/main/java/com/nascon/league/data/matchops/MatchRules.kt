package com.nascon.league.data.matchops

import com.nascon.league.domain.model.Permissions
import com.nascon.league.domain.model.Session
import com.nascon.league.ui.fixtures.StatusKind
import com.nascon.league.ui.fixtures.statusKind
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Admin lifecycle, mirrors backend/src/domain/schedule/statusMachine.js. */
object MatchStatusMachine {
    val ALL = listOf("upcoming", "in_progress", "completed", "postponed", "abandoned", "cancelled")

    private val transitions: Map<String, List<String>> = mapOf(
        "upcoming" to listOf("in_progress", "postponed", "cancelled"),
        "postponed" to listOf("upcoming", "cancelled"),
        "in_progress" to listOf("completed", "abandoned", "postponed"),
        "completed" to emptyList(),
        "abandoned" to emptyList(),
        "cancelled" to emptyList(),
    )

    /** Normalises any status string the app sees ("scheduled", "live"...) to a backend status, or null. */
    fun normalize(raw: String?): String? = when (statusKind(raw)) {
        StatusKind.UPCOMING -> "upcoming"
        StatusKind.LIVE -> "in_progress"
        StatusKind.COMPLETED -> "completed"
        StatusKind.POSTPONED -> "postponed"
        StatusKind.ABANDONED -> "abandoned"
        StatusKind.CANCELLED -> "cancelled"
        StatusKind.UNKNOWN -> null
    }

    fun canTransition(from: String, to: String): Boolean = transitions[from]?.contains(to) == true

    fun nextStatuses(from: String): List<String> = transitions[from].orEmpty()

    fun isFinal(status: String): Boolean = transitions[status]?.isEmpty() == true

    /** Moves that need a live match are shown disabled with a reason, as in the mockup. */
    val NEEDS_LIVE = setOf("completed", "abandoned")
}

/** Who sees the Manage match entry: super admin, or a league organizer of this match's league. */
fun canManageMatch(session: Session?, leagueId: String?): Boolean =
    leagueId != null && Permissions(session).canScore(leagueId)

/** Date and time pickers work in the device zone; the server wants UTC `yyyy-MM-ddTHH:mm:ssZ`. */
object ScheduleTime {
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

    fun toIsoUtc(date: LocalDate, time: LocalTime, zone: ZoneId): String =
        ZonedDateTime.of(date, time.withSecond(0).withNano(0), zone).withZoneSameInstant(ZoneOffset.UTC).format(ISO)

    fun parse(iso: String?, zone: ZoneId): ZonedDateTime? = runCatching {
        if (iso.isNullOrBlank()) null else java.time.Instant.parse(iso).atZone(zone)
    }.getOrNull()
}

/** Conflict kinds the server reports (backend/src/domain/schedule/conflicts.js). */
enum class ConflictKind { VENUE_BOOKED, TEAM_BUSY, PLAYER_BUSY, NOT_ENOUGH_REST, VENUE_CLOSED, OTHER }

fun conflictKind(type: String): ConflictKind = when (type) {
    "venue_double_booking" -> ConflictKind.VENUE_BOOKED
    "team_overlap" -> ConflictKind.TEAM_BUSY
    "player_overlap" -> ConflictKind.PLAYER_BUSY
    "min_rest" -> ConflictKind.NOT_ENOUGH_REST
    "venue_unavailable" -> ConflictKind.VENUE_CLOSED
    else -> ConflictKind.OTHER
}

/** Display-ready conflict: [names] are team / match names resolved from cached fixtures, never ids. */
data class ConflictView(
    val kind: ConflictKind,
    val teams: List<String>,
    val matches: List<String>,
    val playerCount: Int,
)

fun describeConflicts(
    conflicts: List<ConflictDto>,
    teamName: (String) -> String?,
    matchName: (String) -> String?,
): List<ConflictView> = conflicts.map { c ->
    ConflictView(
        kind = conflictKind(c.type),
        teams = c.entityIds.mapNotNull(teamName).distinct(),
        matches = c.matchIds.mapNotNull(matchName).distinct(),
        playerCount = if (conflictKind(c.type) == ConflictKind.PLAYER_BUSY) c.entityIds.size else 0,
    )
}
