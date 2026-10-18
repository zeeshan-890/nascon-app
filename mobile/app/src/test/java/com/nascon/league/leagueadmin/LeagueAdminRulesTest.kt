package com.nascon.league.leagueadmin

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.data.api.dto.LeagueDto
import com.nascon.league.data.leagueadmin.AdminLeagueDto
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import com.nascon.league.domain.model.User
import com.nascon.league.ui.leagueadmin.LeagueAdminAccess
import com.nascon.league.ui.leagueadmin.LeagueFormError
import com.nascon.league.ui.leagueadmin.LeagueFormInput
import com.nascon.league.ui.leagueadmin.LeagueFormValidator
import com.nascon.league.ui.leagueadmin.LeagueFormat
import com.nascon.league.ui.leagueadmin.LeagueMerge
import com.nascon.league.ui.leagueadmin.Load
import com.nascon.league.ui.leagueadmin.PointsError
import com.nascon.league.ui.leagueadmin.PromotionRules
import com.nascon.league.ui.leagueadmin.SeasonFormError
import com.nascon.league.ui.leagueadmin.SeasonFormValidator
import com.nascon.league.ui.leagueadmin.StandingsRulesLogic
import com.nascon.league.ui.leagueadmin.ZoneKind
import com.nascon.league.ui.leagueadmin.combineLeagueResults
import com.nascon.league.ui.leagueadmin.dateToPickerMillis
import com.nascon.league.ui.leagueadmin.parseIsoDate
import com.nascon.league.ui.leagueadmin.pickerMillisToDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LeagueAdminRulesTest {
    private fun session(role: Role, leagues: List<String> = emptyList()) =
        Session(User("u", "u@x.test", role, "tenant-alpha", leagues))

    @Test fun superAdminCanDoEverything() {
        val s = session(Role.SUPER_ADMIN)
        assertTrue(LeagueAdminAccess.canOpenHub(s))
        assertTrue(LeagueAdminAccess.canCreateLeague(s))
        assertTrue(LeagueAdminAccess.canWriteSeasons(s))
        assertTrue(LeagueAdminAccess.canViewLeague(s, "any"))
        assertTrue(LeagueAdminAccess.canEditRules(s, "any"))
    }

    @Test fun organizerOnlyOwnLeague() {
        val s = session(Role.LEAGUE_ORGANIZER, listOf("league-alpha-1"))
        assertTrue(LeagueAdminAccess.canOpenHub(s))
        assertTrue(LeagueAdminAccess.canViewLeague(s, "league-alpha-1"))
        assertTrue(LeagueAdminAccess.canEditRules(s, "league-alpha-1"))
        assertFalse(LeagueAdminAccess.canViewLeague(s, "league-alpha-2"))
        assertFalse(LeagueAdminAccess.canEditRules(s, "league-alpha-2"))
        // Organizers may create leagues (they wait for approval); seasons and approval stay with the super admin.
        assertTrue(LeagueAdminAccess.canCreateLeague(s))
        assertFalse(LeagueAdminAccess.canApproveLeague(s))
        assertFalse(LeagueAdminAccess.canWriteSeasons(s))
    }

    @Test fun onlySuperAdminApproves() {
        assertTrue(LeagueAdminAccess.canApproveLeague(session(Role.SUPER_ADMIN)))
        listOf(Role.LEAGUE_ORGANIZER, Role.CLUB_MANAGER, Role.PLAYER, Role.VIEWER).forEach {
            assertFalse(LeagueAdminAccess.canApproveLeague(session(it)))
        }
        assertFalse(LeagueAdminAccess.canApproveLeague(null))
    }

    @Test fun otherRolesAndSignedOutHaveNoAccess() {
        listOf(session(Role.CLUB_MANAGER), session(Role.PLAYER), session(Role.VIEWER), null).forEach {
            assertFalse(LeagueAdminAccess.canOpenHub(it))
            assertFalse(LeagueAdminAccess.canViewLeague(it, "league-alpha-1"))
            assertFalse(LeagueAdminAccess.canCreateLeague(it))
            assertFalse(LeagueAdminAccess.canWriteSeasons(it))
        }
    }

    @Test fun visibleFiltersToOwnLeagues() {
        val s = session(Role.LEAGUE_ORGANIZER, listOf("b"))
        assertEquals(listOf("b"), LeagueAdminAccess.visible(s, listOf("a", "b", "c")) { it })
    }

    @Test fun mergeOverlaysExtrasAndAppendsAdminOnly() {
        val real = listOf(LeagueDto("league-alpha-1", "Alpha Premier League", "football", "2026"))
        val admin = listOf(
            AdminLeagueDto("league-alpha-1", format = "round_robin", status = "active", teamCount = 8),
            AdminLeagueDto("league-alpha-2", name = "Alpha Cup", format = "hybrid", status = "draft"),
        )
        val out = LeagueMerge.merge(real, admin)
        assertEquals(listOf("league-alpha-1", "league-alpha-2"), out.map { it.leagueId })
        assertEquals("Alpha Premier League", out[0].name)
        assertEquals(8, out[0].teamCount)
        assertEquals(LeagueFormat.HYBRID, out[1].format)
        assertEquals("draft", out[1].status)
    }

    @Test fun mergeWithoutExtrasKeepsRealLeagues() {
        val out = LeagueMerge.merge(listOf(LeagueDto("l1", "One", "football", null)), emptyList())
        assertEquals(1, out.size)
        assertNull(out[0].format)
        assertNull(out[0].extras)
    }

    @Test fun combineFallsBackWhenAdminMissing() {
        val real = NetworkResult.Success(listOf(LeagueDto("l1", "One", "football", "2026")))
        val r = combineLeagueResults(real, NetworkResult.Failure(ApiError.NoConnection))
        assertTrue(r is Load.Data)
        assertEquals(1, (r as Load.Data).value.size)
    }

    @Test fun combineFailsOnlyWhenBothFail() {
        val bad = NetworkResult.Failure(ApiError.Timeout)
        assertTrue(combineLeagueResults(bad, bad) is Load.Failed)
        val admin = NetworkResult.Success(listOf(AdminLeagueDto("l2", name = "Two")))
        assertTrue(combineLeagueResults(bad, admin) is Load.Data)
    }

    @Test fun leagueFormValidation() {
        val ok = LeagueFormInput(name = "Cup", sportId = "football")
        assertTrue(LeagueFormValidator.validate(ok).isEmpty())
        assertTrue(LeagueFormError.NAME_REQUIRED in LeagueFormValidator.validate(ok.copy(name = "  ")))
        assertTrue(LeagueFormError.NAME_TOO_LONG in LeagueFormValidator.validate(ok.copy(name = "x".repeat(61))))
        assertTrue(LeagueFormError.SPORT_REQUIRED in LeagueFormValidator.validate(ok.copy(sportId = null)))
        assertTrue(LeagueFormError.SQUAD_INVALID in LeagueFormValidator.validate(ok.copy(maxSquad = "0")))
        assertTrue(LeagueFormError.SQUAD_INVALID in LeagueFormValidator.validate(ok.copy(maxSquad = "")))
        val d = LocalDate.of(2026, 10, 1)
        assertTrue(LeagueFormError.WINDOW_INCOMPLETE in LeagueFormValidator.validate(ok.copy(windowOpens = d)))
        assertTrue(LeagueFormError.WINDOW_ORDER in LeagueFormValidator.validate(ok.copy(windowOpens = d, windowCloses = d)))
        assertTrue(LeagueFormValidator.validate(ok.copy(windowOpens = d, windowCloses = d.plusDays(30))).isEmpty())
    }

    @Test fun seasonValidation() {
        val a = LocalDate.of(2027, 8, 1)
        assertTrue(SeasonFormValidator.validate("Season 2027", a, a.plusMonths(10)).isEmpty())
        assertTrue(SeasonFormError.NAME_REQUIRED in SeasonFormValidator.validate(" ", a, a.plusDays(1)))
        assertTrue(SeasonFormError.START_REQUIRED in SeasonFormValidator.validate("x", null, a))
        assertTrue(SeasonFormError.END_REQUIRED in SeasonFormValidator.validate("x", a, null))
        assertTrue(SeasonFormError.END_NOT_AFTER_START in SeasonFormValidator.validate("x", a, a))
        assertTrue(SeasonFormError.END_NOT_AFTER_START in SeasonFormValidator.validate("x", a, a.minusDays(1)))
    }

    @Test fun promotionZonesForEightTeams() {
        val z = PromotionRules.zones(8, 2, 2)
        assertEquals(listOf(ZoneKind.PROMOTED, ZoneKind.MID, ZoneKind.RELEGATED), z.map { it.kind })
        assertEquals(1 to 2, z[0].from to z[0].to)
        assertEquals(3 to 6, z[1].from to z[1].to)
        assertEquals(7 to 8, z[2].from to z[2].to)
    }

    @Test fun promotionZonesEdgeCases() {
        assertEquals(listOf(ZoneKind.MID), PromotionRules.zones(8, 0, 0).map { it.kind })
        val full = PromotionRules.zones(8, 4, 4)
        assertEquals(listOf(ZoneKind.PROMOTED, ZoneKind.RELEGATED), full.map { it.kind })
        // Values above the limit are clamped, zones never overlap.
        val clamped = PromotionRules.zones(8, 7, 7)
        assertEquals(4, clamped[0].to)
        assertEquals(5, clamped[1].from)
        assertEquals(4, PromotionRules.maxSpots(8))
        assertEquals(0, PromotionRules.clamp(-3, 8))
        assertEquals(4, PromotionRules.maxSpots(0))
    }

    @Test fun pointsValidation() {
        assertTrue(StandingsRulesLogic.validatePoints(3, 1, 0).isEmpty())
        assertTrue(PointsError.WIN_BELOW_DRAW in StandingsRulesLogic.validatePoints(1, 2, 0))
        assertTrue(PointsError.DRAW_BELOW_LOSS in StandingsRulesLogic.validatePoints(3, 0, 1))
        assertTrue(PointsError.RANGE in StandingsRulesLogic.validatePoints(10, 1, 0))
        assertTrue(PointsError.RANGE in StandingsRulesLogic.validatePoints(3, 1, -1))
    }

    @Test fun tiebreakReordering() {
        val o = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), StandingsRulesLogic.moveUp(o, 1))
        assertEquals(o, StandingsRulesLogic.moveUp(o, 0))
        assertEquals(listOf("a", "c", "b"), StandingsRulesLogic.moveDown(o, 1))
        assertEquals(o, StandingsRulesLogic.moveDown(o, 2))
        assertEquals(listOf("a", "c"), StandingsRulesLogic.remove(o, 1))
        assertEquals(listOf("a"), StandingsRulesLogic.remove(listOf("a"), 0))
    }

    @Test fun tiebreakAddAndSanitize() {
        val o = listOf("head_to_head")
        assertEquals(listOf("head_to_head", "wins"), StandingsRulesLogic.add(o, "wins"))
        assertEquals(o, StandingsRulesLogic.add(o, "head_to_head"))
        assertEquals(o, StandingsRulesLogic.add(o, "bogus"))
        assertFalse("head_to_head" in StandingsRulesLogic.unused(o))
        assertEquals(listOf("wins"), StandingsRulesLogic.sanitize(listOf("bogus", "wins", "wins")))
        assertEquals(3, StandingsRulesLogic.sanitize(emptyList()).size)
    }

    @Test fun dateHelpers() {
        val d = LocalDate.of(2026, 10, 17)
        assertEquals(d, pickerMillisToDate(dateToPickerMillis(d)))
        assertEquals(d, parseIsoDate("2026-10-17"))
        assertNull(parseIsoDate("nope"))
        assertNull(parseIsoDate(null))
    }

    @Test fun formatKnockoutFlag() {
        assertTrue(LeagueFormat.HYBRID.hasKnockout)
        assertFalse(LeagueFormat.ROUND_ROBIN.hasKnockout)
        assertEquals(LeagueFormat.SWISS, LeagueFormat.fromApi("swiss"))
        assertEquals(LeagueFormat.ROUND_ROBIN, LeagueFormat.fromApi("weird"))
    }
}
