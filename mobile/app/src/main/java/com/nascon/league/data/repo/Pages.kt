package com.nascon.league.data.repo

import retrofit2.HttpException
import retrofit2.Response

internal const val API_PAGE_SIZE = 100
internal const val LEAGUE_PAGE_CAP = 200
internal const val FIXTURE_PAGE_CAP = 400
internal const val CLUB_PAGE_CAP = 300
internal const val PRODUCT_PAGE_CAP = 300

internal data class Page<T>(val items: List<T>, val total: Int?)

/** Bare JSON array plus optional `X-Total-Count`. Non-2xx becomes [HttpException]. */
internal fun <T> Response<List<T>>.toPage(): Page<T> {
    if (!isSuccessful) throw HttpException(this)
    val total = headers()["X-Total-Count"]?.trim()?.toIntOrNull()
    return Page(body().orEmpty(), total)
}

/**
 * Follows `limit`/`offset` until a short page, until [Page.total] is covered, or until [cap].
 * A repeated page (the server ignored offset) stops the loop.
 */
internal suspend fun <T> collectPages(
    cap: Int,
    pageSize: Int = API_PAGE_SIZE,
    fetch: suspend (limit: Int, offset: Int) -> Page<T>,
    idOf: (T) -> String,
): List<T> {
    if (cap <= 0 || pageSize <= 0) return emptyList()
    val out = ArrayList<T>(minOf(cap, pageSize))
    val seen = HashSet<String>()
    var offset = 0
    while (out.size < cap) {
        val page = fetch(pageSize, offset)
        if (page.items.isEmpty()) break
        var added = 0
        for (item in page.items) {
            if (out.size >= cap) break
            val id = idOf(item)
            if (id.isNotBlank() && !seen.add(id)) continue
            out.add(item)
            added++
        }
        offset += page.items.size
        val total = page.total
        if (added == 0) break
        if (total != null && offset >= total) break
        if (page.items.size < pageSize) break
    }
    return out
}
