package com.nascon.league.deeplink

import com.nascon.league.deeplink.DeepLinkTarget.NotFound
import java.net.URLDecoder

/**
 * Hand-rolled `nascon://` parser. Pure Kotlin (no android.net.Uri) so it runs in plain JUnit.
 * It never throws and never evaluates an id; the id is only checked against a strict allow-list.
 *
 * Checks run in this order, and the first failure wins:
 *  1. empty / blank input        -> EMPTY
 *  2. scheme                     -> UNSUPPORTED_SCHEME (only `nascon` is accepted, http(s) included)
 *  3. host                       -> UNKNOWN_HOST (`entity` or `stream`; userinfo/port land here too)
 *  4. missing id                 -> MISSING_ID (no path, or an empty first segment such as `//`)
 *  5. extra path segments        -> EXTRA_SEGMENTS
 *  6. percent-decode once + validate the id -> INVALID_ID
 *  7. host/prefix combination    -> UNSUPPORTED_COMBINATION
 *
 * Interpretations: a bare prefix such as `match-` (nothing after it) is an UNSUPPORTED_COMBINATION;
 * prefixes are case-sensitive; when `t` appears several times the first occurrence decides.
 */
object DeepLinkParser {
    private const val SCHEME = "nascon"
    private const val MAX_ID_LENGTH = 64
    private const val MAX_START_SECONDS = 86_400 * 7

    fun parse(raw: String?): DeepLinkTarget {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty()) return NotFound(NotFoundReason.EMPTY)

        // 2. scheme
        val schemeEnd = input.indexOf("://")
        if (schemeEnd < 0 || !input.substring(0, schemeEnd).equals(SCHEME, ignoreCase = true)) {
            return NotFound(NotFoundReason.UNSUPPORTED_SCHEME)
        }

        // Drop the fragment first, then split the query off what is left.
        val afterScheme = input.substring(schemeEnd + 3).substringBefore('#')
        val query = if ('?' in afterScheme) afterScheme.substringAfter('?') else ""
        val hierarchy = afterScheme.substringBefore('?')

        // 3. host (authority is everything up to the first slash)
        val slash = hierarchy.indexOf('/')
        val host = (if (slash < 0) hierarchy else hierarchy.substring(0, slash)).lowercase()
        if (host != HOST_ENTITY && host != HOST_STREAM) return NotFound(NotFoundReason.UNKNOWN_HOST)

        // 4 + 5. path segments. One trailing slash is tolerated.
        var path = if (slash < 0) "" else hierarchy.substring(slash + 1)
        if (path.endsWith("/")) path = path.dropLast(1)
        val segments = path.split('/')
        if (segments.first().isEmpty()) return NotFound(NotFoundReason.MISSING_ID)
        if (segments.size > 1) return NotFound(NotFoundReason.EXTRA_SEGMENTS)

        // 6. decode once, then allow-list validate
        val id = decodeOnce(segments.first())?.takeIf(::isValidId)
            ?: return NotFound(NotFoundReason.INVALID_ID)

        // 7. host/prefix combination
        return when {
            host == HOST_ENTITY && hasPrefix(id, "match-") -> DeepLinkTarget.Match(id, startSeconds(query))
            host == HOST_ENTITY && hasPrefix(id, "club-") -> DeepLinkTarget.Club(id)
            host == HOST_ENTITY && hasPrefix(id, "player-") -> DeepLinkTarget.Player(id)
            hasPrefix(id, "cs-session-") -> DeepLinkTarget.CoStream(id)
            host == HOST_STREAM && hasPrefix(id, "match-") -> DeepLinkTarget.Match(id, startSeconds(query))
            else -> NotFound(NotFoundReason.UNSUPPORTED_COMBINATION)
        }
    }

    private const val HOST_ENTITY = "entity"
    private const val HOST_STREAM = "stream"

    private fun hasPrefix(id: String, prefix: String) = id.length > prefix.length && id.startsWith(prefix)

    /** Decodes percent escapes a single time. Returns null for malformed escapes or a `+` shortcut. */
    private fun decodeOnce(segment: String): String? {
        if ('%' !in segment) return segment
        return try {
            // A literal '+' would become a space and fail validation anyway, so no special handling.
            URLDecoder.decode(segment, "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** ASCII letters, digits, hyphen, underscore; 1..64 chars. Rejects dots, spaces, '%', unicode, NUL. */
    private fun isValidId(id: String): Boolean =
        id.length in 1..MAX_ID_LENGTH && id.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
        }

    /** First `t` key wins. Anything that is not 0..7 days of plain ASCII digits is ignored (null). */
    private fun startSeconds(query: String): Int? {
        if (query.isEmpty()) return null
        val value = query.split('&')
            .firstOrNull { it.substringBefore('=') == "t" && '=' in it }
            ?.substringAfter('=') ?: return null
        if (value.isEmpty() || value.length > 7 || !value.all { it in '0'..'9' }) return null
        return value.toInt().takeIf { it <= MAX_START_SECONDS }
    }
}
