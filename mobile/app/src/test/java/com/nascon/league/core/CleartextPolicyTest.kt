package com.nascon.league.core

import com.nascon.league.core.network.CleartextGuardInterceptor
import com.nascon.league.core.network.CleartextPolicy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class CleartextPolicyTest {
    private fun ok(h: String?) = assertTrue("expected allowed: $h", CleartextPolicy.isAllowedHost(h))
    private fun no(h: String?) = assertFalse("expected blocked: $h", CleartextPolicy.isAllowedHost(h))

    @Test fun localhost() = ok("localhost")
    @Test fun loopbackV4() = ok("127.0.0.1")
    @Test fun loopbackV6() = ok("::1")
    @Test fun loopbackV6Bracketed() = ok("[::1]")
    @Test fun emulatorHost() = ok("10.0.2.2")
    @Test fun genymotionHost() = ok("10.0.3.2")
    @Test fun mdnsName() = ok("my-laptop.local")
    @Test fun mdnsNested() = ok("api.my-laptop.local")
    @Test fun bareLocalBlocked() = no(".local")
    @Test fun localLookalike() = no("evil.local.com")
    @Test fun upperCaseLocalhost() = ok("LOCALHOST")
    @Test fun upperCaseMdns() = ok("Laptop.LOCAL")

    @Test fun ten8() { ok("10.0.0.1"); ok("10.255.255.254"); ok("10.20.30.40") }
    @Test fun private172Edges() {
        no("172.15.255.255")
        ok("172.16.0.1")
        ok("172.31.255.254")
        no("172.32.0.1")
    }
    @Test fun private192Edges() {
        ok("192.168.0.1")
        ok("192.168.255.1")
        no("192.169.0.1")
        no("192.167.1.1")
    }
    @Test fun linkLocal() { ok("169.254.1.1"); no("169.253.1.1"); no("169.255.1.1") }
    @Test fun publicIps() { no("8.8.8.8"); no("1.1.1.1"); no("11.0.0.1"); no("9.255.255.255") }
    @Test fun otherLoopbackRangeNotListed() = no("127.0.0.2")
    @Test fun hostnamesBlocked() { no("example.com"); no("api.nascon.pk"); no("localhosts") }
    @Test fun lookalikeDomains() {
        no("10.0.0.1.evil.com")
        no("localhost.evil.com")
        no("192.168.1.1.nip.io")
        no("evil.com.localhost")
    }
    @Test fun malformedIps() {
        no("192.168.1.256")
        no("10.0.0")
        no("10.0.0.1.1")
        no("10..0.1")
        no("10.0.0.-1")
        no("10.0.0.a")
        no("1000.0.0.1")
    }
    @Test fun emptyAndNull() { no(""); no("   "); no(null); no("[]") }
    @Test fun whitespaceTrimmed() = ok("  localhost  ")

    @Test fun urlHttpsPublicOk() = assertTrue(CleartextPolicy.isAllowedUrl("https://api.example.com/v1"))
    @Test fun urlHttpsUppercaseScheme() = assertTrue(CleartextPolicy.isAllowedUrl("HTTPS://example.com"))
    @Test fun urlHttpPublicBlocked() = assertFalse(CleartextPolicy.isAllowedUrl("http://example.com"))
    @Test fun urlHttpPublicIpBlocked() = assertFalse(CleartextPolicy.isAllowedUrl("http://8.8.8.8:8080/"))
    @Test fun urlHttpLanOk() = assertTrue(CleartextPolicy.isAllowedUrl("http://192.168.1.20:8080/api"))
    @Test fun urlHttpEmulatorOk() = assertTrue(CleartextPolicy.isAllowedUrl("http://10.0.2.2:8080"))
    @Test fun urlHttpIpv6Ok() = assertTrue(CleartextPolicy.isAllowedUrl("http://[::1]:8080/"))
    @Test fun urlOtherSchemesBlocked() {
        assertFalse(CleartextPolicy.isAllowedUrl("ftp://localhost"))
        assertFalse(CleartextPolicy.isAllowedUrl("javascript:alert(1)"))
        assertFalse(CleartextPolicy.isAllowedUrl("file:///etc/passwd"))
    }
    @Test fun urlMalformedBlocked() {
        assertFalse(CleartextPolicy.isAllowedUrl(null))
        assertFalse(CleartextPolicy.isAllowedUrl(""))
        assertFalse(CleartextPolicy.isAllowedUrl("localhost"))
        assertFalse(CleartextPolicy.isAllowedUrl("http://"))
        assertFalse(CleartextPolicy.isAllowedUrl("http://exa mple.com"))
        assertFalse(CleartextPolicy.isAllowedUrl("https://"))
    }

    // --- interceptor ---

    private class FakeChain(private val req: Request) : Interceptor.Chain {
        var proceeded = false
        override fun request(): Request = req
        override fun proceed(request: Request): Response {
            proceeded = true
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 1000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 1000
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 1000
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    @Test fun interceptorBlocksPublicHttp() {
        val chain = FakeChain(Request.Builder().url("http://example.com/x").build())
        try {
            CleartextGuardInterceptor().intercept(chain)
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("Cleartext HTTP is only allowed for development hosts", e.message)
        }
        assertFalse(chain.proceeded)
    }

    @Test fun interceptorAllowsEmulatorHttp() {
        val chain = FakeChain(Request.Builder().url("http://10.0.2.2:8080/health").build())
        val res = CleartextGuardInterceptor().intercept(chain)
        assertTrue(chain.proceeded)
        assertEquals(200, res.code)
    }

    @Test fun interceptorAllowsHttpsPublic() {
        val chain = FakeChain(Request.Builder().url("https://example.com/").build())
        CleartextGuardInterceptor().intercept(chain)
        assertTrue(chain.proceeded)
    }

    @Test fun interceptorBlocksLookalike() {
        val chain = FakeChain(Request.Builder().url("http://10.0.0.1.evil.com/").build())
        try {
            CleartextGuardInterceptor().intercept(chain)
            fail("expected IOException")
        } catch (e: IOException) {
            assertFalse(chain.proceeded)
        }
    }

    @Test fun interceptorAllowsIpv6Loopback() {
        val chain = FakeChain(Request.Builder().url("http://[::1]:8080/").build())
        CleartextGuardInterceptor().intercept(chain)
        assertTrue(chain.proceeded)
    }
}
