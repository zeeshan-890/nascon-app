package com.nascon.league.data.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** Pure parser for live match frames (WebSocket text frames and SSE data payloads). Never throws. */
object LiveFrameParser {
    const val MAX_MESSAGE_CHARS = 64 * 1024
    private const val SECONDS_THRESHOLD = 100_000_000_000L

    private val json = Json { isLenient = false }

    fun parse(text: String): LiveMessage? {
        if (text.isBlank() || text.length > MAX_MESSAGE_CHARS) return null
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return null
        } catch (e: StackOverflowError) {
            return null
        }
        val obj = root as? JsonObject ?: return null
        val type = str(obj["type"]) ?: return null
        val data = obj["data"] as? JsonObject
        return LiveMessage(
            id = str(obj["id"]) ?: str(data?.get("event_id")),
            sequence = long(obj["sequence"]) ?: long(data?.get("sequence")),
            timestampMs = timestamp(obj["timestamp"]) ?: timestamp(obj["ts"]),
            type = type,
            matchId = str(obj["match_id"]) ?: str(data?.get("match_id")),
            data = data,
        )
    }

    fun parseSse(dataLines: List<String>): LiveMessage? {
        if (dataLines.isEmpty()) return null
        return parse(dataLines.joinToString("\n"))
    }

    private fun prim(e: JsonElement?): JsonPrimitive? =
        (e as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun str(e: JsonElement?): String? {
        val p = prim(e) ?: return null
        return if (p.isString) p.content else null
    }

    private fun long(e: JsonElement?): Long? {
        val p = prim(e) ?: return null
        val v = p.content.trim().toLongOrNull() ?: run {
            if (p.isString) return null
            val d = p.content.toDoubleOrNull() ?: return null
            if (d.isNaN() || d.isInfinite() || d != Math.floor(d) || Math.abs(d) > 9.0e18) return null
            d.toLong()
        }
        return if (v < 0) null else v
    }

    private fun timestamp(e: JsonElement?): Long? {
        val p = prim(e) ?: return null
        val numeric = if (p.isString) p.content.trim().toLongOrNull() else long(p)
        if (numeric != null) {
            if (numeric < 0) return null
            return if (numeric < SECONDS_THRESHOLD) {
                if (numeric > Long.MAX_VALUE / 1000) null else numeric * 1000
            } else numeric
        }
        if (!p.isString) return null
        return try {
            Instant.parse(p.content.trim()).toEpochMilli()
        } catch (ex: Exception) {
            null
        }
    }
}
