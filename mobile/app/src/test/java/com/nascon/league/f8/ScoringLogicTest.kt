package com.nascon.league.f8

import com.nascon.league.core.db.PendingActionEntity
import com.nascon.league.data.api.dto.MatchEventDto
import com.nascon.league.data.api.dto.SportConfigDto
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import com.nascon.league.domain.model.User
import com.nascon.league.ui.scoring.ControlsMode
import com.nascon.league.ui.scoring.ReasonCheck
import com.nascon.league.ui.scoring.ScoringLogic
import com.nascon.league.ui.scoring.TapDebouncer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringLogicTest {
    private fun cfg(drawAllowed: Boolean? = null, discipline: JsonElement? = null) = SportConfigDto(
        scoringEvents = buildJsonObject {
            put("alpha_one", buildJsonObject { put("points", 2) })
            put("beta", buildJsonObject { put("points", 5) })
            put("gamma", buildJsonObject { put("label", "x") })
        },
        drawAllowed = drawAllowed,
        discipline = discipline,
    )

    private fun session(role: Role, leagues: List<String> = emptyList()) =
        Session(User(userId = "u", email = "e", role = role, leagueIds = leagues))

    private fun ev(seq: Int, id: String, type: String, payload: JsonObject = JsonObject(emptyMap()), compensated: Boolean = false) =
        MatchEventDto(sequence = seq, eventId = id, type = type, payload = payload, compensated = compensated)

    private fun pending(type: String, payload: JsonObject, id: String = "a") = PendingActionEntity(
        actionId = id, type = "score_event", queuedAt = "2026-01-01T00:00:00Z", baseVersion = 0,
        payloadJson = buildJsonObject {
            put("event_id", id); put("match_id", "m"); put("type", type); put("payload", payload)
        }.toString(),
        entityKey = "m", label = "l", ownerUserId = "u", status = "PENDING", createdAt = 0L,
    )

    @Test fun buttonsFromConfigInOrder() {
        val b = ScoringLogic.scoreButtons(cfg())
        assertEquals(listOf("alpha_one", "beta", "gamma"), b.map { it.eventKey })
        assertEquals(listOf("Alpha one", "Beta", "Gamma"), b.map { it.label })
        assertEquals(listOf<Int?>(2, 5, null), b.map { it.points })
        assertTrue(ScoringLogic.scoreButtons(null).isEmpty())
        assertTrue(ScoringLogic.scoreButtons(SportConfigDto()).isEmpty())
    }

    @Test fun panelGating() {
        assertFalse(ScoringLogic.canShowPanel(null, "L1"))
        for (r in listOf(Role.VIEWER, Role.CLUB_MANAGER, Role.PLAYER)) {
            assertFalse(ScoringLogic.canShowPanel(session(r, listOf("L1")), "L1"))
        }
        val org = session(Role.LEAGUE_ORGANIZER, listOf("L1"))
        assertTrue(ScoringLogic.canShowPanel(org, "L1"))
        assertFalse(ScoringLogic.canShowPanel(org, "L2"))
        assertFalse(ScoringLogic.canShowPanel(org, null))
        assertTrue(ScoringLogic.canShowPanel(session(Role.SUPER_ADMIN), "L9"))
        assertTrue(ScoringLogic.canShowPanel(session(Role.SUPER_ADMIN), null))
    }

    @Test fun controlsModes() {
        assertEquals(ControlsMode.START, ScoringLogic.controlsMode("upcoming"))
        assertEquals(ControlsMode.START, ScoringLogic.controlsMode("scheduled"))
        assertEquals(ControlsMode.SCORE, ScoringLogic.controlsMode("in_progress"))
        for (s in listOf("completed", "postponed", "cancelled", "abandoned", "weird", null)) {
            assertEquals(ControlsMode.READ_ONLY, ScoringLogic.controlsMode(s))
        }
    }

    @Test fun payloadShapes() {
        assertEquals(buildJsonObject { put("team_id", "t"); put("event_type", "alpha") }, ScoringLogic.scorePayload("t", "alpha"))
        assertEquals(buildJsonObject { put("team_id", "t"); put("points", 3) }, ScoringLogic.disciplinePayload("t", 3))
        assertEquals(buildJsonObject { put("team_id", "t") }, ScoringLogic.tiebreakPayload("t"))
        assertTrue(ScoringLogic.emptyPayload().isEmpty())
        assertEquals(
            buildJsonObject { put("compensates", "e1"); put("reason", "typo") },
            ScoringLogic.compensationPayload("e1", "  typo "),
        )
    }

    @Test fun reasonValidation() {
        assertEquals(ReasonCheck.Empty, ScoringLogic.validateReason(""))
        assertEquals(ReasonCheck.Empty, ScoringLogic.validateReason("   "))
        assertEquals(ReasonCheck.Ok("x"), ScoringLogic.validateReason("  x  "))
        assertEquals(ReasonCheck.Ok("a".repeat(200)), ScoringLogic.validateReason("a".repeat(200)))
        assertEquals(ReasonCheck.TooLong, ScoringLogic.validateReason("a".repeat(201)))
        assertEquals(ReasonCheck.Ok("a".repeat(200)), ScoringLogic.validateReason(" " + "a".repeat(200) + " "))
    }

    @Test fun canUndoRules() {
        val s = ev(1, "e1", "score")
        assertTrue(ScoringLogic.canUndo(s, listOf(s)))
        assertFalse(ScoringLogic.canUndo(ev(2, "e2", "score", compensated = true), emptyList()))
        val comp = ev(3, "e3", "compensation", buildJsonObject { put("compensates", "e1") })
        assertFalse(ScoringLogic.canUndo(s, listOf(s, comp)))
        assertFalse(ScoringLogic.canUndo(ev(4, "e4", "match_start"), emptyList()))
        assertFalse(ScoringLogic.canUndo(comp, listOf(comp)))
        assertTrue(ScoringLogic.canUndo(ev(5, "e5", "discipline"), emptyList()))
        assertTrue(ScoringLogic.canUndo(ev(6, "e6", "tiebreak_winner"), emptyList()))
    }

    @Test fun tiebreak() {
        assertTrue(ScoringLogic.tiebreakAvailable(cfg(drawAllowed = false), 2, 2))
        assertFalse(ScoringLogic.tiebreakAvailable(cfg(drawAllowed = true), 2, 2))
        assertFalse(ScoringLogic.tiebreakAvailable(cfg(drawAllowed = false), 2, 3))
        assertFalse(ScoringLogic.tiebreakAvailable(cfg(), 2, 2))
        assertFalse(ScoringLogic.tiebreakAvailable(null, 2, 2))
    }

    @Test fun discipline() {
        assertFalse(ScoringLogic.supportsDiscipline(cfg()))
        assertFalse(ScoringLogic.supportsDiscipline(null))
        val c = cfg(discipline = buildJsonObject { put("points", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(3)))) })
        assertTrue(ScoringLogic.supportsDiscipline(c))
        assertEquals(listOf(1, 3), ScoringLogic.disciplineOptions(c))
        assertTrue(ScoringLogic.disciplineOptions(cfg()).isEmpty())
    }

    @Test fun labels() {
        assertEquals("Add score for Team A", ScoringLogic.label("score", "Team A", "alpha"))
        assertEquals("Start match", ScoringLogic.label("match_start", null, null))
        assertEquals("End match", ScoringLogic.label("match_end", null, null))
        assertEquals("Undo event #3", ScoringLogic.label("compensation", null, "3"))
    }

    @Test fun optimistic() {
        val p = listOf(
            pending("score", ScoringLogic.scorePayload("H", "alpha_one"), "a1"),
            pending("score", ScoringLogic.scorePayload("A", "beta"), "a2"),
            pending("score", ScoringLogic.scorePayload("H", "unknown"), "a3"),
        )
        val r = ScoringLogic.optimisticScores("H", "A", 10, 20, p, cfg())
        assertEquals(12, r.home); assertEquals(25, r.away); assertEquals(3, r.pendingCount); assertTrue(r.hasPending)
        val none = ScoringLogic.optimisticScores("H", "A", 1, 2, emptyList(), cfg())
        assertEquals(1, none.home); assertFalse(none.hasPending)
    }

    @Test fun optimisticCompensation() {
        val target = ev(1, "e1", "score", ScoringLogic.scorePayload("H", "beta"))
        val comp = pending("compensation", ScoringLogic.compensationPayload("e1", "oops"))
        assertEquals(0, ScoringLogic.optimisticScores("H", "A", 5, 0, listOf(comp), cfg(), listOf(target)).home)
        assertEquals(0, ScoringLogic.optimisticScores("H", "A", 3, 0, listOf(comp), cfg(), listOf(target)).home)
        assertEquals(5, ScoringLogic.optimisticScores("H", "A", 5, 0, listOf(comp), cfg(), emptyList()).home)
    }

    @Test fun debouncer() {
        var t = 1000L
        val d = TapDebouncer(400) { t }
        assertTrue(d.allow("a"))
        assertFalse(d.allow("a"))
        assertTrue(d.allow("b"))
        t += 399
        assertFalse(d.allow("a"))
        t += 1
        assertTrue(d.allow("a"))
    }
}
