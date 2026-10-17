package com.nascon.league

import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import com.nascon.league.domain.model.User
import com.nascon.league.ui.home.homeCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCardsTest {
    private fun titles(role: Role, clubId: String? = null) =
        homeCards(Session(User("u", "a@b.c", role, clubId = clubId))).map { it.title }

    @Test fun superAdmin() = assertEquals(
        listOf("Leagues & Standings", "Fixtures", "Clubs", "Store", "Admin overview"),
        titles(Role.SUPER_ADMIN),
    )

    @Test fun organizer() = assertTrue("Score a live match" in titles(Role.LEAGUE_ORGANIZER))

    @Test fun clubManagerRosterRoute() {
        val cards = homeCards(Session(User("u", "a@b.c", Role.CLUB_MANAGER, clubId = "c9")))
        assertEquals("roster/c9", cards.first { it.title == "Manage roster" }.route)
    }

    @Test fun viewerHasNoWriteActions() {
        val t = titles(Role.VIEWER)
        assertEquals(listOf("Fixtures", "Standings", "Clubs", "Store"), t)
        assertFalse("Manage roster" in t || "Score a live match" in t || "Admin overview" in t)
    }
}
