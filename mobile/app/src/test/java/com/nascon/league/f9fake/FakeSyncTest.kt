package com.nascon.league.f9fake

import com.nascon.league.data.api.dto.SyncActionDto
import com.nascon.league.data.api.dto.SyncRequest
import com.nascon.league.data.api.dto.SyncResultDto
import com.nascon.league.data.fake.FakeMatches
import com.nascon.league.data.fake.FakeRoster
import com.nascon.league.data.fake.FakeSync
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeSyncTest {
    private lateinit var matches: FakeMatches
    private lateinit var roster: FakeRoster
    private lateinit var sync: FakeSync
    private var n = 0

    @Before fun setUp() {
        matches = FakeMatches(); roster = FakeRoster(); sync = FakeSync(matches, roster); n = 0
    }

    private fun at(i: Int) = "2026-10-17T10:00:%02dZ".format(i)

    private fun score(
        id: String, eventId: String = "ev-$id", match: String = "demo-m-live", team: String = "team-alpha-lions",
        key: String = "goal", base: Long? = null, queued: String = at(n++),
    ) = SyncActionDto(
        id, "score_event", queued, base ?: matches.version(match),
        buildJsonObject {
            put("event_id", eventId); put("match_id", match); put("type", "score")
            put("payload", buildJsonObject { put("team_id", team); put("event_type", key) })
        },
    )

    private fun simple(id: String, type: String, match: String, payload: JsonObject = JsonObject(emptyMap()), eventId: String = "ev-$id") =
        SyncActionDto(id, "score_event", at(n++), matches.version(match), buildJsonObject {
            put("event_id", eventId); put("match_id", match); put("type", type); put("payload", payload)
        })

    private fun transfer(
        id: String, player: String = "p2", type: String = "transfer", from: String = "sq-first", to: String? = "sq-youth",
        date: String = "2026-10-20", loanEnd: String? = null, base: Long? = null, request: String = "req-$id",
    ) = SyncActionDto(id, "roster_transfer", at(n++), base ?: roster.auditVersion(player), buildJsonObject {
        put("request_id", request); put("league_id", "league-alpha-1"); put("player_id", player)
        put("type", type); put("from_squad_id", from)
        if (to != null && type != "release") put("to_squad_id", to)
        put("effective_date", date)
        if (loanEnd != null) put("loan_end_date", loanEnd)
        put("reason", "needs"); put("requested_by", "u-manager")
    })

    private fun run(vararg a: SyncActionDto): List<SyncResultDto> = sync.sync(SyncRequest("dev", a.toList())).results

    @Test fun scoreAppliedUpdatesSharedMatchState() {
        val r = run(score("a1"))[0]
        assertEquals("applied", r.status); assertEquals("appended", r.resolution)
        assertEquals("demo-signature", r.signature); assertEquals(2L, r.serverVersion)
        val d = matches.detail("demo-m-live")!!
        assertEquals(2, d.home.score); assertEquals(2, d.events.size)
        assertEquals(2L, d.version)
        assertEquals(2, matches.fixtures().first { it.matchId == "demo-m-live" }.version)
    }

    @Test fun staleBaseVersionIsRebased() {
        val r = run(score("a1", base = 0))[0]
        assertEquals("applied", r.status); assertEquals("rebased_append", r.resolution)
    }

    @Test fun baseVersionAboveCurrentRejected() {
        val r = run(score("a1", base = 99))[0]
        assertEquals("rejected", r.status); assertEquals("server_wins", r.resolution)
        assertEquals("INVALID_BASE_VERSION", r.errorCode); assertNull(r.signature)
    }

    @Test fun resendIsAllDuplicateAndStateUnchanged() {
        val actions = arrayOf(score("a1"), transfer("t1"))
        run(*actions)
        val before = matches.detail("demo-m-live")!!
        val again = sync.sync(SyncRequest("dev", actions.toList()))
        assertTrue(again.results.all { it.status == "duplicate" && it.resolution == "skipped_duplicate" })
        assertEquals(2, again.summary.duplicate)
        assertEquals(before, matches.detail("demo-m-live"))
    }

    @Test fun sameEventIdNewActionIdIsDuplicateOrDuplicateEventId() {
        run(score("a1", eventId = "E"))
        assertEquals("duplicate", run(score("a2", eventId = "E"))[0].status)
        val diff = run(score("a3", eventId = "E", team = "team-delta-sharks"))[0]
        assertEquals("rejected", diff.status); assertEquals("DUPLICATE_EVENT_ID", diff.errorCode)
    }

    @Test fun unknownMatchAndPlayer() {
        val m = run(score("a1", match = "nope", base = 0))[0]
        assertEquals("MATCH_NOT_FOUND", m.errorCode); assertEquals(0L, m.serverVersion); assertEquals("server_wins", m.resolution)
        val p = run(transfer("t1", player = "ghost"))[0]
        assertEquals("PLAYER_NOT_FOUND", p.errorCode); assertEquals(0L, p.serverVersion)
    }

    @Test fun scoreOnNonLiveMatchRejected() {
        assertEquals("MATCH_NOT_IN_PROGRESS", run(score("a1", match = "demo-m-1"))[0].errorCode)
        assertEquals("MATCH_NOT_IN_PROGRESS", run(score("a2", match = "demo-m-5"))[0].errorCode)
    }

    @Test fun startAndEndChangeStatusAndStandings() {
        val before = matches.games().count { it.status == "completed" }
        assertEquals("applied", run(simple("s1", "match_start", "demo-m-5"))[0].status)
        assertEquals("in_progress", matches.detail("demo-m-5")!!.status)
        assertEquals("ILLEGAL_TRANSITION", run(simple("s2", "match_start", "demo-m-5"))[0].errorCode)
        assertEquals("ILLEGAL_TRANSITION", run(simple("s3", "match_end", "demo-m-6"))[0].errorCode)
        assertEquals("applied", run(simple("s4", "match_end", "demo-m-live"))[0].status)
        assertEquals("completed", matches.fixtures().first { it.matchId == "demo-m-live" }.status)
        assertEquals(before + 1, matches.games().count { it.status == "completed" })
    }

    @Test fun compensationReversesPointsAndRejectsRepeat() {
        val orig = "demo-m-live-e1"
        fun comp(id: String) = simple(id, "compensation", "demo-m-live", buildJsonObject {
            put("compensates", orig); put("reason", "wrong")
        })
        assertEquals("applied", run(comp("c1"))[0].status)
        val d = matches.detail("demo-m-live")!!
        assertEquals(0, d.home.score)
        assertTrue(d.events.first { it.eventId == orig }.compensated)
        assertEquals("INVALID_COMPENSATION", run(comp("c2"))[0].errorCode)
    }

    @Test fun rosterApplied() {
        val r = run(transfer("t1", base = 0))[0]
        assertEquals("applied", r.status); assertEquals("appended", r.resolution); assertEquals(1L, r.serverVersion)
        assertNull(r.signature)
        val p = roster.playerDtos().first { it.playerId == "p2" }
        assertEquals("sq-youth", p.squadId); assertEquals(1L, p.auditVersion)
    }

    @Test fun rosterRebasedWhenBaseBelowCurrent() {
        run(transfer("t2", player = "p2"))
        val back = run(transfer("t3", player = "p2", from = "sq-youth", to = "sq-first", base = 0))[0]
        assertEquals("applied", back.status); assertEquals("rebased_append", back.resolution)
    }

    @Test fun rosterRuleCodes() {
        fun code(a: SyncActionDto) = run(a)[0].errorCode
        assertEquals("SQUAD_FULL", code(transfer("r1", player = "p6", from = "sq-youth", to = "sq-first")))
        assertEquals("PLAYER_ON_LOAN", code(transfer("r2", player = "p8", from = "sq-youth", to = "sq-first")))
        assertEquals("TRANSFER_WINDOW_CLOSED", code(transfer("r3", date = "2026-12-15")))
        assertEquals("NOT_IN_SQUAD", code(transfer("r4", player = "p2", from = "sq-youth", to = "sq-first")))
        assertEquals("MIN_POSITION_VIOLATION", code(transfer("r5", player = "p1")))
        assertEquals("CROSS_TENANT_TRANSFER", code(transfer("r6", to = "sq-foreign")))
        assertEquals("SQUAD_NOT_FOUND", code(transfer("r7", to = "sq-none")))
        assertEquals("INVALID_BASE_VERSION", code(transfer("r8", base = 5)))
    }

    @Test fun rosterValidation() {
        fun code(a: SyncActionDto) = run(a)[0].errorCode
        assertEquals("VALIDATION_ERROR", code(transfer("v1", date = "2026-13-45")))
        assertEquals("VALIDATION_ERROR", code(transfer("v2", to = null)))
        assertEquals("VALIDATION_ERROR", code(transfer("v3", to = "sq-first")))
        assertEquals("VALIDATION_ERROR", code(transfer("v4", type = "loan", loanEnd = "2026-10-20")))
        assertEquals("VALIDATION_ERROR", code(transfer("v5", type = "loan")))
        assertEquals("VALIDATION_ERROR", code(transfer("v6", type = "swap")))
    }

    @Test fun releaseAndLoanApply() {
        assertEquals("applied", run(transfer("l1", player = "p3", type = "loan", loanEnd = "2026-12-31", to = "sq-youth"))[0].status)
        assertTrue(roster.playerDtos().first { it.playerId == "p3" }.onLoan)
        assertEquals("applied", run(transfer("l2", player = "p4", type = "release", to = null))[0].status)
        assertNull(roster.playerDtos().first { it.playerId == "p4" }.squadId)
    }

    @Test fun rosterIdempotentRequestIdAndConflict() {
        run(transfer("t1", request = "R"))
        assertEquals("duplicate", run(transfer("t2", request = "R"))[0].status)
        val diff = run(transfer("t3", request = "R", player = "p3"))[0]
        assertEquals("DUPLICATE_REQUEST_ID", diff.errorCode)
    }

    @Test fun processedBy_queuedAt_resultsInRequestOrder() {
        val late = score("late", queued = "2026-10-17T10:05:00Z")
        val early = score("early", queued = "2026-10-17T10:01:00Z")
        val tie1 = score("tie1", queued = "2026-10-17T10:03:00Z")
        val tie2 = score("tie2", queued = "2026-10-17T10:03:00Z")
        val res = run(late, tie1, early, tie2)
        assertEquals(listOf("late", "tie1", "early", "tie2"), res.map { it.actionId })
        assertEquals(listOf(4, 2, 1, 3), res.map { it.processedOrder })
        // early applied first -> lowest server version
        assertTrue(res[2].serverVersion < res[1].serverVersion && res[1].serverVersion < res[3].serverVersion)
    }

    @Test fun summaryCounts() {
        val a1 = score("a1")
        val resp = sync.sync(SyncRequest("dev", listOf(a1, score("a2", match = "demo-m-1"), transfer("t1"), a1)))
        assertEquals(4, resp.results.size)
        assertEquals(2, resp.summary.applied); assertEquals(1, resp.summary.rejected); assertEquals(1, resp.summary.duplicate)
    }
}
