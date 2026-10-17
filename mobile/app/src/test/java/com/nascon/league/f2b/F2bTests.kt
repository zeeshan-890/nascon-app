package com.nascon.league.f2b

import com.nascon.league.core.time.RelativeTime
import com.nascon.league.core.time.UpdatedAge
import com.nascon.league.data.realtime.Backoff
import com.nascon.league.data.realtime.SubscriptionRegistry
import com.nascon.league.data.realtime.parseFrame
import com.nascon.league.data.realtime.throttleFirst
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class RelativeTimeTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private fun age(secondsAgo: Long) = RelativeTime.age(
        now.minusSeconds(secondsAgo), now, ZoneId.of("UTC"), Locale.ENGLISH,
    )

    @Test fun underOneMinuteIsJustNow() {
        assertEquals(UpdatedAge.JustNow, age(0))
        assertEquals(UpdatedAge.JustNow, age(59))
    }

    @Test fun negativeIsJustNow() {
        assertEquals(UpdatedAge.JustNow, age(-300))
    }

    @Test fun minutes() {
        assertEquals(UpdatedAge.MinutesAgo(1), age(60))
        assertEquals(UpdatedAge.MinutesAgo(3), age(185))
        assertEquals(UpdatedAge.MinutesAgo(59), age(59 * 60 + 59))
    }

    @Test fun hours() {
        assertEquals(UpdatedAge.HoursAgo(1), age(3600))
        assertEquals(UpdatedAge.HoursAgo(23), age(23 * 3600 + 3599))
    }

    @Test fun absoluteAfterADay() {
        val a = RelativeTime.age(
            Instant.parse("2026-10-15T15:30:00Z"), now, ZoneId.of("UTC"), Locale.ENGLISH,
        )
        assertTrue(a is UpdatedAge.Absolute)
        assertEquals("15 Oct, 3:30 PM", (a as UpdatedAge.Absolute).formatted.replace(' ', ' '))
    }

    @Test fun absoluteUsesZone() {
        val a = RelativeTime.age(
            Instant.parse("2026-10-15T15:30:00Z"), now, ZoneId.of("Asia/Karachi"), Locale.ENGLISH,
        ) as UpdatedAge.Absolute
        assertEquals("15 Oct, 8:30 PM", a.formatted.replace(' ', ' '))
    }
}

class BackoffTest {
    @Test fun schedule() {
        val got = (0..5).map { Backoff.delayMs(it) }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 15000L, 15000L), got)
    }

    @Test fun hugeAttemptCapped() {
        assertEquals(15000L, Backoff.delayMs(500))
        assertEquals(1000L, Backoff.delayMs(-3))
    }

    @Test fun jitterBounded() {
        for (attempt in 0..6) {
            val base = Backoff.delayMs(attempt)
            for (j in listOf(0.0, 0.3, 0.999, 1.0)) {
                val d = Backoff.delayMs(attempt, j)
                assertTrue(d >= base)
                assertTrue(d <= base + (base * 0.2).toLong())
            }
        }
    }
}

class FrameParserTest {
    @Test fun validFrame() {
        val m = parseFrame("""{"type":"match.event_appended","match_id":"m1","data":{"sequence":3}}""")
        assertNotNull(m)
        assertEquals("match.event_appended", m!!.type)
        assertEquals("m1", m.matchId)
        assertNotNull(m.data)
    }

    @Test fun allKnownTypes() {
        listOf("match.event_appended", "match.status_changed", "product.pinned", "standings.updated").forEach {
            assertNotNull(parseFrame("""{"type":"$it"}"""))
        }
    }

    @Test fun missingOptionalFields() {
        val m = parseFrame("""{"type":"standings.updated"}""")!!
        assertNull(m.matchId)
        assertNull(m.data)
    }

    @Test fun malformedIgnored() {
        assertNull(parseFrame("not json"))
        assertNull(parseFrame(""))
        assertNull(parseFrame("[1,2]"))
        assertNull(parseFrame("""{"type":"""))
    }

    @Test fun unknownOrMissingTypeIgnored() {
        assertNull(parseFrame("""{"type":"something.else","match_id":"m1"}"""))
        assertNull(parseFrame("""{"match_id":"m1"}"""))
        assertNull(parseFrame("""{"type":5}"""))
    }

    @Test fun wrongFieldTypesTolerated() {
        val m = parseFrame("""{"type":"match.status_changed","match_id":7,"data":"x"}""")!!
        assertNull(m.matchId)
        assertNull(m.data)
    }
}

class SubscriptionRegistryTest {
    @Test fun refCounting() {
        val r = SubscriptionRegistry()
        assertTrue(r.add("match:1"))
        assertFalse(r.add("match:1"))
        assertFalse(r.remove("match:1"))
        assertTrue(r.has("match:1"))
        assertTrue(r.remove("match:1"))
        assertTrue(r.isEmpty())
    }

    @Test fun removeUnknownIsHarmless() {
        val r = SubscriptionRegistry()
        assertFalse(r.remove("x"))
        assertTrue(r.isEmpty())
    }

    @Test fun replayListHasEachActiveTopicOnce() {
        val r = SubscriptionRegistry()
        r.add("match:1"); r.add("match:2"); r.add("match:1")
        assertEquals(listOf("match:1", "match:2"), r.activeTopics())
        r.remove("match:2")
        assertEquals(listOf("match:1"), r.activeTopics())
    }
}

class ThrottleTest {
    @Test fun dropsItemsInsideWindow() = runBlocking {
        val times = longArrayOf(0, 100, 999, 1000, 1500, 2100)
        var i = 0
        val out = flowOf(1, 2, 3, 4, 5, 6).throttleFirst(1000) { times[i++] }.toList()
        assertEquals(listOf(1, 4, 6), out)
    }

    @Test fun firstItemAlwaysEmitted() = runBlocking {
        val out = flowOf("a").throttleFirst(1000) { 0L }.toList()
        assertEquals(listOf("a"), out)
    }
}
