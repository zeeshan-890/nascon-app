package com.nascon.league.ui.club

import com.nascon.league.core.db.ActionStatus
import com.nascon.league.core.db.PendingActionEntity
import com.nascon.league.data.api.dto.RosterPlayerDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.util.Locale

// ---------- coordinates ----------

/** "33.6844, 73.0479" (4 decimals, dot separator whatever the locale). Null when unusable. */
fun formatCoordinates(lat: Double?, lng: Double?): String? {
    if (lat == null || lng == null) return null
    if (!lat.isFinite() || !lng.isFinite()) return null
    return "${round4(lat)}, ${round4(lng)}"
}

private fun round4(v: Double): String {
    val r = BigDecimal(v).setScale(4, RoundingMode.HALF_UP)
    // avoid "-0.0000"
    val fixed = if (r.signum() == 0) BigDecimal.ZERO.setScale(4) else r
    return fixed.toPlainString()
}

/** geo: URI with a labelled pin, e.g. geo:33.6844,73.0479?q=33.6844,73.0479(Alpha%20Stadium). */
fun buildGeoUri(lat: Double?, lng: Double?, label: String): String? {
    if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) return null
    val coords = "${round4(lat)},${round4(lng)}"
    val cleanLabel = label.trim().replace("(", " ").replace(")", " ").trim()
    if (cleanLabel.isEmpty()) return "geo:$coords?q=$coords"
    val encoded = URLEncoder.encode(cleanLabel, "UTF-8").replace("+", "%20")
    return "geo:$coords?q=$coords($encoded)"
}

// ---------- logo url ----------

/**
 * Absolute http(s) URLs pass through; relative ones (/static/x.png) are resolved against the
 * server base URL. Anything else (javascript:, data:, blank) gives null so the placeholder shows.
 */
fun resolveLogoUrl(raw: String?, baseUrl: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value.any { it.isWhitespace() }) return null
    val lower = value.lowercase(Locale.ROOT)
    if (lower.startsWith("http://") || lower.startsWith("https://")) {
        return if (value.substringAfter("://").isNotEmpty()) value else null
    }
    if (value.contains("://")) return null
    val base = baseUrl?.trim().orEmpty()
    if (base.isEmpty()) return null
    if (value.startsWith("//")) {
        val scheme = if (base.lowercase(Locale.ROOT).startsWith("https")) "https:" else "http:"
        return scheme + value
    }
    // "javascript:...", "data:..." and similar have a colon before any slash
    val firstSlash = value.indexOf('/').let { if (it < 0) value.length else it }
    if (value.substring(0, firstSlash).contains(':')) return null
    return base.trimEnd('/') + "/" + value.trimStart('/')
}

// ---------- socials ----------

data class SocialLink(
    val key: String,
    /** Capitalized label, e.g. "Twitter". */
    val label: String,
    val value: String,
    /** Non-null only when [value] is a web link that may be opened in a browser. */
    val url: String?,
)

fun capitalizeKey(key: String): String {
    val words = key.trim().replace('_', ' ').replace('-', ' ').split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return ""
    return words.joinToString(" ").replaceFirstChar { it.titlecase(Locale.ROOT) }
}

fun classifySocial(key: String, value: String): SocialLink? {
    val v = value.trim()
    if (v.isEmpty()) return null
    val lower = v.lowercase(Locale.ROOT)
    val isWeb = (lower.startsWith("http://") || lower.startsWith("https://")) &&
        v.substringAfter("://").isNotEmpty() && v.none { it.isWhitespace() }
    return SocialLink(key, capitalizeKey(key).ifEmpty { key.trim() }, v, if (isWeb) v else null)
}

fun socialLinks(socials: JsonObject): List<SocialLink> =
    socials.entries.mapNotNull { (k, element) ->
        val text = (element as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        classifySocial(k, text)
    }

// ---------- staff / player role labels ----------

enum class StaffRoleKind { COACH, MANAGER, PHYSIO }

/** [known] non-null means the UI uses a string resource, otherwise it shows [text]. */
data class StaffRoleLabel(val known: StaffRoleKind?, val text: String)

private val SEPARATORS = Regex("[\\s_\\-]+")

private fun normKey(raw: String): String = raw.trim().lowercase(Locale.ROOT).replace(SEPARATORS, "_")

fun titleCaseWords(raw: String): String =
    raw.trim().split(SEPARATORS).filter { it.isNotEmpty() }
        .joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }

fun staffRoleLabel(role: String): StaffRoleLabel {
    val kind = when (normKey(role)) {
        "coach", "head_coach", "assistant_coach" -> StaffRoleKind.COACH
        "manager", "team_manager", "club_manager" -> StaffRoleKind.MANAGER
        "physio", "physiotherapist" -> StaffRoleKind.PHYSIO
        else -> null
    }
    return StaffRoleLabel(kind, titleCaseWords(role))
}

enum class PlayerRoleKind { CAPTAIN, VICE_CAPTAIN, STARTER, SUBSTITUTE, NONE, OTHER }

data class PlayerRoleLabel(val kind: PlayerRoleKind, val text: String)

/** Plain "player" or blank gives NONE (no badge); unknown values keep their capitalized text. */
fun playerRoleLabel(role: String): PlayerRoleLabel {
    val kind = when (normKey(role)) {
        "captain" -> PlayerRoleKind.CAPTAIN
        "vice_captain", "vicecaptain" -> PlayerRoleKind.VICE_CAPTAIN
        "starter", "starting" -> PlayerRoleKind.STARTER
        "substitute", "sub", "bench" -> PlayerRoleKind.SUBSTITUTE
        "", "player" -> PlayerRoleKind.NONE
        else -> PlayerRoleKind.OTHER
    }
    return PlayerRoleLabel(kind, titleCaseWords(role))
}

private fun roleRank(role: String): Int = when (playerRoleLabel(role).kind) {
    PlayerRoleKind.CAPTAIN -> 0
    PlayerRoleKind.VICE_CAPTAIN -> 1
    PlayerRoleKind.STARTER -> 2
    PlayerRoleKind.SUBSTITUTE -> 4
    else -> 3
}

// ---------- roster grouping ----------

/** [position] is empty for the "unknown" group, which is always last. */
data class PositionGroup(val position: String, val players: List<RosterPlayerDto>) {
    val isUnknown: Boolean get() = position.isEmpty()
}

/**
 * Filters by squad (null = all), drops duplicate player ids, groups by position (groups appear
 * in order of first appearance, unknown position last) and sorts each group stably by role
 * (captain first) keeping server order for ties.
 */
fun groupRosterByPosition(players: List<RosterPlayerDto>, squadId: String? = null): List<PositionGroup> {
    val filtered = players
        .distinctBy { it.playerId }
        .filter { squadId == null || it.squadId == squadId }
    val order = LinkedHashMap<String, String>() // normalized -> display
    val buckets = HashMap<String, MutableList<RosterPlayerDto>>()
    for (p in filtered) {
        val display = p.position.trim()
        val norm = display.lowercase(Locale.ROOT)
        order.getOrPut(norm) { display }
        buckets.getOrPut(norm) { mutableListOf() }.add(p)
    }
    val known = order.keys.filter { it.isNotEmpty() }.map { norm ->
        PositionGroup(order.getValue(norm), buckets.getValue(norm).sortedBy { roleRank(it.role) })
    }
    val unknown = buckets[""]?.let { PositionGroup("", it.sortedBy { p -> roleRank(p.role) }) }
    return if (unknown == null) known else known + unknown
}

// ---------- pending chip ----------

/** Pending chip shows only for the current user's own queued/syncing actions on that player. */
fun showPendingChip(actions: List<PendingActionEntity>, currentUserId: String?): Boolean =
    currentUserId != null && actions.any {
        it.ownerUserId == currentUserId &&
            (it.status == ActionStatus.PENDING || it.status == ActionStatus.SYNCING)
    }
