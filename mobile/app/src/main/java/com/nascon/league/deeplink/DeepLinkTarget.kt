package com.nascon.league.deeplink

/** Where a `nascon://` link points. Pure data: no Android types, so it is unit tested on the JVM. */
sealed interface DeepLinkTarget {
    data class Match(val id: String, val startSeconds: Int? = null) : DeepLinkTarget
    data class Club(val id: String) : DeepLinkTarget
    data class Player(val id: String) : DeepLinkTarget
    data class CoStream(val id: String) : DeepLinkTarget
    data class NotFound(val reason: String) : DeepLinkTarget
}

/** Stable reason codes carried by [DeepLinkTarget.NotFound]. The screen maps each one to plain copy. */
object NotFoundReason {
    const val EMPTY = "empty"
    const val UNSUPPORTED_SCHEME = "unsupported_scheme"
    const val UNKNOWN_HOST = "unknown_host"
    const val MISSING_ID = "missing_id"
    const val INVALID_ID = "invalid_id"
    const val UNSUPPORTED_COMBINATION = "unsupported_combination"
    const val EXTRA_SEGMENTS = "extra_segments"

    /** Used by the router when a destination screen reports that the entity does not exist. */
    const val ENTITY_MISSING = "entity_missing"
}

/** Route strings the router produces. They match the NavHost registrations in AppNavHost. */
object DeepLinkRoutes {
    const val HOME = "home"
    fun match(id: String) = "match/$id"
    fun club(id: String) = "club/$id"
    fun player(id: String) = "player/$id"
    fun coStream(id: String) = "costream/$id"
    fun notFound(reason: String) = "notfound/$reason"
    const val NOT_FOUND_PATTERN = "notfound/{reason}"
    const val COSTREAM_PATTERN = "costream/{sessionId}"
}
