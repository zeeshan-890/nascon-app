package com.nascon.league.core

import com.nascon.league.core.config.BaseUrlValidator
import com.nascon.league.core.config.BaseUrlValidator.Reason
import com.nascon.league.core.config.BaseUrlValidator.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class BaseUrlValidatorTest {
    private fun ok(raw: String?, expected: String) =
        assertEquals(raw, Result.Ok(expected), BaseUrlValidator.validate(raw))

    private fun bad(raw: String?, reason: Reason) =
        assertEquals(raw, Result.Invalid(reason), BaseUrlValidator.validate(raw))

    @Test fun nullIsEmpty() = bad(null, Reason.EMPTY)
    @Test fun blankIsEmpty() = bad("   ", Reason.EMPTY)
    @Test fun emptyIsEmpty() = bad("", Reason.EMPTY)

    @Test fun assumesHttpForBareLanHost() = ok("192.168.1.20:8080", "http://192.168.1.20:8080/")
    @Test fun trimsInput() = ok("  http://10.0.2.2:8080  ", "http://10.0.2.2:8080/")
    @Test fun addsTrailingSlash() = ok("https://api.example.com", "https://api.example.com/")
    @Test fun dropsPathQueryFragment() = ok("https://api.example.com/v1/x?a=1#f", "https://api.example.com/")
    @Test fun lowercasesHostAndScheme() = ok("HTTPS://API.Example.COM", "https://api.example.com/")
    @Test fun keepsHttpsPort() = ok("https://example.com:8443/", "https://example.com:8443/")
    @Test fun localhostBare() = ok("localhost:3000", "http://localhost:3000/")
    @Test fun localhostNoPort() = ok("localhost", "http://localhost/")
    @Test fun mdnsHost() = ok("http://Laptop.local:8080", "http://laptop.local:8080/")
    @Test fun ipv6Loopback() = ok("http://[::1]:8080/x", "http://[::1]:8080/")
    @Test fun portLeadingZerosNormalized() = ok("http://localhost:0080", "http://localhost:80/")
    @Test fun portEdgeMax() = ok("http://localhost:65535", "http://localhost:65535/")

    @Test fun whitespaceInside() = bad("http://local host", Reason.HAS_WHITESPACE)
    @Test fun tabInside() = bad("http://localhost\t:80", Reason.HAS_WHITESPACE)
    @Test fun ftpScheme() = bad("ftp://example.com", Reason.BAD_SCHEME)
    @Test fun wsScheme() = bad("ws://localhost", Reason.BAD_SCHEME)
    @Test fun javascriptScheme() = bad("javascript:alert(1)", Reason.BAD_SCHEME)
    @Test fun noHostAfterScheme() = bad("http://", Reason.NO_HOST)
    @Test fun noHostOnlyPort() = bad("http://:8080", Reason.NO_HOST)
    @Test fun noHostOnlyPath() = bad("https:///path", Reason.NO_HOST)
    @Test fun userinfoRejected() = bad("https://user@example.com", Reason.HAS_USERINFO)
    @Test fun userinfoWithPasswordRejected() = bad("http://user:pw@localhost", Reason.HAS_USERINFO)
    @Test fun portZero() = bad("http://localhost:0", Reason.BAD_PORT)
    @Test fun portTooLarge() = bad("http://localhost:65536", Reason.BAD_PORT)
    @Test fun portHuge() = bad("http://localhost:99999999999999", Reason.BAD_PORT)
    @Test fun portNotNumeric() = bad("http://localhost:abc", Reason.BAD_PORT)
    @Test fun portEmpty() = bad("http://localhost:", Reason.BAD_PORT)
    @Test fun cleartextPublicHost() = bad("http://example.com", Reason.CLEARTEXT_NOT_ALLOWED)
    @Test fun cleartextBareHostnameAssumedHttp() = bad("api.example.com", Reason.CLEARTEXT_NOT_ALLOWED)
    @Test fun cleartextPublicIp() = bad("http://8.8.8.8:8080", Reason.CLEARTEXT_NOT_ALLOWED)
    @Test fun cleartextLookalike() = bad("http://localhost.evil.com", Reason.CLEARTEXT_NOT_ALLOWED)
    @Test fun httpsPublicIsFine() = ok("https://8.8.8.8", "https://8.8.8.8/")
    @Test fun badPortBeatsCleartext() = bad("http://example.com:0", Reason.BAD_PORT)
}
