package com.nascon.league.core.config

import com.nascon.league.core.network.CleartextPolicy

object BaseUrlValidator {
    enum class Reason {
        EMPTY, BAD_SCHEME, NO_HOST, HAS_WHITESPACE, HAS_USERINFO, BAD_PORT, CLEARTEXT_NOT_ALLOWED
    }

    sealed interface Result {
        data class Ok(val normalized: String) : Result
        data class Invalid(val reason: Reason) : Result
    }

    private val otherScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:(?!\\d)")
    private val hostChars = Regex("^[A-Za-z0-9._-]+$")

    fun validate(raw: String?): Result {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return bad(Reason.EMPTY)
        if (s.any { it.isWhitespace() }) return bad(Reason.HAS_WHITESPACE)

        val scheme: String
        val rest: String
        val idx = s.indexOf("://")
        if (idx >= 0) {
            scheme = s.substring(0, idx).lowercase()
            rest = s.substring(idx + 3)
        } else {
            if (otherScheme.containsMatchIn(s)) return bad(Reason.BAD_SCHEME)
            scheme = "http"
            rest = s
        }
        if (scheme != "http" && scheme != "https") return bad(Reason.BAD_SCHEME)

        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.isEmpty()) return bad(Reason.NO_HOST)
        if (authority.contains('@')) return bad(Reason.HAS_USERINFO)

        val host: String
        val portText: String?
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return bad(Reason.NO_HOST)
            host = authority.substring(0, end + 1).lowercase()
            val tail = authority.substring(end + 1)
            portText = when {
                tail.isEmpty() -> null
                tail.startsWith(":") -> tail.substring(1)
                else -> return bad(Reason.BAD_PORT)
            }
            val inner = host.substring(1, host.length - 1)
            if (inner.isEmpty() || !inner.all { it.isLetterOrDigit() || it == ':' || it == '.' }) {
                return bad(Reason.NO_HOST)
            }
        } else {
            val colon = authority.indexOf(':')
            val h = if (colon < 0) authority else authority.substring(0, colon)
            portText = if (colon < 0) null else authority.substring(colon + 1)
            if (h.isEmpty() || !hostChars.matches(h)) return bad(Reason.NO_HOST)
            host = h.lowercase()
        }

        var port: Int? = null
        if (portText != null) {
            if (portText.isEmpty() || !portText.all { it in '0'..'9' }) return bad(Reason.BAD_PORT)
            val p = portText.toIntOrNull()
            if (p == null || p !in 1..65535) return bad(Reason.BAD_PORT)
            port = p
        }

        if (scheme == "http" && !CleartextPolicy.isAllowedHost(host)) {
            return bad(Reason.CLEARTEXT_NOT_ALLOWED)
        }
        val portPart = if (port != null) ":$port" else ""
        return Result.Ok("$scheme://$host$portPart/")
    }

    private fun bad(r: Reason) = Result.Invalid(r)
}
