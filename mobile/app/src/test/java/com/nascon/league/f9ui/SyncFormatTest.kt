package com.nascon.league.f9ui

import com.nascon.league.core.network.ConnState
import com.nascon.league.ui.sync.BannerKind
import com.nascon.league.ui.sync.BatchCounts
import com.nascon.league.ui.sync.ResultKind
import com.nascon.league.ui.sync.bannerModel
import com.nascon.league.ui.sync.bannerText
import com.nascon.league.ui.sync.countResults
import com.nascon.league.ui.sync.formatQueuedAt
import com.nascon.league.ui.sync.latestBatch
import com.nascon.league.ui.sync.resultKind
import com.nascon.league.ui.sync.summaryLine
import java.time.ZoneId
import java.util.Locale
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class SyncFormatTest {
    @Test
    fun resultKindMapping() {
        assertEquals(ResultKind.APPLIED, resultKind("applied"))
        assertEquals(ResultKind.DUPLICATE, resultKind("duplicate"))
        assertEquals(ResultKind.REJECTED, resultKind("rejected"))
        assertEquals(ResultKind.REJECTED, resultKind("something_new"))
    }

    @Test
    fun summaryCountsAndText() {
        val c = countResults(listOf("applied", "applied", "rejected"))
        assertEquals(BatchCounts(2, 1, 0), c)
        assertEquals("2 saved, 1 not applied, 0 already saved.", summaryLine(c))
        assertEquals("0 saved, 0 not applied, 1 already saved.", summaryLine(countResults(listOf("duplicate"))))
    }

    @Test
    fun latestBatchUsesNewestWindowOnly() {
        val items = listOf(100_000L, 99_000L, 50_000L, 49_999L)
        assertEquals(listOf(100_000L, 99_000L), latestBatch(items) { it })
        assertTrue(latestBatch(emptyList<Long>()) { it }.isEmpty())
    }

    @Test
    fun queuedTimeUsesInjectedZoneAndLocale() {
        val utc = formatQueuedAt("2026-10-20T10:40:00Z", ZoneId.of("UTC"), Locale.US)
        val karachi = formatQueuedAt("2026-10-20T10:40:00Z", ZoneId.of("Asia/Karachi"), Locale.US)
        assertNotNull(utc)
        assertNotNull(karachi)
        assertTrue(utc!!.contains("Oct 20, 2026"))
        assertTrue(utc!!.contains("10:40"))
        assertTrue(karachi!!.contains("3:40"))
        assertNull(formatQueuedAt("not a date", ZoneId.of("UTC"), Locale.US))
    }

    @Test
    fun bannerSelection() {
        assertEquals("Offline — changes are saved on this device", bannerText(bannerModel(ConnState.OFFLINE, 3)))
        assertEquals("1 change waiting to sync", bannerText(bannerModel(ConnState.ONLINE, 1)))
        assertEquals("4 changes waiting to sync", bannerText(bannerModel(ConnState.ONLINE, 4)))
        assertEquals(BannerKind.ONLINE, bannerModel(ConnState.ONLINE, 0).kind)
        assertEquals("Syncing…", bannerText(bannerModel(ConnState.SYNCING, 2)))
        assertEquals(BannerKind.SERVER_UNREACHABLE, bannerModel(ConnState.SERVER_UNREACHABLE, 2).kind)
        assertEquals(0, bannerModel(ConnState.ONLINE, -5).pendingCount)
    }
}
