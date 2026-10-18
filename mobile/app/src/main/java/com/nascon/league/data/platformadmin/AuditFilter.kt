package com.nascon.league.data.platformadmin

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Audit log filter. [action] is either a full action key or a category (the part before the first dot);
 * [from] / [to] are inclusive calendar days.
 */
data class AuditFilter(
    val actor: String? = null,
    val action: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
) {
    val isActive: Boolean get() = actor != null || action != null || from != null || to != null
}

/** Same rule the fake applies and the backend is asked to implement (see contract_additions/platformadmin.md). */
fun AuditEntryDto.matches(filter: AuditFilter, zone: ZoneId = ZoneId.of("UTC")): Boolean {
    if (filter.actor != null && actorId != filter.actor) return false
    val a = filter.action
    if (a != null && !(action == a || action.startsWith("$a."))) return false
    if (filter.from != null || filter.to != null) {
        val day = try {
            Instant.parse(at).atZone(zone).toLocalDate()
        } catch (_: Exception) {
            return false
        }
        if (filter.from != null && day.isBefore(filter.from)) return false
        if (filter.to != null && day.isAfter(filter.to)) return false
    }
    return true
}
