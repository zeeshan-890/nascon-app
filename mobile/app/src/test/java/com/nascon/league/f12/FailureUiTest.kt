package com.nascon.league.f12

import com.nascon.league.core.error.ErrorContext
import com.nascon.league.core.network.ApiError
import com.nascon.league.data.api.dto.MatchEventDto
import com.nascon.league.data.api.dto.StandingRowDto
import com.nascon.league.domain.model.Fixture
import com.nascon.league.ui.failure.FORBIDDEN_MESSAGE
import com.nascon.league.ui.failure.RATE_LIMIT_MESSAGE
import com.nascon.league.ui.failure.WRONG_PASSWORD_MESSAGE
import com.nascon.league.ui.failure.retryAfterSeconds
import com.nascon.league.ui.failure.userMessage
import com.nascon.league.ui.fixtures.dedupeFixtures
import com.nascon.league.ui.match.buildTimeline
import com.nascon.league.ui.player.formatHistoryDate
import com.nascon.league.ui.receipt.isErrorPayload
import com.nascon.league.ui.standings.dedupeStandingRows
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class FailureUiTest {
    private fun http(status: Int, code: String, message: String = "java.lang.IllegalStateException\n\tat Foo.bar", details: JsonObject? = null) =
        ApiError.Http(status, code, message, details)

    @Test fun wrongPasswordIsPlain() {
        val stack = "java.lang.IllegalStateException\n\tat Foo.bar"
        val msg = userMessage(http(401, "HTTP_401", stack), ErrorContext.Login)
        assertEquals(WRONG_PASSWORD_MESSAGE, msg)
        assertFalse(msg.contains("Exception"))
        assertFalse(msg.contains("HTTP_401"))
        assertEquals(WRONG_PASSWORD_MESSAGE, userMessage(http(401, "UNAUTHENTICATED", stack), ErrorContext.Login))
    }

    @Test fun forbiddenSentence() {
        val msg = userMessage(http(403, "HTTP_403"))
        assertEquals("You don't have permission to do that.", msg)
        assertEquals(FORBIDDEN_MESSAGE, userMessage(http(403, "FORBIDDEN", "stack trace")))
        assertFalse(msg.contains("403"))
    }

    @Test fun rateLimitSentenceIgnoresBody() {
        val msg = userMessage(http(429, "HTTP_429", "RATE_LIMITED stack"))
        assertEquals("Too many requests — try again in a few seconds.", msg)
        assertEquals(RATE_LIMIT_MESSAGE, userMessage(http(429, "RATE_LIMITED")))
        assertFalse(msg.contains("stack"))
    }

    @Test fun retryAfterComesFromDetailsOnly() {
        val details = JsonObject(mapOf("retry_after" to JsonPrimitive(4)))
        assertEquals(4, retryAfterSeconds(http(429, "RATE_LIMITED", details = details)))
        assertEquals(8, retryAfterSeconds(http(429, "HTTP_429", details = JsonObject(mapOf("retryAfterSeconds" to JsonPrimitive("8"))))))
        assertNull(retryAfterSeconds(http(429, "RATE_LIMITED")))
        assertNull(retryAfterSeconds(ApiError.Timeout))
    }

    @Test fun fixturesKeepOneRowPerMatch() {
        val older = fixture("m1", version = 1, score = 0)
        val newer = fixture("m1", version = 3, score = 2)
        val other = fixture("m2", version = 1, score = 1)
        val out = dedupeFixtures(listOf(older, other, newer))
        assertEquals(listOf("m1", "m2"), out.map { it.matchId })
        assertEquals(2, out.first().homeScore)
    }

    @Test fun timelineDropsRepeatedEventId() {
        val events = listOf(
            MatchEventDto(1, "e1", "score"),
            MatchEventDto(2, "e1", "score"),
            MatchEventDto(3, "e2", "score"),
        )
        val keys = buildTimeline(events, emptyList()) { null }.map { it.key }
        assertEquals(listOf("e-e2", "e-e1"), keys)
    }

    @Test fun standingsDropRepeatedTeam() {
        val rows = dedupeStandingRows(
            listOf(
                StandingRowDto(rank = 2, teamId = "a"),
                StandingRowDto(rank = 1, teamId = "a"),
                StandingRowDto(rank = 3, teamId = "b"),
            ),
        )
        assertEquals(listOf("a", "b"), rows.map { it.teamId })
        assertEquals(1, rows.first().rank)
    }

    @Test fun instantsUseDeviceZoneWithoutStrippingZ() {
        val raw = "2026-03-05T20:30:00Z"
        assertEquals("6 Mar 2026", formatHistoryDate(raw, Locale.ENGLISH, ZoneId.of("Asia/Karachi")))
        assertEquals("5 Mar 2026", formatHistoryDate(raw, Locale.ENGLISH, ZoneId.of("UTC")))
        assertEquals("5 Mar 2026", formatHistoryDate("2026-03-05", Locale.ENGLISH, ZoneId.of("Asia/Karachi")))
    }

    @Test fun jsonBodyIsNotAReceipt() {
        assertEquals(true, isErrorPayload("{\"error\":{\"code\":\"FORBIDDEN\"}}"))
        assertEquals(true, isErrorPayload("  [1]"))
        assertEquals(false, isErrorPayload("%PDF-1.4"))
    }

    private fun fixture(id: String, version: Int, score: Int) = Fixture(
        matchId = id, leagueId = "L", homeTeamId = "h", homeName = "Home",
        awayTeamId = "a", awayName = "Away", status = "live", scheduledAt = null,
        venueName = null, homeScore = score, awayScore = 0, version = version,
    )
}
