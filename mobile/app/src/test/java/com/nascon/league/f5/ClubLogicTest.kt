package com.nascon.league.f5

import com.nascon.league.core.db.PendingActionEntity
import com.nascon.league.data.api.dto.RosterPlayerDto
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import com.nascon.league.domain.model.User
import com.nascon.league.ui.club.ClubDestination
import com.nascon.league.ui.club.PlayerRoleKind
import com.nascon.league.ui.club.StaffRoleKind
import com.nascon.league.ui.club.buildGeoUri
import com.nascon.league.ui.club.capitalizeKey
import com.nascon.league.ui.club.classifySocial
import com.nascon.league.ui.club.clubDestinationFor
import com.nascon.league.ui.club.formatCoordinates
import com.nascon.league.ui.club.groupRosterByPosition
import com.nascon.league.ui.club.playerRoleLabel
import com.nascon.league.ui.club.resolveLogoUrl
import com.nascon.league.ui.club.showPendingChip
import com.nascon.league.ui.club.socialLinks
import com.nascon.league.ui.club.staffRoleLabel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClubLogicTest {
    private fun session(role: Role, clubId: String?) =
        Session(User(userId = "u1", email = "a@b.c", role = role, clubId = clubId))

    // ---- routing ----
    @Test fun managerAndPlayerGoToOwnClub() {
        assertEquals(ClubDestination.Profile("c1"), clubDestinationFor(session(Role.CLUB_MANAGER, "c1")))
        assertEquals(ClubDestination.Profile("c1"), clubDestinationFor(session(Role.PLAYER, "c1")))
    }

    @Test fun managerOrPlayerWithoutClubGetsNoClub() {
        assertEquals(ClubDestination.NoClub, clubDestinationFor(session(Role.CLUB_MANAGER, null)))
        assertEquals(ClubDestination.NoClub, clubDestinationFor(session(Role.PLAYER, "  ")))
    }

    @Test fun otherRolesGetClubsList() {
        listOf(Role.LEAGUE_ORGANIZER, Role.VIEWER, Role.SUPER_ADMIN).forEach {
            assertEquals(ClubDestination.List, clubDestinationFor(session(it, "c1")))
            assertEquals(ClubDestination.List, clubDestinationFor(session(it, null)))
        }
        assertEquals(ClubDestination.List, clubDestinationFor(null))
    }

    // ---- position grouping ----
    private fun pl(id: String, pos: String, role: String = "player", squad: String? = null) =
        RosterPlayerDto(playerId = id, name = "N$id", position = pos, role = role, squadId = squad)

    @Test fun groupsKeepFirstAppearanceOrderAndUnknownLast() {
        val groups = groupRosterByPosition(
            listOf(pl("1", ""), pl("2", "DEF"), pl("3", "GK"), pl("4", "def"), pl("5", "  ")),
        )
        assertEquals(listOf("DEF", "GK", ""), groups.map { it.position })
        assertEquals(listOf("2", "4"), groups[0].players.map { it.playerId })
        assertEquals(listOf("1", "5"), groups[2].players.map { it.playerId })
        assertTrue(groups.last().isUnknown)
    }

    @Test fun sortingIsStableWithCaptainFirst() {
        val groups = groupRosterByPosition(
            listOf(
                pl("1", "MID", "substitute"), pl("2", "MID", "player"), pl("3", "MID", "captain"),
                pl("4", "MID", "player"), pl("5", "MID", "vice_captain"),
            ),
        )
        assertEquals(listOf("3", "5", "2", "4", "1"), groups.single().players.map { it.playerId })
    }

    @Test fun squadFilterAndDuplicateIds() {
        val players = listOf(pl("1", "GK", squad = "a"), pl("2", "GK", squad = "b"), pl("1", "GK", squad = "a"))
        assertEquals(2, groupRosterByPosition(players).sumOf { it.players.size })
        assertEquals(listOf("2"), groupRosterByPosition(players, "b").flatMap { it.players }.map { it.playerId })
        assertTrue(groupRosterByPosition(players, "zzz").isEmpty())
        assertTrue(groupRosterByPosition(emptyList()).isEmpty())
    }

    // ---- role labels ----
    @Test fun playerRoleMapping() {
        assertEquals(PlayerRoleKind.CAPTAIN, playerRoleLabel("captain").kind)
        assertEquals(PlayerRoleKind.VICE_CAPTAIN, playerRoleLabel("vice-captain").kind)
        assertEquals(PlayerRoleKind.VICE_CAPTAIN, playerRoleLabel("Vice Captain").kind)
        assertEquals(PlayerRoleKind.STARTER, playerRoleLabel("starter").kind)
        assertEquals(PlayerRoleKind.SUBSTITUTE, playerRoleLabel("SUBSTITUTE").kind)
        assertEquals(PlayerRoleKind.NONE, playerRoleLabel("player").kind)
        assertEquals(PlayerRoleKind.NONE, playerRoleLabel("").kind)
        val other = playerRoleLabel("team_mascot")
        assertEquals(PlayerRoleKind.OTHER, other.kind)
        assertEquals("Team Mascot", other.text)
    }

    @Test fun staffRoleMapping() {
        assertEquals(StaffRoleKind.COACH, staffRoleLabel("coach").known)
        assertEquals(StaffRoleKind.COACH, staffRoleLabel("Head Coach").known)
        assertEquals(StaffRoleKind.MANAGER, staffRoleLabel("manager").known)
        assertEquals(StaffRoleKind.PHYSIO, staffRoleLabel("PHYSIO").known)
        val unknown = staffRoleLabel("kit_man")
        assertNull(unknown.known)
        assertEquals("Kit Man", unknown.text)
        assertNull(staffRoleLabel("").known)
    }

    // ---- coordinates ----
    @Test fun coordinatesRoundToFourDecimals() {
        assertEquals("33.6844, 73.0479", formatCoordinates(33.684422, 73.047885))
        assertEquals("31.5204, 74.3587", formatCoordinates(31.5204, 74.3587))
        assertEquals("33.0000, 73.1000", formatCoordinates(33.0, 73.1))
        assertEquals("-33.6844, -73.0479", formatCoordinates(-33.684422, -73.047885))
        assertEquals("0.0000, 0.0000", formatCoordinates(-0.00001, 0.0))
        assertEquals("33.1235, 1.0000", formatCoordinates(33.123456789012345, 0.99999999))
    }

    @Test fun coordinatesMissingOrInvalid() {
        assertNull(formatCoordinates(null, 1.0))
        assertNull(formatCoordinates(1.0, null))
        assertNull(formatCoordinates(Double.NaN, 1.0))
        assertNull(formatCoordinates(1.0, Double.POSITIVE_INFINITY))
        assertNull(buildGeoUri(null, null, "x"))
    }

    @Test fun geoUriCarriesLabel() {
        assertEquals(
            "geo:33.6844,73.0479?q=33.6844,73.0479(Alpha%20Stadium)",
            buildGeoUri(33.6844, 73.0479, "Alpha Stadium"),
        )
        assertEquals("geo:1.0000,2.0000?q=1.0000,2.0000", buildGeoUri(1.0, 2.0, "  "))
        assertTrue(buildGeoUri(1.0, 2.0, "A (B)")!!.endsWith("(A%20%20B)"))
    }

    // ---- pending chip ----
    private fun action(owner: String, status: String) = PendingActionEntity(
        actionId = "a", type = "roster", queuedAt = "t", baseVersion = 1, payloadJson = "{}",
        entityKey = "p1", label = "l", ownerUserId = owner, status = status, createdAt = 0L,
    )

    @Test fun pendingChipRule() {
        assertTrue(showPendingChip(listOf(action("u1", "PENDING")), "u1"))
        assertTrue(showPendingChip(listOf(action("u1", "SYNCING")), "u1"))
        assertFalse(showPendingChip(listOf(action("u2", "PENDING")), "u1"))
        assertFalse(showPendingChip(listOf(action("u1", "APPLIED"), action("u1", "REJECTED")), "u1"))
        assertFalse(showPendingChip(emptyList(), "u1"))
        assertFalse(showPendingChip(listOf(action("u1", "PENDING")), null))
    }

    // ---- logo url ----
    @Test fun logoUrlResolution() {
        val base = "http://10.0.2.2:8080/"
        assertEquals("http://10.0.2.2:8080/static/a.png", resolveLogoUrl("/static/a.png", base))
        assertEquals("http://10.0.2.2:8080/static/a.png", resolveLogoUrl("static/a.png", "http://10.0.2.2:8080"))
        assertEquals("https://cdn.example.com/a.png", resolveLogoUrl("https://cdn.example.com/a.png", base))
        assertEquals("https://cdn.example.com/a.png", resolveLogoUrl("  https://cdn.example.com/a.png ", null))
        assertEquals("https://h.example.com/a.png", resolveLogoUrl("//h.example.com/a.png", "https://x.test/"))
        assertNull(resolveLogoUrl(null, base))
        assertNull(resolveLogoUrl("", base))
        assertNull(resolveLogoUrl("   ", base))
        assertNull(resolveLogoUrl("/static/a.png", null))
        assertNull(resolveLogoUrl("javascript:alert(1)", base))
        assertNull(resolveLogoUrl("data:image/png;base64,AAAA", base))
        assertNull(resolveLogoUrl("ftp://x/y.png", base))
        assertNull(resolveLogoUrl("http://", base))
    }

    // ---- socials ----
    @Test fun socialClassification() {
        val web = classifySocial("website", " https://alphalions.example.com ")!!
        assertEquals("Website", web.label)
        assertEquals("https://alphalions.example.com", web.url)
        assertEquals("HTTP://X.COM", classifySocial("site", "HTTP://X.COM")!!.url)

        val handle = classifySocial("twitter", "@alphalions")!!
        assertEquals("Twitter", handle.label)
        assertNull(handle.url)

        assertNull(classifySocial("x", "javascript:alert(1)")!!.url)
        assertNull(classifySocial("x", "https://has space.com")!!.url)
        assertNull(classifySocial("x", "https://")!!.url)
        assertNull(classifySocial("x", "   "))
        assertEquals("Tik tok", capitalizeKey("tik_tok"))
    }

    @Test fun socialLinksSkipNonText() {
        val json = buildJsonObject {
            put("instagram", "@club")
            put("blank", "")
            put("site", "https://club.example.com")
        }
        assertEquals(listOf("Instagram", "Site"), socialLinks(json).map { it.label })
    }
}
