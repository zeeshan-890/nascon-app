package com.nascon.league.matchlive

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RemoteMatchLiveRepositoryTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

    private fun http(status: Int, code: String = "X"): HttpException = HttpException(
        Response.error<Any>(
            status,
            """{"error":{"code":"$code","message":"m"}}""".toResponseBody("application/json".toMediaType()),
        ),
    )

    private val okJson = obj("""{"match_id":"m1","status":"in_progress","home":{"team_id":"h","score":1},"away":{"team_id":"a","score":0},"version":3}""")

    private class Fake : MatchLiveApi {
        var scoreCalls = 0
        var matchCalls = 0
        var scoreImpl: () -> JsonObject = { JsonObject(emptyMap()) }
        var matchImpl: () -> JsonObject = { JsonObject(emptyMap()) }
        var chatImpl: () -> JsonObject = { JsonObject(emptyMap()) }
        var sendImpl: (JsonObject) -> JsonObject = { JsonObject(emptyMap()) }
        var lastAfter: Long? = null
        var lastBody: JsonObject? = null
        override suspend fun score(matchId: String): JsonObject { scoreCalls++; return scoreImpl() }
        override suspend fun match(matchId: String): JsonObject { matchCalls++; return matchImpl() }
        override suspend fun chatMessages(roomId: String, afterSequence: Long?): JsonObject { lastAfter = afterSequence; return chatImpl() }
        override suspend fun sendChat(body: JsonObject): JsonObject { lastBody = body; return sendImpl(body) }
    }

    private fun repo(f: Fake, token: String?) = RemoteMatchLiveRepository({ f }, { token })

    @Test fun guestTokenUsesScoreOnly() = runBlocking {
        val f = Fake().apply { scoreImpl = { okJson } }
        val r = repo(f, "guest_abc").snapshot("m1")
        assertTrue(r is NetworkResult.Success)
        assertEquals(1, f.scoreCalls)
        assertEquals(0, f.matchCalls)
    }

    @Test fun nullTokenUsesScoreOnly() = runBlocking {
        val f = Fake().apply { scoreImpl = { okJson } }
        repo(f, null).snapshot("m1")
        assertEquals(1, f.scoreCalls)
        assertEquals(0, f.matchCalls)
    }

    @Test fun memberUsesMatch() = runBlocking {
        val f = Fake().apply { matchImpl = { okJson } }
        val r = repo(f, "eyJ.jwt").snapshot("m1") as NetworkResult.Success
        assertEquals(0, f.scoreCalls)
        assertEquals(1, f.matchCalls)
        assertEquals(1, r.data.homeScore)
        assertEquals(3L, r.data.version)
    }

    @Test fun member401FallsBackToScore() = runBlocking {
        val f = Fake().apply { matchImpl = { throw http(401) }; scoreImpl = { okJson } }
        val r = repo(f, "jwt").snapshot("m1")
        assertTrue(r is NetworkResult.Success)
        assertEquals(1, f.matchCalls)
        assertEquals(1, f.scoreCalls)
    }

    @Test fun member403FallsBackToScore() = runBlocking {
        val f = Fake().apply { matchImpl = { throw http(403) }; scoreImpl = { okJson } }
        assertTrue(repo(f, "jwt").snapshot("m1") is NetworkResult.Success)
        assertEquals(1, f.scoreCalls)
    }

    @Test fun member404DoesNotFallBack() = runBlocking {
        val f = Fake().apply { matchImpl = { throw http(404, "MATCH_NOT_FOUND") } }
        val r = repo(f, "jwt").snapshot("m1") as NetworkResult.Failure
        val e = r.error as ApiError.Http
        assertEquals(404, e.status)
        assertEquals("MATCH_NOT_FOUND", e.code)
        assertEquals(0, f.scoreCalls)
    }

    @Test fun guest404MapsToHttpFailure() = runBlocking {
        val f = Fake().apply { scoreImpl = { throw http(404, "MATCH_NOT_FOUND") } }
        val r = repo(f, "guest_1").snapshot("m1") as NetworkResult.Failure
        assertEquals(404, (r.error as ApiError.Http).status)
    }

    @Test fun fallbackFailureSurfaces() = runBlocking {
        val f = Fake().apply { matchImpl = { throw http(401) }; scoreImpl = { throw http(404, "MATCH_NOT_FOUND") } }
        val r = repo(f, "jwt").snapshot("m1") as NetworkResult.Failure
        assertEquals(404, (r.error as ApiError.Http).status)
    }

    @Test fun unmappableJsonIsUnexpected() = runBlocking {
        val f = Fake().apply { scoreImpl = { obj("""{"nothing":1}""") } }
        val r = repo(f, null).snapshot("m1") as NetworkResult.Failure
        val e = r.error as ApiError.Unexpected
        assertEquals("unmappable match", e.cause?.message)
    }

    @Test fun ioErrorMapsToNoConnection() = runBlocking {
        val f = Fake().apply { scoreImpl = { throw java.io.IOException("x") } }
        val r = repo(f, null).snapshot("m1") as NetworkResult.Failure
        assertEquals(ApiError.NoConnection, r.error)
    }

    @Test fun sendChat403Passthrough() = runBlocking {
        val f = Fake().apply { sendImpl = { throw http(403, "GUEST_WRITE_FORBIDDEN") } }
        val r = repo(f, "guest_1").sendChat("chat-m1", "hi") as NetworkResult.Failure
        val e = r.error as ApiError.Http
        assertEquals(403, e.status)
        assertEquals("GUEST_WRITE_FORBIDDEN", e.code)
    }

    @Test fun sendChatSuccessAndBody() = runBlocking {
        val f = Fake().apply { sendImpl = { obj("""{"message_id":"a","room_id":"chat-m1","sequence":4,"sender":"me","text":"hi"}""") } }
        val r = repo(f, "jwt").sendChat("chat-m1", "hi") as NetworkResult.Success
        assertEquals(4L, r.data.sequence)
        assertEquals("chat-m1", f.lastBody!!["room_id"]!!.jsonPrimitive.content)
        assertEquals("hi", f.lastBody!!["text"]!!.jsonPrimitive.content)
        assertEquals(2, f.lastBody!!.size)
    }

    @Test fun sendChatUnmappableResponse() = runBlocking {
        val f = Fake().apply { sendImpl = { obj("{}") } }
        val r = repo(f, "jwt").sendChat("r", "t")
        assertTrue(r is NetworkResult.Failure && r.error is ApiError.Unexpected)
    }

    @Test fun chatHistoryOrderingUntouched() = runBlocking {
        val f = Fake().apply {
            chatImpl = {
                obj(
                    """{"messages":[{"message_id":"c","sequence":9,"text":"3"},{"message_id":"a","sequence":2,"text":"1"},{"message_id":"b","sequence":5,"text":"2"}]}""",
                )
            }
        }
        val r = repo(f, "jwt").chatHistory("chat-m1", 1L) as NetworkResult.Success
        assertEquals(listOf("c", "a", "b"), r.data.map { it.messageId })
        assertEquals(1L, f.lastAfter)
    }

    @Test fun chatHistoryErrorPassthrough() = runBlocking {
        val f = Fake().apply { chatImpl = { throw http(404) } }
        val r = repo(f, "jwt").chatHistory("chat-m1", null) as NetworkResult.Failure
        assertEquals(404, (r.error as ApiError.Http).status)
    }

    @Test fun chatHistoryEmptyOnGarbage() = runBlocking {
        val f = Fake().apply { chatImpl = { obj("""{"x":1}""") } }
        val r = repo(f, "jwt").chatHistory("chat-m1", null) as NetworkResult.Success
        assertTrue(r.data.isEmpty())
    }

    @Test fun cancellationNotSwallowed() {
        val f = Fake().apply { scoreImpl = { throw CancellationException("cancel") } }
        try {
            runBlocking { repo(f, null).snapshot("m1") }
            fail("expected cancellation")
        } catch (e: CancellationException) {
            assertFalse(e.message.isNullOrEmpty())
        }
    }

    @Test fun cancellationInSendNotSwallowed() {
        val f = Fake().apply { sendImpl = { throw CancellationException("cancel") } }
        try {
            runBlocking { repo(f, "jwt").sendChat("r", "t") }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
    }

    @Test fun tokenReadPerCall() = runBlocking {
        val f = Fake().apply { scoreImpl = { okJson }; matchImpl = { okJson } }
        var token: String? = "guest_1"
        val r = RemoteMatchLiveRepository({ f }, { token })
        r.snapshot("m1")
        token = "jwt"
        r.snapshot("m1")
        assertEquals(1, f.scoreCalls)
        assertEquals(1, f.matchCalls)
    }
}
