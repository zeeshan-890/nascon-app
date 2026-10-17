package com.nascon.league.f3

import com.nascon.league.domain.model.Fixture
import com.nascon.league.ui.fixtures.FixtureFilter
import com.nascon.league.ui.fixtures.FixtureGroup
import com.nascon.league.ui.fixtures.FixtureTime
import com.nascon.league.ui.fixtures.StatusKind
import com.nascon.league.ui.fixtures.groupAndSort
import com.nascon.league.ui.fixtures.pickLeague
import com.nascon.league.ui.fixtures.statusKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class FixtureLogicTest {
    private fun fx(id: String, status: String, at: String?) = Fixture(
        matchId = id, leagueId = "L", homeTeamId = "h", homeName = "Home",
        awayTeamId = "a", awayName = "Away", status = status, scheduledAt = at,
        venueName = null, homeScore = null, awayScore = null, version = 1,
    )

    @Test fun statusMappingIsDefensive() {
        assertEquals(StatusKind.UPCOMING, statusKind("scheduled"))
        assertEquals(StatusKind.UPCOMING, statusKind("upcoming"))
        assertEquals(StatusKind.LIVE, statusKind("in_progress"))
        assertEquals(StatusKind.LIVE, statusKind("LIVE"))
        assertEquals(StatusKind.COMPLETED, statusKind("completed"))
        assertEquals(StatusKind.POSTPONED, statusKind("postponed"))
        assertEquals(StatusKind.ABANDONED, statusKind("abandoned"))
        assertEquals(StatusKind.CANCELLED, statusKind("cancelled"))
        assertEquals(StatusKind.UNKNOWN, statusKind("weird"))
        assertEquals(StatusKind.UNKNOWN, statusKind(null))
    }

    @Test fun groupsInFixedOrderAndAllSixStatuses() {
        val list = listOf(
            fx("c", "completed", "2026-10-01T10:00:00Z"),
            fx("p", "postponed", "2026-10-02T10:00:00Z"),
            fx("u", "scheduled", "2026-10-20T10:00:00Z"),
            fx("l", "in_progress", "2026-10-17T10:00:00Z"),
            fx("a", "abandoned", null),
            fx("x", "cancelled", "2026-10-03T10:00:00Z"),
        )
        val sections = groupAndSort(list)
        assertEquals(
            listOf(FixtureGroup.LIVE, FixtureGroup.UPCOMING, FixtureGroup.COMPLETED, FixtureGroup.OTHER),
            sections.map { it.group },
        )
        assertEquals(listOf("p", "x", "a"), sections.last().items.map { it.matchId })
    }

    @Test fun upcomingAscendingCompletedDescendingNullLast() {
        val list = listOf(
            fx("u3", "scheduled", null),
            fx("u2", "scheduled", "2026-10-22T10:00:00Z"),
            fx("u1", "scheduled", "2026-10-20T10:00:00Z"),
            fx("c1", "completed", "2026-10-01T10:00:00Z"),
            fx("c3", "completed", null),
            fx("c2", "completed", "2026-10-05T10:00:00Z"),
        )
        val s = groupAndSort(list)
        assertEquals(listOf("u1", "u2", "u3"), s[0].items.map { it.matchId })
        assertEquals(listOf("c2", "c1", "c3"), s[1].items.map { it.matchId })
    }

    @Test fun tiesKeepInputOrder() {
        val t = "2026-10-20T10:00:00Z"
        val list = listOf(fx("b", "scheduled", t), fx("a", "scheduled", t), fx("c", "scheduled", t))
        assertEquals(listOf("b", "a", "c"), groupAndSort(list)[0].items.map { it.matchId })
        val done = listOf(fx("b", "completed", t), fx("a", "completed", t))
        assertEquals(listOf("b", "a"), groupAndSort(done)[0].items.map { it.matchId })
    }

    @Test fun filtersRestrictGroups() {
        val list = listOf(fx("l", "live", null), fx("u", "upcoming", null), fx("c", "completed", null), fx("p", "postponed", null))
        assertEquals(listOf(FixtureGroup.LIVE), groupAndSort(list, FixtureFilter.LIVE).map { it.group })
        assertEquals(listOf(FixtureGroup.UPCOMING), groupAndSort(list, FixtureFilter.UPCOMING).map { it.group })
        assertEquals(listOf(FixtureGroup.COMPLETED), groupAndSort(list, FixtureFilter.COMPLETED).map { it.group })
        assertEquals(4, groupAndSort(list, FixtureFilter.ALL).size)
        assertEquals(emptyList<Any>(), groupAndSort(emptyList(), FixtureFilter.LIVE))
    }

    @Test fun filterFromKey() {
        assertEquals(FixtureFilter.LIVE, FixtureFilter.fromKey("live"))
        assertEquals(FixtureFilter.ALL, FixtureFilter.fromKey(null))
        assertEquals(FixtureFilter.ALL, FixtureFilter.fromKey("nonsense"))
    }

    @Test fun timeFormatsInSeveralZones() {
        val raw = "2026-10-17T10:30:00Z"
        assertEquals("Sat 17 Oct, 3:30 PM", FixtureTime.format(raw, ZoneId.of("Asia/Karachi"), Locale.ENGLISH))
        assertEquals("Sat 17 Oct, 10:30 AM", FixtureTime.format(raw, ZoneId.of("UTC"), Locale.ENGLISH))
        assertEquals("Sat 17 Oct, 6:30 AM", FixtureTime.format(raw, ZoneId.of("America/New_York"), Locale.ENGLISH))
        assertEquals("Sun 18 Oct, 12:30 AM", FixtureTime.format(raw, ZoneId.of("Pacific/Kiritimati"), Locale.ENGLISH))
    }

    @Test fun invalidOrNullTimeFallsBack() {
        val z = ZoneId.of("UTC")
        assertEquals("Time to be confirmed", FixtureTime.format(null, z, Locale.ENGLISH))
        assertEquals("Time to be confirmed", FixtureTime.format("", z, Locale.ENGLISH))
        assertEquals("Time to be confirmed", FixtureTime.format("not a date", z, Locale.ENGLISH))
        assertEquals("TBC", FixtureTime.format("garbage", z, Locale.ENGLISH, fallback = "TBC"))
        assertNull(FixtureTime.parse("2026-13-45T99:00:00Z"))
    }

    @Test fun pickLeagueRules() {
        val ids = listOf("a", "b", "c")
        assertEquals("b", pickLeague(ids, chosen = "b", persisted = "c", sessionLeagueIds = listOf("a")))
        assertEquals("c", pickLeague(ids, chosen = null, persisted = "c", sessionLeagueIds = listOf("a")))
        assertEquals("b", pickLeague(ids, chosen = "zzz", persisted = null, sessionLeagueIds = listOf("x", "b")))
        assertEquals("a", pickLeague(ids, null, null, emptyList()))
        assertNull(pickLeague(emptyList(), "a", "a", listOf("a")))
    }
}
