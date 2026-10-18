package com.nascon.league.ui.leagueadmin

import com.nascon.league.data.api.dto.LeagueDto
import com.nascon.league.data.leagueadmin.AdminLeagueDto
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Who may see or change what in league management. Hide, never disable. */
object LeagueAdminAccess {
    private fun role(s: Session?) = s?.user?.role
    private fun ownsLeague(s: Session?, leagueId: String) = leagueId in (s?.user?.leagueIds ?: emptyList())

    /** Entry to the leagues list. */
    fun canOpenHub(s: Session?): Boolean = role(s) == Role.SUPER_ADMIN || role(s) == Role.LEAGUE_ORGANIZER

    fun canViewLeague(s: Session?, leagueId: String): Boolean = when (role(s)) {
        Role.SUPER_ADMIN -> true
        Role.LEAGUE_ORGANIZER -> ownsLeague(s, leagueId)
        else -> false
    }

    /** league.create: super admin, and league organizers for their own tenant. */
    fun canCreateLeague(s: Session?): Boolean = role(s) == Role.SUPER_ADMIN || role(s) == Role.LEAGUE_ORGANIZER

    fun canEditLeague(s: Session?, leagueId: String): Boolean = canViewLeague(s, leagueId)

    /** league.approve: super admin only. Organizers' new leagues stay hidden from viewers until approved. */
    fun canApproveLeague(s: Session?): Boolean = role(s) == Role.SUPER_ADMIN

    /** season.write is super admin only. Organizers may read seasons of their own league. */
    fun canWriteSeasons(s: Session?): Boolean = role(s) == Role.SUPER_ADMIN

    /** Divisions, promotion rules, standings rules and the fixtures generator. */
    fun canEditRules(s: Session?, leagueId: String): Boolean = canViewLeague(s, leagueId)

    fun <T> visible(s: Session?, items: List<T>, idOf: (T) -> String): List<T> = items.filter { canViewLeague(s, idOf(it)) }
}

enum class LeagueFormat(val apiValue: String) {
    ROUND_ROBIN("round_robin"),
    SWISS("swiss"),
    SINGLE_ELIMINATION("single_elimination"),
    DOUBLE_ELIMINATION("double_elimination"),
    HYBRID("hybrid");

    val hasKnockout: Boolean get() = this == SINGLE_ELIMINATION || this == DOUBLE_ELIMINATION || this == HYBRID

    companion object {
        fun fromApi(v: String?): LeagueFormat = entries.firstOrNull { it.apiValue == v } ?: ROUND_ROBIN
    }
}

/** One row of the leagues list: real league data plus whatever the contract-addition endpoint knows. */
data class LeagueSummary(
    val leagueId: String,
    val name: String,
    val sportId: String,
    val season: String?,
    val format: LeagueFormat?,
    val status: String?,
    val teamCount: Int?,
    val divisionCount: Int?,
    val extras: AdminLeagueDto?,
)

object LeagueMerge {
    /** Real leagues first (in server order); extra data overlays them; admin-only leagues (e.g. drafts) are appended. */
    fun merge(real: List<LeagueDto>, admin: List<AdminLeagueDto>): List<LeagueSummary> {
        val adminById = admin.associateBy { it.leagueId }
        val out = real.map { r ->
            val a = adminById[r.leagueId]
            LeagueSummary(
                r.leagueId,
                a?.name?.ifBlank { null } ?: r.name,
                a?.sportId?.ifBlank { null } ?: r.sportId,
                a?.season ?: r.season,
                a?.let { LeagueFormat.fromApi(it.format) },
                a?.status,
                a?.teamCount,
                a?.divisionCount,
                a,
            )
        }.toMutableList()
        val seen = real.map { it.leagueId }.toSet()
        admin.filter { it.leagueId !in seen }.forEach { a ->
            out += LeagueSummary(
                a.leagueId, a.name, a.sportId, a.season, LeagueFormat.fromApi(a.format), a.status,
                a.teamCount, a.divisionCount, a,
            )
        }
        return out
    }
}

fun parseIsoDate(value: String?): LocalDate? =
    if (value.isNullOrBlank()) null else try { LocalDate.parse(value) } catch (_: DateTimeParseException) { null }

/** Epoch millis of UTC midnight, as the Material date picker expects. */
fun dateToPickerMillis(d: LocalDate): Long = d.toEpochDay() * 86_400_000L

fun pickerMillisToDate(ms: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(ms, 86_400_000L))

// ---------- league form ----------

enum class LeagueFormError { NAME_REQUIRED, NAME_TOO_LONG, SPORT_REQUIRED, WINDOW_INCOMPLETE, WINDOW_ORDER, SQUAD_INVALID }

data class LeagueFormInput(
    val name: String = "",
    val sportId: String? = null,
    val format: LeagueFormat = LeagueFormat.ROUND_ROBIN,
    val windowOpens: LocalDate? = null,
    val windowCloses: LocalDate? = null,
    val maxSquad: String = "25",
)

object LeagueFormValidator {
    const val NAME_MAX = 60
    const val SQUAD_MAX = 99

    fun validate(i: LeagueFormInput): Set<LeagueFormError> = buildSet {
        val name = i.name.trim()
        if (name.isEmpty()) add(LeagueFormError.NAME_REQUIRED) else if (name.length > NAME_MAX) add(LeagueFormError.NAME_TOO_LONG)
        if (i.sportId.isNullOrBlank()) add(LeagueFormError.SPORT_REQUIRED)
        if ((i.windowOpens == null) != (i.windowCloses == null)) {
            add(LeagueFormError.WINDOW_INCOMPLETE)
        } else if (i.windowOpens != null && i.windowCloses != null && !i.windowCloses.isAfter(i.windowOpens)) {
            add(LeagueFormError.WINDOW_ORDER)
        }
        val squad = i.maxSquad.trim().toIntOrNull()
        if (squad == null || squad < 1 || squad > SQUAD_MAX) add(LeagueFormError.SQUAD_INVALID)
    }
}

// ---------- season form ----------

enum class SeasonFormError { NAME_REQUIRED, START_REQUIRED, END_REQUIRED, END_NOT_AFTER_START }

object SeasonFormValidator {
    fun validate(name: String, start: LocalDate?, end: LocalDate?): Set<SeasonFormError> = buildSet {
        if (name.isBlank()) add(SeasonFormError.NAME_REQUIRED)
        if (start == null) add(SeasonFormError.START_REQUIRED)
        if (end == null) add(SeasonFormError.END_REQUIRED)
        if (start != null && end != null && !end.isAfter(start)) add(SeasonFormError.END_NOT_AFTER_START)
    }
}

// ---------- promotion ----------

enum class ZoneKind { PROMOTED, MID, RELEGATED }

data class PromotionZone(val kind: ZoneKind, val from: Int, val to: Int)

object PromotionRules {
    const val DEFAULT_TEAMS = 8

    /** Each side may use at most half the table so the two zones never overlap. */
    fun maxSpots(teamCount: Int): Int = (if (teamCount > 0) teamCount else DEFAULT_TEAMS) / 2

    fun clamp(value: Int, teamCount: Int): Int = value.coerceIn(0, maxSpots(teamCount))

    fun zones(teamCount: Int, up: Int, down: Int): List<PromotionZone> {
        val n = if (teamCount > 0) teamCount else DEFAULT_TEAMS
        val u = clamp(up, n)
        val d = clamp(down, n)
        val out = mutableListOf<PromotionZone>()
        if (u > 0) out += PromotionZone(ZoneKind.PROMOTED, 1, u)
        if (n - d >= u + 1) out += PromotionZone(ZoneKind.MID, u + 1, n - d)
        if (d > 0) out += PromotionZone(ZoneKind.RELEGATED, n - d + 1, n)
        return out
    }
}

// ---------- standings rules ----------

enum class PointsError { RANGE, WIN_BELOW_DRAW, DRAW_BELOW_LOSS }

object StandingsRulesLogic {
    const val MAX_POINTS = 9
    const val MAX_QUALIFY = 32
    val allTiebreaks = listOf("head_to_head", "goal_difference", "goals_for", "wins", "fair_play")

    fun validatePoints(win: Int, draw: Int, loss: Int): Set<PointsError> = buildSet {
        if (listOf(win, draw, loss).any { it < 0 || it > MAX_POINTS }) add(PointsError.RANGE)
        if (win < draw) add(PointsError.WIN_BELOW_DRAW)
        if (draw < loss) add(PointsError.DRAW_BELOW_LOSS)
    }

    fun clampQualify(v: Int): Int = v.coerceIn(0, MAX_QUALIFY)

    fun moveUp(order: List<String>, index: Int): List<String> =
        if (index <= 0 || index >= order.size) order else order.toMutableList().also { java.util.Collections.swap(it, index, index - 1) }

    fun moveDown(order: List<String>, index: Int): List<String> =
        if (index < 0 || index >= order.size - 1) order else order.toMutableList().also { java.util.Collections.swap(it, index, index + 1) }

    /** At least one tie-break must stay in the list. */
    fun remove(order: List<String>, index: Int): List<String> =
        if (order.size <= 1 || index !in order.indices) order else order.toMutableList().also { it.removeAt(index) }

    fun add(order: List<String>, id: String): List<String> =
        if (id in order || id !in allTiebreaks) order else order + id

    fun unused(order: List<String>): List<String> = allTiebreaks.filter { it !in order }

    /** Keeps only known ids, drops duplicates, falls back to the default when nothing is left. */
    fun sanitize(order: List<String>): List<String> =
        order.filter { it in allTiebreaks }.distinct().ifEmpty { listOf("head_to_head", "goal_difference", "goals_for") }
}

/** Static example table used for the preview card. */
data class SampleRow(val position: Int, val name: String, val points: Int)

val sampleStandings = listOf(
    SampleRow(1, "Lions", 19), SampleRow(2, "Tigers", 17), SampleRow(3, "Eagles", 13), SampleRow(4, "Wolves", 5),
)
