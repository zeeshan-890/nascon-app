package com.nascon.league.states

import com.nascon.league.core.network.ApiError
import com.nascon.league.ui.components.formatCountdown
import com.nascon.league.ui.login.LoginFailureKind
import com.nascon.league.ui.login.attemptsLeft
import com.nascon.league.ui.login.lockedForSeconds
import com.nascon.league.ui.login.loginFailureKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatesLogicTest {
    private fun http(code: String, details: JsonObject? = null) = ApiError.Http(400, code, "m", details)

    @Test fun countdownFormat() {
        assertEquals("0:47", formatCountdown(47))
        assertEquals("1:05", formatCountdown(65))
        assertEquals("0:00", formatCountdown(-3))
    }

    @Test fun failureKinds() {
        assertEquals(LoginFailureKind.CREDENTIALS, loginFailureKind(http("UNAUTHENTICATED")))
        assertEquals(LoginFailureKind.LOCKED, loginFailureKind(http("RATE_LIMITED")))
        assertEquals(LoginFailureKind.UNREACHABLE, loginFailureKind(ApiError.ServerUnreachable))
        assertEquals(LoginFailureKind.OTHER, loginFailureKind(ApiError.NoConnection))
    }

    @Test fun lockSecondsAndAttempts() {
        val d = JsonObject(mapOf("retryAfterSeconds" to JsonPrimitive(47)))
        assertEquals(47, lockedForSeconds(http("RATE_LIMITED", d)))
        assertNull(lockedForSeconds(http("RATE_LIMITED")))
        assertNull(lockedForSeconds(http("UNAUTHENTICATED", d)))
        val a = JsonObject(mapOf("attemptsRemaining" to JsonPrimitive(2)))
        assertEquals(2, attemptsLeft(http("UNAUTHENTICATED", a)))
        assertNull(attemptsLeft(http("RATE_LIMITED", a)))
    }
}
