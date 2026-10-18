package com.nascon.league.matchops

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.data.matchops.ConflictDto
import com.nascon.league.data.matchops.ConflictKind
import com.nascon.league.data.matchops.FakeMatchOps
import com.nascon.league.data.matchops.MatchOpsRepository
import com.nascon.league.data.matchops.MatchOverride
import com.nascon.league.data.matchops.MatchStatusMachine
import com.nascon.league.data.matchops.PinRequest
import com.nascon.league.data.matchops.ScheduleConflicts
import com.nascon.league.data.matchops.ScheduleRequest
import com.nascon.league.data.matchops.ScheduleResult
import com.nascon.league.data.matchops.ScheduleTime
import com.nascon.league.data.matchops.StatusRequest
import com.nascon.league.data.matchops.canManageMatch
import com.nascon.league.data.matchops.conflictKind
import com.nascon.league.data.matchops.describeConflicts
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import com.nascon.league.domain.model.User
import com.nascon.league.core.network.mapThrowable
import com.nascon.league.ui.matchops.MatchOpsFormat
import com.nascon.league.ui.matchops.MatchOpsViewModel
import com.nascon.league.data.api.dto.PinnedProductDto
import com.nascon.league.data.api.dto.ProductDto
import com.nascon.league.data.api.dto.VariantDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class MatchOpsTest {

    private fun session(role: Role, leagues: List<String> = emptyList()) =
        Session(User("u", "e@x", role, leagueIds = leagues))

    // ---- state machine ----
    @Test fun transitionsMirrorBackend() {
        assertEquals(listOf("in_progress", "postponed", "cancelled"), MatchStatusMachine.nextStatuses("upcoming"))
        assertEquals(listOf("upcoming", "cancelled"), MatchStatusMachine.nextStatuses("postponed"))
        assertEquals(listOf("completed", "abandoned", "postponed"), MatchStatusMachine.nextStatuses("in_progress"))
        listOf("completed", "abandoned", "cancelled").forEach {
            assertTrue(MatchStatusMachine.isFinal(it))
            assertTrue(MatchStatusMachine.nextStatuses(it).isEmpty())
        }
        assertFalse(MatchStatusMachine.canTransition("upcoming", "completed"))
        assertFalse(MatchStatusMachine.canTransition("bogus", "upcoming"))
    }

    @Test fun normalizesAppStatusStrings() {
        assertEquals("upcoming", MatchStatusMachine.normalize("scheduled"))
        assertEquals("in_progress", MatchStatusMachine.normalize("live"))
        assertEquals("cancelled", MatchStatusMachine.normalize("canceled"))
        assertNull(MatchStatusMachine.normalize("weird"))
    }

    // ---- role gate ----
    @Test fun roleGate() {
        assertTrue(canManageMatch(session(Role.SUPER_ADMIN), "league-alpha-1"))
        assertTrue(canManageMatch(session(Role.LEAGUE_ORGANIZER, listOf("league-alpha-1")), "league-alpha-1"))
        assertFalse(canManageMatch(session(Role.LEAGUE_ORGANIZER, listOf("league-alpha-2")), "league-alpha-1"))
        assertFalse(canManageMatch(session(Role.CLUB_MANAGER), "league-alpha-1"))
        assertFalse(canManageMatch(session(Role.VIEWER), "league-alpha-1"))
        assertFalse(canManageMatch(null, "league-alpha-1"))
        assertFalse(canManageMatch(session(Role.SUPER_ADMIN), null))
    }

    // ---- time ----
    @Test fun isoUtcConversion() {
        val iso = ScheduleTime.toIsoUtc(LocalDate.of(2026, 10, 24), LocalTime.of(18, 0), ZoneId.of("Asia/Karachi"))
        assertEquals("2026-10-24T13:00:00Z", iso)
        val back = ScheduleTime.parse(iso, ZoneId.of("Asia/Karachi"))!!
        assertEquals(18, back.hour)
        assertNull(ScheduleTime.parse("not a date", ZoneId.of("UTC")))
        assertNull(ScheduleTime.parse(null, ZoneId.of("UTC")))
    }

    @Test fun formatting() {
        assertEquals("Sat 24 Oct 2026", MatchOpsFormat.date(LocalDate.of(2026, 10, 24), Locale.ENGLISH))
        assertEquals("6:00 PM", MatchOpsFormat.time(18 * 60, Locale.ENGLISH).replace(' ', ' '))
    }

    // ---- conflict rules ----
    private val ms = 60_000L
    private fun slot(id: String, h: String, a: String, startMin: Long, venue: String? = "v1") =
        ScheduleConflicts.Slot(id, h, a, startMin * ms, 120, venue)

    @Test fun venueAndTeamOverlap() {
        val subject = slot("m1", "a", "b", 0)
        val others = listOf(slot("m2", "a", "c", 60), slot("m3", "x", "y", 30))
        val kinds = ScheduleConflicts.detect(subject, others).map { it.type }
        assertEquals(listOf("venue_double_booking", "team_overlap"), kinds)
    }

    @Test fun minRest() {
        val subject = slot("m1", "a", "b", 0)
        val near = listOf(slot("m2", "a", "c", 120 + 600, venue = "v2"))
        assertEquals(listOf("min_rest"), ScheduleConflicts.detect(subject, near).map { it.type })
        val far = listOf(slot("m2", "a", "c", 120 + 1500, venue = "v2"))
        assertTrue(ScheduleConflicts.detect(subject, far).isEmpty())
    }

    @Test fun playerOverlap() {
        val subject = slot("m1", "a", "b", 0, venue = "v1")
        val others = listOf(slot("m2", "c", "d", 30, venue = "v2"))
        val players = mapOf("a" to setOf("p1"), "c" to setOf("p1", "p2"))
        val c = ScheduleConflicts.detect(subject, others, players).single()
        assertEquals("player_overlap", c.type)
        assertEquals(listOf("p1"), c.entityIds)
    }

    @Test fun conflictsDescribedWithoutIds() {
        val list = describeConflicts(
            listOf(ConflictDto("team_overlap", listOf("t1", "t9"), listOf("m1", "m9")), ConflictDto("weird", emptyList(), emptyList())),
            teamName = { mapOf("t1" to "Lions")[it] },
            matchName = { mapOf("m1" to "Lions vs Hawks")[it] },
        )
        assertEquals(ConflictKind.TEAM_BUSY, list[0].kind)
        assertEquals(listOf("Lions"), list[0].teams)
        assertEquals(listOf("Lions vs Hawks"), list[0].matches)
        assertEquals(ConflictKind.OTHER, list[1].kind)
        assertEquals(ConflictKind.VENUE_CLOSED, conflictKind("venue_unavailable"))
    }

    // ---- fake backend ----
    private fun fake(online: Boolean = true) = FakeMatchOps({ online }, withDelay = false)

    private fun codeOf(block: suspend () -> Unit): String? = runBlocking {
        try {
            block(); null
        } catch (e: Throwable) {
            (mapThrowable(e) as? ApiError.Http)?.code
        }
    }

    @Test fun fakeStatusValidation() {
        val f = fake()
        assertEquals("ILLEGAL_TRANSITION", codeOf { f.changeStatus("demo-m-5", StatusRequest("completed")) })
        assertEquals("ILLEGAL_TRANSITION", codeOf { f.changeStatus("demo-m-1", StatusRequest("upcoming")) })
        assertEquals("VALIDATION_ERROR", codeOf { f.changeStatus("demo-m-5", StatusRequest("nonsense")) })
        assertEquals("MATCH_NOT_FOUND", codeOf { f.changeStatus("nope", StatusRequest("postponed")) })
        runBlocking {
            assertEquals("postponed", f.changeStatus("demo-m-5", StatusRequest("postponed")).status)
            assertEquals("upcoming", f.changeStatus("demo-m-5", StatusRequest("upcoming")).status)
            assertEquals("cancelled", f.changeStatus("demo-m-5", StatusRequest("cancelled")).status)
        }
        assertEquals("ILLEGAL_TRANSITION", codeOf { f.changeStatus("demo-m-5", StatusRequest("upcoming")) })
    }

    @Test fun fakeScheduleConflictsAndSave() {
        val f = fake()
        runBlocking {
            // same venue and time as the live match demo-m-4
            val clash = f.checkSchedule("demo-m-5", ScheduleRequest("2026-10-17T14:00:00Z"))
            assertEquals(listOf("venue_double_booking"), clash.conflicts.map { it.type })
            // wolves already play demo-m-6 then
            val two = f.checkSchedule("demo-m-5", ScheduleRequest("2026-10-25T14:00:00Z"))
            assertEquals(listOf("venue_double_booking", "team_overlap"), two.conflicts.map { it.type })
        }
        assertEquals("SCHEDULE_CONFLICT", codeOf { f.reschedule("demo-m-5", ScheduleRequest("2026-10-17T14:00:00Z")) })
        assertEquals("VALIDATION_ERROR", codeOf { f.reschedule("demo-m-5", ScheduleRequest("garbage")) })
        runBlocking {
            val ok = f.reschedule("demo-m-5", ScheduleRequest("2026-10-21T10:00:00Z"))
            assertEquals("2026-10-21T10:00:00Z", ok.scheduledAt)
        }
        assertEquals("2026-10-21T10:00:00Z", f.snapshot("demo-m-5")!!.scheduledAt)
    }

    @Test fun fakePins() {
        val f = fake()
        runBlocking {
            assertTrue(f.pin("demo-m-5", PinRequest("prod-jersey")).pinned)
            f.pin("demo-m-5", PinRequest("prod-jersey"))
            assertEquals(setOf("prod-jersey"), f.pinsOf("demo-m-5"))
            assertFalse(f.unpin("demo-m-5", "prod-jersey").pinned)
        }
        assertTrue(f.pinsOf("demo-m-5").isEmpty())
        assertEquals("PRODUCT_NOT_FOUND", codeOf { f.pin("demo-m-5", PinRequest("zzz")) })
    }

    @Test fun fakeOffline() {
        val f = fake(online = false)
        runBlocking {
            try {
                f.changeStatus("demo-m-5", StatusRequest("postponed"))
                throw AssertionError("expected offline failure")
            } catch (e: java.net.UnknownHostException) {
                // expected
            }
        }
    }

    // ---- repository ----
    private class Harness(val online: Boolean = true, val demo: Boolean = false) {
        val fake = FakeMatchOps({ true }, withDelay = false)
        val matchRefreshes = mutableListOf<String>()
        val fixtureRefreshes = mutableListOf<String>()
        val repo = MatchOpsRepository(
            api = { fake },
            refreshMatch = { matchRefreshes += it; NetworkResult.Success(Unit) },
            refreshFixtures = { fixtureRefreshes += it; NetworkResult.Success(Unit) },
            isOnline = { online },
            keepOverrides = { demo },
        )
    }

    @Test fun repositoryOfflineIsNotQueued() = runBlocking {
        val h = Harness(online = false)
        val r = h.repo.changeStatus("demo-m-5", "league-alpha-1", "postponed")
        assertEquals(NetworkResult.Failure(ApiError.NoConnection), r)
        assertEquals(ScheduleResult.Failed(ApiError.NoConnection), h.repo.reschedule("demo-m-5", null, "2026-10-21T10:00:00Z"))
        assertEquals("upcoming", h.fake.snapshot("demo-m-5")!!.status)
        assertTrue(h.matchRefreshes.isEmpty())
    }

    @Test fun repositoryRefreshesAfterSuccess() = runBlocking {
        val h = Harness(demo = true)
        val r = h.repo.changeStatus("demo-m-5", "league-alpha-1", "postponed")
        assertTrue(r is NetworkResult.Success)
        assertEquals(listOf("demo-m-5"), h.matchRefreshes)
        assertEquals(listOf("league-alpha-1"), h.fixtureRefreshes)
        assertEquals("postponed", h.repo.overrides.value["demo-m-5"]?.status)
    }

    @Test fun repositoryClearsOverrideOnLiveRefresh() = runBlocking {
        val h = Harness(demo = false)
        h.repo.changeStatus("demo-m-5", "league-alpha-1", "postponed")
        assertNull(h.repo.overrides.value["demo-m-5"])
    }

    @Test fun repositoryRescheduleConflictThenSave() = runBlocking {
        val h = Harness(demo = true)
        val clash = h.repo.reschedule("demo-m-5", "league-alpha-1", "2026-10-17T14:00:00Z")
        assertTrue(clash is ScheduleResult.Conflicts)
        assertEquals("venue_double_booking", (clash as ScheduleResult.Conflicts).conflicts.single().type)
        assertTrue(h.matchRefreshes.isEmpty())
        val ok = h.repo.reschedule("demo-m-5", "league-alpha-1", "2026-10-21T10:00:00Z")
        assertEquals(ScheduleResult.Saved("2026-10-21T10:00:00Z"), ok)
        assertEquals("2026-10-21T10:00:00Z", h.repo.overrides.value["demo-m-5"]?.scheduledAt)
    }

    @Test fun repositoryMapsIllegalTransition() = runBlocking {
        val h = Harness()
        val r = h.repo.changeStatus("demo-m-1", null, "upcoming")
        assertEquals("ILLEGAL_TRANSITION", ((r as NetworkResult.Failure).error as ApiError.Http).code)
    }

    @Test fun conflictsFromServerError() {
        val err = ApiError.Http(
            409, "SCHEDULE_CONFLICT", "x",
            kotlinx.serialization.json.JsonObject(
                mapOf(
                    "conflicts" to kotlinx.serialization.json.JsonArray(
                        listOf(
                            kotlinx.serialization.json.buildJsonObject {
                                put("type", kotlinx.serialization.json.JsonPrimitive("min_rest"))
                                put("entity_ids", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("t1"))))
                                put("match_ids", kotlinx.serialization.json.JsonArray(emptyList()))
                            },
                        ),
                    ),
                ),
            ),
        )
        assertEquals("min_rest", MatchOpsRepository.conflictsFrom(err)!!.single().type)
        assertNull(MatchOpsRepository.conflictsFrom(ApiError.Http(409, "ILLEGAL_TRANSITION", "x")))
        assertNull(MatchOpsRepository.conflictsFrom(ApiError.NoConnection))
    }

    // ---- pins ----
    @Test fun effectivePinsAndRows() {
        val o = MatchOverride(pinned = mapOf("b" to true, "a" to false))
        assertEquals(setOf("b"), MatchOpsRepository.effectivePinned(setOf("a"), o))
        assertEquals(setOf("a"), MatchOpsRepository.effectivePinned(setOf("a"), null))

        val products = listOf(
            ProductDto("p1", "Jersey", variants = listOf(VariantDto("v1", priceMinor = 5000), VariantDto("v2", priceMinor = 4500))),
            ProductDto("p2", "Ball"),
        )
        val rows = MatchOpsViewModel.pinRows(products, listOf(PinnedProductDto("gone", "Old cap", 100)), setOf("p2", "gone"))
        assertEquals(listOf("gone", "p2", "p1"), rows.map { it.productId }.let { r -> r })
        assertEquals(4500L, rows.first { it.productId == "p1" }.fromPriceMinor)
        assertTrue(rows.first { it.productId == "gone" }.pinned)
        assertNull(rows.first { it.productId == "p2" }.fromPriceMinor)
    }
}
