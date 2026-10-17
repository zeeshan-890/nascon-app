package com.nascon.league.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nascon.league.core.db.SyncResultEntity
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.data.receipts.ReceiptEntry
import com.nascon.league.di.AppContainer
import com.nascon.league.ui.sync.ResultKind
import com.nascon.league.ui.sync.resultKind
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

enum class NotificationKind { SAVED, NOT_APPLIED, ALREADY_SAVED, ORDER_PAID }

/** [detail] is plain language already (label, label plus mapped reason, or short order reference). */
data class NotificationItem(
    val id: String,
    val kind: NotificationKind,
    val detail: String,
    /** Only for ORDER_PAID; null when the total is unknown. */
    val totalMinor: Long?,
    val timeMs: Long,
)

/** "#A1B2C3": last 6 letters/digits of the id, upper-cased. */
fun shortOrderRef(orderId: String): String {
    val clean = orderId.filter { it.isLetterOrDigit() }
    return "#" + clean.takeLast(6).uppercase(Locale.ROOT)
}

/** Pure merge, newest first; ties are ordered by id so the result is stable. */
@Suppress("UNUSED_PARAMETER")
fun mergeNotifications(
    results: List<SyncResultEntity>,
    receipts: List<ReceiptEntry>,
    now: Long,
): List<NotificationItem> {
    val fromResults = results.map { r ->
        when (resultKind(r.status)) {
            ResultKind.APPLIED -> NotificationItem("r_" + r.actionId, NotificationKind.SAVED, r.label, null, r.syncedAt)
            ResultKind.DUPLICATE -> NotificationItem("r_" + r.actionId, NotificationKind.ALREADY_SAVED, r.label, null, r.syncedAt)
            ResultKind.REJECTED -> NotificationItem(
                "r_" + r.actionId,
                NotificationKind.NOT_APPLIED,
                r.label + ": " + ErrorMapper.syncRejection(r.reasonCode),
                null,
                r.syncedAt,
            )
        }
    }
    val fromReceipts = receipts.mapNotNull { e ->
        val t = try {
            Instant.parse(e.paidAt).toEpochMilli()
        } catch (ex: DateTimeParseException) {
            return@mapNotNull null
        }
        NotificationItem("o_" + e.orderId, NotificationKind.ORDER_PAID, shortOrderRef(e.orderId), e.totalMinor.takeIf { it >= 0 }, t)
    }
    return (fromResults + fromReceipts)
        .sortedWith(compareByDescending<NotificationItem> { it.timeMs }.thenBy { it.id })
}

/** Pure relative time: "Just now", "10 min ago", "3 h ago", "Yesterday", else a local date. */
fun relativeTime(
    timeMs: Long,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val diff = now - timeMs
    if (diff < 60_000L) return "Just now"
    val days = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay() -
        Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate().toEpochDay()
    return when {
        diff < 3_600_000L -> "${diff / 60_000L} min ago"
        days <= 0L -> "${diff / 3_600_000L} h ago"
        days == 1L -> "Yesterday"
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone)
            .format(Instant.ofEpochMilli(timeMs))
    }
}

class NotificationsViewModel(container: AppContainer) : ViewModel() {
    val items: StateFlow<List<NotificationItem>> = combine(
        container.syncRepository.observeResults(),
        container.receiptStore.observeIndex(),
    ) { results, receipts -> mergeNotifications(results, receipts, System.currentTimeMillis()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { NotificationsViewModel(container) }
        }
    }
}
