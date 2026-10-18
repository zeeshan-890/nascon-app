package com.nascon.league.matchlive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchJsonMapperTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject
    private fun el(s: String): JsonElement = Json.parseToJsonElement(s)
    private fun snap(s: String) = MatchJsonMapper.snapshot(obj(s), "fb")

    private val audit = """{"match_id":"m1","sport_id":"football","league_id":"lg1","status":"in_progress","outcome":null,
        "home":{"team_id":"t-home","score":2},"away":{"team_id":"t-away","score":1},"version":7,"events":[]}"""

    @Test fun auditShape_basic() {
        val s = snap(audit)!!
        assertEquals("m1", s.matchId)
        assertEquals("in_progress", s.status)
        assertEquals(2, s.homeScore)
        assertEquals(1, s.awayScore)
        assertEquals(7L, s.version)
        assertEquals("lg1", s.leagueName)
    }

    @Test fun missingNamesFallBackToIds() {
        val s = snap(audit)!!
        assertEquals("t-home", s.homeName)
        assertEquals("t-away", s.awayName)
    }

    @Test fun nameWins() {
        val s = snap("""{"status":"upcoming","home":{"team_id":"a","name":"Lions"},"away":{"team_id":"b","team_name":"Tigers"}}""")!!
        assertEquals("Lions", s.homeName)
        assertEquals("Tigers", s.awayName)
    }

    @Test fun topLevelNamesUsed() {
        val s = snap("""{"status":"upcoming","home_name":"H","away_name":"A","home":{"team_id":"a"},"away":{"team_id":"b"}}""")!!
        assertEquals("H", s.homeName)
        assertEquals("A", s.awayName)
    }

    @Test fun nullScores() {
        val s = snap("""{"status":"upcoming","home":{"team_id":"a","score":null},"away":{"team_id":"b"}}""")!!
        assertNull(s.homeScore)
        assertNull(s.awayScore)
    }

    @Test fun fallbackMatchIdUsed() {
        assertEquals("fb", snap("""{"status":"completed"}""")!!.matchId)
    }

    @Test fun missingStatusIsNull() {
        assertNull(snap("""{"match_id":"m1"}"""))
    }

    @Test fun garbageEmptyObjectIsNull() {
        assertNull(snap("{}"))
    }

    @Test fun wrongTypesDoNotThrow() {
        val s = snap("""{"status":"in_progress","home":"x","away":[1],"pinned_products":"nope","version":"abc"}""")!!
        assertNull(s.homeScore)
        assertEquals(0L, s.version)
        assertTrue(s.pinned.isEmpty())
        assertEquals("", s.homeName)
    }

    @Test fun apiMatchShapeWithPinned() {
        val s = snap(
            """{"match_id":"m2","status":"upcoming","league_name":"Premier","scheduled_at":"2026-10-20T15:00:00Z",
            "venue_name":"Arena","home":{"team_id":"h","score":0},"away":{"team_id":"a","score":0},"version":1,
            "pinned_products":[{"product_id":"p1","name":"Scarf","price_minor":150000,"image_url":"http://i/x.png",
            "variants":[{"variant_id":"v-9"}]}]}""",
        )!!
        assertEquals("Premier", s.leagueName)
        assertEquals("2026-10-20T15:00:00Z", s.scheduledAtIso)
        assertEquals(1, s.pinned.size)
        val p = s.pinned[0]
        assertEquals("p1", p.productId)
        assertEquals("v-9", p.variantId)
        assertEquals("Scarf", p.name)
        assertEquals(150000L, p.priceMinor)
        assertEquals("http://i/x.png", p.imageUrl)
    }

    @Test fun jerseyDefaultVariant() {
        val s = snap("""{"status":"upcoming","pinned_products":[{"product_id":"prod-jersey-001","name":"Jersey","price_minor":450000}]}""")!!
        assertEquals("var-jersey-001-L", s.pinned.single().variantId)
    }

    @Test fun jerseyExplicitVariantWins() {
        val s = snap("""{"status":"upcoming","pinned_products":[{"product_id":"prod-jersey-001","variant_id":"var-x"}]}""")!!
        assertEquals("var-x", s.pinned.single().variantId)
    }

    @Test fun topLevelVariantIdUsed() {
        val s = snap("""{"status":"upcoming","pinned_products":[{"product_id":"p2","variant_id":"v2","name":"Cap","price_minor":1}]}""")!!
        assertEquals("v2", s.pinned.single().variantId)
    }

    @Test fun nonJerseyWithoutVariantSkipped() {
        val s = snap("""{"status":"upcoming","pinned_products":[{"product_id":"p3","name":"Cap","price_minor":1}]}""")!!
        assertTrue(s.pinned.isEmpty())
    }

    @Test fun pinnedWithoutProductIdSkipped() {
        val s = snap("""{"status":"upcoming","pinned_products":[{"name":"x","variant_id":"v"},{"product_id":"p","variant_id":"v"},5,null]}""")!!
        assertEquals(listOf("p"), s.pinned.map { it.productId })
    }

    @Test fun pinnedNameDefaultsToProductId() {
        val s = snap("""{"status":"upcoming","pinned_products":[{"product_id":"p","variant_id":"v"}]}""")!!
        assertEquals("p", s.pinned.single().name)
        assertEquals(0L, s.pinned.single().priceMinor)
    }

    @Test fun streamUrlAlternatives() {
        assertEquals("a", snap("""{"status":"vod","stream_url":"a","playback_url":"b"}""")!!.streamUrl)
        assertEquals("b", snap("""{"status":"vod","playback_url":"b","hls_url":"c"}""")!!.streamUrl)
        assertEquals("c", snap("""{"status":"vod","hls_url":"c"}""")!!.streamUrl)
        assertNull(snap("""{"status":"vod"}""")!!.streamUrl)
    }

    @Test fun posterRecapReason() {
        val s = snap("""{"status":"postponed","poster_url":"p.jpg","recap":"Good game","reason":"Rain"}""")!!
        assertEquals("p.jpg", s.posterUrl)
        assertEquals("Good game", s.recapText)
        assertEquals("Rain", s.reason)
    }

    @Test fun hugeScoreOutOfIntRangeIsNull() {
        val s = snap("""{"status":"in_progress","home":{"team_id":"a","score":99999999999},"away":{"team_id":"b","score":3}}""")!!
        assertNull(s.homeScore)
        assertEquals(3, s.awayScore)
    }

    @Test fun hugeVersionAndPrice() {
        val s = snap(
            """{"status":"upcoming","version":9007199254740993,"pinned_products":[{"product_id":"p","variant_id":"v","price_minor":9000000000000}]}""",
        )!!
        assertEquals(9007199254740993L, s.version)
        assertEquals(9000000000000L, s.pinned.single().priceMinor)
    }

    @Test fun floatWholeScoreAccepted() {
        val s = snap("""{"status":"in_progress","home":{"team_id":"a","score":3.0},"away":{"team_id":"b","score":2.5}}""")!!
        assertEquals(3, s.homeScore)
        assertNull(s.awayScore)
    }

    @Test fun chatObjectForm() {
        val l = MatchJsonMapper.chatMessages(
            el("""{"messages":[{"message_id":"a","room_id":"chat-m1","sequence":1,"sender":"u1","text":"hi","flagged":false},
            {"message_id":"b","room_id":"chat-m1","sequence":2,"sender":"u2","text":"yo","flagged":true}]}"""),
        )
        assertEquals(listOf("a", "b"), l.map { it.messageId })
        assertFalse(l[0].flagged)
        assertTrue(l[1].flagged)
        assertEquals("chat-m1", l[0].roomId)
    }

    @Test fun chatBareArrayForm() {
        val l = MatchJsonMapper.chatMessages(el("""[{"message_id":"a","sequence":5,"sender":"s","text":"t"}]"""))
        assertEquals(1, l.size)
        assertEquals(5L, l[0].sequence)
    }

    @Test fun chatMalformedEntriesSkipped() {
        val l = MatchJsonMapper.chatMessages(
            el("""[1,"x",null,{"message_id":"a","text":"no seq"},{"sequence":1,"text":"no id"},{"message_id":"c","sequence":"z","text":"t"},
            {"message_id":"ok","sequence":3,"text":"fine"}]"""),
        )
        assertEquals(listOf("ok"), l.map { it.messageId })
    }

    @Test fun chatGarbageRootsEmpty() {
        assertTrue(MatchJsonMapper.chatMessages(el("42")).isEmpty())
        assertTrue(MatchJsonMapper.chatMessages(el("\"s\"")).isEmpty())
        assertTrue(MatchJsonMapper.chatMessages(el("null")).isEmpty())
        assertTrue(MatchJsonMapper.chatMessages(el("{}")).isEmpty())
        assertTrue(MatchJsonMapper.chatMessages(el("""{"messages":"x"}""")).isEmpty())
    }

    @Test fun chatEmojiAndUrduText() {
        val m = MatchJsonMapper.chatMessage(obj("""{"message_id":"a","sequence":1,"sender":"s","text":"گول! ⚽🔥"}"""))!!
        assertEquals("گول! ⚽🔥", m.text)
    }

    @Test fun chatFlaggedDefaultsFalse() {
        val m = MatchJsonMapper.chatMessage(obj("""{"message_id":"a","sequence":1,"text":"t"}"""))!!
        assertFalse(m.flagged)
        assertEquals("", m.sender)
        assertEquals("", m.roomId)
        assertFalse(m.pending)
    }

    @Test fun chatFlaggedGarbageIsFalse() {
        val m = MatchJsonMapper.chatMessage(obj("""{"message_id":"a","sequence":1,"text":"t","flagged":"maybe"}"""))!!
        assertFalse(m.flagged)
    }

    @Test fun chatHugeSequence() {
        val m = MatchJsonMapper.chatMessage(obj("""{"message_id":"a","sequence":9223372036854775000,"text":"t"}"""))!!
        assertEquals(9223372036854775000L, m.sequence)
    }

    @Test fun chatSequenceOverflowSkipped() {
        val m = MatchJsonMapper.chatMessage(obj("""{"message_id":"a","sequence":99999999999999999999999,"text":"t"}"""))
        assertNull(m)
    }

    @Test fun chatNullTextSkipped() {
        assertNull(MatchJsonMapper.chatMessage(obj("""{"message_id":"a","sequence":1,"text":null}""")))
    }

    @Test fun chatSingleMessageParsed() {
        assertNotNull(MatchJsonMapper.chatMessage(obj("""{"message_id":"a","sequence":1,"text":"t"}""")))
    }
}
