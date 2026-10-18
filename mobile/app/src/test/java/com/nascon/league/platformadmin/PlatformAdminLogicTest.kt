package com.nascon.league.platformadmin

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.data.platformadmin.AUDIT_SEED_NEWEST
import com.nascon.league.data.platformadmin.AccessMatrixDto
import com.nascon.league.data.platformadmin.AdminClubCreateRequest
import com.nascon.league.data.platformadmin.AuditFilter
import com.nascon.league.data.platformadmin.FakePlatformAdminApi
import com.nascon.league.data.platformadmin.defaultAccessPolicies
import com.nascon.league.data.platformadmin.isValidEventKey
import com.nascon.league.data.platformadmin.matches
import com.nascon.league.data.platformadmin.seededAudit
import com.nascon.league.data.platformadmin.seededSports
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import com.nascon.league.domain.model.User
import com.nascon.league.ui.platformadmin.CheckState
import com.nascon.league.ui.platformadmin.DateRangeProblem
import com.nascon.league.ui.platformadmin.FieldProblem
import com.nascon.league.ui.platformadmin.SCOPE_NONE
import com.nascon.league.ui.platformadmin.accessChangeCount
import com.nascon.league.ui.platformadmin.accessRowsToPolicies
import com.nascon.league.ui.platformadmin.buildAccessRows
import com.nascon.league.ui.platformadmin.buildEvent
import com.nascon.league.ui.platformadmin.canAdminister
import com.nascon.league.ui.platformadmin.canEditAccessCell
import com.nascon.league.ui.platformadmin.classifyHealth
import com.nascon.league.ui.platformadmin.classifyLatency
import com.nascon.league.ui.platformadmin.diffJson
import com.nascon.league.ui.platformadmin.eventLabel
import com.nascon.league.ui.platformadmin.eventPoints
import com.nascon.league.ui.platformadmin.formatAuditWhen
import com.nascon.league.ui.platformadmin.humanizeKey
import com.nascon.league.ui.platformadmin.jsonOf
import com.nascon.league.ui.platformadmin.parseDateInput
import com.nascon.league.ui.platformadmin.sportPointsKey
import com.nascon.league.ui.platformadmin.suggestEventKey
import com.nascon.league.ui.platformadmin.validateClubName
import com.nascon.league.ui.platformadmin.validateDateRange
import com.nascon.league.ui.platformadmin.validateEventKey
import com.nascon.league.ui.platformadmin.validateEventLabel
import com.nascon.league.ui.platformadmin.validateTenantId
import com.nascon.league.ui.platformadmin.withEvent
import com.nascon.league.ui.platformadmin.withScope
import com.nascon.league.ui.platformadmin.withoutEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class PlatformAdminLogicTest {
    private fun session(role: Role) = Session(User("u1", "a@b.c", role))

    @Test fun onlySuperAdminCanAdminister() {
        assertTrue(canAdminister(session(Role.SUPER_ADMIN)))
        listOf(Role.LEAGUE_ORGANIZER, Role.CLUB_MANAGER, Role.PLAYER, Role.VIEWER).forEach {
            assertFalse(canAdminister(session(it)))
        }
        assertFalse(canAdminister(null))
    }

    @Test fun humanizeKeys() {
        assertEquals("Two pointer", humanizeKey("two_pointer"))
        assertEquals("Club roster write", humanizeKey("club.roster.write"))
        assertEquals("", humanizeKey("  "))
    }

    @Test fun eventKeyRules() {
        assertTrue(isValidEventKey("free_throw"))
        assertTrue(isValidEventKey("goal"))
        assertFalse(isValidEventKey("Goal"))
        assertFalse(isValidEventKey("free__throw"))
        assertFalse(isValidEventKey("_goal"))
        assertFalse(isValidEventKey("1goal"))
        assertFalse(isValidEventKey("free throw"))
        assertEquals(FieldProblem.REQUIRED, validateEventKey("", emptySet(), false))
        assertEquals(FieldProblem.BAD_FORMAT, validateEventKey("Free Throw", emptySet(), false))
        assertEquals(FieldProblem.DUPLICATE, validateEventKey("goal", setOf("goal"), false))
        assertNull(validateEventKey("goal", setOf("goal"), true))
        assertEquals(FieldProblem.REQUIRED, validateEventLabel(" "))
        assertNull(validateEventLabel("Goal"))
    }

    @Test fun suggestedKeyIsSnakeCase() {
        assertEquals("free_throw", suggestEventKey("Free throw!"))
        assertEquals("e_3_pointer", suggestEventKey("3 pointer"))
        assertEquals("", suggestEventKey("   "))
    }

    @Test fun clubFormValidation() {
        assertEquals(FieldProblem.REQUIRED, validateClubName(" "))
        assertEquals(FieldProblem.TOO_SHORT, validateClubName("A"))
        assertEquals(FieldProblem.TOO_LONG, validateClubName("x".repeat(61)))
        assertNull(validateClubName("Panthers FC"))
        assertNull(validateTenantId("tenant-alpha"))
        assertEquals(FieldProblem.BAD_FORMAT, validateTenantId("tenant alpha"))
    }

    @Test fun buildEventKeepsOtherFieldsAndUsesPointsKey() {
        val sport = seededSports().first { it.scoringModel == "innings" }
        val existing = sport.scoringEvents["wicket"] as JsonObject
        assertEquals("runs", sportPointsKey(sport))
        val built = buildEvent(existing, "runs", 3, "Big one", true)
        assertEquals(3, eventPoints(built))
        assertEquals("Big one", eventLabel("wicket", built))
        assertEquals(JsonPrimitive(true), built["wicket"])
        assertEquals("Wicket", eventLabel("wicket", existing))
    }

    @Test fun addAndRemoveEvent() {
        val sport = seededSports().first()
        val n = sport.scoringEvents.size
        val added = sport.withEvent("extra", buildEvent(null, "points", 2, "Extra", false))
        assertEquals(n + 1, added.scoringEvents.size)
        assertEquals(n, added.withoutEvent("extra").scoringEvents.size)
        assertEquals(sport.scoringEvents.keys, added.withoutEvent("extra").scoringEvents.keys)
    }

    @Test fun accessMatrixRoundTripsDefaultPolicies() {
        val policies = defaultAccessPolicies()
        val rows = buildAccessRows(policies)
        assertEquals(policies.toSet(), accessRowsToPolicies(rows).toSet())
        val roster = rows.first { it.action == "club.roster.write" }
        assertEquals("any", roster.scopes["super_admin"])
        assertEquals("own_club", roster.scopes["club_manager"])
        assertEquals(SCOPE_NONE, roster.scopes["viewer"])
    }

    @Test fun accessEditsAreCounted() {
        val rows = buildAccessRows(defaultAccessPolicies())
        val edited = rows.withScope("order.manage", "player", "self").withScope("order.manage", "club_manager", SCOPE_NONE)
        assertEquals(2, accessChangeCount(rows, edited))
        assertEquals(0, accessChangeCount(rows, rows))
        assertFalse(canEditAccessCell("super_admin"))
        assertTrue(canEditAccessCell("viewer"))
    }

    @Test fun diffShowsOnlyChangedFields() {
        val d = diffJson(jsonOf("home" to "1", "away" to "2"), jsonOf("home" to "0", "away" to "2"))
        assertEquals(1, d.size)
        assertEquals("home", d[0].key)
        assertEquals("1", d[0].before)
        assertEquals("0", d[0].after)
        val created = diffJson(null, jsonOf("name" to "X"))
        assertNull(created[0].before)
        assertEquals("X", created[0].after)
        assertTrue(diffJson(null, null).isEmpty())
    }

    @Test fun dateRangeValidation() {
        assertNull(validateDateRange("", ""))
        assertNull(validateDateRange("2026-10-10", "2026-10-17"))
        assertEquals(DateRangeProblem.FROM_INVALID, validateDateRange("tomorrow", ""))
        assertEquals(DateRangeProblem.TO_INVALID, validateDateRange("", "2026-13-40"))
        assertEquals(DateRangeProblem.FROM_AFTER_TO, validateDateRange("2026-10-18", "2026-10-17"))
        assertEquals(LocalDate.of(2026, 10, 17), parseDateInput("2026/10/17"))
    }

    @Test fun auditTimeFormatting() {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-10-17T12:00:00Z")
        val today = formatAuditWhen(Instant.parse("2026-10-17T10:42:00Z"), now, zone, "Yesterday", Locale.US)
        assertTrue(today, today.contains("10:42"))
        val y = formatAuditWhen(Instant.parse("2026-10-16T09:00:00Z"), now, zone, "Yesterday", Locale.US)
        assertTrue(y, y.startsWith("Yesterday, "))
        val older = formatAuditWhen(Instant.parse("2026-10-01T09:00:00Z"), now, zone, "Yesterday", Locale.US)
        assertTrue(older, older.startsWith("1 Oct, "))
    }

    @Test fun auditFilterMatching() {
        val e = seededAudit().first()
        assertTrue(e.matches(AuditFilter()))
        assertTrue(e.matches(AuditFilter(action = e.action.substringBefore('.'))))
        assertTrue(e.matches(AuditFilter(action = e.action)))
        assertFalse(e.matches(AuditFilter(action = "nothing")))
        assertFalse(e.matches(AuditFilter(actor = "someone-else")))
        val day = LocalDate.of(2026, 10, 17)
        assertTrue(e.matches(AuditFilter(from = day, to = day)))
        assertFalse(e.matches(AuditFilter(from = day.plusDays(1))))
        assertFalse(e.matches(AuditFilter(to = day.minusDays(1))))
        assertTrue(AuditFilter(actor = "x").isActive)
        assertFalse(AuditFilter().isActive)
    }

    @Test fun healthClassification() {
        assertEquals(CheckState.OK, classifyHealth("ok", 120))
        assertEquals(CheckState.SLOW, classifyHealth("ok", 3000))
        assertEquals(CheckState.STARTING, classifyHealth("starting", 10))
        assertEquals(CheckState.OK, classifyLatency(10))
    }

    // ---- seed data and fake API

    @Test fun seedsAreRealistic() {
        assertTrue(seededAudit().size >= 30)
        assertTrue(seededAudit().map { it.actorId }.distinct().size >= 3)
        assertTrue(seededAudit().map { it.action }.distinct().size >= 8)
        assertEquals(seededAudit().size, seededAudit().map { it.id }.distinct().size)
        val sports = seededSports()
        assertTrue(sports.all { it.sportId.isNotBlank() && it.scoringEvents.isNotEmpty() && it.tieBreakOrder.isNotEmpty() })
        assertEquals(AUDIT_SEED_NEWEST.toString(), seededAudit().first().at)
        assertTrue(defaultAccessPolicies().any { it.role == "club_manager" && it.action == "order.manage" && it.scope == "own_club" })
    }

    private fun fake(online: Boolean = true) =
        FakePlatformAdminApi(hasNetwork = { online }, pause = {}, now = { Instant.parse("2026-10-17T11:00:00Z") })

    @Test fun fakeFailsOfflineLikeARealCall() = runBlocking {
        val r = safeCall { fake(online = false).access() }
        assertTrue(r is NetworkResult.Failure)
        assertEquals(ApiError.ServerUnreachable, (r as NetworkResult.Failure).error)
    }

    @Test fun fakeAuditFiltersAndPages() = runBlocking {
        val api = fake()
        val all = api.audit(null, null, null, null, 10, 0)
        assertEquals(10, all.items.size)
        assertTrue(all.total >= 30)
        assertTrue(all.items.zipWithNext().all { (a, b) -> a.at >= b.at })
        val scores = api.audit(null, "score", null, null, 100, 0)
        assertTrue(scores.items.isNotEmpty() && scores.items.all { it.action.startsWith("score.") })
        val none = api.audit(null, null, "2030-01-01", null, 100, 0)
        assertEquals(0, none.total)
        val detail = api.auditEntry(all.items.first().id)
        assertEquals(all.items.first().id, detail.id)
    }

    @Test fun fakeCreateClubValidatesAndRejectsDuplicates() = runBlocking {
        val api = fake()
        val ok = api.createClub(AdminClubCreateRequest("Panthers FC", "tenant-alpha", "Panther Park", "Islamabad"))
        assertEquals("club-panthers-fc", ok.clubId)
        val dup = safeCall { api.createClub(AdminClubCreateRequest("panthers fc", "tenant-alpha", "Elsewhere")) }
        assertEquals(409, ((dup as NetworkResult.Failure).error as ApiError.Http).status)
        val badTenant = safeCall { api.createClub(AdminClubCreateRequest("Other", "nope", "Park")) }
        assertEquals(404, ((badTenant as NetworkResult.Failure).error as ApiError.Http).status)
        val audit = api.audit(null, "platform", null, null, 5, 0)
        assertEquals("platform.club_created", audit.items.first().action)
    }

    @Test fun fakeSaveSportKeepsEditsAndRejectsBadCodes() = runBlocking {
        val api = fake()
        val sport = api.sport("football")
        val edited = sport.withEvent("own_goal", buildEvent(null, "points", 1, "Own goal", true))
        val saved = api.saveSport("football", edited)
        assertEquals(2, saved.scoringEvents.size)
        assertEquals(2, api.sport("football").scoringEvents.size)
        val bad = safeCall { api.saveSport("football", sport.withEvent("Bad Code", buildEvent(null, "points", 1, "x", false))) }
        assertEquals(422, ((bad as NetworkResult.Failure).error as ApiError.Http).status)
        val missing = safeCall { api.sport("unknown") }
        assertEquals(404, ((missing as NetworkResult.Failure).error as ApiError.Http).status)
        assertNotNull(api.sports().firstOrNull { it.sportId == "football" })
    }

    @Test fun fakeAccessSaveProtectsSuperAdmin() = runBlocking {
        val api = fake()
        val rows = buildAccessRows(api.access().policies)
        val ok = rows.withScope("order.manage", "player", "self")
        val saved = api.saveAccess(AccessMatrixDto(accessRowsToPolicies(ok)))
        assertTrue(saved.policies.any { it.role == "player" && it.action == "order.manage" })
        val lockout = rows.withScope("order.manage", "super_admin", SCOPE_NONE)
        val r = safeCall { api.saveAccess(AccessMatrixDto(accessRowsToPolicies(lockout))) }
        assertTrue(r is NetworkResult.Failure)
    }
}
