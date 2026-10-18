package com.nascon.league.deeplink

/** Holds a link until the user can be shown it; survives process death via save/restore. */
class PendingDeepLink(
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val dedupeWindowMillis: Long = 1000,
) {
    private var pending: String? = null
    private var lastOffered: String? = null
    private var lastOfferedAt: Long = 0L

    fun offer(uri: String?): Boolean {
        val u = uri?.trim()
        if (u.isNullOrEmpty()) return false
        val now = nowMillis()
        if (u == lastOffered && now - lastOfferedAt < dedupeWindowMillis) return false
        lastOffered = u
        lastOfferedAt = now
        pending = u
        return true
    }

    fun peek(): String? = pending

    fun consume(): String? {
        val p = pending
        pending = null
        return p
    }

    fun hasPending(): Boolean = pending != null

    fun save(): String? = pending

    fun restore(saved: String?) {
        pending = saved?.trim()?.takeIf { it.isNotEmpty() }
    }
}

object DeepLinkGate {
    /** True when the link must wait (login/onboarding, or NavHost not ready). */
    fun shouldHold(currentRoute: String?): Boolean {
        if (currentRoute == null) return true
        return currentRoute.startsWith("login") || currentRoute.startsWith("onboarding")
    }
}
