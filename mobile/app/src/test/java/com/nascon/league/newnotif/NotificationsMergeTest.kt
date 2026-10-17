package com.nascon.league.newnotif

import com.nascon.league.core.db.SyncResultEntity
import com.nascon.league.data.receipts.ReceiptEntry
import com.nascon.league.ui.notifications.NotificationKind
import com.nascon.league.ui.notifications.mergeNotifications
import com.nascon.league.ui.notifications.relativeTime
import com.nascon.league.ui.notifications.shortOrderRef
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class NotificationsMergeTest {
    private val utc = ZoneId.of("UTC")
    private fun res(id: String, status: String, at: Long, reason: String? = null) =
        SyncResultEntity(id, "u", "t", "Label $id", status, null, reason, 0L, 0, at)

    @Test
    fun mergesNewestFirstAndMapsKinds() {
        val t0 = Instant.parse("2026-10-17T10:00:00Z").toEpochMilli()
        val out = mergeNotifications(
            listOf(res("a", "applied", t0 - 1000), res("b", "rejected", t0 - 5000, "TRANSFER_WINDOW_CLOSED"), res("c", "duplicate", t0 - 9000)),
            listOf(ReceiptEntry("order-abc123def", 450000, "2026-10-17T09:59:58Z")),
            t0,
        )
        assertEquals(listOf("r_a", "o_order-abc123def", "r_b", "r_c"), out.map { it.id }.let { it })
        assertEquals(NotificationKind.SAVED, out[0].kind)
        assertEquals(NotificationKind.ORDER_PAID, out[1].kind)
        assertEquals(NotificationKind.NOT_APPLIED, out[2].kind)
        assertEquals(NotificationKind.ALREADY_SAVED, out[3].kind)
        assertTrue(out[2].detail.contains("transfer window"))
        assertTrue(!out[2].detail.contains("TRANSFER_WINDOW_CLOSED"))
    }

    @Test
    fun orderRefAndUnknownTotalAndBadDate() {
        assertEquals("#ABC123", shortOrderRef("x-abc123"))
        val out = mergeNotifications(
            emptyList(),
            listOf(ReceiptEntry("o1", -1, "2026-10-17T09:00:00Z"), ReceiptEntry("o2", 100, "garbage")),
            0L,
        )
        assertEquals(1, out.size)
        org.junit.Assert.assertNull(out[0].totalMinor)
    }

    @Test
    fun relativeTimes() {
        val now = Instant.parse("2026-10-17T10:00:00Z").toEpochMilli()
        fun r(iso: String) = relativeTime(Instant.parse(iso).toEpochMilli(), now, utc, Locale.US)
        assertEquals("Just now", r("2026-10-17T09:59:30Z"))
        assertEquals("10 min ago", r("2026-10-17T09:50:00Z"))
        assertEquals("3 h ago", r("2026-10-17T07:00:00Z"))
        assertEquals("Yesterday", r("2026-10-16T23:00:00Z"))
        assertTrue(r("2026-10-10T10:00:00Z").contains("2026"))
    }
}
