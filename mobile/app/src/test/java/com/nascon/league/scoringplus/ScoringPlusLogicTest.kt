package com.nascon.league.scoringplus

import com.nascon.league.core.db.PendingActionEntity
import com.nascon.league.data.api.dto.MatchEventDto
import com.nascon.league.data.api.dto.SportConfigDto
import com.nascon.league.ui.scoring.ScoringLogic
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringPlusLogicTest {
    private fun cfg(drawAllowed: Boolean?) = SportConfigDto(drawAllowed = drawAllowed)

    private fun ev(id: String, type: String, compensated: Boolean = false) =
        MatchEventDto(sequence = 1, eventId = id, type = type, payload = JsonObject(emptyMap()), compensated = compensated)

    private fun pending(type: String) = PendingActionEntity(
        actionId = "p", type = "score_event", queuedAt = "2026-01-01T00:00:00Z", baseVersion = 0,
        payloadJson = buildJsonObject {
            put("event_id", "p"); put("match_id", "m"); put("type", type); put("payload", JsonObject(emptyMap()))
        }.toString(),
        entityKey = "m", label = "l", ownerUserId = "u", status = "PENDING", createdAt = 0L,
    )

    @Test fun levelWithoutDrawsRequiresTiebreak() {
        assertTrue(ScoringLogic.tiebreakRequired(cfg(false), 2, 2, recorded = false))
        assertFalse(ScoringLogic.canEndMatch(cfg(false), 2, 2))
    }

    @Test fun recordedTiebreakAllowsEnd() {
        assertFalse(ScoringLogic.tiebreakRequired(cfg(false), 2, 2, recorded = true))
        assertTrue(ScoringLogic.canEndMatch(cfg(false), 2, 2, tiebreakRecorded = true))
    }

    @Test fun drawsAllowedOrUnequalCanEnd() {
        assertTrue(ScoringLogic.canEndMatch(cfg(true), 2, 2))
        assertTrue(ScoringLogic.canEndMatch(cfg(false), 3, 2))
        assertTrue(ScoringLogic.canEndMatch(cfg(null), 1, 1))
        assertTrue(ScoringLogic.canEndMatch(null, 0, 0))
    }

    @Test fun tiebreakRecordedFromServerEvents() {
        val events = listOf(ev("a", "score"), ev("b", "tiebreak_winner"))
        assertTrue(ScoringLogic.tiebreakRecorded(events, emptyList()))
        assertFalse(ScoringLogic.tiebreakRecorded(listOf(ev("b", "tiebreak_winner", compensated = true)), emptyList()))
        assertFalse(ScoringLogic.tiebreakRecorded(listOf(ev("a", "score")), emptyList()))
    }

    @Test fun tiebreakRecordedFromPendingQueue() {
        assertTrue(ScoringLogic.tiebreakRecorded(emptyList(), listOf(pending("tiebreak_winner"))))
        assertFalse(ScoringLogic.tiebreakRecorded(emptyList(), listOf(pending("score"))))
    }

    @Test fun tieNotAllowedCode() {
        assertTrue(ScoringLogic.isTieNotAllowed("TIE_NOT_ALLOWED"))
        assertFalse(ScoringLogic.isTieNotAllowed("OTHER"))
        assertFalse(ScoringLogic.isTieNotAllowed(null))
    }

    @Test fun disciplineStepperWithOptions() {
        val opts = listOf(3, 1, 2)
        assertEquals(1, ScoringLogic.initialDiscipline(opts))
        assertEquals(2, ScoringLogic.stepDiscipline(opts, 1, 1))
        assertEquals(3, ScoringLogic.stepDiscipline(opts, 3, 1))
        assertEquals(1, ScoringLogic.stepDiscipline(opts, 1, -1))
    }

    @Test fun disciplineStepperFree() {
        assertEquals(1, ScoringLogic.initialDiscipline(emptyList()))
        assertEquals(1, ScoringLogic.stepDiscipline(emptyList(), 1, -1))
        assertEquals(5, ScoringLogic.stepDiscipline(emptyList(), 4, 1))
        assertEquals(99, ScoringLogic.stepDiscipline(emptyList(), 99, 1))
    }
}
