package com.nascon.league.f7ui

import com.nascon.league.ui.roster.MoveType
import com.nascon.league.ui.roster.RosterDraft
import com.nascon.league.ui.roster.RosterDraftReducer
import com.nascon.league.ui.roster.SavedKind
import com.nascon.league.ui.roster.ROSTER_REASON_MAX
import com.nascon.league.ui.roster.dateToPickerMillis
import com.nascon.league.ui.roster.formatRosterDate
import com.nascon.league.ui.roster.pickerMillisToDate
import com.nascon.league.ui.roster.requestedByFor
import com.nascon.league.ui.roster.savedKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class RosterUiHelpersTest {
    private val day = LocalDate.of(2026, 10, 20)

    @Test fun savedKindOfflineWinsForAllTypes() {
        MoveType.values().forEach { assertEquals(SavedKind.OFFLINE, savedKind(it, online = false)) }
    }

    @Test fun savedKindOnlinePerType() {
        assertEquals(SavedKind.TRANSFER, savedKind(MoveType.TRANSFER, true))
        assertEquals(SavedKind.LOAN, savedKind(MoveType.LOAN, true))
        assertEquals(SavedKind.RELEASE, savedKind(MoveType.RELEASE, true))
    }

    @Test fun dateFormatIsReadable() {
        assertEquals("20 Oct 2026", formatRosterDate(day, Locale.ENGLISH))
    }

    @Test fun pickerMillisRoundTripsInAnyZone() {
        for (zone in listOf(ZoneId.of("UTC"), ZoneId.of("Asia/Karachi"), ZoneId.of("America/Los_Angeles"))) {
            assertEquals(day, pickerMillisToDate(dateToPickerMillis(day, zone), zone))
        }
        // default (UTC) matches the picker contract
        assertEquals(day, pickerMillisToDate(dateToPickerMillis(day)))
    }

    @Test fun releaseClearsDestinationAndLoanEnd() {
        val d = RosterDraft(MoveType.LOAN, "sq2", day, day.plusDays(30), "why")
        val r = RosterDraftReducer.withType(d, MoveType.RELEASE)
        assertNull(r.toSquadId)
        assertNull(r.loanEndDate)
        assertEquals("why", r.reason)
        assertNull(r.toForm().toSquadId)
    }

    @Test fun switchingToTransferKeepsSquadDropsLoanEnd() {
        val d = RosterDraft(MoveType.LOAN, "sq2", day, day.plusDays(30), "x")
        val t = RosterDraftReducer.withType(d, MoveType.TRANSFER)
        assertEquals("sq2", t.toSquadId)
        assertNull(t.loanEndDate)
    }

    @Test fun formDropsLoanEndUnlessLoan() {
        val d = RosterDraft(MoveType.TRANSFER, "sq2", day, day.plusDays(5), "x")
        assertNull(d.toForm().loanEndDate)
        assertEquals(day.plusDays(5), d.copy(type = MoveType.LOAN).toForm().loanEndDate)
    }

    @Test fun reasonIsCappedAtMax() {
        val d = RosterDraftReducer.withReason(RosterDraftReducer.start(MoveType.TRANSFER, day), "a".repeat(250))
        assertEquals(ROSTER_REASON_MAX, d.reason.length)
        assertEquals(day, d.effectiveDate)
    }

    @Test fun requestedByPrefersUserIdThenEmail() {
        assertEquals("u1", requestedByFor("u1", "a@b.c"))
        assertEquals("a@b.c", requestedByFor(" ".repeat(0), "a@b.c"))
        assertEquals("a@b.c", requestedByFor(null, "a@b.c"))
        assertEquals("", requestedByFor(null, null))
    }
}
