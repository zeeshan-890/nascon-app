package com.nascon.league.matchlive

import com.nascon.league.data.realtime.LiveMessage
import com.nascon.league.matchlive.MatchLiveLogic.StatusKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchLiveLogicTest {

    private fun snap(version: Long = 5, home: Int? = 1, away: Int? = 2, status: String = "in_progress") =
        MatchSnapshot("m1", status, "A", "B", home, away, version = version)

    private fun msg(type: String, data: JsonObject?) = LiveMessage(null, null, null, type, "m1", data)

    // score
    @Test fun scoreZero() = assertEquals("0 - 0", MatchLiveLogic.formatScore(0, 0))
    @Test fun scoreNulls() = assertEquals("0 - 0", MatchLiveLogic.formatScore(null, null))
    @Test fun scoreOneNull() = assertEquals("3 - 0", MatchLiveLogic.formatScore(3, null))
    @Test fun scoreTwoDigits() = assertEquals("10 - 12", MatchLiveLogic.formatScore(10, 12))
    @Test fun scoreBig() = assertEquals("2147483647 - 100000", MatchLiveLogic.formatScore(Int.MAX_VALUE, 100000))

    // price
    @Test fun price450000() = assertEquals("PKR 4,500.00", MatchLiveLogic.formatPrice(450000))
    @Test fun priceZero() = assertEquals("PKR 0.00", MatchLiveLogic.formatPrice(0))
    @Test fun priceOne() = assertEquals("PKR 0.01", MatchLiveLogic.formatPrice(1))
    @Test fun price99() = assertEquals("PKR 0.99", MatchLiveLogic.formatPrice(99))
    @Test fun price100() = assertEquals("PKR 1.00", MatchLiveLogic.formatPrice(100))
    @Test fun priceBig() = assertEquals("PKR 1,234,567.89", MatchLiveLogic.formatPrice(123456789))
    @Test fun priceNegative() = assertEquals("-PKR 1.00", MatchLiveLogic.formatPrice(-100))
    @Test fun priceThousandBoundary() = assertEquals("PKR 999.99", MatchLiveLogic.formatPrice(99999))
    @Test fun priceExactThousand() = assertEquals("PKR 1,000.00", MatchLiveLogic.formatPrice(100000))

    // status
    @Test fun statusLive() = assertEquals(StatusKind.LIVE, MatchLiveLogic.statusInfo("in_progress").kind)
    @Test fun statusLiveText() = assertEquals("LIVE", MatchLiveLogic.statusInfo("in_progress").text)
    @Test fun statusUpcoming() = assertEquals(MatchLiveLogic.StatusInfo(StatusKind.UPCOMING, "Upcoming"), MatchLiveLogic.statusInfo("upcoming"))
    @Test fun statusCompleted() = assertEquals(MatchLiveLogic.StatusInfo(StatusKind.COMPLETED, "Completed"), MatchLiveLogic.statusInfo("completed"))
    @Test fun statusPostponed() = assertEquals(MatchLiveLogic.StatusInfo(StatusKind.POSTPONED, "Postponed"), MatchLiveLogic.statusInfo("postponed"))
    @Test fun statusCancelled() = assertEquals(MatchLiveLogic.StatusInfo(StatusKind.CANCELLED, "Cancelled"), MatchLiveLogic.statusInfo("cancelled"))
    @Test fun statusAbandoned() = assertEquals(MatchLiveLogic.StatusInfo(StatusKind.ABANDONED, "Abandoned"), MatchLiveLogic.statusInfo("abandoned"))
    @Test fun statusMixedCaseWhitespace() = assertEquals(StatusKind.LIVE, MatchLiveLogic.statusInfo("  In_Progress \n").kind)
    @Test fun statusNull() = assertEquals(MatchLiveLogic.StatusInfo(StatusKind.UNKNOWN, "Status unknown"), MatchLiveLogic.statusInfo(null))
    @Test fun statusUnknown() = assertEquals(StatusKind.UNKNOWN, MatchLiveLogic.statusInfo("weird").kind)
    @Test fun statusEmpty() = assertEquals(StatusKind.UNKNOWN, MatchLiveLogic.statusInfo("").kind)

    // applyEvent
    @Test fun statusChangedUpdatesStatus() {
        val r = MatchLiveLogic.applyEvent(snap(), msg("match.status_changed", buildJsonObject { put("status", "completed") }))
        assertEquals("completed", r.snapshot.status)
        assertEquals(5L, r.snapshot.version)
        assertFalse(r.needsRefetch)
    }

    @Test fun statusChangedMissingStatusIgnored() {
        val cur = snap()
        val r = MatchLiveLogic.applyEvent(cur, msg("match.status_changed", buildJsonObject { }))
        assertEquals(cur, r.snapshot)
        assertFalse(r.needsRefetch)
    }

    @Test fun statusChangedWrongTypeIgnored() {
        val cur = snap()
        val r = MatchLiveLogic.applyEvent(cur, msg("match.status_changed", buildJsonObject { put("status", 7) }))
        assertEquals(cur, r.snapshot)
    }

    @Test fun statusChangedNullData() {
        val cur = snap()
        assertEquals(cur, MatchLiveLogic.applyEvent(cur, msg("match.status_changed", null)).snapshot)
    }

    private fun snapshotData(version: Long?, h: Int?, a: Int?, status: String? = "in_progress") = buildJsonObject {
        put("match_id", "m1")
        if (status != null) put("status", status)
        if (version != null) put("version", version)
        if (h != null) putJsonObject("home") { put("score", h) }
        if (a != null) putJsonObject("away") { put("score", a) }
    }

    @Test fun snapshotNewerApplied() {
        val r = MatchLiveLogic.applyEvent(snap(), msg("match.snapshot", snapshotData(6, 3, 4, "completed")))
        assertEquals(3, r.snapshot.homeScore)
        assertEquals(4, r.snapshot.awayScore)
        assertEquals("completed", r.snapshot.status)
        assertEquals(6L, r.snapshot.version)
        assertFalse(r.needsRefetch)
    }

    @Test fun snapshotSameVersionApplied() {
        val r = MatchLiveLogic.applyEvent(snap(), msg("match.snapshot", snapshotData(5, 9, 9)))
        assertEquals(9, r.snapshot.homeScore)
    }

    @Test fun snapshotOlderIgnored() {
        val cur = snap()
        val r = MatchLiveLogic.applyEvent(cur, msg("match.snapshot", snapshotData(4, 9, 9)))
        assertEquals(cur, r.snapshot)
        assertFalse(r.needsRefetch)
    }

    @Test fun snapshotNoVersionApplied() {
        val r = MatchLiveLogic.applyEvent(snap(), msg("match.snapshot", snapshotData(null, 7, 8)))
        assertEquals(7, r.snapshot.homeScore)
        assertEquals(5L, r.snapshot.version)
    }

    @Test fun snapshotMissingScoresKeepsOld() {
        val r = MatchLiveLogic.applyEvent(snap(), msg("match.snapshot", snapshotData(6, null, 4)))
        assertEquals(1, r.snapshot.homeScore)
        assertEquals(4, r.snapshot.awayScore)
    }

    @Test fun snapshotWrongTypesDoNotThrow() {
        val data = buildJsonObject {
            put("version", "abc")
            put("home", "x")
            putJsonObject("away") { put("score", "many") }
            put("status", 3)
        }
        val cur = snap()
        val r = MatchLiveLogic.applyEvent(cur, msg("match.snapshot", data))
        assertEquals(cur.homeScore, r.snapshot.homeScore)
        assertEquals(cur.awayScore, r.snapshot.awayScore)
        assertEquals(cur.status, r.snapshot.status)
    }

    @Test fun snapshotNullData() {
        val cur = snap()
        assertEquals(cur, MatchLiveLogic.applyEvent(cur, msg("match.snapshot", null)).snapshot)
    }

    @Test fun eventAppendedRequestsRefetchAndKeepsScore() {
        val r = MatchLiveLogic.applyEvent(
            snap(),
            msg("match.event_appended", buildJsonObject {
                put("event_id", "e1"); put("sequence", 3); put("version", 8); put("event_type", "goal")
            }),
        )
        assertTrue(r.needsRefetch)
        assertEquals(8L, r.snapshot.version)
        assertEquals(1, r.snapshot.homeScore)
        assertEquals(2, r.snapshot.awayScore)
    }

    @Test fun eventAppendedOlderVersionKeepsMax() {
        val r = MatchLiveLogic.applyEvent(snap(), msg("match.event_appended", buildJsonObject { put("version", 2) }))
        assertEquals(5L, r.snapshot.version)
        assertTrue(r.needsRefetch)
    }

    @Test fun eventAppendedMissingDataStillRefetches() {
        val r = MatchLiveLogic.applyEvent(snap(), msg("match.event_appended", null))
        assertTrue(r.needsRefetch)
        assertEquals(5L, r.snapshot.version)
    }

    @Test fun productPinnedRefetch() {
        val cur = snap()
        val r = MatchLiveLogic.applyEvent(cur, msg("product.pinned", buildJsonObject { put("product_id", "p") }))
        assertTrue(r.needsRefetch)
        assertEquals(cur, r.snapshot)
    }

    @Test fun unknownTypeUnchanged() {
        val cur = snap()
        val r = MatchLiveLogic.applyEvent(cur, msg("something.else", buildJsonObject { put("status", "completed") }))
        assertEquals(cur, r.snapshot)
        assertFalse(r.needsRefetch)
    }

    // countdown
    @Test fun countdownExactlyAtKickoff() {
        val c = MatchLiveLogic.countdown(1000, 1000)
        assertTrue(c.finished)
        assertEquals(0L, c.days); assertEquals(0, c.hours); assertEquals(0, c.minutes); assertEquals(0, c.seconds)
    }

    @Test fun countdownPast() {
        val c = MatchLiveLogic.countdown(5000, 1000)
        assertTrue(c.finished)
        assertEquals(0, c.seconds)
    }

    @Test fun countdown59Seconds() {
        val c = MatchLiveLogic.countdown(0, 59_000)
        assertFalse(c.finished)
        assertEquals(0L, c.days); assertEquals(0, c.hours); assertEquals(0, c.minutes); assertEquals(59, c.seconds)
    }

    @Test fun countdownDayHourMinuteSecond() {
        val ms = (86_400L + 3_600 + 60 + 1) * 1000
        val c = MatchLiveLogic.countdown(0, ms)
        assertEquals(Countdown(1, 1, 1, 1, false), c)
    }

    @Test fun countdownSubSecondRemainderNotFinished() {
        val c = MatchLiveLogic.countdown(0, 500)
        assertFalse(c.finished)
        assertEquals(0, c.seconds)
    }

    @Test fun countdownManyDays() {
        val c = MatchLiveLogic.countdown(0, 400L * 86_400_000)
        assertEquals(400L, c.days)
    }

    // iso
    @Test fun isoZ() = assertEquals(1792335600000L, MatchLiveLogic.parseIsoMillis("2026-10-18T15:00:00Z"))
    @Test fun isoOffset() = assertEquals(1792335600000L, MatchLiveLogic.parseIsoMillis("2026-10-18T20:00:00+05:00"))
    @Test fun isoMillis() = assertEquals(1792335600123L, MatchLiveLogic.parseIsoMillis("2026-10-18T15:00:00.123Z"))
    @Test fun isoNull() = assertNull(MatchLiveLogic.parseIsoMillis(null))
    @Test fun isoBlank() = assertNull(MatchLiveLogic.parseIsoMillis("   "))
    @Test fun isoGarbage() = assertNull(MatchLiveLogic.parseIsoMillis("tomorrow-ish"))

    // misc
    @Test fun chatRoomId() = assertEquals("chat-m42", MatchLiveLogic.chatRoomIdFor("m42"))
    @Test fun minuteLabelTrimmed() = assertEquals("45'", MatchLiveLogic.minuteLabel(snap().copy(minuteLabel = " 45' ")))
    @Test fun minuteLabelBlank() = assertNull(MatchLiveLogic.minuteLabel(snap().copy(minuteLabel = "  ")))
    @Test fun minuteLabelNull() = assertNull(MatchLiveLogic.minuteLabel(snap()))

    @Suppress("unused")
    private val keep = JsonPrimitive("")
}
