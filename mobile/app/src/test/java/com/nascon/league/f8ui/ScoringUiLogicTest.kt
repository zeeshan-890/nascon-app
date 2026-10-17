package com.nascon.league.f8ui

import com.nascon.league.ui.match.PendingInput
import com.nascon.league.ui.scoring.ActionKind
import com.nascon.league.ui.scoring.ControlsMode
import com.nascon.league.ui.scoring.ReasonError
import com.nascon.league.ui.scoring.ScoringLogic
import com.nascon.league.ui.scoring.ScoringMessage
import com.nascon.league.ui.scoring.ScoringSurface
import com.nascon.league.ui.scoring.dedupePendingByEventId
import com.nascon.league.ui.scoring.messageFor
import com.nascon.league.ui.scoring.reasonErrorOf
import com.nascon.league.ui.scoring.surfaceFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoringUiLogicTest {
    private fun pending(id: String, eventId: String?) = PendingInput(
        id, "score_event", "PENDING", "u1", "m1",
        if (eventId == null) "{}" else """{"event_id":"$eventId","match_id":"m1","type":"score","payload":{}}""",
        1L,
    )

    @Test fun nonOrganizerNeverSeesPanel() {
        for (mode in ControlsMode.values()) {
            assertEquals(ScoringSurface.NONE, surfaceFor(false, mode, embedded = true))
        }
        assertEquals(ScoringSurface.NOTICE, surfaceFor(false, ControlsMode.SCORE, embedded = false))
    }

    @Test fun modeMapsToSurface() {
        assertEquals(ScoringSurface.START, surfaceFor(true, ControlsMode.START, true))
        assertEquals(ScoringSurface.SCORE, surfaceFor(true, ControlsMode.SCORE, true))
        assertEquals(ScoringSurface.NONE, surfaceFor(true, ControlsMode.READ_ONLY, true))
        assertEquals(ScoringSurface.NOTICE, surfaceFor(true, ControlsMode.READ_ONLY, false))
    }

    @Test fun snackbarSelection() {
        assertEquals(ScoringMessage.SCORE_ADDED, messageFor(ActionKind.SCORE, false))
        assertEquals(ScoringMessage.SCORE_SAVED_OFFLINE, messageFor(ActionKind.SCORE, true))
        assertEquals(ScoringMessage.CHANGE_SAVED, messageFor(ActionKind.UNDO, false))
        assertEquals(ScoringMessage.CHANGE_SAVED_OFFLINE, messageFor(ActionKind.STATE, true))
    }

    @Test fun reasonErrors() {
        assertNull(reasonErrorOf(ScoringLogic.validateReason("  typo  ")))
        assertEquals(ReasonError.EMPTY, reasonErrorOf(ScoringLogic.validateReason("   ")))
        assertEquals(ReasonError.TOO_LONG, reasonErrorOf(ScoringLogic.validateReason("x".repeat(201))))
        assertNull(reasonErrorOf(ScoringLogic.validateReason("x".repeat(200))))
    }

    @Test fun dedupeByEventId() {
        val list = listOf(pending("a", "e1"), pending("b", "e2"), pending("c", null))
        assertEquals(list, dedupePendingByEventId(list, emptySet()))
        assertEquals(listOf("b", "c"), dedupePendingByEventId(list, setOf("e1")).map { it.actionId })
    }
}
