package com.nascon.league.newfixtures

import com.nascon.league.data.api.dto.MatchEventDto
import com.nascon.league.domain.model.Fixture
import com.nascon.league.ui.fixtures.splitByDay
import com.nascon.league.ui.match.Countdown
import com.nascon.league.ui.match.buildTimeline
import com.nascon.league.ui.match.countdownTo
import com.nascon.league.ui.match.eventMinute
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class NewFixturesLogicTest {
    private fun fx(id: String, at: String?) = Fixture(
        id, "L", "h", "Home", "a", "Away", "scheduled", at, null, null, null, 1,
    )

    @Test fun splitsConsecutiveFixturesByLocalDay() {
        val runs = splitByDay(
            listOf(
                fx("1", "2026-10-24T10:00:00Z"), fx("2", "2026-10-24T14:00:00Z"),
                fx("3", "2026-10-25T10:00:00Z"), fx("4", null),
            ),
            ZoneOffset.UTC,
        )
        assertEquals(3, runs.size)
        assertEquals(LocalDate.of(2026, 10, 24), runs[0].day)
        assertEquals(2, runs[0].items.size)
        assertNull(runs[2].day)
    }

    @Test fun countdownPicksCoarsestUnit() {
        val now = Instant.parse("2026-10-17T10:00:00Z")
        assertNull(countdownTo(now, null))
        assertEquals(Countdown.Soon, countdownTo(now, now.minusSeconds(60)))
        assertEquals(Countdown.Minutes(30), countdownTo(now, now.plusSeconds(30 * 60)))
        assertEquals(Countdown.Hours(5), countdownTo(now, now.plusSeconds(5 * 3600 + 60)))
        assertEquals(Countdown.Days(3), countdownTo(now, now.plusSeconds(3 * 86400 + 600)))
    }

    @Test fun eventMinuteOnlyWhenNumericAndPresent() {
        assertEquals("67", eventMinute(JsonObject(mapOf("minute" to JsonPrimitive("67")))))
        assertNull(eventMinute(JsonObject(emptyMap())))
        assertNull(eventMinute(JsonObject(mapOf("minute" to JsonPrimitive("abc")))))
    }

    @Test fun timelineCarriesTeamAndMinute() {
        val payload = JsonObject(
            mapOf("team_id" to JsonPrimitive("h"), "minute" to JsonPrimitive("12"), "event_type" to JsonPrimitive("goal")),
        )
        val t = buildTimeline(listOf(MatchEventDto(1, "e1", "score", payload)), emptyList()) { if (it == "h") "Lions" else null }
        assertEquals("Lions", t[0].teamName)
        assertEquals("12", t[0].minute)
    }
}
