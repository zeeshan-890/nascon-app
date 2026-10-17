package com.nascon.league.f3

import com.nascon.league.data.api.dto.MatchDetailDto
import com.nascon.league.data.api.dto.MatchEventDto
import com.nascon.league.data.api.dto.TeamScoreDto
import com.nascon.league.ui.match.EventLabel
import com.nascon.league.ui.match.OutcomeText
import com.nascon.league.ui.match.PendingInput
import com.nascon.league.ui.match.ScoreText
import com.nascon.league.ui.match.buildTimeline
import com.nascon.league.ui.match.eventLabel
import com.nascon.league.ui.match.formatPriceMinor
import com.nascon.league.ui.match.formatScore
import com.nascon.league.ui.match.outcomeText
import com.nascon.league.ui.match.pendingEventsFor
import com.nascon.league.ui.match.snakeToTitle
import com.nascon.league.ui.match.tiebreakTeamId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchLogicTest {
    private fun obj(vararg p: Pair<String, String>) = JsonObject(p.associate { it.first to JsonPrimitive(it.second) })
    private val names: (String?) -> String? = { when (it) { "h" -> "Lions"; "a" -> "Tigers"; else -> null } }

    // ---- snake_case -> Title Case
    @Test fun snakeToTitleCases() {
        assertEquals("Three pointer", snakeToTitle("three_pointer"))
        assertEquals("A long event name", snakeToTitle("a_long_event_name"))
        assertEquals("Foo bar", snakeToTitle("_foo__bar_"))
        assertEquals("", snakeToTitle(""))
        assertEquals("", snakeToTitle("___"))
        assertEquals("Single", snakeToTitle("single"))
    }

    // ---- event labels
    @Test fun structuralLabels() {
        val e = JsonObject(emptyMap())
        assertEquals(EventLabel.MatchInit, eventLabel("match_init", e, names))
        assertEquals(EventLabel.MatchStart, eventLabel("match_start", e, names))
        assertEquals(EventLabel.MatchEnd, eventLabel("match_end", e, names))
        assertEquals(EventLabel.TiebreakWinner("Lions"), eventLabel("tiebreak_winner", obj("team_id" to "h"), names))
        assertEquals(
            EventLabel.Discipline("2", "Tigers"),
            eventLabel("discipline", obj("team_id" to "a", "points" to "2"), names),
        )
        assertEquals(EventLabel.Compensation("wrong team"), eventLabel("compensation", obj("reason" to "wrong team"), names))
    }

    @Test fun scoreLabelUsesPayloadEventType() {
        assertEquals(
            EventLabel.Score("Three pointer", "Lions"),
            eventLabel("score", obj("event_type" to "three_pointer", "team_id" to "h"), names),
        )
        assertEquals(EventLabel.Score("Try", null), eventLabel("score", obj("event_type" to "try"), names))
    }

    @Test fun unknownTypesFallBackToGeneric() {
        assertEquals(EventLabel.Unknown, eventLabel("mystery", JsonObject(emptyMap()), names))
        assertEquals(EventLabel.Unknown, eventLabel("score", JsonObject(emptyMap()), names))
        assertEquals(EventLabel.Unknown, eventLabel("", JsonObject(emptyMap()), names))
    }

    // ---- score formatter
    @Test fun plainScore() {
        assertEquals(ScoreText("2", null, 0), formatScore(TeamScoreDto("h", score = 2)))
    }

    @Test fun wicketsAndOvers() {
        val t = TeamScoreDto("h", score = 145, wickets = 4, overs = JsonPrimitive("19.2"))
        assertEquals(ScoreText("145/4", "19.2", 0), formatScore(t))
    }

    @Test fun wicketsAndBalls() {
        val t = TeamScoreDto("h", score = 100, wickets = 3, balls = 74)
        assertEquals(ScoreText("100/3", "12.2", 0), formatScore(t))
    }

    @Test fun wicketsWithoutOversStaysPlain() {
        assertEquals("100", formatScore(TeamScoreDto("h", score = 100, wickets = 3)).main)
        assertEquals("7", formatScore(TeamScoreDto("h", score = 7, balls = 12)).main) // rounds only, no wickets
    }

    @Test fun fairPlayCarried() {
        assertEquals(3, formatScore(TeamScoreDto("h", score = 1, fairPlay = 3)).fairPlay)
        assertEquals(0, formatScore(TeamScoreDto("h", score = 1)).fairPlay)
    }

    // ---- outcome
    private fun match(
        status: String = "completed",
        outcome: String = "",
        winner: String? = null,
        tb: String? = null,
        hs: Int = 1,
        aws: Int = 1,
    ) = MatchDetailDto(
        matchId = "m", status = status, outcome = outcome, winnerTeamId = winner, tiebreakWinnerTeamId = tb,
        home = TeamScoreDto("h", score = hs), away = TeamScoreDto("a", score = aws),
    )

    @Test fun outcomeCompleted() {
        assertEquals(OutcomeText.HomeWin, outcomeText(match(outcome = "home_win", winner = "h", hs = 2)))
        assertEquals(OutcomeText.AwayWin, outcomeText(match(outcome = "away_win", winner = "a", aws = 2)))
        assertEquals(OutcomeText.Draw, outcomeText(match(outcome = "draw")))
        assertEquals(OutcomeText.ResultPending, outcomeText(match(outcome = "pending")))
        assertEquals(OutcomeText.ResultPending, outcomeText(match(outcome = "")))
        assertEquals(OutcomeText.AwayWin, outcomeText(match(outcome = "", winner = "a")))
        assertEquals(OutcomeText.NotDecided, outcomeText(match(outcome = "undecided")))
    }

    @Test fun outcomeLiveAndOthers() {
        assertEquals(OutcomeText.NotDecided, outcomeText(match(status = "in_progress")))
        assertEquals(OutcomeText.NotDecided, outcomeText(match(status = "in_progress", outcome = "undecided")))
        assertEquals(OutcomeText.None, outcomeText(match(status = "scheduled")))
        assertEquals(OutcomeText.None, outcomeText(match(status = "postponed")))
    }

    @Test fun tiebreakOnlyWhenLevel() {
        assertEquals("a", tiebreakTeamId(match(tb = "a", hs = 2, aws = 2)))
        assertNull(tiebreakTeamId(match(tb = "a", hs = 3, aws = 2)))
        assertNull(tiebreakTeamId(match(tb = null)))
    }

    @Test fun priceFormatting() {
        assertEquals("4,500.00", formatPriceMinor(450_000))
        assertEquals("0.05", formatPriceMinor(5))
        assertEquals("1,234,567.89", formatPriceMinor(123_456_789))
    }

    // ---- pending merge
    private fun pending(
        id: String,
        owner: String = "u1",
        status: String = "PENDING",
        type: String = "score_event",
        matchId: String = "m1",
        at: Long = 1,
        eventType: String = "try",
    ) = PendingInput(
        actionId = id, type = type, status = status, ownerUserId = owner, entityKey = matchId,
        payloadJson = """{"event_id":"$id","match_id":"$matchId","type":"score","payload":{"event_type":"$eventType","team_id":"h"}}""",
        createdAt = at,
    )

    @Test fun pendingMergeFiltersOwnerStatusTypeAndMatch() {
        val actions = listOf(
            pending("ok1", at = 1),
            pending("ok2", at = 5, eventType = "three_pointer"),
            pending("otherUser", owner = "u2"),
            pending("syncing", status = "SYNCING"),
            pending("applied", status = "APPLIED"),
            pending("roster", type = "roster_transfer"),
            pending("otherMatch", matchId = "m2"),
        )
        val out = pendingEventsFor("m1", "u1", actions, names)
        assertEquals(listOf("ok2", "ok1"), out.map { it.actionId })
        assertEquals(EventLabel.Score("Three pointer", "Lions"), out.first().label)
        assertTrue(pendingEventsFor("m1", null, actions, names).isEmpty())
    }

    @Test fun pendingWithMalformedPayloadFallsBackToEntityKey() {
        val a = PendingInput("x", "score_event", "PENDING", "u1", "m1", "not json", 1)
        val out = pendingEventsFor("m1", "u1", listOf(a), names)
        assertEquals(1, out.size)
        assertEquals(EventLabel.Unknown, out[0].label)
    }

    @Test fun timelinePutsPendingFirstThenNewestServerEvents() {
        val events = listOf(
            MatchEventDto(1, "e1", "match_start"),
            MatchEventDto(3, "e3", "score", obj("event_type" to "try", "team_id" to "h"), compensated = true),
            MatchEventDto(2, "e2", "score", obj("event_type" to "try", "team_id" to "a")),
        )
        val p = pendingEventsFor("m1", "u1", listOf(pending("p1")), names)
        val t = buildTimeline(events, p, names)
        assertEquals(listOf("p-p1", "e-e3", "e-e2", "e-e1"), t.map { it.key })
        assertTrue(t[0].pending)
        assertTrue(t[1].compensated)
        assertNull(t[0].sequence)
        assertEquals(3, t[1].sequence)
    }
}
