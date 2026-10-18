package com.nascon.league.data.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class LiveFrameParserTest {
    private val hub =
        """{"type":"match.event_appended","match_id":"m1","data":{"match_id":"m1","event_id":"e5","sequence":5,"version":5,"event_type":"goal"}}"""

    @Test fun hubFrameIdFromEventId() { assertEquals("e5", LiveFrameParser.parse(hub)!!.id) }
    @Test fun hubFrameSequenceFromData() { assertEquals(5L, LiveFrameParser.parse(hub)!!.sequence) }
    @Test fun hubFrameTypeAndMatch() {
        val m = LiveFrameParser.parse(hub)!!
        assertEquals("match.event_appended", m.type); assertEquals("m1", m.matchId)
    }
    @Test fun hubFrameDataKept() {
        assertEquals("goal", (LiveFrameParser.parse(hub)!!.data!!["event_type"] as JsonPrimitive).content)
    }
    @Test fun plainFrame() {
        val m = LiveFrameParser.parse("""{"type":"score","match_id":"m1","id":"x","sequence":7,"timestamp":1700000000000}""")!!
        assertEquals("x", m.id); assertEquals(7L, m.sequence); assertEquals(1700000000000L, m.timestampMs)
        assertNull(m.data)
    }
    @Test fun timestampSecondsScaled() {
        assertEquals(1700000000000L, LiveFrameParser.parse("""{"type":"a","timestamp":1700000000}""")!!.timestampMs)
    }
    @Test fun timestampMillisKept() {
        assertEquals(1700000000123L, LiveFrameParser.parse("""{"type":"a","ts":1700000000123}""")!!.timestampMs)
    }
    @Test fun timestampIso() {
        assertEquals(1700000000000L, LiveFrameParser.parse("""{"type":"a","timestamp":"2023-11-14T22:13:20Z"}""")!!.timestampMs)
    }
    @Test fun timestampBadStringNull() {
        assertNull(LiveFrameParser.parse("""{"type":"a","timestamp":"yesterday"}""")!!.timestampMs)
    }
    @Test fun timestampNegativeNull() {
        assertNull(LiveFrameParser.parse("""{"type":"a","timestamp":-5}""")!!.timestampMs)
    }
    @Test fun numericStringSequence() {
        assertEquals(42L, LiveFrameParser.parse("""{"type":"a","sequence":"42"}""")!!.sequence)
    }
    @Test fun negativeSequenceNull() {
        assertNull(LiveFrameParser.parse("""{"type":"a","sequence":-1}""")!!.sequence)
    }
    @Test fun topLevelSequenceWinsOverData() {
        assertEquals(9L, LiveFrameParser.parse("""{"type":"a","sequence":9,"data":{"sequence":3}}""")!!.sequence)
    }
    @Test fun garbageSequenceStringNull() {
        assertNull(LiveFrameParser.parse("""{"type":"a","sequence":"abc"}""")!!.sequence)
    }
    @Test fun subscribedControlFramePasses() {
        val m = LiveFrameParser.parse("""{"type":"subscribed","match_id":"m2"}""")!!
        assertEquals("subscribed", m.type); assertEquals("m2", m.matchId)
    }
    @Test fun errorControlFramePasses() {
        val m = LiveFrameParser.parse("""{"type":"error","code":4401}""")!!
        assertEquals("error", m.type)
    }
    @Test fun blankNull() { assertNull(LiveFrameParser.parse("")); assertNull(LiveFrameParser.parse("   \n")) }
    @Test fun garbageNull() { assertNull(LiveFrameParser.parse("not json {")) }
    @Test fun arrayNull() { assertNull(LiveFrameParser.parse("""[{"type":"a"}]""")) }
    @Test fun missingTypeNull() { assertNull(LiveFrameParser.parse("""{"match_id":"m1"}""")) }
    @Test fun nonStringTypeNull() { assertNull(LiveFrameParser.parse("""{"type":5}""")) }
    @Test fun sizeCapRejects() {
        val big = """{"type":"a","data":{"x":"${"a".repeat(65 * 1024)}"}}"""
        assertNull(LiveFrameParser.parse(big))
    }
    @Test fun belowCapAccepted() {
        val ok = """{"type":"a","data":{"x":"${"a".repeat(60 * 1024)}"}}"""
        assertNotNull(LiveFrameParser.parse(ok))
    }
    @Test fun hugeNumberSequenceNull() {
        assertNull(LiveFrameParser.parse("""{"type":"a","sequence":99999999999999999999999}""")!!.sequence)
    }
    @Test fun hugeNumberTimestampNull() {
        assertNull(LiveFrameParser.parse("""{"type":"a","timestamp":1e400}""")?.timestampMs)
    }
    @Test fun unicodeData() {
        val m = LiveFrameParser.parse("""{"type":"a","data":{"name":"پاکستان ⚽ 🎉"}}""")!!
        assertEquals("پاکستان ⚽ 🎉", (m.data!!["name"] as JsonPrimitive).content)
    }
    @Test fun dataNotObjectIsNull() {
        val m = LiveFrameParser.parse("""{"type":"a","data":[1,2],"match_id":"m"}""")!!
        assertNull(m.data); assertEquals("m", m.matchId)
    }
    @Test fun dataStringIsNull() { assertNull(LiveFrameParser.parse("""{"type":"a","data":"x"}""")!!.data) }
    @Test fun matchIdFromData() {
        assertEquals("q", LiveFrameParser.parse("""{"type":"a","data":{"match_id":"q"}}""")!!.matchId)
    }
    @Test fun parseSseJoinsLines() {
        val m = LiveFrameParser.parseSse(listOf("""{"type":"a",""", """"match_id":"m9"}"""))!!
        assertEquals("m9", m.matchId)
    }
    @Test fun parseSseEmptyNull() { assertNull(LiveFrameParser.parseSse(emptyList())) }
    @Test fun parseSseBlankNull() { assertNull(LiveFrameParser.parseSse(listOf(""))) }

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun pollMapperMapsVersion() {
        val j = obj("""{"match_id":"m1","status":"live","version":12,"home":{"score":2},"away":{"score":1}}""")
        val m = ScorePollMapper.fromScoreJson(j)!!
        assertEquals("poll-m1-12", m.id); assertEquals(12L, m.sequence)
        assertEquals("match.snapshot", m.type); assertEquals("m1", m.matchId)
        assertNull(m.timestampMs)
    }
    @Test fun pollMapperPreservesData() {
        val j = obj("""{"match_id":"m1","version":1,"home":{"score":2}}""")
        assertEquals(j, ScorePollMapper.fromScoreJson(j)!!.data)
    }
    @Test fun pollMapperMissingMatchId() { assertNull(ScorePollMapper.fromScoreJson(obj("""{"version":1}"""))) }
    @Test fun pollMapperMissingVersion() { assertNull(ScorePollMapper.fromScoreJson(obj("""{"match_id":"m"}"""))) }

    @Test fun neverThrowsOnRandomInput() {
        val rnd = Random(7)
        val alphabet = "{}[]\":,0123456789abtype_ \n\\-.eE\u0000é🎉"
        repeat(1000) {
            val s = buildString { repeat(rnd.nextInt(0, 80)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            LiveFrameParser.parse(s)
            LiveFrameParser.parseSse(listOf(s, s))
        }
    }
}
