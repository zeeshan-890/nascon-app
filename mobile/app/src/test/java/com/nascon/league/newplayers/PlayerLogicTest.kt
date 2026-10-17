package com.nascon.league.newplayers

import com.nascon.league.data.api.dto.HistoryEntryDto
import com.nascon.league.ui.player.HistoryKind
import com.nascon.league.ui.player.HistoryStateKind
import com.nascon.league.ui.player.currentEntryIndex
import com.nascon.league.ui.player.displayable
import com.nascon.league.ui.player.formatHistoryDate
import com.nascon.league.ui.player.historyKindOf
import com.nascon.league.ui.player.historyStateLabel
import com.nascon.league.ui.player.initialsFromEmail
import com.nascon.league.ui.player.initialsOf
import com.nascon.league.ui.player.sortedHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class PlayerLogicTest {
    @Test fun initials() {
        assertEquals("BA", initialsOf("Bilal Ahmed"))
        assertEquals("HA", initialsOf("hamza"))
        assertEquals("?", initialsOf("  "))
        assertEquals("AK", initialsFromEmail("ayesha.khan@club.pk"))
        assertEquals("AY", initialsFromEmail("ayesha@club.pk"))
    }

    @Test fun kinds() {
        assertEquals(HistoryKind.LOAN, historyKindOf("loan"))
        assertEquals(HistoryKind.RECALL, historyKindOf("loan_return"))
        assertEquals(HistoryKind.RELEASE, historyKindOf("Release"))
        assertEquals(HistoryKind.TRANSFER, historyKindOf("transfer"))
        assertEquals(HistoryKind.OTHER, historyKindOf(""))
    }

    @Test fun hidesIds() {
        assertNull(displayable("3f2504e0-4f89-11d3-9a0c-0305e82c3301"))
        assertNull(displayable("  "))
        assertEquals("Ayesha Khan", displayable("Ayesha Khan"))
    }

    @Test fun dates() {
        assertEquals("1 Jul 2025", formatHistoryDate("2025-07-01T10:00:00Z", Locale.ENGLISH))
        assertNull(formatHistoryDate("soon", Locale.ENGLISH))
    }

    @Test fun sortsNewestFirstAndFindsCurrent() {
        val a = HistoryEntryDto(auditId = "a", effectiveDate = "2025-06-30", toSquadId = "x")
        val b = HistoryEntryDto(auditId = "b", effectiveDate = "2026-01-14", toSquadId = "y")
        val c = HistoryEntryDto(auditId = "c")
        val sorted = sortedHistory(listOf(a, c, b))
        assertEquals(listOf("b", "a", "c"), sorted.map { it.auditId })
        assertEquals(0, currentEntryIndex(sorted, "y"))
        assertEquals(-1, currentEntryIndex(sorted, null))
    }

    @Test fun states() {
        assertNull(historyStateLabel("approved"))
        assertEquals(HistoryStateKind.PENDING, historyStateLabel("pending_approval")?.kind)
        assertEquals(HistoryStateKind.REJECTED, historyStateLabel("Rejected")?.kind)
        assertEquals("Under Review", historyStateLabel("under_review")?.text)
    }
}
