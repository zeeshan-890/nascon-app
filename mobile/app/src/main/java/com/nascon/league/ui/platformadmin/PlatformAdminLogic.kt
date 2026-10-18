package com.nascon.league.ui.platformadmin

import com.nascon.league.data.platformadmin.ACCESS_ROLES
import com.nascon.league.data.platformadmin.AccessPolicyDto
import com.nascon.league.data.platformadmin.SportDefinitionDto
import com.nascon.league.data.platformadmin.isValidEventKey
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Platform admin screens are for the super admin only; everyone else never sees an entry point. */
fun canAdminister(session: Session?): Boolean = session?.user?.role == Role.SUPER_ADMIN

/** "two_pointer" -> "Two pointer"; "club.roster.write" -> "Club roster write". Used for data keys without a label. */
fun humanizeKey(key: String): String {
    val words = key.trim().split('_', '.', '-', ' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return ""
    val s = words.joinToString(" ").lowercase()
    return s.replaceFirstChar { it.uppercase() }
}

/** Plain text for a JSON value: strings without quotes, numbers and booleans as written, objects as "a: 1, b: 2". */
fun jsonDisplay(e: JsonElement?): String = when (e) {
    null, JsonNull -> ""
    is JsonPrimitive -> e.content
    is JsonObject -> e.entries.joinToString(", ") { (k, v) -> "${humanizeKey(k)}: ${jsonDisplay(v)}" }
    else -> e.toString()
}

// ---------- Clubs (G08) ----------

enum class ClubField { NAME, TENANT, VENUE }
enum class FieldProblem { REQUIRED, TOO_LONG, TOO_SHORT, BAD_FORMAT, DUPLICATE }

fun validateClubName(name: String): FieldProblem? {
    val t = name.trim()
    return when {
        t.isEmpty() -> FieldProblem.REQUIRED
        t.length < 2 -> FieldProblem.TOO_SHORT
        t.length > 60 -> FieldProblem.TOO_LONG
        else -> null
    }
}

fun validateVenueName(name: String): FieldProblem? {
    val t = name.trim()
    return when {
        t.isEmpty() -> FieldProblem.REQUIRED
        t.length > 80 -> FieldProblem.TOO_LONG
        else -> null
    }
}

fun validateTenantId(id: String): FieldProblem? {
    val t = id.trim()
    return when {
        t.isEmpty() -> FieldProblem.REQUIRED
        !Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$").matches(t) -> FieldProblem.BAD_FORMAT
        else -> null
    }
}

// ---------- Sports (G09 / G10 / G11) ----------

fun SportDefinitionDto.eventCount(): Int = scoringEvents.size

/** Display name of the sport: the server name, or a readable form of its id. */
fun SportDefinitionDto.displayName(): String = name.ifBlank { humanizeKey(sportId) }

private val POINT_KEYS = listOf("points", "runs", "rounds")

/** Which number inside an event holds its score value; falls back to "points". */
fun eventPointsKey(event: JsonObject?): String = POINT_KEYS.firstOrNull { event?.get(it) is JsonPrimitive } ?: "points"

/** The score value of an event, from whichever of points / runs / rounds it carries. */
fun eventPoints(event: JsonObject?): Int =
    (event?.get(eventPointsKey(event)) as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0

fun eventLabel(key: String, event: JsonObject?): String =
    (event?.get("label") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: humanizeKey(key)

fun eventNeedsPlayer(event: JsonObject?): Boolean = (event?.get("needs_player") as? JsonPrimitive)?.booleanOrNull ?: false

/** The points key a new event of this sport should use: what its existing events use. */
fun sportPointsKey(sport: SportDefinitionDto): String =
    sport.scoringEvents.values.firstNotNullOfOrNull { it as? JsonObject }?.let(::eventPointsKey) ?: "points"

/** Builds the stored event object, keeping every other field the existing event had (flags, etc.). */
fun buildEvent(existing: JsonObject?, pointsKey: String, points: Int, label: String, needsPlayer: Boolean): JsonObject {
    val base = LinkedHashMap<String, JsonElement>()
    existing?.forEach { (k, v) -> base[k] = v }
    base[pointsKey] = JsonPrimitive(points)
    base["label"] = JsonPrimitive(label.trim())
    base["needs_player"] = JsonPrimitive(needsPlayer)
    return JsonObject(base)
}

/** Adds or replaces one scoring event, keeping order; the whole sport definition is what the server stores. */
fun SportDefinitionDto.withEvent(key: String, event: JsonObject): SportDefinitionDto {
    val m = LinkedHashMap<String, JsonElement>(scoringEvents)
    m[key] = event
    return copy(scoringEvents = JsonObject(m))
}

fun SportDefinitionDto.withoutEvent(key: String): SportDefinitionDto =
    copy(scoringEvents = JsonObject(scoringEvents.filterKeys { it != key }))

const val MAX_EVENT_POINTS = 99

fun validateEventKey(key: String, existingKeys: Set<String>, editing: Boolean): FieldProblem? = when {
    key.isBlank() -> FieldProblem.REQUIRED
    key.length > 40 -> FieldProblem.TOO_LONG
    !isValidEventKey(key) -> FieldProblem.BAD_FORMAT
    !editing && key in existingKeys -> FieldProblem.DUPLICATE
    else -> null
}

fun validateEventLabel(label: String): FieldProblem? = when {
    label.isBlank() -> FieldProblem.REQUIRED
    label.trim().length > 30 -> FieldProblem.TOO_LONG
    else -> null
}

/** Suggests a snake_case code from a label: "Free throw" -> "free_throw". */
fun suggestEventKey(label: String): String =
    label.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').let { if (it.firstOrNull()?.isDigit() == true) "e_$it" else it }.take(40)

/** Rows of "name: value" for the periods block, in the order the server sent them. */
fun periodRows(periods: JsonObject): List<Pair<String, String>> =
    periods.entries.map { (k, v) -> humanizeKey(k) to jsonDisplay(v) }

/** "none" / "extra_time" -> readable text from data; the overtime policy name plus any extra settings. */
fun overtimeText(overtime: JsonObject): String {
    val policy = (overtime["policy"] as? JsonPrimitive)?.contentOrNull ?: return ""
    val extras = overtime.entries.filter { it.key != "policy" }.joinToString(", ") { (k, v) -> "${humanizeKey(k)} ${jsonDisplay(v)}" }
    return if (extras.isEmpty()) humanizeKey(policy) else "${humanizeKey(policy)} ($extras)"
}

// ---------- Access rules (G12) ----------

/** One action with the scope each role has ("none" when the role has no rule). */
data class AccessRow(val action: String, val scopes: Map<String, String>)

const val SCOPE_NONE = "none"
val SCOPE_OPTIONS = listOf(SCOPE_NONE, "any", "same_tenant", "own_league", "own_club", "own_club_player", "self")

fun buildAccessRows(policies: List<AccessPolicyDto>): List<AccessRow> {
    val order = LinkedHashSet<String>()
    policies.forEach { order.add(it.action) }
    return order.map { action ->
        AccessRow(action, ACCESS_ROLES.associateWith { role ->
            policies.firstOrNull { it.role == role && it.action == action }?.scope ?: SCOPE_NONE
        })
    }
}

fun accessRowsToPolicies(rows: List<AccessRow>): List<AccessPolicyDto> =
    rows.flatMap { row ->
        ACCESS_ROLES.mapNotNull { role ->
            row.scopes[role]?.takeIf { it != SCOPE_NONE }?.let { AccessPolicyDto(role, row.action, it) }
        }
    }

fun List<AccessRow>.withScope(action: String, role: String, scope: String): List<AccessRow> =
    map { if (it.action == action) it.copy(scopes = it.scopes + (role to scope)) else it }

/** The super admin column is fixed so nobody can lock themselves out. */
fun canEditAccessCell(role: String): Boolean = role != "super_admin"

fun accessChangeCount(original: List<AccessRow>, edited: List<AccessRow>): Int {
    val before = original.associateBy { it.action }
    return edited.sumOf { row -> ACCESS_ROLES.count { role -> before[row.action]?.scopes?.get(role) != row.scopes[role] } }
}

// ---------- Audit (G13 / G14 / G15) ----------

/** Quick filter chips on the audit list: the action category (text before the first dot). */
val AUDIT_CATEGORIES = listOf("roster", "score", "user", "fixture", "order", "platform")

fun auditCategory(action: String): String = action.substringBefore('.')

/**
 * "10:42" today, "Yesterday" for the day before, otherwise "17 Oct". Time of day is included for the last two days.
 */
fun formatAuditWhen(
    at: Instant,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    yesterdayLabel: String,
    locale: Locale = Locale.getDefault(),
): String {
    val day = at.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(at)
    return when {
        day == today -> time
        day == today.minusDays(1) -> "$yesterdayLabel, $time"
        else -> DateTimeFormatter.ofPattern("d MMM, ", locale).withZone(zone).format(at) + time
    }
}

fun formatAuditFull(at: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", locale).withZone(zone).format(at)

fun parseAuditInstant(raw: String): Instant? = try {
    Instant.parse(raw)
} catch (_: Exception) {
    null
}

/** Parses "2026-10-17" (also with "/" or "."); null when it is not a real date. */
fun parseDateInput(raw: String): LocalDate? {
    val t = raw.trim().replace('/', '-').replace('.', '-')
    return try {
        LocalDate.parse(t)
    } catch (_: Exception) {
        null
    }
}

enum class DateRangeProblem { FROM_INVALID, TO_INVALID, FROM_AFTER_TO }

/** Both dates are optional; blank means "no limit". */
fun validateDateRange(from: String, to: String): DateRangeProblem? {
    val f = if (from.isBlank()) null else parseDateInput(from) ?: return DateRangeProblem.FROM_INVALID
    val t = if (to.isBlank()) null else parseDateInput(to) ?: return DateRangeProblem.TO_INVALID
    if (f != null && t != null && f.isAfter(t)) return DateRangeProblem.FROM_AFTER_TO
    return null
}

data class DiffLine(val key: String, val before: String?, val after: String?)

private fun flatten(prefix: String, e: JsonElement?, out: MutableMap<String, String>) {
    when (e) {
        null, JsonNull -> if (prefix.isNotEmpty()) out[prefix] = ""
        is JsonObject -> e.forEach { (k, v) -> flatten(if (prefix.isEmpty()) k else "$prefix.$k", v, out) }
        is JsonPrimitive -> out[prefix] = e.content
        else -> out[prefix] = e.toString()
    }
}

/** Changed (or added / removed) fields between the before and after snapshot of an audit entry. */
fun diffJson(before: JsonElement?, after: JsonElement?): List<DiffLine> {
    val b = LinkedHashMap<String, String>()
    val a = LinkedHashMap<String, String>()
    flatten("", before, b)
    flatten("", after, a)
    val keys = LinkedHashSet<String>().apply { addAll(b.keys); addAll(a.keys) }
    return keys.mapNotNull { k ->
        val old = b[k]
        val new = a[k]
        if (old == new) null else DiffLine(k, old, new)
    }
}

// ---------- System status (G16) ----------

enum class CheckState { OK, SLOW, STARTING, DOWN }

const val SLOW_THRESHOLD_MS = 1500L

fun classifyLatency(ms: Long): CheckState = if (ms > SLOW_THRESHOLD_MS) CheckState.SLOW else CheckState.OK

/** The server's /health answers {"status":"ok"}; anything else (or a 503) means it is not ready. */
fun classifyHealth(status: String?, ms: Long): CheckState =
    if (status.equals("ok", ignoreCase = true)) classifyLatency(ms) else CheckState.STARTING

/** Builds a tiny JSON object for tests and fakes. */
fun jsonOf(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
