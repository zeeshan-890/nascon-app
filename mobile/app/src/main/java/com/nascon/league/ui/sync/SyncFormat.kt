package com.nascon.league.ui.sync

import com.nascon.league.core.network.ConnState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/** Pure helpers for the sync status screen and the connectivity banner (no Android types, JVM-testable). */

enum class ResultKind { APPLIED, DUPLICATE, REJECTED }

/** Maps the stored sync result status to a display kind. Anything unknown is treated as "not applied". */
fun resultKind(status: String): ResultKind = when (status.lowercase(Locale.ROOT)) {
    "applied" -> ResultKind.APPLIED
    "duplicate" -> ResultKind.DUPLICATE
    else -> ResultKind.REJECTED
}

data class BatchCounts(val saved: Int, val notApplied: Int, val alreadySaved: Int) {
    val total: Int get() = saved + notApplied + alreadySaved
}

/** Counts statuses (applied -> saved, rejected/unknown -> not applied, duplicate -> already saved). */
fun countResults(statuses: List<String>): BatchCounts {
    var saved = 0
    var notApplied = 0
    var already = 0
    for (s in statuses) {
        when (resultKind(s)) {
            ResultKind.APPLIED -> saved++
            ResultKind.DUPLICATE -> already++
            ResultKind.REJECTED -> notApplied++
        }
    }
    return BatchCounts(saved, notApplied, already)
}

/** Results whose syncedAt is within this window of the newest result belong to the same sync batch. */
const val BATCH_WINDOW_MS: Long = 5_000L

/**
 * Summary rule: the "latest sync batch" is every result whose syncedAt is within [windowMs] of the
 * newest syncedAt (a batch is stored together, so its rows share a near-identical stamp).
 * Older results are history and are not counted in the summary line.
 */
fun <T> latestBatch(items: List<T>, windowMs: Long = BATCH_WINDOW_MS, syncedAt: (T) -> Long): List<T> {
    if (items.isEmpty()) return emptyList()
    val max = items.maxOf(syncedAt)
    return items.filter { max - syncedAt(it) <= windowMs }
}

/** Reference English wording; the UI uses the same shape from strings_f9.xml. */
fun summaryLine(c: BatchCounts): String =
    "${c.saved} saved, ${c.notApplied} not applied, ${c.alreadySaved} already saved."

private fun stamp(locale: Locale, zone: ZoneId): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(zone)

/** Local-zone, locale-aware stamp from a UTC ISO instant; null if unparseable. */
fun formatQueuedAt(
    iso: String,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String? {
    val instant = try {
        Instant.parse(iso)
    } catch (e: DateTimeParseException) {
        return null
    }
    return stamp(locale, zone).format(instant)
}

fun formatSyncedAt(
    epochMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = stamp(locale, zone).format(Instant.ofEpochMilli(epochMs))

enum class BannerKind { ONLINE, OFFLINE, SERVER_UNREACHABLE, PENDING, SYNCING }

data class BannerModel(val kind: BannerKind, val pendingCount: Int)

/** Which banner wording applies. The pending count is only the headline while online. */
fun bannerModel(state: ConnState, pendingCount: Int): BannerModel {
    val n = pendingCount.coerceAtLeast(0)
    val kind = when (state) {
        ConnState.SYNCING -> BannerKind.SYNCING
        ConnState.OFFLINE -> BannerKind.OFFLINE
        ConnState.SERVER_UNREACHABLE -> BannerKind.SERVER_UNREACHABLE
        ConnState.ONLINE -> if (n > 0) BannerKind.PENDING else BannerKind.ONLINE
    }
    return BannerModel(kind, n)
}

/** Reference English text for a banner model (matches strings_f9.xml). */
fun bannerText(m: BannerModel): String = when (m.kind) {
    BannerKind.ONLINE -> "Online"
    BannerKind.OFFLINE -> "Offline — changes are saved on this device"
    BannerKind.SERVER_UNREACHABLE -> "Server unreachable — showing saved data"
    BannerKind.PENDING ->
        if (m.pendingCount == 1) "1 change waiting to sync" else "${m.pendingCount} changes waiting to sync"
    BannerKind.SYNCING -> "Syncing…"
}
