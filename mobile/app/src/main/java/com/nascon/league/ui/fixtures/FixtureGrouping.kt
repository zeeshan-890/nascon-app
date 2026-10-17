package com.nascon.league.ui.fixtures

import com.nascon.league.domain.model.Fixture

/** Defensive interpretation of the server's match status strings. */
enum class StatusKind { UPCOMING, LIVE, COMPLETED, POSTPONED, ABANDONED, CANCELLED, UNKNOWN }

fun statusKind(raw: String?): StatusKind = when (raw?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')) {
    "scheduled", "upcoming" -> StatusKind.UPCOMING
    "in_progress", "live" -> StatusKind.LIVE
    "completed" -> StatusKind.COMPLETED
    "postponed" -> StatusKind.POSTPONED
    "abandoned" -> StatusKind.ABANDONED
    "cancelled", "canceled" -> StatusKind.CANCELLED
    else -> StatusKind.UNKNOWN
}

enum class FixtureFilter(val key: String) {
    ALL("all"), LIVE("live"), UPCOMING("upcoming"), COMPLETED("completed");

    companion object {
        fun fromKey(key: String?): FixtureFilter = entries.firstOrNull { it.key == key } ?: ALL
    }
}

enum class FixtureGroup { LIVE, UPCOMING, COMPLETED, OTHER }

fun StatusKind.group(): FixtureGroup = when (this) {
    StatusKind.LIVE -> FixtureGroup.LIVE
    StatusKind.UPCOMING -> FixtureGroup.UPCOMING
    StatusKind.COMPLETED -> FixtureGroup.COMPLETED
    else -> FixtureGroup.OTHER
}

fun FixtureFilter.accepts(group: FixtureGroup): Boolean = when (this) {
    FixtureFilter.ALL -> true
    FixtureFilter.LIVE -> group == FixtureGroup.LIVE
    FixtureFilter.UPCOMING -> group == FixtureGroup.UPCOMING
    FixtureFilter.COMPLETED -> group == FixtureGroup.COMPLETED
}

data class FixtureSection(val group: FixtureGroup, val items: List<Fixture>)

/** Groups in fixed order Live, Upcoming, Completed, Other. Live/upcoming/other ascending, completed descending, null times last. */
fun groupAndSort(fixtures: List<Fixture>, filter: FixtureFilter = FixtureFilter.ALL): List<FixtureSection> {
    val byGroup = fixtures.groupBy { statusKind(it.status).group() }
    return FixtureGroup.entries.mapNotNull { g ->
        if (!filter.accepts(g)) return@mapNotNull null
        val list = byGroup[g].orEmpty()
        if (list.isEmpty()) return@mapNotNull null
        val withTime = list.filter { FixtureTime.parse(it.scheduledAt) != null }
            .let { l ->
                val sorted = l.sortedBy { FixtureTime.parse(it.scheduledAt)!!.toEpochMilli() }
                if (g == FixtureGroup.COMPLETED) {
                    // descending but keep original order for equal times (stable)
                    l.sortedByDescending { FixtureTime.parse(it.scheduledAt)!!.toEpochMilli() }
                } else sorted
            }
        val withoutTime = list.filter { FixtureTime.parse(it.scheduledAt) == null }
        FixtureSection(g, withTime + withoutTime)
    }
}

/** Default league: remembered choice, else first of the user's leagues, else first league returned. */
fun pickLeague(
    availableIds: List<String>,
    chosen: String?,
    persisted: String?,
    sessionLeagueIds: List<String>,
): String? {
    if (availableIds.isEmpty()) return null
    if (chosen != null && chosen in availableIds) return chosen
    if (persisted != null && persisted in availableIds) return persisted
    sessionLeagueIds.firstOrNull { it in availableIds }?.let { return it }
    return availableIds.first()
}
