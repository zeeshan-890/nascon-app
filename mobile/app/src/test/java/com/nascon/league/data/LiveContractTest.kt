package com.nascon.league.data

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.AppJson
import com.nascon.league.core.network.mapThrowable
import com.nascon.league.data.api.dto.CheckoutResponse
import com.nascon.league.data.api.dto.ClubDto
import com.nascon.league.data.api.dto.MeResponse
import com.nascon.league.data.repo.Page
import com.nascon.league.data.repo.collectPages
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class LiveContractTest {
    @Test
    fun clubDecodesNullVenueNameAsEmptyDisplay() {
        val club = AppJson.decodeFromString<ClubDto>(
            """{"club_id":"c1","name":"Lions","venue":{"name":null,"lat":31.5,"lng":74.3},"extra":true}""",
        )
        val venue = club.venue!!
        assertEquals("", venue.name)
        assertEquals(31.5, venue.lat!!, 0.0)
    }

    @Test
    fun checkoutCurrencyDefaultsToPkr() {
        val body = AppJson.decodeFromString<CheckoutResponse>("""{"order_id":"ord-1","status":"paid"}""")
        assertEquals("PKR", body.currency)
        assertEquals("ord-1", body.orderId)
    }

    @Test
    fun meResponseKeepsUserTenantColoursAndIgnoresUnknown() {
        val me = AppJson.decodeFromString<MeResponse>(
            """
            {"user":{"user_id":"u1","email":"organizer@nascon.test","role":"league_organizer","extra":1},
             "tenant":{"tenant_id":"t1","name":"Alpha","logo_url":"/static/logo/t1.png",
             "primary_color":"#0B5FFF","secondary_color":"#FFB800","unused":true},"token":"no"}
            """.trimIndent(),
        )
        assertEquals("u1", me.user.userId)
        assertEquals("organizer@nascon.test", me.user.email)
        assertEquals("/static/logo/t1.png", me.tenant!!.logoUrl)
        assertEquals("#0B5FFF", me.tenant!!.primaryColor)
        assertEquals("#FFB800", me.tenant!!.secondaryColor)
    }

    @Test
    fun rateLimitKeepsRetryAfterSecondsAndHeader() {
        val json = """{"error":{"code":"RATE_LIMITED","message":"slow down","details":{"retry_after_seconds":10}}}"""
        val error = mapThrowable(http(429, json, "Retry-After" to "10")) as ApiError.Http
        assertEquals("RATE_LIMITED", error.code)
        assertEquals(10, error.retryAfterSeconds)
        assertEquals(10, (error.details?.get("retry_after_seconds") as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertEquals(10, (error.details?.get("retry_after") as kotlinx.serialization.json.JsonPrimitive).content.toInt())
    }

    @Test
    fun pagesFollowOffsetUntilTotalAndStopOnShortPage() = runBlocking {
        val all = (1..250).map { "id-$it" }
        val seenOffsets = mutableListOf<Int>()
        val got = collectPages(cap = 500, pageSize = 100, fetch = { limit, offset ->
            seenOffsets += offset
            Page(all.drop(offset).take(limit), total = all.size)
        }, idOf = { it })
        assertEquals(all, got)
        assertEquals(listOf(0, 100, 200), seenOffsets)
    }

    @Test
    fun pagesStopAtCapAndOnRepeatedPage() = runBlocking {
        val capped = collectPages(cap = 3, pageSize = 2, fetch = { _, offset ->
            Page(listOf("a$offset", "b$offset"), total = null)
        }, idOf = { it })
        assertEquals(3, capped.size)

        var calls = 0
        val repeated = collectPages(cap = 50, pageSize = 2, fetch = { _, _ ->
            calls++
            Page(listOf("same", "page"), total = null)
        }, idOf = { it })
        assertEquals(listOf("same", "page"), repeated)
        assertEquals(2, calls)
    }

    private fun http(status: Int, json: String, vararg headers: Pair<String, String>): HttpException {
        val builder = okhttp3.Response.Builder()
            .request(Request.Builder().url("http://127.0.0.1/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("err")
            .body("".toResponseBody(null))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val raw = builder.build()
        return HttpException(Response.error<Any>(json.toResponseBody("application/json".toMediaType()), raw))
    }
}
