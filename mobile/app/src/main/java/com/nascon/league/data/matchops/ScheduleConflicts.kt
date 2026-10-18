package com.nascon.league.data.matchops

/** Kotlin port of backend/src/domain/schedule/conflicts.js (venue, team, player and rest rules). Times are epoch ms. */
object ScheduleConflicts {
    const val DEFAULT_DURATION_MIN = 120
    const val DEFAULT_MIN_REST_MIN = 1440
    private const val MIN = 60_000L

    data class Slot(
        val matchId: String,
        val homeTeamId: String,
        val awayTeamId: String,
        val start: Long,
        val durationMin: Int = DEFAULT_DURATION_MIN,
        val venueId: String? = null,
    ) {
        val end: Long get() = start + durationMin * MIN
        val teams: List<String> get() = listOf(homeTeamId, awayTeamId)
    }

    fun detect(
        subject: Slot,
        others: List<Slot>,
        playersByTeam: Map<String, Set<String>> = emptyMap(),
        minRestMin: Int = DEFAULT_MIN_REST_MIN,
    ): List<ConflictDto> {
        val venue = mutableListOf<Slot>()
        val team = mutableListOf<Pair<Slot, List<String>>>()
        val rest = mutableListOf<Pair<Slot, List<String>>>()
        val player = mutableListOf<Pair<Slot, List<String>>>()
        for (o in others) {
            if (o.matchId == subject.matchId) continue
            val overlap = subject.start < o.end && o.start < subject.end
            val shared = subject.teams.filter { it == o.homeTeamId || it == o.awayTeamId }
            if (overlap && subject.venueId != null && o.venueId == subject.venueId) venue += o
            if (overlap && shared.isNotEmpty()) team += o to shared
            if (!overlap && shared.isNotEmpty()) {
                val gap = if (o.start >= subject.end) (o.start - subject.end) / MIN else (subject.start - o.end) / MIN
                if (gap < minRestMin) rest += o to shared
            }
            if (overlap) {
                val mine = subject.teams.flatMap { playersByTeam[it].orEmpty() }.toSet()
                val common = o.teams.flatMap { playersByTeam[it].orEmpty() }.filter { it in mine }.distinct()
                if (common.isNotEmpty()) player += o to common
            }
        }
        val out = mutableListOf<ConflictDto>()
        if (venue.isNotEmpty()) {
            out += ConflictDto("venue_double_booking", listOf(subject.venueId.orEmpty()), venue.map { it.matchId }.sorted())
        }
        if (team.isNotEmpty()) {
            out += ConflictDto(
                "team_overlap",
                team.flatMap { it.second }.distinct().sorted(),
                team.map { it.first.matchId }.distinct().sorted(),
            )
        }
        if (player.isNotEmpty()) {
            out += ConflictDto(
                "player_overlap",
                player.flatMap { it.second }.distinct().sorted(),
                player.map { it.first.matchId }.distinct().sorted(),
            )
        }
        if (rest.isNotEmpty()) {
            out += ConflictDto(
                "min_rest",
                rest.flatMap { it.second }.distinct().sorted(),
                rest.map { it.first.matchId }.distinct().sorted(),
            )
        }
        return out
    }
}
