package com.nascon.league.useradmin

import com.nascon.league.data.useradmin.ChangeRoleRequest
import com.nascon.league.data.useradmin.FakeUserAdminApi
import com.nascon.league.data.useradmin.NewUserRequest
import com.nascon.league.data.useradmin.SignInLabel
import com.nascon.league.data.useradmin.UserFormError
import com.nascon.league.data.useradmin.canAdminister
import com.nascon.league.data.useradmin.canDeactivate
import com.nascon.league.data.useradmin.signInLabel
import com.nascon.league.data.useradmin.validateAssignment
import com.nascon.league.data.useradmin.validateNewUser
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import com.nascon.league.domain.model.User
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneOffset

class UserAdminTest {
    private fun session(role: Role) = Session(User("u1", "a@b.co", role))
    private fun fake(online: Boolean = true) = FakeUserAdminApi({ online }, { 0L })

    @Test fun onlySuperAdminCanAdminister() {
        assertTrue(canAdminister(session(Role.SUPER_ADMIN)))
        listOf(Role.LEAGUE_ORGANIZER, Role.CLUB_MANAGER, Role.PLAYER, Role.VIEWER).forEach {
            assertFalse(canAdminister(session(it)))
        }
        assertFalse(canAdminister(null))
    }

    @Test fun cannotDeactivateSelf() {
        assertFalse(canDeactivate("u1", "u1"))
        assertTrue(canDeactivate("u1", "u2"))
        assertFalse(canDeactivate(null, "u2"))
    }

    @Test fun validNewUserHasNoErrors() {
        assertTrue(validateNewUser("Hamza", "hamza@club.pk", "longenough", "player", null, emptyList()).isEmpty())
    }

    @Test fun newUserFieldErrors() {
        val e = validateNewUser(" ", "nope", "short", "wizard", null, emptyList())
        assertEquals(UserFormError.NAME_REQUIRED, e["name"])
        assertEquals(UserFormError.EMAIL_INVALID, e["email"])
        assertEquals(UserFormError.PASSWORD_SHORT, e["password"])
        assertEquals(UserFormError.ROLE_UNKNOWN, e["role"])
    }

    @Test fun duplicateEmailIsCaseInsensitive() {
        val e = validateNewUser("A", "Admin@Nascon.test", "longenough", "viewer", null, emptyList(), listOf("admin@nascon.test"))
        assertEquals(UserFormError.EMAIL_TAKEN, e["email"])
    }

    @Test fun assignmentRules() {
        assertEquals(UserFormError.CLUB_REQUIRED, validateAssignment("club_manager", null, emptyList()))
        assertNull(validateAssignment("club_manager", "club-alpha-1", emptyList()))
        assertEquals(UserFormError.LEAGUE_REQUIRED, validateAssignment("league_organizer", null, emptyList()))
        assertNull(validateAssignment("league_organizer", null, listOf("league-alpha-1")))
        assertNull(validateAssignment("super_admin", null, emptyList()))
        assertNull(validateAssignment("player", null, emptyList()))
    }

    @Test fun fakeSeedsFiveDemoUsersAndFilters() = runBlocking {
        val api = fake()
        val all = api.users()
        assertTrue(all.total >= 5)
        listOf("admin", "organizer", "manager", "player", "viewer").forEach { n ->
            assertTrue(all.items.any { it.email == "$n@nascon.test" })
        }
        assertTrue(api.users(role = "player").items.all { it.role == "player" })
        assertEquals(1, api.users(query = "ayesha").items.size)
    }

    @Test fun fakeCreateRejectsDuplicateEmail() = runBlocking {
        val api = fake()
        try {
            api.createUser(NewUserRequest("X", "ADMIN@nascon.test", "longenough", "viewer"))
            fail("expected conflict")
        } catch (e: HttpException) {
            assertEquals(409, e.code())
        }
    }

    @Test fun fakeCreateAndChangeRoleAndDeactivate() = runBlocking {
        val api = fake()
        val u = api.createUser(NewUserRequest("Hamza", "hamza@club.pk", "longenough", "club_manager", "club-alpha-1"))
        assertEquals("Alpha Club 1", u.clubName)
        val organizer = api.changeRole(u.userId, ChangeRoleRequest("league_organizer", leagueIds = listOf("league-alpha-1")))
        assertEquals("league_organizer", organizer.role)
        assertNull(organizer.clubId)
        assertFalse(api.deactivate(u.userId).active)
        assertTrue(api.activate(u.userId).active)
        try {
            api.changeRole(u.userId, ChangeRoleRequest("club_manager"))
            fail("club required")
        } catch (e: HttpException) {
            assertEquals(422, e.code())
        }
    }

    @Test fun fakeOfflineThrowsUnknownHost() = runBlocking {
        try {
            fake(online = false).users()
            fail("expected offline")
        } catch (e: UnknownHostException) {
            // expected
        }
    }

    @Test fun signInLabels() {
        val now = Instant.parse("2026-10-17T12:00:00Z")
        assertEquals(SignInLabel.Never, signInLabel(null, now, ZoneOffset.UTC))
        assertEquals(SignInLabel.Today("9:14 AM"), signInLabel("2026-10-17T09:14:00Z", now, ZoneOffset.UTC))
        assertEquals(SignInLabel.On("14 Oct 2026"), signInLabel("2026-10-14T12:00:00Z", now, ZoneOffset.UTC))
    }
}
