package com.nascon.league.f10

import com.nascon.league.core.error.ErrorContext
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.core.network.ApiError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorMapperCompleteTest {
    private fun m(code: String, ctx: ErrorContext = ErrorContext.General) = ErrorMapper.forCode(code, ctx)

    @Test
    fun knownCodesIncludeNewOnes() {
        for (c in listOf("SQUAD_NOT_FOUND", "DUPLICATE_REQUEST_ID", "VARIANT_NOT_FOUND", "SPORT_NOT_FOUND", "EMPTY_CART")) {
            assertTrue(c, c in ErrorMapper.knownCodes)
        }
    }

    @Test
    fun everyKnownCodeIsPlainEverywhere() {
        for (c in ErrorMapper.knownCodes) {
            assertNotEquals(c, ErrorMapper.GENERIC, m(c))
            assertFalse(ErrorMapper.syncRejection(c).contains('_'))
            for (ctx in ErrorContext.entries) {
                val msg = m(c, ctx)
                assertFalse(msg.contains('_'))
                assertFalse(msg.contains("HTTP", ignoreCase = true))
                assertFalse(msg.matches(Regex("\\d+")))
            }
        }
    }

    @Test
    fun exactWording() {
        assertEquals("The destination squad is already full, so this transfer was not made.", m("SQUAD_FULL"))
        assertEquals("This item changed on the server, so your change was not applied.", m("INVALID_BASE_VERSION"))
        assertEquals("This item no longer exists.", m("SQUAD_NOT_FOUND"))
        assertEquals("This item no longer exists.", m("ANYTHING_NOT_FOUND"))
        assertEquals("This change was not valid and was not saved.", m("VALIDATION_ERROR"))
        assertEquals("This change conflicts with an earlier one and was not saved.", m("DUPLICATE_EVENT_ID"))
        assertEquals("This change conflicts with an earlier one and was not saved.", m("DUPLICATE_REQUEST_ID"))
        assertEquals("This was already submitted.", m("DUPLICATE_ORDER_ID"))
        assertEquals("Please sign in again.", m("UNAUTHENTICATED"))
        assertEquals("Email or password is incorrect.", m("UNAUTHENTICATED", ErrorContext.Login))
        assertEquals("You don't have permission to do that.", m("FORBIDDEN"))
        assertEquals("The transfer window is closed for that date.", m("TRANSFER_WINDOW_CLOSED"))
        assertEquals("This player is on loan and can't be moved.", m("PLAYER_ON_LOAN"))
        assertEquals("This player isn't in that squad anymore.", m("NOT_IN_SQUAD"))
        assertEquals("This match isn't live, so scores can't be added.", m("MATCH_NOT_IN_PROGRESS"))
        assertEquals("That event was already corrected.", m("INVALID_COMPENSATION"))
        assertEquals("Too many requests — try again in a few seconds.", m("RATE_LIMITED"))
        assertEquals("Too many attempts. Please wait a few minutes and try again.", m("RATE_LIMITED", ErrorContext.Login))
        assertEquals("That squad belongs to a different organisation.", m("CROSS_TENANT_TRANSFER"))
        assertEquals("Something went wrong on the server. Please try again.", m("WHATEVER"))
        assertEquals("You're offline. We'll use saved data where we can.", ErrorMapper.message(ApiError.NoConnection))
        assertEquals(
            "You're offline. Connect to the internet to sign in.",
            ErrorMapper.message(ApiError.NoConnection, ErrorContext.Login),
        )
    }

    @Test
    fun minPosition() {
        assertEquals("This would leave the squad without enough players in that position.", m("MIN_POSITION_VIOLATION"))
        val d = JsonObject(mapOf("position" to JsonPrimitive("goalkeeper")))
        assertEquals(
            "This would leave the squad without enough goalkeeper players.",
            ErrorMapper.forCode("MIN_POSITION_VIOLATION", ErrorContext.General, d),
        )
        assertEquals(
            "This would leave the squad without enough goalkeeper players.",
            ErrorMapper.message(ApiError.Http(409, "MIN_POSITION_VIOLATION", "", d)),
        )
        assertEquals(
            "This would leave the squad without enough players in that position.",
            ErrorMapper.forCode("MIN_POSITION_VIOLATION", ErrorContext.General, JsonObject(emptyMap())),
        )
    }

    @Test
    fun syncRejection() {
        assertEquals("This change was not applied.", ErrorMapper.syncRejection(null))
        assertEquals("This change was not applied.", ErrorMapper.syncRejection("  "))
        assertEquals(
            "The destination squad is already full, so this transfer was not made.",
            ErrorMapper.syncRejection("SQUAD_FULL"),
        )
        assertEquals(ErrorMapper.GENERIC, ErrorMapper.syncRejection("ODD_CODE"))
    }
}
