package com.nascon.league.ui.player

import com.nascon.league.data.api.dto.HistoryEntryDto
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Up to two initials from a display name; "?" when empty. */
fun initialsOf(name: String?): String {
    val words = name.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase(Locale.ROOT)
        else -> (words[0].take(1) + words[1].take(1)).uppercase(Locale.ROOT)
    }
}

/** Initials from an email local part ("ayesha.khan@x" gives "AK"). */
fun initialsFromEmail(email: String?): String {
    val local = email.orEmpty().substringBefore('@')
    val parts = local.split(Regex("[._\\-+\\s]+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase(Locale.ROOT)
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase(Locale.ROOT)
    }
}

enum class HistoryKind { LOAN, TRANSFER, RELEASE, RECALL, OTHER }

fun historyKindOf(action: String): HistoryKind {
    val a = action.trim().lowercase(Locale.ROOT).replace(Regex("[\\s\\-]+"), "_")
    return when {
        a.isEmpty() -> HistoryKind.OTHER
        a.contains("recall") || a.contains("return") -> HistoryKind.RECALL
        a.contains("loan") -> HistoryKind.LOAN
        a.contains("release") || a.contains("terminate") -> HistoryKind.RELEASE
        a.contains("transfer") || a.contains("move") || a.contains("join") ||
            a.contains("register") || a.contains("sign") || a.contains("promot") -> HistoryKind.TRANSFER
        else -> HistoryKind.OTHER
    }
}

private val UUID_LIKE =
    Regex("^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$")

/** True for strings that look like machine identifiers and must not be shown to people. */
fun looksLikeId(s: String?): Boolean {
    val t = s?.trim().orEmpty()
    if (t.isEmpty()) return false
    if (UUID_LIKE.matches(t)) return true
    return t.length >= 24 && !t.contains(' ') && t.any { it.isDigit() } &&
        t.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}

/** Text safe to show, or null when blank or id-like. */
fun displayable(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && !looksLikeId(it) }

private val DATE_PREFIX = Regex("^\\d{4}-\\d{2}-\\d{2}")

fun parseHistoryDate(raw: String?): LocalDate? {
    val t = raw?.trim().orEmpty()
    if (!DATE_PREFIX.containsMatchIn(t)) return null
    return try {
        LocalDate.parse(t.take(10))
    } catch (_: Exception) {
        null
    }
}

fun formatHistoryDate(raw: String?, locale: Locale = Locale.getDefault()): String? =
    parseHistoryDate(raw)?.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))

fun HistoryEntryDto.sortDate(): LocalDate? = parseHistoryDate(effectiveDate) ?: parseHistoryDate(whenAt)

/** Newest first; entries without a date keep server order after the dated ones. */
fun sortedHistory(entries: List<HistoryEntryDto>): List<HistoryEntryDto> {
    val dated = entries.filter { it.sortDate() != null }.sortedByDescending { it.sortDate() }
    return dated + entries.filter { it.sortDate() == null }
}

enum class HistoryStateKind { PENDING, REJECTED, OTHER }

/** [text] is empty for PENDING / REJECTED (the UI uses string resources). */
data class HistoryStateLabel(val kind: HistoryStateKind, val text: String)

/** Null when the entry is simply completed (no chip needed). */
fun historyStateLabel(state: String?): HistoryStateLabel? {
    val s = state?.trim()?.lowercase(Locale.ROOT)?.replace(Regex("[\\s\\-]+"), "_").orEmpty()
    return when {
        s.isEmpty() -> null
        s in setOf("approved", "applied", "complete", "completed", "done", "accepted", "active", "success") -> null
        s.startsWith("pending") || s == "queued" || s == "submitted" ->
            HistoryStateLabel(HistoryStateKind.PENDING, "")
        s in setOf("rejected", "denied", "declined", "failed", "cancelled", "canceled") ->
            HistoryStateLabel(HistoryStateKind.REJECTED, "")
        else -> HistoryStateLabel(
            HistoryStateKind.OTHER,
            s.split('_').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } },
        )
    }
}

/** Index of the entry that represents the current squad, or -1. Input must be newest-first. */
fun currentEntryIndex(sorted: List<HistoryEntryDto>, currentSquadId: String?): Int {
    if (currentSquadId == null) return -1
    return sorted.indexOfFirst {
        it.toSquadId == currentSquadId && historyStateLabel(it.state)?.kind != HistoryStateKind.REJECTED
    }
}
