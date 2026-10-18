package com.nascon.league.ui.guest

import java.net.URLDecoder
import java.net.URLEncoder

/** Where a guest was when the sign-in sheet opened, so login can return them there. Pure Kotlin. */
data class SignInOrigin(val route: String?, val playbackSeconds: Int?)

class SignInOriginStore {
    private var current: SignInOrigin? = null

    fun record(route: String?, playbackSeconds: Int?) {
        current = SignInOrigin(route, playbackSeconds?.takeIf { it >= 0 })
    }

    fun peek(): SignInOrigin? = current

    fun consume(): SignInOrigin? {
        val c = current
        current = null
        return c
    }

    /** Encodes as "route|seconds"; route is percent-encoded so it never contains '|'. Null when empty. */
    fun save(): String? {
        val c = current ?: return null
        val r = c.route?.let { URLEncoder.encode(it, "UTF-8") }.orEmpty()
        return "$r|${c.playbackSeconds?.toString().orEmpty()}"
    }

    fun restore(s: String?) {
        current = try {
            parse(s)
        } catch (_: Exception) {
            null
        }
    }

    private fun parse(s: String?): SignInOrigin? {
        if (s.isNullOrEmpty()) return null
        val parts = s.split('|')
        if (parts.size != 2) return null
        val route = parts[0].takeIf { it.isNotEmpty() }?.let { URLDecoder.decode(it, "UTF-8") }
        val secs = if (parts[1].isEmpty()) null else parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return SignInOrigin(route, secs)
    }
}
