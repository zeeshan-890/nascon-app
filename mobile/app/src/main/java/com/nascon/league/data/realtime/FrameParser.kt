package com.nascon.league.data.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

val KNOWN_TYPES = setOf("match.event_appended", "match.status_changed", "product.pinned", "standings.updated")

private val parserJson = Json { ignoreUnknownKeys = true }

/** Returns null for malformed frames or unknown types. */
fun parseFrame(text: String): RealtimeMessage? = try {
    val obj = parserJson.parseToJsonElement(text).jsonObject
    val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    if (type == null || type !in KNOWN_TYPES) null
    else RealtimeMessage(
        type = type,
        matchId = (obj["match_id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull,
        data = obj["data"] as? JsonObject,
    )
} catch (e: Exception) {
    null
}
