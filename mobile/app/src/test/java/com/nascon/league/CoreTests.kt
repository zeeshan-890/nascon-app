package com.nascon.league

import com.nascon.league.core.config.ServerConfigStore
import com.nascon.league.core.error.ErrorContext
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.mapThrowable
import com.nascon.league.core.time.Clock
import com.nascon.league.domain.model.Permissions
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import com.nascon.league.domain.model.User
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.ConnectException
import java.time.Instant

class ErrorMapperTest {
    @Test
    fun everyKnownCodeHasPlainMessage() {
        for (code in ErrorMapper.knownCodes) {
            for (ctx in ErrorContext.entries) {
                val msg = ErrorMapper.forCode(code, ctx)
                assertTrue(code, msg.isNotBlank())
                assertFalse("$code -> $msg", msg.contains('_'))
                assertFalse("$code -> $msg", msg.matches(Regex("\\d+")))
                assertFalse(msg.contains("HTTP", ignoreCase = true))
            }
        }
    }

    @Test
    fun unknownCodeIsGeneric() {
        assertEquals(ErrorMapper.GENERIC, ErrorMapper.forCode("SOMETHING_ODD"))
        assertEquals(ErrorMapper.GENERIC, ErrorMapper.message(ApiError.Http(500, "HTTP_500", "")))
    }

    @Test
    fun loginContextMessages() {
        assertEquals("Email or password is incorrect.", ErrorMapper.forCode("UNAUTHENTICATED", ErrorContext.Login))
        assertEquals("Please sign in again.", ErrorMapper.forCode("UNAUTHENTICATED"))
    }
}

class PermissionsTest {
    private fun session(role: Role) =
        Session(User("u", "u@x", role, leagueIds = listOf("L1"), clubId = "C1"))

    @Test
    fun scoring() {
        assertTrue(Permissions(session(Role.SUPER_ADMIN)).canScore("any"))
        assertTrue(Permissions(session(Role.LEAGUE_ORGANIZER)).canScore("L1"))
        assertFalse(Permissions(session(Role.LEAGUE_ORGANIZER)).canScore("L2"))
        assertFalse(Permissions(session(Role.CLUB_MANAGER)).canScore("L1"))
        assertFalse(Permissions(session(Role.PLAYER)).canScore("L1"))
        assertFalse(Permissions(session(Role.VIEWER)).canScore("L1"))
    }

    @Test
    fun roster() {
        assertTrue(Permissions(session(Role.SUPER_ADMIN)).canManageRoster("any"))
        assertTrue(Permissions(session(Role.CLUB_MANAGER)).canManageRoster("C1"))
        assertFalse(Permissions(session(Role.CLUB_MANAGER)).canManageRoster("C2"))
        assertFalse(Permissions(session(Role.LEAGUE_ORGANIZER)).canManageRoster("C1"))
        assertFalse(Permissions(session(Role.PLAYER)).canManageRoster("C1"))
        assertFalse(Permissions(session(Role.VIEWER)).canManageRoster("C1"))
    }

    @Test
    fun buyAndAdmin() {
        Role.entries.forEach { assertTrue(Permissions(session(it)).canBuy) }
        assertFalse(Permissions(null).canBuy)
        assertTrue(Permissions(session(Role.SUPER_ADMIN)).canSeeAdmin)
        Role.entries.filter { it != Role.SUPER_ADMIN }.forEach { assertFalse(Permissions(session(it)).canSeeAdmin) }
    }
}

class ServerConfigStoreTest {
    @Test
    fun normalises() {
        assertEquals("http://192.168.1.5:8080/", ServerConfigStore.normalizeUrl("192.168.1.5:8080"))
        assertEquals("http://192.168.1.5:8080/", ServerConfigStore.normalizeUrl("http://192.168.1.5:8080"))
        assertEquals("https://example.com/", ServerConfigStore.normalizeUrl(" https://example.com/ "))
    }

    @Test
    fun rejectsInvalid() {
        assertNull(ServerConfigStore.normalizeUrl(""))
        assertNull(ServerConfigStore.normalizeUrl("   "))
        assertNull(ServerConfigStore.normalizeUrl("http://bad host"))
        assertNull(ServerConfigStore.normalizeUrl("ftp://host"))
        assertNull(ServerConfigStore.normalizeUrl("http://"))
    }
}

class RoleTest {
    @Test
    fun mapsApiValues() {
        assertEquals(Role.SUPER_ADMIN, Role.fromApi("super_admin"))
        assertEquals(Role.LEAGUE_ORGANIZER, Role.fromApi("league_organizer"))
        assertEquals(Role.CLUB_MANAGER, Role.fromApi("club_manager"))
        assertEquals(Role.PLAYER, Role.fromApi("player"))
        assertEquals(Role.VIEWER, Role.fromApi("viewer"))
    }

    @Test
    fun unknownFallsBackToViewer() {
        assertEquals(Role.VIEWER, Role.fromApi("wizard"))
        assertEquals(Role.VIEWER, Role.fromApi(null))
    }
}

class SafeCallMappingTest {
    private fun http(status: Int, body: String) = HttpException(
        Response.error<Any>(status, body.toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun parsesErrorEnvelope() {
        val e = mapThrowable(http(401, """{"error":{"code":"UNAUTHENTICATED","message":"no"}}"""))
        assertTrue(e is ApiError.Http)
        e as ApiError.Http
        assertEquals(401, e.status)
        assertEquals("UNAUTHENTICATED", e.code)
    }

    @Test
    fun nonJsonBodyFallsBack() {
        val e = mapThrowable(http(500, "<html>boom</html>")) as ApiError.Http
        assertEquals("HTTP_500", e.code)
    }

    @Test
    fun connectExceptionIsServerUnreachable() {
        assertEquals(ApiError.ServerUnreachable, mapThrowable(ConnectException("refused")))
    }
}

class ClockTest {
    @Test
    fun formatsUtcWithoutMillis() {
        val instant = Instant.parse("2026-10-17T11:00:00.789Z")
        assertEquals("2026-10-17T11:00:00Z", Clock.format(instant))
    }
}
