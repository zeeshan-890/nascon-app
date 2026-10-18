package com.nascon.league.costream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoStreamLogicTest {

    private fun ps(n: Int, hostIndex: Int = 0) =
        (0 until n).map { CoParticipant("u$it", "User$it", isHost = it == hostIndex) }

    private fun session(
        status: String = "active",
        host: String? = "u0",
        parts: List<CoParticipant> = ps(2),
        layout: String = "grid_2",
    ) = CoSession("s1", null, status, host, layout, parts, null, null, "chat-s1", null)

    @Test fun solo_with_any_count_is_one_tile() {
        for (n in 1..5) assertEquals(1, CoStreamLogic.tiles(CoLayout.SOLO, ps(n)).size)
    }

    @Test fun grid2_counts() {
        assertEquals(listOf(1, 2, 2, 2, 2), (1..5).map { CoStreamLogic.tiles(CoLayout.GRID_2, ps(it)).size })
    }

    @Test fun grid4_counts() {
        assertEquals(listOf(1, 2, 3, 4, 4), (1..5).map { CoStreamLogic.tiles(CoLayout.GRID_4, ps(it)).size })
    }

    @Test fun spotlight_counts_never_exceed_four() {
        assertEquals(listOf(1, 2, 3, 4, 4), (1..5).map { CoStreamLogic.tiles(CoLayout.SPOTLIGHT, ps(it)).size })
    }

    @Test fun never_more_than_four_for_any_layout() {
        for (l in CoLayout.values()) for (n in 0..9) assertTrue(CoStreamLogic.tiles(l, ps(n)).size <= 4)
    }

    @Test fun kicked_are_excluded() {
        val p = listOf(
            CoParticipant("a", "A", true),
            CoParticipant("b", "B", false, kicked = true),
            CoParticipant("c", "C", false),
        )
        assertEquals(listOf("a", "c"), CoStreamLogic.tiles(CoLayout.GRID_4, p).map { it.userId })
    }

    @Test fun host_comes_first_even_if_listed_last() {
        val p = listOf(CoParticipant("a", "A", false), CoParticipant("h", "H", true))
        assertEquals("h", CoStreamLogic.tiles(CoLayout.GRID_2, p).first().userId)
    }

    @Test fun spotlight_first_tile_large_rest_small() {
        val t = CoStreamLogic.tiles(CoLayout.SPOTLIGHT, ps(4))
        assertTrue(t[0].large)
        assertTrue(t.drop(1).none { it.large })
    }

    @Test fun non_spotlight_has_no_large_tile() {
        assertTrue(CoStreamLogic.tiles(CoLayout.GRID_4, ps(4)).none { it.large })
    }

    @Test fun muted_flag_carried_to_tile() {
        val t = CoStreamLogic.tiles(CoLayout.GRID_2, listOf(CoParticipant("a", "A", true, muted = true)))
        assertTrue(t[0].muted)
    }

    @Test fun layout_strings_case_insensitive() {
        assertEquals(CoLayout.SOLO, CoStreamLogic.layoutOf("SOLO"))
        assertEquals(CoLayout.GRID_2, CoStreamLogic.layoutOf("Grid_2"))
        assertEquals(CoLayout.GRID_4, CoStreamLogic.layoutOf("grid_4"))
        assertEquals(CoLayout.SPOTLIGHT, CoStreamLogic.layoutOf("spotlight"))
    }

    @Test fun unknown_layout_derives_from_count() {
        assertEquals(1, CoStreamLogic.tilesFor("weird", ps(1)).size)
        assertEquals(2, CoStreamLogic.tilesFor(null, ps(2)).size)
        assertEquals(3, CoStreamLogic.tilesFor("", ps(3)).size)
        assertEquals(4, CoStreamLogic.tilesFor("x", ps(6)).size)
    }

    @Test fun banner_host_change_uses_display_name() {
        val prev = session(host = "u0")
        val next = session(host = "u1")
        assertEquals("Host changed to User1", CoStreamLogic.handoffBanner(prev, next))
    }

    @Test fun banner_host_change_falls_back_to_id() {
        val next = session(host = "zz")
        assertEquals("Host changed to zz", CoStreamLogic.handoffBanner(session(host = "u0"), next))
    }

    @Test fun banner_same_host_null() {
        assertNull(CoStreamLogic.handoffBanner(session(), session()))
    }

    @Test fun banner_null_prev_null() {
        assertNull(CoStreamLogic.handoffBanner(null, session()))
    }

    @Test fun banner_pending() {
        assertEquals("Waiting for a new host", CoStreamLogic.handoffBanner(null, session(status = "handoff_pending", host = null)))
    }

    @Test fun banner_prev_host_null_no_change_banner() {
        assertNull(CoStreamLogic.handoffBanner(session(host = null), session(host = "u1")))
    }

    @Test fun ended_when_terminated() {
        assertTrue(CoStreamLogic.isEnded(session(status = "terminated")))
    }

    @Test fun not_ended_when_active_even_if_empty() {
        assertFalse(CoStreamLogic.isEnded(session(parts = emptyList())))
    }

    @Test fun ended_when_all_kicked_and_not_active() {
        val p = listOf(CoParticipant("a", "A", true, kicked = true))
        assertTrue(CoStreamLogic.isEnded(session(status = "handoff_pending", parts = p)))
    }

    @Test fun phase_mapping() {
        assertEquals(CoPhase.ACTIVE, CoStreamLogic.phaseOf("active"))
        assertEquals(CoPhase.HANDOFF_PENDING, CoStreamLogic.phaseOf("handoff_pending"))
        assertEquals(CoPhase.TERMINATED, CoStreamLogic.phaseOf("terminated"))
        assertEquals(CoPhase.ACTIVE, CoStreamLogic.phaseOf("???"))
        assertEquals(CoPhase.ACTIVE, CoStreamLogic.phaseOf(null))
    }

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun json_primary_fields() {
        val s = CoStreamJson.session(
            obj(
                """{"session_id":"s9","title":"T","status":"active","active_host":"p1","layout":"spotlight",
                "participants":[{"user_id":"p1","display_name":"Ann","is_host":true,"muted":true},
                {"user_id":"p2","display_name":"Bob","kicked":false}],
                "composite_playback_url":"https://x/a.m3u8","poster_url":"https://x/p.jpg","chat_room_id":"room1","vod_url":"https://x/v.mp4"}""",
            ),
            "fb",
        )!!
        assertEquals("s9", s.sessionId)
        assertEquals("T", s.title)
        assertEquals("room1", s.chatRoomId)
        assertEquals("https://x/a.m3u8", s.playbackUrl)
        assertEquals("https://x/v.mp4", s.vodUrl)
        assertTrue(s.participants[0].muted)
        assertTrue(s.participants[0].isHost)
    }

    @Test fun json_aliases() {
        val s = CoStreamJson.session(
            obj("""{"active_host":"p2","participants":[{"player_id":"p1","name":"Ann"},{"id":"p2","name":"Bob","host":false}],"hls_url":"h"}"""),
            "fb",
        )!!
        assertEquals("fb", s.sessionId)
        assertEquals("chat-fb", s.chatRoomId)
        assertEquals("h", s.playbackUrl)
        assertEquals("Ann", s.participants[0].displayName)
        assertFalse(s.participants[0].isHost)
        assertTrue(s.participants[1].isHost)
        assertEquals("active", s.status)
    }

    @Test fun json_playback_url_alias() {
        val s = CoStreamJson.session(obj("""{"playback_url":"pb","status":"terminated"}"""), "fb")!!
        assertEquals("pb", s.playbackUrl)
        assertEquals("terminated", s.status)
    }

    @Test fun json_empty_is_null() {
        assertNull(CoStreamJson.session(obj("{}"), "fb"))
    }

    @Test fun json_skips_bad_participants() {
        val s = CoStreamJson.session(obj("""{"participants":[1,{"name":"x"},{"user_id":"ok"}]}"""), "fb")!!
        assertEquals(listOf("ok"), s.participants.map { it.userId })
        assertEquals("ok", s.participants[0].displayName)
    }
}
