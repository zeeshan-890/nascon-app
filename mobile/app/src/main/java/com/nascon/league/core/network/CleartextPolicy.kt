package com.nascon.league.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.URI

/** Cleartext HTTP is only acceptable for development hosts; https is always fine. */
object CleartextPolicy {
    private val exactHosts = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2", "10.0.3.2")

    fun isAllowedHost(host: String?): Boolean {
        if (host == null) return false
        var h = host.trim().lowercase()
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
        if (h.isEmpty()) return false
        if (h in exactHosts) return true
        if (isLocalMdns(h)) return true
        val ip = parseIpv4(h) ?: return false
        val a = ip[0]
        val b = ip[1]
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)
    }

    fun isAllowedUrl(url: String?): Boolean {
        if (url == null) return false
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host
        return when (scheme) {
            "https" -> !host.isNullOrBlank()
            "http" -> isAllowedHost(host)
            else -> false
        }
    }

    private fun isLocalMdns(h: String): Boolean {
        if (!h.endsWith(".local") || h.length <= ".local".length) return false
        val name = h.removeSuffix(".local")
        return name.split('.').all { label ->
            label.isNotEmpty() && label.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    private fun parseIpv4(h: String): IntArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val n = p.toInt()
            if (n > 255) return null
            out[i] = n
        }
        return out
    }
}

/** Blocks http requests to non-development hosts before anything is sent. */
class CleartextGuardInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.scheme == "http" && !CleartextPolicy.isAllowedHost(url.host)) {
            throw IOException("Cleartext HTTP is only allowed for development hosts")
        }
        return chain.proceed(request)
    }
}
