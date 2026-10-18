package com.nascon.league.ui.fixturegen

import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.core.network.ApiError
import com.nascon.league.data.fixturegen.GenMatchDto
import com.nascon.league.data.fixturegen.GeneratedDto
import com.nascon.league.data.fixturegen.SwissHistoryMatch
import com.nascon.league.data.fixturegen.SwissHistoryRound
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Who may open the generator: super admin anywhere, league organizer only for their own league. */
object FixtureGenAccess {
    fun canGenerate(session: Session?, leagueId: String): Boolean {
        val user = session?.user ?: return false
        return when (user.role) {
            Role.SUPER_ADMIN -> true
            Role.LEAGUE_ORGANIZER -> leagueId in user.leagueIds
            else -> false
        }
    }
}

data class TeamOpt(val id: String, val name: String, val points: Int? = null)

object GenDemoTeams {
    val all: List<TeamOpt> = listOf(
        "Lions FC", "Tigers", "Eagles FC", "Wolves", "Bears", "Hawks", "Foxes", "Owls", "Dingos", "Rhinos",
    ).mapIndexed { i, n -> TeamOpt("demo-team-${i + 1}", n) }
}

fun teamLabel(id: String?, names: Map<String, String>): String = when {
    id == null -> ""
    else -> names[id]?.takeIf { it.isNotBlank() } ?: id
}

/** Server code NO_VALID_PAIRING has no entry in ErrorMapper; everything else goes through it. */
fun isNoValidPairing(e: ApiError): Boolean = e is ApiError.Http && e.code == "NO_VALID_PAIRING"

fun genErrorMessage(e: ApiError, noPairing: String): String =
    if (isNoValidPairing(e)) noPairing else ErrorMapper.message(e)

enum class RoundKind { FINAL, SEMI, QUARTER, ROUND }

/** Name of a knockout round: last round = final, then semi-finals, quarter-finals, otherwise "Round n". */
fun roundKind(round: Int, totalRounds: Int): RoundKind = when (totalRounds - round) {
    0 -> RoundKind.FINAL
    1 -> RoundKind.SEMI
    2 -> RoundKind.QUARTER
    else -> RoundKind.ROUND
}

object GenSchedule {
    val saturdayAndSunday: Set<Int> = setOf(DayOfWeek.SATURDAY.value, DayOfWeek.SUNDAY.value)

    fun parseDate(text: String): LocalDate? = try {
        LocalDate.parse(text.trim())
    } catch (_: Exception) {
        null
    }

    /** Round 1 is the first match day on or after [start]; each later round is the next match day after the previous. */
    fun roundDates(start: LocalDate, matchDays: Set<Int>, rounds: Int): List<LocalDate> {
        if (rounds <= 0) return emptyList()
        val days = matchDays.filter { it in 1..7 }.toSet()
        if (days.isEmpty()) return List(rounds) { start.plusDays(it.toLong()) }
        val out = ArrayList<LocalDate>()
        var d = start
        while (d.dayOfWeek.value !in days) d = d.plusDays(1)
        out += d
        while (out.size < rounds) {
            d = d.plusDays(1)
            while (d.dayOfWeek.value !in days) d = d.plusDays(1)
            out += d
        }
        return out
    }

    fun format(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("EEE d MMM", locale).format(date)
}

data class RrCheck(val doubleBooked: Int, val unbalanced: Int) {
    val clean: Boolean get() = doubleBooked == 0 && unbalanced == 0
}

/** The "No conflicts found" line: nobody plays twice in a round and home/away counts differ by at most one. */
fun checkRoundRobin(dto: GeneratedDto): RrCheck {
    var booked = 0
    val home = HashMap<String, Int>()
    val away = HashMap<String, Int>()
    for (r in dto.rounds) {
        val seen = HashSet<String>()
        for (m in r.matches) {
            val h = m.home ?: continue
            val a = m.away ?: continue
            if (!seen.add(h)) booked++
            if (!seen.add(a)) booked++
            home[h] = (home[h] ?: 0) + 1
            away[a] = (away[a] ?: 0) + 1
        }
    }
    val teams = home.keys + away.keys
    val unbalanced = teams.count { Math.abs((home[it] ?: 0) - (away[it] ?: 0)) > 1 }
    return RrCheck(booked, unbalanced)
}

/** Matches that will actually be played in a swiss round / bracket (no byes, no conditional reset final). */
fun playableMatches(dto: GeneratedDto): List<GenMatchDto> = when (dto.format) {
    "round_robin" -> dto.rounds.flatMap { it.matches }.filter { it.status != "voided" }
    "single_elim" -> dto.rounds.flatMap { it.matches }.filter { !it.bye }
    "double_elim" -> (dto.winners + dto.losers).flatMap { it.matches }.filter { !it.bye } + dto.grandFinal.filter { !it.conditional }
    else -> emptyList()
}

/** A swiss round is complete once every pairing has a result. */
fun swissRoundComplete(round: GeneratedDto, results: Map<String, String>): Boolean =
    round.pairings.isNotEmpty() && round.pairings.all { results[it.matchId] in SWISS_RESULTS }

val SWISS_RESULTS = listOf("home", "draw", "away")

/** Builds the `history` parameter for generating round (rounds.size + 1) from all rounds shown so far. */
fun swissHistory(rounds: List<GeneratedDto>, results: Map<String, String>): List<SwissHistoryRound> =
    rounds.map { r ->
        SwissHistoryRound(
            round = r.round,
            bye = r.bye,
            matches = r.pairings.map { SwissHistoryMatch(it.home, it.away, results[it.matchId] ?: "draw") },
        )
    }

/** Knockout size helpers for the "padded to N slots" notice. */
fun byeCount(teamCount: Int): Int = com.nascon.league.data.fixturegen.FixtureGenRules.nextPow2(teamCount) - teamCount

fun hybridRange(teamCount: Int): IntRange = 2..teamCount.coerceAtLeast(2)

/** Opponent seed in round 1 of a bracket padded to a power of two, or null when [seed] gets a bye. */
fun firstRoundOpponent(seed: Int, teamCount: Int): Int? {
    val size = com.nascon.league.data.fixturegen.FixtureGenRules.nextPow2(teamCount)
    val opp = size + 1 - seed
    return if (opp in 1..teamCount && opp != seed) opp else null
}

/** Seeds that skip round 1 for [teamCount] teams. */
fun byeSeeds(teamCount: Int): List<Int> = (1..teamCount).filter { firstRoundOpponent(it, teamCount) == null }

/** "WR1M2" -> "W1-2", "LR3M1" -> "L3-1", "R2M1" -> "R2-1", "GF1" -> "grand final", "TP" -> "third place". */
fun shortMatchId(id: String): String {
    val m = Regex("^(WR|LR|R)([0-9]+)M([0-9]+)$").matchEntire(id)
    if (m != null) {
        val (kind, r, i) = m.destructured
        val letter = when (kind) { "WR" -> "W"; "LR" -> "L"; else -> "R" }
        return "$letter$r-$i"
    }
    return when (id) {
        "TP" -> "third place"
        "GF1" -> "grand final"
        "GF2" -> "grand final reset"
        else -> id
    }
}

/** Where a still-empty slot comes from: key "matchId:slot" -> (isLoser, source match id). */
fun slotSources(matches: List<GenMatchDto>): Map<String, Pair<Boolean, String>> {
    val out = HashMap<String, Pair<Boolean, String>>()
    for (m in matches) {
        m.winnerTo?.let { out["${it.matchId}:${it.slot}"] = false to m.matchId }
        m.loserTo?.let { out["${it.matchId}:${it.slot}"] = true to m.matchId }
    }
    return out
}

/** 6.0 -> "6", 2.5 -> "2.5" (swiss scores are whole or half points). */
fun formatScore(score: Double): String =
    if (score % 1.0 == 0.0) score.toLong().toString() else String.format(Locale.US, "%.1f", score)
