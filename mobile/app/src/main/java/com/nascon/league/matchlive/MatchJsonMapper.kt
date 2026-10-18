package com.nascon.league.matchlive

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Pure, tolerant JSON to model mapping. Never throws; returns null or empty for garbage. */
object MatchJsonMapper {
    private const val JERSEY_PRODUCT = "prod-jersey-001"
    private const val JERSEY_DEFAULT_VARIANT = "var-jersey-001-L"

    fun snapshot(json: JsonObject, fallbackMatchId: String): MatchSnapshot? = try {
        val status = json.str("status")
        if (status == null) {
            null
        } else {
            val home = json.obj("home")
            val away = json.obj("away")
            MatchSnapshot(
                matchId = json.str("match_id") ?: fallbackMatchId,
                status = status,
                homeName = teamName(home, json.str("home_name")),
                awayName = teamName(away, json.str("away_name")),
                homeScore = home?.int("score"),
                awayScore = away?.int("score"),
                minuteLabel = json.str("minute_label") ?: json.str("minute"),
                leagueName = json.str("league_name") ?: json.str("league_id"),
                scheduledAtIso = json.str("scheduled_at"),
                version = json.long("version") ?: 0L,
                streamUrl = json.str("stream_url") ?: json.str("playback_url") ?: json.str("hls_url"),
                posterUrl = json.str("poster_url"),
                recapText = json.str("recap"),
                reason = json.str("reason"),
                pinned = pinned(json["pinned_products"]),
            )
        }
    } catch (_: Exception) {
        null
    }

    fun chatMessages(json: JsonElement): List<ChatMessage> = try {
        val arr = when (json) {
            is JsonArray -> json
            is JsonObject -> json["messages"] as? JsonArray
            else -> null
        }
        arr?.mapNotNull { (it as? JsonObject)?.let(::chatMessage) } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun chatMessage(json: JsonObject): ChatMessage? = try {
        val id = json.str("message_id")
        val seq = json.long("sequence") ?: json.long("seq")
        val text = json.str("text")
        if (id == null || seq == null || text == null) {
            null
        } else {
            ChatMessage(
                roomId = json.str("room_id") ?: json.str("chat_room_id") ?: "",
                messageId = id,
                sequence = seq,
                sender = json.str("sender") ?: "",
                text = text,
                flagged = (json["flagged"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun teamName(team: JsonObject?, topLevel: String?): String =
        team?.str("name") ?: team?.str("team_name") ?: topLevel ?: team?.str("team_id") ?: ""

    /** Entries without a product id, or without any usable variant id, are skipped (nothing to add to the cart). */
    private fun pinned(el: JsonElement?): List<MerchItem> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val productId = o.str("product_id") ?: return@mapNotNull null
            val variant = ((o["variants"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("variant_id")
                ?: o.str("variant_id")
                ?: (if (productId == JERSEY_PRODUCT) JERSEY_DEFAULT_VARIANT else null)
                ?: return@mapNotNull null
            MerchItem(
                productId = productId,
                variantId = variant,
                name = o.str("name") ?: productId,
                priceMinor = o.long("price_minor") ?: 0L,
                imageUrl = o.str("image_url"),
            )
        }
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.str(key: String): String? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.contentOrNull
    }

    private fun JsonObject.long(key: String): Long? {
        val s = str(key) ?: return null
        s.toLongOrNull()?.let { return it }
        val d = s.toDoubleOrNull() ?: return null
        return if (d % 1.0 == 0.0 && d >= -9.0E18 && d <= 9.0E18) d.toLong() else null
    }

    private fun JsonObject.int(key: String): Int? = long(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}
