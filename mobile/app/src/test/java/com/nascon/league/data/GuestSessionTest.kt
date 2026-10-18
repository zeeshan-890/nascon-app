package com.nascon.league.data

import com.nascon.league.data.repo.GuestSession
import com.nascon.league.domain.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestSessionTest {
    @Test fun debugBuildUsesTheSeededDevToken() {
        assertEquals("guest_anon_tok_7721", GuestSession.newToken(debugBuild = true))
    }

    @Test fun releaseBuildNeverUsesTheDevToken() {
        val t = GuestSession.newToken(debugBuild = false)
        assertNotEquals("guest_anon_tok_7721", t)
        assertTrue(t.startsWith("guest_"))
        assertTrue(t.length > "guest_".length + 16)
    }

    @Test fun releaseTokensAreDifferentEachTime() {
        assertNotEquals(GuestSession.newToken(false), GuestSession.newToken(false))
    }

    @Test fun releaseTokenUsesTheInjectedRandom() {
        assertEquals("guest_abc", GuestSession.newToken(false) { "abc" })
    }

    @Test fun guestTokenDetection() {
        assertTrue(GuestSession.isGuestToken("guest_x"))
        assertFalse(GuestSession.isGuestToken("eyJhbGciOi"))
        assertFalse(GuestSession.isGuestToken(""))
        assertFalse(GuestSession.isGuestToken(null))
        assertFalse(GuestSession.isGuestToken("Guest_x"))
    }

    @Test fun guestSessionIsAReadOnlyViewer() {
        val s = GuestSession.session()
        assertEquals(Role.VIEWER, s.user.role)
        assertTrue(GuestSession.isGuest(s))
        assertFalse(GuestSession.isGuest(null))
    }

    @Test fun aMemberIsNotAGuest() {
        val member = GuestSession.session().copy(user = GuestSession.session().user.copy(userId = "u-viewer"))
        assertFalse(GuestSession.isGuest(member))
    }
}
